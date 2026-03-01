package browser;

import arc.util.Log;
import java.lang.reflect.*;
import java.nio.ByteBuffer;

public class AndroidBrowser {
    private Object webView;
    private Object bitmap;
    private Object canvas;

    private volatile ByteBuffer pixelBuffer;
    private volatile boolean dirty = false;
    private volatile int width, height;
    private volatile boolean disposed = false;

    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;
    static final int ACTION_MOVE = 2;

    private static boolean reflectionReady = false;
    private static Object mainHandler;

    private static Constructor<?> webViewCtor, canvasCtor;
    private static Method loadUrlM, reloadM, viewDrawM, viewMeasureM, viewLayoutM,
            setLayerTypeM, webViewDestroyM, getSettingsM,
            setJsEnabledM, setDomStorageM, setWideViewPortM, setUserAgentM,
            setLoadWithOverviewM,
            createBitmapM, copyPixelsBufM, setPixelM,
            motionObtainM, motionRecycleM, dispatchTouchM,
            makeMeasureSpecM, handlerPostM, handlerPostDelayedM,
            evaluateJsM, stopLoadingM, setInitialScaleM,
            goBackM, goForwardM;
    private static int LAYER_TYPE_SOFTWARE, EXACTLY;
    private static Object ARGB_8888;
    private static boolean needSwapRB = true;

