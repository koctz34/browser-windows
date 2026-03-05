package browser;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.geom.*;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.mod.*;
import mindustry.ui.*;
import arc.util.CommandHandler;
import org.cef.*;
import org.cef.browser.*;
import org.cef.handler.CefDisplayHandlerAdapter;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;


public class BrowserMod extends Mod {
    public static final boolean isAndroid = detectAndroid();
    private static boolean detectAndroid() {
        try { Class.forName("android.webkit.WebView"); return true; }
        catch (Throwable t) { return false; }
    }

    public static CefApp cefApp;
    public static CefClient cefClient;
    private static Method nDoMessageLoopWork;
    public static Seq<WorldBrowser> browsers = new Seq<>();
    public static boolean placing = false;
    static boolean browserReady = false;
    static WorldBrowser keyboardTarget = null;
    private static TextField kbDummy;
    private static final KeyCode[] ALL_KEYS = KeyCode.values();

    private static final boolean[] keyHeldState = new boolean[ALL_KEYS.length];
    private static final long[] keyFirstPressNs = new long[ALL_KEYS.length];
    private static final long[] keyLastRepeatNs = new long[ALL_KEYS.length];
    private static final long REPEAT_DELAY_NS  = 500_000_000L;
    private static final long REPEAT_RATE_NS   =  33_333_333L;

    private static final String[] DESKTOP_STUBS = {
        "java.beans.PropertyChangeListener",
        "java.beans.PropertyChangeEvent",
        "java.beans.PropertyChangeSupport",
        "java.awt.Component",
        "java.awt.AWTEvent",
        "java.awt.Rectangle",
        "java.awt.Point",
        "java.awt.Dimension",
        "java.awt.Insets",
        "java.awt.Color",
        "java.awt.Cursor",
        "java.awt.image.BufferedImage",
        "java.awt.event.ComponentEvent",
        "java.awt.event.InputEvent",
        "java.awt.event.KeyEvent",
        "java.awt.event.MouseEvent",
        "java.awt.event.MouseWheelEvent",
        "java.awt.event.ActionEvent",
        "java.awt.event.ActionListener",
        "java.awt.event.FocusEvent",
        "java.awt.event.FocusListener",
        "java.awt.event.WindowEvent",
        "java.awt.event.WindowListener",
        "java.awt.KeyboardFocusManager",
        "java.awt.Container",
    };

    private static void defineDesktopStubs() {
        boolean needed = false;
        try {
            Class.forName("java.awt.Component");
        } catch (Throwable e) {
            needed = true;
        }
        if (!needed) {
            return;
        }


        // Read IMPL_LOOKUP via Unsafe memory-access, then use it to call
        // ClassLoader.defineClass1 (native) which has no java.* SecurityException
        // and no module-lookup parameter.
        try {
            defineStubs_implLookup();
            return;
        } catch (Throwable e) {
            Log.err("BrowserMod: IMPL_LOOKUP strategy failed", e);
        }

        Log.err("BrowserMod: Stub injection failed. The mod requires java.desktop classes.");
    }

    private static void defineStubs_implLookup() throws Throwable {
        // Step 1: Get sun.misc.Unsafe instance (jdk.unsupported module, always available)
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        // Step 2: Use Unsafe memory-access to read IMPL_LOOKUP (fully-trusted Lookup)
        Method staticFieldOffset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field.class);
        staticFieldOffset.setAccessible(true);

        Method getObj;
        try {
            getObj = unsafeClass.getDeclaredMethod("getReference", Object.class, long.class);
        } catch (NoSuchMethodException e) {
            getObj = unsafeClass.getDeclaredMethod("getObject", Object.class, long.class);
        }
        getObj.setAccessible(true);

        Field implField = java.lang.invoke.MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        long offset = ((Long) staticFieldOffset.invoke(unsafe, implField)).longValue();
        java.lang.invoke.MethodHandles.Lookup trusted =
            (java.lang.invoke.MethodHandles.Lookup) getObj.invoke(unsafe,
                java.lang.invoke.MethodHandles.Lookup.class, offset);

        // Step 3: Get a MethodHandle for the native defineClass method
        java.lang.invoke.MethodHandle defineMH = null;
        boolean useDC1 = false;
        try {
            defineMH = trusted.findStatic(ClassLoader.class, "defineClass1",
                java.lang.invoke.MethodType.methodType(Class.class,
                    ClassLoader.class, String.class, byte[].class, int.class, int.class,
                    java.security.ProtectionDomain.class, String.class));
            useDC1 = true;
        } catch (Throwable e) {
            defineMH = trusted.findStatic(ClassLoader.class, "defineClass0",
                java.lang.invoke.MethodType.methodType(Class.class,
                    ClassLoader.class, Class.class, String.class, byte[].class,
                    int.class, int.class, java.security.ProtectionDomain.class,
                    boolean.class, int.class, Object.class));
        }

