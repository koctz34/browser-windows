package java.awt.event;

import java.awt.Component;

public class MouseWheelEvent extends MouseEvent {
    public static final int WHEEL_UNIT_SCROLL  = 0;
    public static final int WHEEL_BLOCK_SCROLL = 1;

    int scrollType;
    int scrollAmount;
    int wheelRotation;

    public MouseWheelEvent(Component source, int id, long when, int modifiers,
                           int x, int y, int clickCount, boolean popupTrigger,
                           int scrollType, int scrollAmount, int wheelRotation) {
        super(source, id, when, modifiers, x, y, clickCount, popupTrigger, 0);
        this.scrollType = scrollType;
        this.scrollAmount = scrollAmount;
        this.wheelRotation = wheelRotation;
    }

    public int getScrollType()     { return scrollType; }
    public int getScrollAmount()   { return scrollAmount; }
    public int getWheelRotation()  { return wheelRotation; }
}
