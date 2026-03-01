package javax.swing;

/**
 * Stub that queues runnables instead of running them immediately.
 * Running invokeLater callbacks synchronously causes re-entrant native calls
 * during CefApp.N_Initialize(), deadlocking CEF's internal mutexes.
 * The queue is drained explicitly from the game loop via SwingQueue.processQueue().
 */
public class SwingUtilities {
    public static boolean isEventDispatchThread() { return true; }

    public static void invokeLater(Runnable r) {
        if (r != null) browser.SwingQueue.QUEUE.add(r);
    }

    public static void invokeAndWait(Runnable r) throws Exception {
        if (r != null) r.run();
    }
}