        // Step 4: Define each stub on the bootstrap classloader
        ClassLoader ownLoader = BrowserMod.class.getClassLoader();
        int ok = 0, skip = 0, fail = 0;
        for (String className : DESKTOP_STUBS) {
            try {
                Class.forName(className);
                skip++;
                continue;
            } catch (Throwable ignored) {}

            String resPath = "desktop-stubs/" + className.replace('.', '/') + ".class";
            InputStream is = ownLoader.getResourceAsStream(resPath);
            if (is == null) {
                Log.warn("BrowserMod: stub resource missing: @", resPath);
                fail++;
                continue;
            }

            try {
                byte[] bytes = readAllBytes(is);
                if (useDC1) {
                    defineMH.invokeWithArguments(null, className, bytes, 0, bytes.length, null, null);
                } else {
                    defineMH.invokeWithArguments(null, Object.class, className, bytes, 0, bytes.length, null, false, 0, null);
                }
                ok++;
            } catch (Throwable e) {
                Log.err("BrowserMod: failed to define " + className + ": " + e);
                fail++;
            }
        }
        if (ok == 0 && fail > 0) throw new RuntimeException("No stubs were successfully defined");
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.register("bw-debug", "Toggle Browser Windows debug logging", args -> {
            BrowserNet.verbose = !BrowserNet.verbose;
            if (Vars.ui != null) {
                Vars.ui.showInfoFade("[accent]BW debug: " + (BrowserNet.verbose ? "ON" : "OFF"));
            }
        });
    }

    @Override
    public void init() {
        if (!isAndroid) {
            defineDesktopStubs();
            Core.app.post(() -> {
                try {
                    initializeCEF();
                } catch (Exception e) {
                    Log.err("CEF initialization failed", e);
                }
            });
        } else {
            Core.app.post(() -> {
                browserReady = AndroidBrowser.initReflection();
                if (browserReady) Log.info("[BW] Android browser backend ready");
            });
        }

        BrowserNet.init();

        Events.on(ClientLoadEvent.class, e -> {
            Table addBtnTable = new Table();
            final boolean[] suppressClick = {false};
            final boolean[] longPressed = {false};
            final boolean[] hasCustomPos = {false};
            final boolean[] touchActive = {false};
            final float[] touchOffset = {0, 0};

            TextButton addBtn = addBtnTable.button("+ Browser", Icon.add, Styles.cleart, () -> {
                if (suppressClick[0]) return;
                if (!browserReady) {
                    Vars.ui.showInfo("Browser not available.\nBackend failed to load. Check logs.");
                    return;
                }
                placing = true;
            }).size(150, 50).margin(10).get();

            addBtn.addListener(new InputListener() {
                Timer.Task pressTask;

                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    suppressClick[0] = false;
                    longPressed[0] = false;
                    touchActive[0] = true;
                    touchOffset[0] = x;
                    touchOffset[1] = y;
                    pressTask = Timer.schedule(() -> Core.app.post(() -> {
                        if (touchActive[0]) {
                            longPressed[0] = true;
                            suppressClick[0] = true;
                        }
                    }), 0.4f);
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (longPressed[0]) {
                        hasCustomPos[0] = true;
                        float newX = addBtnTable.x + (x - touchOffset[0]);
                        float newY = addBtnTable.y + (y - touchOffset[1]);
                        newX = Math.max(0, Math.min(Core.graphics.getWidth() - addBtnTable.getPrefWidth(), newX));
                        newY = Math.max(0, Math.min(Core.graphics.getHeight() - addBtnTable.getPrefHeight(), newY));
                        addBtnTable.setPosition(newX, newY);
                    }
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    touchActive[0] = false;
                    if (pressTask != null) {
                        pressTask.cancel();
                        pressTask = null;
                    }
                    longPressed[0] = false;
                    if (suppressClick[0]) {
                        Timer.schedule(() -> Core.app.post(() -> suppressClick[0] = false), 0.15f);
                    }
                }
            });

            addBtnTable.pack();
            addBtnTable.update(() -> {
                if (!hasCustomPos[0]) {
                    addBtnTable.setPosition(
                        Core.graphics.getWidth() - addBtnTable.getPrefWidth() - 10,
                        10
                    );
                }
            });
            Vars.ui.hudGroup.addChild(addBtnTable);

            Vars.ui.hudGroup.fill(t -> {
                t.bottom();
                t.label(() -> keyboardTarget != null
                        ? (isAndroid
                            ? "[yellow]Keyboard captured. Tap [accent]KB[yellow] button to release."
                            : "[yellow]Keyboard captured. Press [accent]Esc[yellow] to release.")
                        : "").padBottom(60);
            });

            Events.run(Trigger.draw, () -> {
                Draw.z(Layer.overlayUI);
                browsers.each(WorldBrowser::draw);
            });

            Events.run(Trigger.update, () -> {
                try { BrowserNet.update(); } catch (Exception ignored) {}
                updateKeyboard();

                if (placing && Core.input.keyTap(KeyCode.mouseLeft)) {
                    createBrowser(Core.input.mouseWorld().x, Core.input.mouseWorld().y);
                    placing = false;
                }
                if (placing && Core.input.keyTap(KeyCode.mouseRight)) {
                    placing = false;
                }

                if (!isAndroid && cefApp != null && browserReady) {
                    pumpCefMessageLoop();
                }

                for (int i = 0; i < browsers.size; i++) {
                    browsers.get(i).update();
                }
            });
        });

        if (!isAndroid) {
            Events.on(DisposeEvent.class, e -> shutdownCEF());
            Runtime.getRuntime().addShutdownHook(new Thread(BrowserMod::shutdownCEF));
        }
    }

    private static void shutdownCEF() {
        if (!browserReady) return;
        browserReady = false;

        for (WorldBrowser wb : browsers) {
            wb.dispose();
        }
        browsers.clear();

        if (cefApp != null) {
            pumpCefMessageLoop();
        }

        if (cefClient != null) {
            try {
                cefClient.dispose();
            } catch (Exception ignored) {}
            cefClient = null;
        }
        if (cefApp != null) {
            try {
                cefApp.dispose();
            } catch (Exception ignored) {}
            cefApp = null;
        }

        killHelperProcesses();
    }

    private static void killHelperProcesses() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            Process p;
            if (os.contains("win")) {
                p = Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/IM", "jcef_helper.exe"});
            } else {
                p = Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "jcef_helper"});
            }
            p.waitFor();
        } catch (Exception e) {
            Log.err("Failed to kill helper processes: @", e.getMessage());
        }
    }

    static final String FOCUS_CANVAS_JS =
        "(function(){" +
        "var c=document.querySelector('canvas');" +
        "if(c){c.tabIndex=-1;c.focus({preventScroll:true});}" +
        "try{var fs=document.querySelectorAll('iframe');" +
        "for(var i=0;i<fs.length;i++){try{var fc=fs[i].contentDocument.querySelector('canvas');" +
        "if(fc){fc.tabIndex=-1;fc.focus({preventScroll:true});}}catch(e){}}}catch(e){}" +
        "})()";

    static void activateKeyboard(WorldBrowser target) {
        if (keyboardTarget == target) return;
        if (keyboardTarget != null) deactivateKeyboard();
        keyboardTarget = target;
        if (!isAndroid && target.browser != null) {
            target.browser.setFocus(true);
            executeInAllFrames(target.browser, FOCUS_CANVAS_JS);
        }
        if (isAndroid && target.androidBrowser != null) {
            target.androidBrowser.executeJavaScript(FOCUS_CANVAS_JS);
        }

        for (int i = 0; i < keyHeldState.length; i++) keyHeldState[i] = false;

        kbDummy = new TextField("");
        if (isAndroid) {
            kbDummy.setSize(1, 1);
            kbDummy.setPosition(-100, -100);
        } else {
            kbDummy.setSize(0, 0);
            kbDummy.setPosition(-9999, -9999);
        }
        Core.scene.add(kbDummy);
        Core.scene.setKeyboardFocus(kbDummy);
    }

    static void deactivateKeyboard() {
        if (!isAndroid && keyboardTarget != null && keyboardTarget.browser != null) {
            CefBrowser b = keyboardTarget.browser;
            int mods = getModifiers();
            for (int i = 0; i < ALL_KEYS.length; i++) {
                if (keyHeldState[i]) {
                    int vk = arcToVK(ALL_KEYS[i]);
                    if (vk != 0) {
                        CefBrowserHelper.sendKeyEvent(b,
                            CefBrowserHelper.makeKeyEvent(KeyEvent.KEY_RELEASED, vk,
                                KeyEvent.CHAR_UNDEFINED, mods));
                    }
                    keyHeldState[i] = false;
                }
            }
            b.setFocus(false);
        }
        if (kbDummy != null) {
            Core.scene.setKeyboardFocus(null);
            kbDummy.remove();
            kbDummy = null;
        }
        keyboardTarget = null;
    }

    private static void updateKeyboard() {
        if (keyboardTarget == null) return;
        if (!isAndroid && keyboardTarget.browser == null) return;
        if (isAndroid && keyboardTarget.androidBrowser == null) return;

        if (kbDummy != null) {
            if (Core.scene.getKeyboardFocus() != kbDummy) {
                if (Core.scene.getKeyboardFocus() == null) {
                    Core.scene.setKeyboardFocus(kbDummy);
                } else {
                    return;
                }
            }
        }

        if (Core.input.keyTap(KeyCode.escape)) {
            deactivateKeyboard();
            return;
        }

        int mods = getModifiers();
        boolean ctrl = (mods & KeyEvent.CTRL_DOWN_MASK) != 0;
        long now = System.nanoTime();

        for (int i = 0; i < ALL_KEYS.length; i++) {
            KeyCode key = ALL_KEYS[i];
            int vk = arcToVK(key);
            if (vk == 0) continue;

            if (Core.input.keyTap(key)) {
                keyHeldState[i] = true;
                keyFirstPressNs[i] = now;
                keyLastRepeatNs[i] = now;
                dispatchKey(KeyEvent.KEY_PRESSED, vk, vkToChar(vk, mods), mods);
            } else if (keyHeldState[i] && Core.input.keyDown(key)) {
                long sinceFirst = now - keyFirstPressNs[i];
                long sinceLast  = now - keyLastRepeatNs[i];
                if (sinceFirst > REPEAT_DELAY_NS && sinceLast > REPEAT_RATE_NS) {
                    keyLastRepeatNs[i] = now;
                    dispatchKey(KeyEvent.KEY_PRESSED, vk, vkToChar(vk, mods), mods);
                }
            }

            if (Core.input.keyRelease(key)) {
                keyHeldState[i] = false;
                dispatchKey(KeyEvent.KEY_RELEASED, vk, vkToChar(vk, mods), mods);
            }
        }

        if (kbDummy != null && !ctrl) {
            String typed = kbDummy.getText();
            for (int ci = 0; ci < typed.length(); ci++) {
                char ch = typed.charAt(ci);
                if (ch != 0) {
                    dispatchKey(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ch, 0);
                }
            }
        }
        if (kbDummy != null) {
            kbDummy.setText("");
            kbDummy.setCursorPosition(0);
        }
    }

    private static char vkToChar(int vk, int mods) {
        boolean shift = (mods & KeyEvent.SHIFT_DOWN_MASK) != 0;
        if (vk >= 0x41 && vk <= 0x5A) return shift ? (char) vk : (char) (vk + 32);
        if (vk >= 0x30 && vk <= 0x39) return (char) vk;
        if (vk == 0x20) return ' ';
        if (vk == 0x0D) return '\r';
        if (vk == 0x08) return '\b';
        if (vk == 0x09) return '\t';
        return KeyEvent.CHAR_UNDEFINED;
    }

    private static void dispatchKey(int eventType, int vk, char ch, int mods) {
        if (keyboardTarget == null) return;

        if (!isAndroid && keyboardTarget.browser != null) {
            CefBrowser b = keyboardTarget.browser;
            CefBrowserHelper.sendKeyEvent(b,
                CefBrowserHelper.makeKeyEvent(eventType, vk, ch, mods));
            if (eventType == KeyEvent.KEY_PRESSED) {
                injectKeyJS(b, "keydown", vk, mods);
                injectEditCommand(b, vk);
            } else if (eventType == KeyEvent.KEY_RELEASED) {
                injectKeyJS(b, "keyup", vk, mods);
            }
        } else if (isAndroid && keyboardTarget.androidBrowser != null) {
            AndroidBrowser ab = keyboardTarget.androidBrowser;
            if (eventType == KeyEvent.KEY_PRESSED) {
                ab.executeJavaScript(buildKeyJS("keydown", vk, mods));
            } else if (eventType == KeyEvent.KEY_RELEASED) {
                ab.executeJavaScript(buildKeyJS("keyup", vk, mods));
            } else if (eventType == KeyEvent.KEY_TYPED && ch != 0 && ch != KeyEvent.CHAR_UNDEFINED) {
                String safe = String.valueOf(ch).replace("\\", "\\\\").replace("'", "\\'");
                ab.executeJavaScript("(function(){var a=document.activeElement;"
                    + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable))"
                    + "document.execCommand('insertText',false,'" + safe + "');})()");
            }
        }

        if (BrowserNet.multiplayerActive) {
            char t = eventType == KeyEvent.KEY_PRESSED ? 'P'
                   : eventType == KeyEvent.KEY_RELEASED ? 'R' : 'T';
            BrowserNet.sendKey(keyboardTarget, t, vk, ch, mods);
        }
    }

    static String buildKeyJS(String jsType, int vk, int mods) {
        String key = vkToJsKey(vk, mods);
        String code = vkToJsCode(vk);
        boolean shift = (mods & KeyEvent.SHIFT_DOWN_MASK) != 0;
        boolean ctrl  = (mods & KeyEvent.CTRL_DOWN_MASK) != 0;
        boolean alt   = (mods & KeyEvent.ALT_DOWN_MASK) != 0;
        boolean printable = key.length() == 1 && vk != 0x1B && vk != 0x08 && vk != 0x09;

        StringBuilder js = new StringBuilder("(function(){var o={key:'");
        js.append(key.replace("\\", "\\\\").replace("'", "\\'")).append("',code:'").append(code);
        js.append("',keyCode:").append(vk).append(",which:").append(vk);
        js.append(",charCode:").append(printable ? (int) key.charAt(0) : 0);
        js.append(",shiftKey:").append(shift).append(",ctrlKey:").append(ctrl).append(",altKey:").append(alt);
        js.append(",repeat:false,bubbles:true,cancelable:true,composed:true};");
        js.append("function dp(el,t){try{el.dispatchEvent(new KeyboardEvent(t,o));}catch(x){}}");
        js.append("function go(w,d){dp(w,'").append(jsType).append("');dp(d,'").append(jsType).append("');");
        js.append("var ae=d.activeElement;if(ae&&ae!==d.body&&ae!==d.documentElement)dp(ae,'").append(jsType).append("');");
        js.append("var c=d.querySelectorAll('canvas');for(var i=0;i<c.length;i++){if(c[i]!==ae)dp(c[i],'").append(jsType).append("');}}");
        js.append("go(window,document);");
        if (printable && "keydown".equals(jsType)) {
            js.append("o={key:o.key,code:o.code,keyCode:o.key.charCodeAt(0),which:o.key.charCodeAt(0),");
            js.append("charCode:o.key.charCodeAt(0),shiftKey:o.shiftKey,ctrlKey:o.ctrlKey,altKey:o.altKey,");
            js.append("bubbles:true,cancelable:true};go(window,document);");
        }
        js.append("})()");
        return js.toString();
    }

    static void executeInAllFrames(CefBrowser b, String js) {
        b.executeJavaScript(js, "", 0);
        try {
            java.util.Vector<String> ids = b.getFrameIdentifiers();
            if (ids != null) {
                for (int i = 0; i < ids.size(); i++) {
                    CefFrame frame = b.getFrameByIdentifier(ids.get(i));
                    if (frame != null && frame.isValid() && !frame.isMain()) {
                        frame.executeJavaScript(js, "", 0);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    static void injectKeyJS(CefBrowser b, String jsType, int vk, int mods) {
        String key = vkToJsKey(vk, mods);
        String code = vkToJsCode(vk);
        boolean shift = (mods & KeyEvent.SHIFT_DOWN_MASK) != 0;
        boolean ctrl  = (mods & KeyEvent.CTRL_DOWN_MASK) != 0;
        boolean alt   = (mods & KeyEvent.ALT_DOWN_MASK) != 0;
        boolean printable = key.length() == 1 && vk != 0x1B && vk != 0x08 && vk != 0x09;

        String opts = "{key:'" + key.replace("\\", "\\\\").replace("'", "\\'") + "',"
            + "code:'" + code + "',"
            + "keyCode:" + vk + ",which:" + vk + ","
            + "charCode:" + (printable ? (int) key.charAt(0) : 0) + ","
            + "shiftKey:" + shift + ",ctrlKey:" + ctrl + ",altKey:" + alt + ","
            + "repeat:false,bubbles:true,cancelable:true,composed:true}";

        StringBuilder js = new StringBuilder("(function(){var o=").append(opts).append(";");
        js.append("var t='").append(jsType).append("';");
        js.append("function dp(el,tp){try{el.dispatchEvent(new KeyboardEvent(tp,o));}catch(x){}}");
        js.append("function go(w,d){");
        js.append("dp(w,t);dp(d,t);");
        js.append("var ae=d.activeElement;");
        js.append("if(ae&&ae!==d.body&&ae!==d.documentElement)dp(ae,t);");
        js.append("var c=d.querySelectorAll('canvas');");
        js.append("for(var i=0;i<c.length;i++){if(c[i]!==ae)dp(c[i],t);}");
        js.append("}");
        js.append("go(window,document);");

        js.append("try{var fs=document.querySelectorAll('iframe');");
        js.append("for(var f=0;f<fs.length;f++){try{var w=fs[f].contentWindow;");
        js.append("if(w)go(w,w.document);}catch(e){}}}catch(e){}");

        if (printable && "keydown".equals(jsType)) {
            js.append("o={key:o.key,code:o.code,keyCode:o.key.charCodeAt(0),which:o.key.charCodeAt(0),");
            js.append("charCode:o.key.charCodeAt(0),shiftKey:o.shiftKey,ctrlKey:o.ctrlKey,altKey:o.altKey,");
            js.append("bubbles:true,cancelable:true};t='keypress';");
            js.append("go(window,document);");
            js.append("try{var fs2=document.querySelectorAll('iframe');");
            js.append("for(var f2=0;f2<fs2.length;f2++){try{var w2=fs2[f2].contentWindow;");
            js.append("if(w2)go(w2,w2.document);}catch(e){}}}catch(e){}");
        }
        js.append("})()");
        executeInAllFrames(b, js.toString());
    }

    static void injectEditCommand(CefBrowser b, int vk) {
        String cmd = null;
        switch (vk) {
            case 0x08: cmd = "delete"; break;
            case 0x2E: cmd = "forwardDelete"; break;
            case 0x0D: cmd = "insertParagraph"; break;
        }
        if (cmd == null) return;
        String js = "(function(){var a=document.activeElement;"
            + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable))"
            + "document.execCommand('" + cmd + "',false);})()";
        executeInAllFrames(b, js);
    }

    private static String vkToJsKey(int vk, int mods) {
        boolean shift = (mods & KeyEvent.SHIFT_DOWN_MASK) != 0;
        if (vk >= 0x41 && vk <= 0x5A) {
            char c = shift ? (char) vk : (char) (vk + 32);
            return String.valueOf(c);
        }
        if (vk >= 0x30 && vk <= 0x39) return String.valueOf((char) vk);
        if (vk >= 0x60 && vk <= 0x69) return String.valueOf(vk - 0x60);
        if (vk >= 0x70 && vk <= 0x7B) return "F" + (vk - 0x70 + 1);
        switch (vk) {
            case 0x20: return " ";
            case 0x0D: return "Enter";
            case 0x08: return "Backspace";
            case 0x09: return "Tab";
            case 0x1B: return "Escape";
            case 0x2E: return "Delete";
            case 0x2D: return "Insert";
            case 0x25: return "ArrowLeft";
            case 0x26: return "ArrowUp";
            case 0x27: return "ArrowRight";
            case 0x28: return "ArrowDown";
            case 0x24: return "Home";
            case 0x23: return "End";
            case 0x21: return "PageUp";
            case 0x22: return "PageDown";
            case 0x10: return "Shift";
            case 0x11: return "Control";
            case 0x12: return "Alt";
            case 0x14: return "CapsLock";
            case 0xBD: return shift ? "_" : "-";
            case 0xBB: return shift ? "+" : "=";
            case 0xBA: return shift ? ":" : ";";
            case 0xBC: return shift ? "<" : ",";
            case 0xBE: return shift ? ">" : ".";
            case 0xBF: return shift ? "?" : "/";
            case 0xDC: return shift ? "|" : "\\\\";
            case 0xDB: return shift ? "{" : "[";
            case 0xDD: return shift ? "}" : "]";
            case 0xDE: return shift ? "\"" : "'";
            case 0xC0: return shift ? "~" : "`";
            default: return "Unidentified";
        }
    }

    private static String vkToJsCode(int vk) {
        if (vk >= 0x41 && vk <= 0x5A) return "Key" + (char) vk;
        if (vk >= 0x30 && vk <= 0x39) return "Digit" + (char) vk;
        if (vk >= 0x60 && vk <= 0x69) return "Numpad" + (vk - 0x60);
        if (vk >= 0x70 && vk <= 0x7B) return "F" + (vk - 0x70 + 1);
        switch (vk) {
            case 0x20: return "Space";
            case 0x0D: return "Enter";
            case 0x08: return "Backspace";
            case 0x09: return "Tab";
            case 0x1B: return "Escape";
            case 0x2E: return "Delete";
            case 0x2D: return "Insert";
            case 0x25: return "ArrowLeft";
            case 0x26: return "ArrowUp";
            case 0x27: return "ArrowRight";
            case 0x28: return "ArrowDown";
            case 0x24: return "Home";
            case 0x23: return "End";
            case 0x21: return "PageUp";
            case 0x22: return "PageDown";
            case 0x10: return "ShiftLeft";
            case 0x11: return "ControlLeft";
            case 0x12: return "AltLeft";
            case 0x14: return "CapsLock";
            case 0xBD: return "Minus";
            case 0xBB: return "Equal";
            case 0xBA: return "Semicolon";
            case 0xBC: return "Comma";
            case 0xBE: return "Period";
            case 0xBF: return "Slash";
            case 0xDC: return "Backslash";
            case 0xDB: return "BracketLeft";
            case 0xDD: return "BracketRight";
            case 0xDE: return "Quote";
            case 0xC0: return "Backquote";
            default: return "Unidentified";
        }
    }

    private static int getModifiers() {
        int m = 0;
        if (Core.input.keyDown(KeyCode.shiftLeft) || Core.input.keyDown(KeyCode.shiftRight))
            m |= KeyEvent.SHIFT_DOWN_MASK;
        if (Core.input.keyDown(KeyCode.controlLeft) || Core.input.keyDown(KeyCode.controlRight))
            m |= KeyEvent.CTRL_DOWN_MASK;
        if (Core.input.keyDown(KeyCode.altLeft) || Core.input.keyDown(KeyCode.altRight))
            m |= KeyEvent.ALT_DOWN_MASK;
        return m;
    }

    static int arcToVK(KeyCode key) {
        if (key == null) return 0;
        switch (key) {
            case a: return 0x41;  case b: return 0x42;
            case c: return 0x43;  case d: return 0x44;
            case e: return 0x45;  case f: return 0x46;
            case g: return 0x47;  case h: return 0x48;
            case i: return 0x49;  case j: return 0x4A;
            case k: return 0x4B;  case l: return 0x4C;
            case m: return 0x4D;  case n: return 0x4E;
            case o: return 0x4F;  case p: return 0x50;
            case q: return 0x51;  case r: return 0x52;
            case s: return 0x53;  case t: return 0x54;
            case u: return 0x55;  case v: return 0x56;
            case w: return 0x57;  case x: return 0x58;
            case y: return 0x59;  case z: return 0x5A;

            case num0: return 0x30;  case num1: return 0x31;
            case num2: return 0x32;  case num3: return 0x33;
            case num4: return 0x34;  case num5: return 0x35;
            case num6: return 0x36;  case num7: return 0x37;
            case num8: return 0x38;  case num9: return 0x39;

            case space:     return 0x20;
            case enter:     return 0x0D;
            case backspace: return 0x08;
            case tab:       return 0x09;
            case escape:    return 0x1B;
            case del:       return 0x2E;
            case insert:    return 0x2D;

            case left:  return 0x25;  case up:    return 0x26;
            case right: return 0x27;  case down:  return 0x28;
            case home:  return 0x24;  case end:   return 0x23;
            case pageUp:   return 0x21;
            case pageDown: return 0x22;

            case shiftLeft: case shiftRight:     return 0x10;
            case controlLeft: case controlRight: return 0x11;
            case altLeft: case altRight:         return 0x12;
            case capsLock: return 0x14;

            case minus:        return 0xBD;
            case equals:       return 0xBB;
            case semicolon:    return 0xBA;
            case comma:        return 0xBC;
            case period:       return 0xBE;
            case slash:        return 0xBF;
            case backslash:    return 0xDC;
            case leftBracket:  return 0xDB;
            case rightBracket: return 0xDD;
            case apostrophe:   return 0xDE;
            case backtick:     return 0xC0;

            case f1:  return 0x70;  case f2:  return 0x71;
            case f3:  return 0x72;  case f4:  return 0x73;
            case f5:  return 0x74;  case f6:  return 0x75;
            case f7:  return 0x76;  case f8:  return 0x77;
            case f9:  return 0x78;  case f10: return 0x79;
            case f11: return 0x7A;  case f12: return 0x7B;

            case numpad0: return 0x60;  case numpad1: return 0x61;
            case numpad2: return 0x62;  case numpad3: return 0x63;
            case numpad4: return 0x64;  case numpad5: return 0x65;
            case numpad6: return 0x66;  case numpad7: return 0x67;
            case numpad8: return 0x68;  case numpad9: return 0x69;

            case pause:       return 0x13;
            case printScreen: return 0x2C;
            case scrollLock:  return 0x91;

            default: return 0;
        }
    }

    private void initializeCEF() {
        NativeLoader.extractAndLoad();
        File nativesDir = NativeLoader.getNativesDir();
        if (nativesDir == null || !nativesDir.exists()) {
            throw new RuntimeException("Natives directory not found after extraction");
        }

        File modDir = new File(Core.settings.getDataDirectory().child("mods").file(), "browser-windows");
        File cefDataDir = new File(modDir, "cef-data");
        cefDataDir.mkdirs();

        CefApp.startup(new String[0]);

        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = true;
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO;
        settings.resources_dir_path = nativesDir.getAbsolutePath();

        File localesDir = new File(nativesDir, "locales");
        if (localesDir.exists()) {
            settings.locales_dir_path = localesDir.getAbsolutePath();
        }

        File helperExe = findHelper(nativesDir);
        if (helperExe != null) {
            settings.browser_subprocess_path = helperExe.getAbsolutePath();
        }

        settings.root_cache_path = cefDataDir.getAbsolutePath();
        settings.cache_path = new File(cefDataDir, "cache").getAbsolutePath();
        settings.log_file = "";

        String[] cefArgs = {
            "--enable-gpu-rasterization",
            "--ignore-gpu-blocklist",
            "--disable-gpu-compositing"
        };

        cefApp = CefApp.getInstance(cefArgs, settings);
        cefClient = cefApp.createClient();
        SwingQueue.processQueue();

        cefClient.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser b, CefFrame frame, String url) {
                for (int i = 0; i < browsers.size; i++) {
                    WorldBrowser wb = browsers.get(i);
                    if (wb.browser == b && !wb.remote) {
                        if (url != null && !url.equals(wb.currentURL) && !"about:blank".equals(url)) {
                            wb.currentURL = url;
                            if (BrowserNet.multiplayerActive) {
                                BrowserNet.sendUrl(wb);
                            }
                        }
                        break;
                    }
                }
            }

            @Override
            public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String message, String source, int line) {
                if (message != null && message.startsWith("BMEDIA:")) {
                    for (int i = 0; i < browsers.size; i++) {
                        WorldBrowser wb = browsers.get(i);
                        if (wb.browser == b && !wb.remote) {
                            wb.onMediaStateFromJs(message.substring(7));
                            break;
                        }
                    }
                    return true;
                }
                return false;
            }
        });

        browserReady = true;

        if (BrowserNet.multiplayerActive || Vars.net.client()) {
            BrowserNet.requestResync();
        }
    }

    /**
     * Pumps the CEF message loop by calling N_DoMessageLoopWork directly via
     * reflection (bypassing the SwingUtilities.invokeLater wrapper that would
     * cause re-entrant native calls), then drains any queued Swing callbacks.
     */
    private static void pumpCefMessageLoop() {
        try {
            if (nDoMessageLoopWork == null) {
                nDoMessageLoopWork = CefApp.class.getDeclaredMethod("N_DoMessageLoopWork");
                nDoMessageLoopWork.setAccessible(true);
            }
            nDoMessageLoopWork.invoke(cefApp);
        } catch (Throwable ignored) {}
        SwingQueue.processQueue();
    }

    private File findHelper(File dir) {
        String os = System.getProperty("os.name").toLowerCase();
        String name = os.contains("win") ? "jcef_helper.exe" : "jcef_helper";
        File f = new File(dir, name);
        if (f.exists()) return f;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().contains("helper")) return file;
            }
        }
        return null;
    }

    private static final String TUTORIAL_SEEN_KEY = "browsermod-tutorial-seen";

    public static void createBrowser(float x, float y) {
        if (!browserReady) {
            Vars.ui.showInfo("Browser backend is not initialized.");
            return;
        }
        if (!isAndroid && cefClient == null) {
            Vars.ui.showInfo("CEF is not initialized.");
            return;
        }

        String id = BrowserNet.shortId();
        String owner = Vars.player != null ? BrowserNet.cleanName(Vars.player.name()) : "Local";
        WorldBrowser wb = new WorldBrowser(id, owner, false, x, y, 960, 540, null);
        browsers.add(wb);

        boolean tutorialSeen = Core.settings.getBool(TUTORIAL_SEEN_KEY, false);
        if (!tutorialSeen) {
            showTutorialDialog(() -> showEnterUrlDialog(wb));
        } else {
            showEnterUrlDialog(wb);
        }
    }

    private static void showTutorialDialog(Runnable onClose) {

        new Dialog("Tutorial") {{
            cont.add("A short tutorial to get familiar with the buttons in the image.")
                .wrap().width(400).padBottom(12).left();
            cont.row();
            cont.image(Core.atlas.find("browser-windows-tutorial-image")).padBottom(12).row();
            cont.add("Thanks for installing the mod!").wrap().width(400).padBottom(16).left();
            cont.row();
            TextButton gotItBtn = buttons.button("Got it", () -> {
                Core.settings.put(TUTORIAL_SEEN_KEY, true);
                Core.settings.forceSave();
                hide();
                onClose.run();
            }).get();
            gotItBtn.setDisabled(true);
            Timer.schedule(() -> Core.app.post(() -> gotItBtn.setDisabled(false)), 5f);
        }}.show();
    }

    private static void showEnterUrlDialog(WorldBrowser wb) {
        new Dialog("Enter URL") {{
            if (BrowserNet.isOnMultiplayerServer() && !BrowserNet.handshakeReceived) {
                cont.add("[scarlet]Server does not have the sync plugin.\nOther players will not see your windows.[]")
                    .colspan(2).pad(5).wrap().width(300);
                cont.row();
            }
            cont.add("URL:").padRight(10);
            TextField field = new TextField();
            field.setText("https://www.google.com");
            cont.add(field).width(300).pad(5);
            buttons.button("Load", () -> {
                wb.loadURL(field.getText());
                if (BrowserNet.multiplayerActive) {
                    BrowserNet.sendCreate(wb);
                }
                hide();
            });
            buttons.button("Cancel", this::hide);
        }}.show();
    }
}

