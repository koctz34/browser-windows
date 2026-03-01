package browser;

import org.cef.browser.CefPaintEvent;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Receives CEF paint events and stores the latest frame.
 * The render thread calls consumeFrame() to get pixels exactly once per paint.
 */
public class MindustryRenderer implements Consumer<CefPaintEvent> {
    private volatile int width;
    private volatile int height;
    private ByteBuffer pixelBuffer;
    private volatile boolean dirty = false;

    public MindustryRenderer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void accept(CefPaintEvent event) {
        if (event.getPopup()) return;

        ByteBuffer src = event.getRenderedFrame();
        int w = event.getWidth();
        int h = event.getHeight();
        if (src == null || w <= 0 || h <= 0) return;

        int size = w * h * 4;

        synchronized (this) {
            if (pixelBuffer == null || pixelBuffer.capacity() != size) {
                pixelBuffer = ByteBuffer.allocateDirect(size);
            }
            pixelBuffer.position(0);
            pixelBuffer.limit(size);
            src.position(0);
            src.limit(Math.min(src.capacity(), size));
            pixelBuffer.put(src);
            pixelBuffer.position(0);

            this.width = w;
            this.height = h;
            dirty = true;
        }
    }

    /**
     * Returns pixel buffer if a NEW frame is available since last consume.
     * Marks the frame as consumed so the same frame is not processed twice.
     */
    public synchronized ByteBuffer consumeFrame() {
        if (!dirty) return null;
        dirty = false;
        return pixelBuffer;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
