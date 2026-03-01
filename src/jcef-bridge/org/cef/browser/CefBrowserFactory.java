package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;

/**
 * Replaces the original CefBrowserFactory from jcef.jar.
 * Creates CefBrowserOsrHeadless (no JOGL dependency) instead of CefBrowserOsr.
 * Our compiled class takes priority over jcef.jar's version via DuplicatesStrategy.EXCLUDE.
 */
public class CefBrowserFactory {
    public static CefBrowser create(CefClient client, String url,
                                    boolean isOffscreenRendered, boolean isTransparent,
                                    CefRequestContext requestContext,
                                    CefBrowserSettings settings) {
        if (isOffscreenRendered) {
            return new CefBrowserOsrHeadless(client, url, isTransparent, requestContext, settings);
        }
        throw new UnsupportedOperationException(
                "Windowed browser mode is not available (no JOGL). Use OSR mode (isOffscreenRendered=true).");
    }
}