    private static final String DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    static boolean initReflection() {
        if (reflectionReady) return true;
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Class<?> handlerClass = Class.forName("android.os.Handler");
            Class<?> webViewClass = Class.forName("android.webkit.WebView");
            Class<?> viewClass = Class.forName("android.view.View");
            Class<?> bitmapClass = Class.forName("android.graphics.Bitmap");
            Class<?> canvasClass = Class.forName("android.graphics.Canvas");
            Class<?> measureSpecClass = Class.forName("android.view.View$MeasureSpec");
            Class<?> motionEventClass = Class.forName("android.view.MotionEvent");
            Class<?> webSettingsClass = Class.forName("android.webkit.WebSettings");
            Class<?> bitmapConfigClass = Class.forName("android.graphics.Bitmap$Config");
            Class<?> contextClass = Class.forName("android.content.Context");
            Class<?> paintClass = Class.forName("android.graphics.Paint");

            Object mainLooper = looperClass.getMethod("getMainLooper").invoke(null);
            mainHandler = handlerClass.getConstructor(looperClass).newInstance(mainLooper);
            handlerPostM = handlerClass.getMethod("post", Runnable.class);
            handlerPostDelayedM = handlerClass.getMethod("postDelayed", Runnable.class, long.class);

            webViewCtor = webViewClass.getConstructor(contextClass);
            loadUrlM = webViewClass.getMethod("loadUrl", String.class);
            reloadM = webViewClass.getMethod("reload");
            webViewDestroyM = webViewClass.getMethod("destroy");
            getSettingsM = webViewClass.getMethod("getSettings");
            stopLoadingM = webViewClass.getMethod("stopLoading");
            dispatchTouchM = viewClass.getMethod("dispatchTouchEvent", motionEventClass);

            viewDrawM = viewClass.getMethod("draw", canvasClass);
            viewMeasureM = viewClass.getMethod("measure", int.class, int.class);
            viewLayoutM = viewClass.getMethod("layout", int.class, int.class, int.class, int.class);
            setLayerTypeM = viewClass.getMethod("setLayerType", int.class, paintClass);

            setJsEnabledM = webSettingsClass.getMethod("setJavaScriptEnabled", boolean.class);
            setDomStorageM = webSettingsClass.getMethod("setDomStorageEnabled", boolean.class);
            setWideViewPortM = webSettingsClass.getMethod("setUseWideViewPort", boolean.class);
            try { setUserAgentM = webSettingsClass.getMethod("setUserAgentString", String.class); } catch (Throwable ignored) {}
            try { setLoadWithOverviewM = webSettingsClass.getMethod("setLoadWithOverviewMode", boolean.class); } catch (Throwable ignored) {}

            goBackM = webViewClass.getMethod("goBack");
            goForwardM = webViewClass.getMethod("goForward");

            createBitmapM = bitmapClass.getMethod("createBitmap", int.class, int.class, bitmapConfigClass);
            copyPixelsBufM = bitmapClass.getMethod("copyPixelsToBuffer", java.nio.Buffer.class);
            setPixelM = bitmapClass.getMethod("setPixel", int.class, int.class, int.class);
            canvasCtor = canvasClass.getConstructor(bitmapClass);

            motionObtainM = motionEventClass.getMethod("obtain",
                    long.class, long.class, int.class, float.class, float.class, int.class);
            motionRecycleM = motionEventClass.getMethod("recycle");

            makeMeasureSpecM = measureSpecClass.getMethod("makeMeasureSpec", int.class, int.class);

            try {
                Class<?> vcClass = Class.forName("android.webkit.ValueCallback");
                evaluateJsM = webViewClass.getMethod("evaluateJavascript", String.class, vcClass);
            } catch (Throwable t) { evaluateJsM = null; }

            try {
                setInitialScaleM = webViewClass.getMethod("setInitialScale", int.class);
            } catch (Throwable t) { setInitialScaleM = null; }

            LAYER_TYPE_SOFTWARE = viewClass.getField("LAYER_TYPE_SOFTWARE").getInt(null);
            EXACTLY = measureSpecClass.getField("EXACTLY").getInt(null);
            ARGB_8888 = bitmapConfigClass.getField("ARGB_8888").get(null);

            try {
                Object testBmp = createBitmapM.invoke(null, 1, 1, ARGB_8888);
                setPixelM.invoke(testBmp, 0, 0, 0xFFFF0000); // pure red = ARGB 0xFFFF0000
                ByteBuffer probe = ByteBuffer.allocateDirect(4);
                copyPixelsBufM.invoke(testBmp, probe);
                // If native order is BGRA: byte[0]=0x00(B), byte[2]=0xFF(R) -> need swap
                // If native order is RGBA: byte[0]=0xFF(R) -> no swap needed
                needSwapRB = ((probe.get(0) & 0xFF) == 0);
                Log.info("[BW] Bitmap byte order: " + (needSwapRB ? "BGRA (swap R<->B)" : "RGBA (native)"));
            } catch (Throwable t) {
                Log.warn("[BW] Could not detect bitmap byte order, assuming BGRA");
                needSwapRB = true;
            }

            reflectionReady = true;
            Log.info("[BW] Android WebView reflection ready");
            return true;
        } catch (Throwable e) {
            Log.err("[BW] Android reflection init failed", e);
            return false;
        }
    }

    AndroidBrowser(int w, int h, String url) {
        this.width = w;
        this.height = h;
        if (!reflectionReady) return;

        final String loadUrlStr = url;
        postToUi(() -> {
            try {
                Object ctx = arc.Core.app;
                webView = webViewCtor.newInstance(ctx);
                setLayerTypeM.invoke(webView, LAYER_TYPE_SOFTWARE, (Object) null);

                Object settings = getSettingsM.invoke(webView);
                setJsEnabledM.invoke(settings, true);
                setDomStorageM.invoke(settings, true);
                setWideViewPortM.invoke(settings, true);
                if (setUserAgentM != null) setUserAgentM.invoke(settings, DESKTOP_USER_AGENT);
                if (setLoadWithOverviewM != null) setLoadWithOverviewM.invoke(settings, true);

                if (setInitialScaleM != null) {
                    setInitialScaleM.invoke(webView, 100);
                }

                recreateRenderTarget(width, height);
                doLayout(width, height);

                if (loadUrlStr != null && !loadUrlStr.isEmpty()) {
                    loadUrlM.invoke(webView, loadUrlStr);
                }

                scheduleRender();
            } catch (Throwable e) {
                Log.err("[BW] WebView create failed", e);
            }
        });
    }

    private void recreateRenderTarget(int w, int h) throws Throwable {
        bitmap = createBitmapM.invoke(null, w, h, ARGB_8888);
        canvas = canvasCtor.newInstance(bitmap);
    }

    private void doLayout(int w, int h) throws Throwable {
        int wSpec = (int) makeMeasureSpecM.invoke(null, w, EXACTLY);
        int hSpec = (int) makeMeasureSpecM.invoke(null, h, EXACTLY);
        viewMeasureM.invoke(webView, wSpec, hSpec);
        viewLayoutM.invoke(webView, 0, 0, w, h);
    }

    private void scheduleRender() {
        if (disposed || webView == null) return;
        try {
            handlerPostDelayedM.invoke(mainHandler, (Runnable) () -> {
                if (disposed || webView == null) return;
                try {
                    renderFrame();
                } catch (Throwable ignored) {}
                scheduleRender();
            }, 66L);
        } catch (Throwable ignored) {}
    }

    private void renderFrame() throws Throwable {
        if (webView == null || bitmap == null || canvas == null) return;

        viewDrawM.invoke(webView, canvas);

        int size = width * height * 4;
        synchronized (this) {
            if (pixelBuffer == null || pixelBuffer.capacity() != size) {
                pixelBuffer = ByteBuffer.allocateDirect(size);
            }
            pixelBuffer.position(0);
            copyPixelsBufM.invoke(bitmap, pixelBuffer);
            pixelBuffer.position(0);

            if (needSwapRB) {
                for (int i = 0; i < size; i += 4) {
                    byte b0 = pixelBuffer.get(i);
                    byte b2 = pixelBuffer.get(i + 2);
                    pixelBuffer.put(i, b2);
                    pixelBuffer.put(i + 2, b0);
                }
            }
            pixelBuffer.position(0);
            dirty = true;
        }
    }

    void loadUrl(String url) {
        if (disposed) return;
        postToUi(() -> {
            try {
                if (webView != null) loadUrlM.invoke(webView, url);
            } catch (Throwable ignored) {}
        });
    }

    void doReload() {
        if (disposed) return;
        postToUi(() -> {
            try {
                if (webView != null) reloadM.invoke(webView);
            } catch (Throwable ignored) {}
        });
    }

    void goBack() {
        if (disposed) return;
        postToUi(() -> {
            try {
                if (webView != null) goBackM.invoke(webView);
            } catch (Throwable ignored) {}
        });
    }

    void goForward() {
        if (disposed) return;
        postToUi(() -> {
            try {
                if (webView != null) goForwardM.invoke(webView);
            } catch (Throwable ignored) {}
        });
    }

    void resize(int w, int h) {
        this.width = w;
        this.height = h;
        if (disposed) return;
        postToUi(() -> {
            try {
                if (webView != null) {
                    recreateRenderTarget(w, h);
                    doLayout(w, h);
                }
            } catch (Throwable ignored) {}
        });
    }

    void sendTouchEvent(int action, float touchX, float touchY) {
        if (disposed) return;
        postToUi(() -> {
            try {
                if (webView == null) return;
                long now = System.currentTimeMillis();
                Object event = motionObtainM.invoke(null, now, now, action, touchX, touchY, 0);
                dispatchTouchM.invoke(webView, event);
                motionRecycleM.invoke(event);
            } catch (Throwable ignored) {}
        });
    }

    void executeJavaScript(String js) {
        if (disposed) return;
        postToUi(() -> {
            try {
                if (webView == null) return;
                if (evaluateJsM != null) {
                    evaluateJsM.invoke(webView, js, (Object) null);
                } else {
                    loadUrlM.invoke(webView, "javascript:" + js);
                }
            } catch (Throwable ignored) {}
        });
    }

    synchronized ByteBuffer consumeFrame() {
        if (!dirty) return null;
        dirty = false;
        return pixelBuffer;
    }

    int getWidth() { return width; }
    int getHeight() { return height; }

    void dispose() {
        disposed = true;
        postToUi(() -> {
            try {
                if (webView != null) {
                    stopLoadingM.invoke(webView);
                    webViewDestroyM.invoke(webView);
                    webView = null;
                }
            } catch (Throwable ignored) {}
            bitmap = null;
            canvas = null;
        });
    }

    private static void postToUi(Runnable r) {
        if (mainHandler == null) return;
        try {
            handlerPostM.invoke(mainHandler, r);
        } catch (Throwable ignored) {}
    }
}
