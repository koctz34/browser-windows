package org.cef.browser;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Headless off-screen rendered browser.  Does NOT depend on JOGL/GLCanvas.
 * Replaces the original CefBrowserOsr which requires com.jogamp.opengl.
 */
class CefBrowserOsrHeadless extends CefBrowser_N implements CefRenderHandler {

    private final boolean isTransparent_;
    private final Rectangle browser_rect_ = new Rectangle(0, 0, 1, 1);
    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> onPaintListeners =
            new CopyOnWriteArrayList<>();

    CefBrowserOsrHeadless(CefClient client, String url, boolean transparent,
                          CefRequestContext requestContext, CefBrowserSettings settings) {
        super(client, url, requestContext, null, null, settings);
        this.isTransparent_ = transparent;
    }

    void setSize(int width, int height) {
        browser_rect_.setBounds(0, 0, Math.max(1, width), Math.max(1, height));
    }

    @Override
    public void createImmediately() {
        createBrowser(getClient(), 0, getUrl(), true, isTransparent_, null, getRequestContext());
    }

    @Override
    public Component getUIComponent() {
        return null;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
                                                  CefRequestContext ctx, CefBrowser_N parent,
                                                  Point inspectAt) {
        return null;
    }

    // ---- CefRenderHandler ----

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return browser_rect_;
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        return false;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        return viewPoint;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {}

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {}

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                        ByteBuffer buffer, int width, int height) {
        CefPaintEvent event = new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
        for (Consumer<CefPaintEvent> listener : onPaintListeners) {
            listener.accept(event);
        }
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.clear();
        onPaintListeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.remove(listener);
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return false;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData,
                                 int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {}

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        CompletableFuture<BufferedImage> f = new CompletableFuture<>();
        f.completeExceptionally(new UnsupportedOperationException("Screenshots not supported in headless mode"));
        return f;
    }
}
