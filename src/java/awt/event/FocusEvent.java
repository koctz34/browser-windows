package java.awt.event;

import java.awt.Component;

public class FocusEvent extends ComponentEvent {
    public static final int FOCUS_GAINED = 1004;
    public static final int FOCUS_LOST   = 1005;

    public FocusEvent(Component source, int id) {
        super(source, id);
    }
}
