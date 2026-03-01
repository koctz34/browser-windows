package java.awt.event;

import java.awt.Component;

public class WindowEvent extends ComponentEvent {
    public static final int WINDOW_OPENED = 200;
    public static final int WINDOW_CLOSING = 201;
    public static final int WINDOW_CLOSED = 202;

    public WindowEvent(Component source, int id) {
        super(source, id);
    }
}
