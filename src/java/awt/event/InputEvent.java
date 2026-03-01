package java.awt.event;

import java.awt.Component;

public class InputEvent extends ComponentEvent {
    public static final int SHIFT_DOWN_MASK = 1 << 6;
    public static final int CTRL_DOWN_MASK  = 1 << 7;
    public static final int ALT_DOWN_MASK   = 1 << 9;

    long when;
    int modifiers;

    InputEvent(Component source, int id, long when, int modifiers) {
        super(source, id);
        this.when = when;
        this.modifiers = modifiers;
    }

    public int  getModifiers()   { return modifiers; }
    public int  getModifiersEx() { return modifiers; }
    public long getWhen()        { return when; }
}
