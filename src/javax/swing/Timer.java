package javax.swing;

import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal stub: JCEF uses Timer to schedule periodic doMessageLoopWork calls.
 * We pump the message loop ourselves from the game thread, so Timer.start()
 * must NOT fire immediately -- that causes infinite recursion through
 * invokeLater -> CefApp$6.run -> Timer.start -> fire -> doMessageLoopWork -> ...
 */
public class Timer {
    private int delay;
    private boolean repeats = true;
    private boolean running = false;
    private final CopyOnWriteArrayList<ActionListener> listeners = new CopyOnWriteArrayList<>();

    public Timer(int delay, ActionListener listener) {
        this.delay = delay;
        if (listener != null) listeners.add(listener);
    }

    public void addActionListener(ActionListener l) { if (l != null) listeners.add(l); }
    public void removeActionListener(ActionListener l) { listeners.remove(l); }
    public void setDelay(int delay) { this.delay = delay; }
    public int getDelay() { return delay; }
    public void setInitialDelay(int initialDelay) { /* ignored */ }
    public int getInitialDelay() { return delay; }
    public void setRepeats(boolean flag) { this.repeats = flag; }
    public boolean isRepeats() { return repeats; }
    public boolean isRunning() { return running; }
    public void setCoalesce(boolean flag) { /* ignored */ }
    public boolean isCoalesce() { return true; }

    public void start() { running = true; }
    public void restart() { stop(); start(); }
    public void stop() { running = false; }
}