class WorldBrowser {
    String id;
    String ownerName;
    boolean remote;

    float x, y;
    int browserW, browserH;
    float worldW, worldH;

    CefBrowser browser;
    MindustryRenderer renderer;
    AndroidBrowser androidBrowser;

    boolean dragging, resizing;
    final Vec2 dragOffset = new Vec2();
    final Vec2 resizeStartPos = new Vec2();
    float resizeStartWorldW, resizeStartWorldH;
    String currentURL = "";

    String lastVideoId = "";
    float lastVideoTime = 0;
    boolean lastVideoPlaying = false;

    private Texture texture;
    private TextureRegion texRegion;
    private int texW, texH;

    static final float SCALE = 7f / 48f;
    static final float HEADER_H = 8f;
    static final float NAME_H = 4f;
    static final float RESIZE_CORNER = 5f;
    static final float BTN_SIZE = HEADER_H * 0.7f;

    private static TextureRegion backBtnTex, fwdBtnTex, menuBtnTex, kbBtnTex, kbBtnActiveTex, closeBtnTex;

    private static void loadHeaderTextures() {
        if (backBtnTex != null) return;
        try {
            var mod = Vars.mods.getMod(BrowserMod.class);
            if (mod == null) return;
            var root = mod.root.child("sprites");
            backBtnTex = new TextureRegion(new Texture(root.child("arrow-left-btn.png")));
            fwdBtnTex = new TextureRegion(new Texture(root.child("arrow-right-btn.png")));
            menuBtnTex = new TextureRegion(new Texture(root.child("menu-btn.png")));
            kbBtnTex = new TextureRegion(new Texture(root.child("keyboard-btn.png")));
            kbBtnActiveTex = new TextureRegion(new Texture(root.child("keyboard-btn-active.png")));
            closeBtnTex = new TextureRegion(new Texture(root.child("close-btn.png")));
        } catch (Throwable t) {
            Log.err("BrowserMod: Failed to load header button textures", t);
        }
    }
    static final int MIN_PX = 320;
    static final int MAX_PX_W = 1920, MAX_PX_H = 1200;

