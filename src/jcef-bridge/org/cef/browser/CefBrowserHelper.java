package org.cef.browser;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * Bridge that exposes protected methods of CefBrowser_N.
 * Must live in the org.cef.browser package for package-level access.
 */
public final class CefBrowserHelper {

    private static final Component DUMMY = new Component() {};

    private CefBrowserHelper() {}

    public static void wasResized(CefBrowser browser, int width, int height) {
        if (browser instanceof CefBrowserOsrHeadless) {
            ((CefBrowserOsrHeadless) browser).setSize(width, height);
        }
        if (browser instanceof CefBrowser_N) {
            ((CefBrowser_N) browser).wasResized(width, height);
        }
    }

    public static void sendMouseEvent(CefBrowser browser, MouseEvent event) {
        if (browser instanceof CefBrowser_N) {
            ((CefBrowser_N) browser).sendMouseEvent(event);
        }
    }

    public static void sendMouseWheelEvent(CefBrowser browser, MouseWheelEvent event) {
        if (browser instanceof CefBrowser_N) {
            ((CefBrowser_N) browser).sendMouseWheelEvent(event);
        }
    }

    public static void sendKeyEvent(CefBrowser browser, KeyEvent event) {
        if (browser instanceof CefBrowser_N) {
            ((CefBrowser_N) browser).sendKeyEvent(event);
        }
    }

    /** Creates a dummy AWT MouseEvent that CEF understands. */
    public static MouseEvent makeMouseEvent(int id, int x, int y, int button, int clickCount, int modifiers) {
        return new MouseEvent(DUMMY, id, System.currentTimeMillis(), modifiers, x, y, clickCount, false, button);
    }

    /** Creates a dummy AWT MouseWheelEvent. */
    public static MouseWheelEvent makeMouseWheelEvent(int x, int y, int scrollAmount) {
        return new MouseWheelEvent(DUMMY, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                0, x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, scrollAmount);
    }

    /** Creates a dummy AWT KeyEvent with proper key location for JCEF native mapping. */
    public static KeyEvent makeKeyEvent(int id, int keyCode, char keyChar, int modifiers) {
        int location = (id == KeyEvent.KEY_TYPED) ? KeyEvent.KEY_LOCATION_UNKNOWN : KeyEvent.KEY_LOCATION_STANDARD;
        return new KeyEvent(DUMMY, id, System.currentTimeMillis(), modifiers, keyCode, keyChar, location);
    }
}
