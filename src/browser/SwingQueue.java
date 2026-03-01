package browser;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds the queue that the javax.swing.SwingUtilities stub delegates to.
 * Lives in the browser package so BrowserMod can reference processQueue()
 * without the compiler resolving to the real javax.swing.SwingUtilities.
 */
public final class SwingQueue {
    public static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();

    public static void processQueue() {
        Runnable r;
        while ((r = QUEUE.poll()) != null) {
            try {
                r.run();
            } catch (Throwable ignored) {}
        }
    }
}