    WorldBrowser(String id, String ownerName, boolean remote, float x, float y, int pixelW, int pixelH, String url) {
        this.id = id;
        this.ownerName = ownerName;
        this.remote = remote;
        this.x = x;
        this.y = y;
        this.browserW = pixelW;
        this.browserH = pixelH;
        recalcWorldSize();

        if (BrowserMod.isAndroid) {
            androidBrowser = new AndroidBrowser(browserW, browserH,
                (url != null && !url.isEmpty() && !"about:blank".equals(url)) ? url : null);
        } else if (BrowserMod.cefClient != null) {
            renderer = new MindustryRenderer(browserW, browserH);
            browser = BrowserMod.cefClient.createBrowser("about:blank", true, false);
            CefBrowserHelper.wasResized(browser, browserW, browserH);
            browser.createImmediately();
            browser.getRenderHandler().addOnPaintListener(renderer);
            browser.setFocus(true);

            if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
                if (remote) {
                    final String u = url;
                    Timer.schedule(() -> Core.app.post(() -> loadURL(u)), 0.5f);
                } else {
                    loadURL(url);
                }
            }
        }
    }

    void recalcWorldSize() {
        worldW = browserW * SCALE;
        worldH = browserH * SCALE + HEADER_H;
    }

    void resizeTo(int w, int h) {
        browserW = Math.max(MIN_PX, Math.min(MAX_PX_W, w));
        browserH = Math.max(MIN_PX, Math.min(MAX_PX_H, h));
        recalcWorldSize();
        disposeGpu();
        if (browser != null) {
            CefBrowserHelper.wasResized(browser, browserW, browserH);
        }
        if (androidBrowser != null) {
            androidBrowser.resize(browserW, browserH);
        }
    }

    void loadURL(String url) {
        if (url == null || url.trim().isEmpty()) url = "about:blank";
        if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("about:") && !url.startsWith("data:")) {
            url = "https://" + url;
        }
        currentURL = url;
        if (browser != null) {
            browser.loadURL(url);
            browser.setFocus(true);
        }
        if (androidBrowser != null) {
            androidBrowser.loadUrl(url);
        }
    }

    private boolean isOnScreen() {
        float camX = Core.camera.position.x;
        float camY = Core.camera.position.y;
        float halfCamW = Core.camera.width / 2f;
        float halfCamH = Core.camera.height / 2f;
        return (x + worldW / 2 > camX - halfCamW) && (x - worldW / 2 < camX + halfCamW)
                && (y + worldH / 2 > camY - halfCamH) && (y - worldH / 2 < camY + halfCamH);
    }

    void draw() {
        if (!isOnScreen()) return;

        updateTexture();
        Draw.z(Layer.overlayUI);

        float contentWorldH = worldH - HEADER_H;

        Draw.color(Color.gray);
        Fill.rect(x, y, worldW, worldH);

        Draw.color(Color.darkGray);
        Fill.rect(x, y + worldH / 2 - HEADER_H / 2, worldW, HEADER_H);

        if (texRegion != null) {
            Draw.color(Color.white);
            Draw.rect(texRegion, x, y - HEADER_H / 2, worldW, contentWorldH);
        } else {
            Draw.color(Color.white);
            Fill.rect(x, y - HEADER_H / 2, worldW, contentWorldH);
        }

        Draw.color(Color.black);
        Lines.stroke(0.4f);
        Lines.rect(x - worldW / 2, y - worldH / 2, worldW, worldH);

        if (!remote) {
            Draw.color(Color.lightGray);
            Fill.rect(x + worldW / 2 - RESIZE_CORNER / 2,
                    y - worldH / 2 + RESIZE_CORNER / 2,
                    RESIZE_CORNER, RESIZE_CORNER);
        }

        float headerY = y + worldH / 2 - HEADER_H / 2;
        float btnR = HEADER_H * 0.35f;
        float btnW = btnR * 2;
        float btnStep = btnR * 2 + 1;
        float nextX = x - worldW / 2 + btnR + 1;

        loadHeaderTextures();

        if (backBtnTex != null) {
            Draw.color(Color.white);
            Draw.rect(backBtnTex, nextX, headerY, btnW, btnW);
        } else {
            Draw.color(Color.valueOf("6677aa"));
            Fill.circle(nextX, headerY, btnR);
            Draw.color(Color.white);
            Lines.stroke(0.25f);
            float ar = btnR * 0.45f;
            Lines.line(nextX + ar * 0.3f, headerY + ar, nextX - ar * 0.5f, headerY);
            Lines.line(nextX - ar * 0.5f, headerY, nextX + ar * 0.3f, headerY - ar);
        }
        nextX += btnStep;

        if (fwdBtnTex != null) {
            Draw.color(Color.white);
            Draw.rect(fwdBtnTex, nextX, headerY, btnW, btnW);
        } else {
            Draw.color(Color.valueOf("6677aa"));
            Fill.circle(nextX, headerY, btnR);
            Draw.color(Color.white);
            Lines.stroke(0.25f);
            float ar = btnR * 0.45f;
            Lines.line(nextX - ar * 0.3f, headerY + ar, nextX + ar * 0.5f, headerY);
            Lines.line(nextX + ar * 0.5f, headerY, nextX - ar * 0.3f, headerY - ar);
        }
        nextX += btnStep;

        if (menuBtnTex != null) {
            Draw.color(Color.white);
            Draw.rect(menuBtnTex, nextX, headerY, btnW, btnW);
        } else {
            Draw.color(Color.valueOf("5588cc"));
            Fill.circle(nextX, headerY, btnR);
            Draw.color(Color.white);
            Lines.stroke(0.25f);
            float barH = btnR * 0.3f;
            for (int bi = -1; bi <= 1; bi++) {
                float by = headerY + bi * barH * 1.2f;
                Lines.line(nextX - btnR * 0.5f, by, nextX + btnR * 0.5f, by);
            }
        }
        nextX += btnStep;

        boolean kbActive = BrowserMod.keyboardTarget == this;
        TextureRegion kbTex = kbActive ? kbBtnActiveTex : kbBtnTex;
        if (kbTex != null) {
            Draw.color(Color.white);
            Draw.rect(kbTex, nextX, headerY, btnW, btnW);
        } else {
            Draw.color(kbActive ? Color.yellow : Color.valueOf("55aa55"));
            Fill.circle(nextX, headerY, btnR);
        }
        float kbBtnX = nextX;
        nextX += btnStep;

        // URL text
        Draw.color(Color.white);
        String disp = currentURL.isEmpty() ? "about:blank" :
                (currentURL.length() > 40 ? currentURL.substring(0, 37) + "..." : currentURL);
        float fontSize = HEADER_H / Fonts.def.getLineHeight();
        float urlStartX = kbBtnX + btnR + 2;
        float urlY = y + worldH / 2 - HEADER_H * 0.25f;
        try {
            Fonts.def.getData().setScale(fontSize * 0.6f);
            Fonts.def.draw(disp, urlStartX, urlY, Align.left);
        } finally {
            Fonts.def.getData().setScale(1f);
        }

        if (!remote) {
            float cx = x + worldW / 2 - btnR - 1;
            if (closeBtnTex != null) {
                Draw.color(Color.white);
                Draw.rect(closeBtnTex, cx, headerY, btnW, btnW);
            } else {
                Draw.color(Color.red);
                Fill.circle(cx, headerY, btnR);
                Draw.color(Color.white);
                Lines.stroke(0.3f);
                float d = btnR * 0.55f;
                Lines.line(cx - d, headerY - d, cx + d, headerY + d);
                Lines.line(cx + d, headerY - d, cx - d, headerY + d);
            }
        }

        if (ownerName != null && BrowserNet.multiplayerActive) {
            Draw.color(Color.white);
            float nameScale = NAME_H / Fonts.def.getLineHeight();
            try {
                Fonts.def.getData().setScale(nameScale * 0.7f);
                Fonts.def.draw(ownerName, x, y + worldH / 2 + NAME_H * 0.8f, Align.center);
            } finally {
                Fonts.def.getData().setScale(1f);
            }
        }

        Draw.color();
    }

    void update() {
        Vec2 m = Core.input.mouseWorld();

        // Header button hit tests on tap
        if (Core.input.keyTap(KeyCode.mouseLeft) && isInHeader(m.x, m.y)) {
            if (isInBackButton(m.x, m.y)) {
                if (browser != null) browser.goBack();
                if (androidBrowser != null) androidBrowser.goBack();
                return;
            }
            if (isInForwardButton(m.x, m.y)) {
                if (browser != null) browser.goForward();
                if (androidBrowser != null) androidBrowser.goForward();
                return;
            }
            if (isInMenuButton(m.x, m.y)) {
                showContextMenu();
                return;
            }
            if (isInKbButton(m.x, m.y)) {
                if (BrowserMod.keyboardTarget == this) {
                    BrowserMod.deactivateKeyboard();
                } else {
                    BrowserMod.activateKeyboard(this);
                }
                return;
            }
        }

        if (!remote && Core.input.keyTap(KeyCode.mouseLeft) && isInCloseButton(m.x, m.y)) {
            if (BrowserNet.multiplayerActive) BrowserNet.sendDelete(this);
            dispose();
            BrowserMod.browsers.remove(this);
            return;
        }

        if (!remote && Core.input.keyTap(KeyCode.mouseLeft) && isInHeader(m.x, m.y) && !resizing) {
            dragging = true;
            dragOffset.set(m.x - x, m.y - y);
        }

        if (!remote && Core.input.keyTap(KeyCode.mouseLeft) && isInResizeCorner(m.x, m.y) && !dragging) {
            resizing = true;
            resizeStartPos.set(m.x, m.y);
            resizeStartWorldW = worldW;
            resizeStartWorldH = worldH;
        }

        if (Core.input.keyRelease(KeyCode.mouseLeft)) {
            boolean wasDragging = dragging;
            boolean wasResizing = resizing;
            dragging = false;
            resizing = false;

            if (!remote && BrowserNet.multiplayerActive) {
                if (wasDragging) BrowserNet.sendMove(this);
                if (wasResizing) BrowserNet.sendResize(this);
            }
        }

        if (dragging) {
            x = m.x - dragOffset.x;
            y = m.y - dragOffset.y;
        }

        if (resizing) {
            float dxW = m.x - resizeStartPos.x;
            float dyH = m.y - resizeStartPos.y;
            float nwW = Math.max(MIN_PX * SCALE, Math.min(MAX_PX_W * SCALE, resizeStartWorldW + dxW));
            float nwH = Math.max(MIN_PX * SCALE + HEADER_H,
                    Math.min(MAX_PX_H * SCALE + HEADER_H, resizeStartWorldH - dyH));

            if (Math.abs(nwW - worldW) > SCALE * 8 || Math.abs(nwH - worldH) > SCALE * 8) {
                worldW = nwW;
                worldH = nwH;
                browserW = Math.max(MIN_PX, Math.min(MAX_PX_W, (int) (worldW / SCALE)));
                browserH = Math.max(MIN_PX, Math.min(MAX_PX_H, (int) ((worldH - HEADER_H) / SCALE)));
                disposeGpu();
                if (browser != null) CefBrowserHelper.wasResized(browser, browserW, browserH);
                if (androidBrowser != null) androidBrowser.resize(browserW, browserH);
            }
        }

        if (!dragging && !resizing && isOnScreen() && isInBrowserArea(m.x, m.y)) {
            int bx = toBrowserX(m.x);
            int by = toBrowserY(m.y);

            if (Core.input.keyTap(KeyCode.mouseLeft)) {
                sendMouse(MouseEvent.MOUSE_PRESSED, bx, by, MouseEvent.BUTTON1);
            } else if (Core.input.keyDown(KeyCode.mouseLeft)) {
                sendMouse(MouseEvent.MOUSE_DRAGGED, bx, by, MouseEvent.BUTTON1);
            }
            if (Core.input.keyRelease(KeyCode.mouseLeft)) {
                sendMouse(MouseEvent.MOUSE_RELEASED, bx, by, MouseEvent.BUTTON1);
            }

            if (!BrowserMod.isAndroid) {
                if (Core.input.keyTap(KeyCode.mouseMiddle)) {
                    sendMouse(MouseEvent.MOUSE_PRESSED, bx, by, MouseEvent.BUTTON2);
                }
                if (Core.input.keyRelease(KeyCode.mouseMiddle)) {
                    sendMouse(MouseEvent.MOUSE_RELEASED, bx, by, MouseEvent.BUTTON2);
                }

                if (!Core.input.keyDown(KeyCode.mouseLeft) && browser != null) {
                    CefBrowserHelper.sendMouseEvent(browser,
                        CefBrowserHelper.makeMouseEvent(MouseEvent.MOUSE_MOVED, bx, by, MouseEvent.NOBUTTON, 1, 0));
                }
            }

            float scroll = Core.input.axis(KeyCode.scroll);
            if (scroll != 0) {
                int scrollDir = scroll > 0 ? -3 : 3;
                if (browser != null) {
                    CefBrowserHelper.sendMouseWheelEvent(browser,
                        CefBrowserHelper.makeMouseWheelEvent(bx, by, scrollDir));
                }
                if (BrowserNet.multiplayerActive) {
                    BrowserNet.sendScroll(this, bx, by, scrollDir);
                }
            }
        }

        if (!BrowserMod.isAndroid && isInBrowserArea(m.x, m.y)) {
            int bx = toBrowserX(m.x);
            int by = toBrowserY(m.y);
            if (Core.input.keyTap(KeyCode.mouseRight)) {
                sendMouse(MouseEvent.MOUSE_PRESSED, bx, by, MouseEvent.BUTTON3);
            }
            if (Core.input.keyRelease(KeyCode.mouseRight)) {
                sendMouse(MouseEvent.MOUSE_RELEASED, bx, by, MouseEvent.BUTTON3);
            }
        }
    }


    private void sendMouse(int id, int bx, int by, int button) {
        if (BrowserMod.isAndroid) {
            if (androidBrowser == null) return;
            int action;
            if (id == MouseEvent.MOUSE_PRESSED) action = AndroidBrowser.ACTION_DOWN;
            else if (id == MouseEvent.MOUSE_RELEASED) action = AndroidBrowser.ACTION_UP;
            else if (id == MouseEvent.MOUSE_DRAGGED) action = AndroidBrowser.ACTION_MOVE;
            else return;
            androidBrowser.sendTouchEvent(action, bx, by);
            if (id == MouseEvent.MOUSE_PRESSED) {
                androidBrowser.executeJavaScript(BrowserMod.FOCUS_CANVAS_JS);
            }
        } else {
            if (browser == null) return;
            CefBrowserHelper.sendMouseEvent(browser,
                    CefBrowserHelper.makeMouseEvent(id, bx, by, button, 1, 0));
            if (id == MouseEvent.MOUSE_PRESSED) {
                BrowserMod.executeInAllFrames(browser, BrowserMod.FOCUS_CANVAS_JS);
            }
        }

        if (BrowserNet.multiplayerActive
                && id != MouseEvent.MOUSE_MOVED
                && id != MouseEvent.MOUSE_DRAGGED) {
            BrowserNet.sendMouseInput(this, id, bx, by, button);
        }
    }

    private int toBrowserX(float wx) {
        float leftEdge = x - worldW / 2;
        float localX = wx - leftEdge;
        int px = (int) (localX / SCALE);
        return Math.max(0, Math.min(browserW - 1, px));
    }

    private int toBrowserY(float wy) {
        float contentTop = y + worldH / 2 - HEADER_H;
        float localY = contentTop - wy;
        int py = (int) (localY / SCALE);
        return Math.max(0, Math.min(browserH - 1, py));
    }

    private static final int GL_BGRA = 0x80E1;

    private void updateTexture() {
        ByteBuffer pixels;
        int w, h;
        int pixelFormat;

        if (BrowserMod.isAndroid) {
            if (androidBrowser == null) return;
            pixels = androidBrowser.consumeFrame();
            if (pixels == null) return;
            w = androidBrowser.getWidth();
            h = androidBrowser.getHeight();
            pixelFormat = Gl.rgba;
        } else {
            if (renderer == null) return;
            pixels = renderer.consumeFrame();
            if (pixels == null) return;
            w = renderer.getWidth();
            h = renderer.getHeight();
            pixelFormat = GL_BGRA;
        }
        if (w <= 0 || h <= 0) return;

        try {
            boolean sizeChanged = (texture == null || texW != w || texH != h);

            if (sizeChanged) {
                disposeGpu();
                texture = new Texture(w, h);
                texture.setFilter(Texture.TextureFilter.linear);
                texRegion = new TextureRegion(texture);
                texW = w;
                texH = h;
            }

            pixels.position(0);
            texture.bind();
            Gl.texImage2D(Gl.texture2d, 0, Gl.rgba, w, h, 0, pixelFormat, Gl.unsignedByte, pixels);
        } catch (Exception e) {
            Log.err("Texture update failed", e);
        }
    }

    // ---- hit tests ----

    private boolean isInBounds(float mx, float my) {
        return mx >= x - worldW / 2 && mx <= x + worldW / 2
                && my >= y - worldH / 2 && my <= y + worldH / 2;
    }

    private boolean isInHeader(float mx, float my) {
        return mx >= x - worldW / 2 && mx <= x + worldW / 2
                && my >= y + worldH / 2 - HEADER_H && my <= y + worldH / 2;
    }

    private boolean isInBrowserArea(float mx, float my) {
        return mx >= x - worldW / 2 && mx <= x + worldW / 2
                && my >= y - worldH / 2 && my <= y + worldH / 2 - HEADER_H;
    }

    private boolean isInResizeCorner(float mx, float my) {
        return mx >= x + worldW / 2 - RESIZE_CORNER && mx <= x + worldW / 2
                && my >= y - worldH / 2 && my <= y - worldH / 2 + RESIZE_CORNER;
    }

    private boolean isInCloseButton(float mx, float my) {
        float btnR = HEADER_H * 0.35f;
        float cx = x + worldW / 2 - btnR - 1;
        float cy = y + worldH / 2 - HEADER_H / 2;
        float ddx = mx - cx, ddy = my - cy;
        return !remote && (float) Math.sqrt(ddx * ddx + ddy * ddy) <= btnR;
    }

    private float headerBtnX(int index) {
        float btnR = HEADER_H * 0.35f;
        float btnStep = btnR * 2 + 1;
        return x - worldW / 2 + btnR + 1 + index * btnStep;
    }

    private boolean isInHeaderBtn(float mx, float my, int index) {
        float btnR = HEADER_H * 0.35f;
        float bx = headerBtnX(index);
        float by = y + worldH / 2 - HEADER_H / 2;
        float ddx = mx - bx, ddy = my - by;
        return (float) Math.sqrt(ddx * ddx + ddy * ddy) <= btnR * 1.3f;
    }

    private boolean isInBackButton(float mx, float my) { return isInHeaderBtn(mx, my, 0); }
    private boolean isInForwardButton(float mx, float my) { return isInHeaderBtn(mx, my, 1); }
    private boolean isInMenuButton(float mx, float my) { return isInHeaderBtn(mx, my, 2); }
    private boolean isInKbButton(float mx, float my) { return isInHeaderBtn(mx, my, 3); }

    private void showContextMenu() {
        new Dialog("Browser Options") {{
            if (!remote) {
                cont.button("Change URL", () -> {
                    hide();
                    new Dialog("Enter URL") {{
                        cont.add("URL:").padRight(10);
                        TextField field = new TextField();
                        field.setText(currentURL);
                        cont.add(field).width(300).pad(5);
                        buttons.button("Load", () -> {
                            loadURL(field.getText());
                            if (BrowserNet.multiplayerActive) BrowserNet.sendUrl(WorldBrowser.this);
                            hide();
                        });
                        buttons.button("Cancel", this::hide);
                    }}.show();
                }).size(200, 50).row();
            }

            cont.button("Keyboard", () -> {
                BrowserMod.activateKeyboard(WorldBrowser.this);
                hide();
            }).size(200, 50).row();

            cont.button("Reload", () -> {
                if (browser != null) browser.reload();
                if (androidBrowser != null) androidBrowser.doReload();
                hide();
            }).size(200, 50).row();

            if (!remote) {
                cont.button("Delete", () -> {
                    if (BrowserNet.multiplayerActive) BrowserNet.sendDelete(WorldBrowser.this);
                    dispose();
                    BrowserMod.browsers.remove(WorldBrowser.this);
                    hide();
                }).size(200, 50).row();
            }

            buttons.button("Close", this::hide);
        }}.show();
    }

    // ---- Media state sync ----

    private static final String MEDIA_EXTRACT_JS =
        "(function(){" +
        "var v=document.querySelector('video');" +
        "if(!v)return;" +
        "var u=window.location.href;" +
        "var m=u.match(/[?&]v=([^&]+)/);" +
        "var vid=m?m[1]:'';" +
        "console.log('BMEDIA:'+vid+'|'+Math.round(v.currentTime*10)/10+'|'+(v.paused?'S':'P'));" +
        "})()";

    /**
     * Called by the periodic timer in BrowserNet for local (non-remote) browsers.
     * Injects JS to extract media state; the result comes back via onConsoleMessage.
     */
    void extractAndSendMediaState() {
        if (remote) return;
        if (currentURL == null || !currentURL.contains("youtube.com/watch")) return;
        if (browser != null) browser.executeJavaScript(MEDIA_EXTRACT_JS, "", 0);
    }

    /**
     * Called from the onConsoleMessage handler when "BMEDIA:" is detected.
     * Parses the JS result and sends to peers.
     */
    void onMediaStateFromJs(String data) {
        try {
            String[] p = data.split("\\|", 3);
            if (p.length < 3) return;
            lastVideoId = p[0];
            lastVideoTime = Float.parseFloat(p[1]);
            lastVideoPlaying = "P".equals(p[2]);
            if (!lastVideoId.isEmpty()) {
                BrowserNet.sendMediaState(this, lastVideoId, lastVideoTime, lastVideoPlaying);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Called on remote browsers when a V (media state) packet arrives.
     * Seeks the video if drift is > 3 seconds and syncs play/pause.
     */
    void applyRemoteMouse(int eventType, int bx, int by, int button) {
        if (BrowserMod.isAndroid) {
            if (androidBrowser == null) return;
            int action;
            if (eventType == MouseEvent.MOUSE_PRESSED) action = AndroidBrowser.ACTION_DOWN;
            else if (eventType == MouseEvent.MOUSE_RELEASED) action = AndroidBrowser.ACTION_UP;
            else return;
            androidBrowser.sendTouchEvent(action, bx, by);
        } else {
            if (browser == null) return;
            CefBrowserHelper.sendMouseEvent(browser,
                CefBrowserHelper.makeMouseEvent(eventType, bx, by, button, 1, 0));
        }
    }

    void applyRemoteScroll(int bx, int by, int scrollAmount) {
        if (browser != null) {
            CefBrowserHelper.sendMouseWheelEvent(browser,
                CefBrowserHelper.makeMouseWheelEvent(bx, by, scrollAmount));
        }
    }

    void applyRemoteKey(int eventType, int vk, char ch, int mods) {
        if (BrowserMod.isAndroid) {
            if (androidBrowser == null) return;
            if (eventType == KeyEvent.KEY_PRESSED) {
                androidBrowser.executeJavaScript(BrowserMod.buildKeyJS("keydown", vk, mods));
            } else if (eventType == KeyEvent.KEY_RELEASED) {
                androidBrowser.executeJavaScript(BrowserMod.buildKeyJS("keyup", vk, mods));
            } else if (eventType == KeyEvent.KEY_TYPED && ch != 0 && ch != KeyEvent.CHAR_UNDEFINED) {
                String safe = String.valueOf(ch).replace("\\", "\\\\").replace("'", "\\'");
                androidBrowser.executeJavaScript("(function(){var a=document.activeElement;"
                    + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable))"
                    + "document.execCommand('insertText',false,'" + safe + "');})()");
            }
        } else {
            if (browser == null) return;
            CefBrowserHelper.sendKeyEvent(browser,
                CefBrowserHelper.makeKeyEvent(eventType, vk, ch, mods));
            if (eventType == KeyEvent.KEY_PRESSED) {
                BrowserMod.injectKeyJS(browser, "keydown", vk, mods);
                BrowserMod.injectEditCommand(browser, vk);
            } else if (eventType == KeyEvent.KEY_RELEASED) {
                BrowserMod.injectKeyJS(browser, "keyup", vk, mods);
            }
        }
    }

    void applyMediaState(String videoId, float time, boolean playing) {
        if (videoId == null || videoId.isEmpty()) return;
        String js =
            "(function(){" +
            "var v=document.querySelector('video');" +
            "if(!v)return;" +
            "if(Math.abs(v.currentTime-" + time + ")>3)v.currentTime=" + time + ";" +
            (playing ? "v.play();" : "v.pause();") +
            "})()";
        if (browser != null) browser.executeJavaScript(js, "", 0);
        if (androidBrowser != null) androidBrowser.executeJavaScript(js);
    }

    void disposeGpu() {
        texRegion = null;
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        texW = texH = 0;
    }

    void dispose() {
        if (BrowserMod.keyboardTarget == this) {
            BrowserMod.deactivateKeyboard();
        }
        disposeGpu();
        if (browser != null) {
            try {
                browser.stopLoad();
                browser.loadURL("about:blank");
                browser.setCloseAllowed();
                browser.close(true);
            } catch (Exception e) {
                Log.err("Error closing browser", e);
            }
            browser = null;
        }
        renderer = null;
        if (androidBrowser != null) {
            androidBrowser.dispose();
            androidBrowser = null;
        }
    }
}
