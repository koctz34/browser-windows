package java.awt.event;

import java.awt.Component;

public class KeyEvent extends InputEvent {
    public static final int KEY_TYPED    = 400;
    public static final int KEY_PRESSED  = 401;
    public static final int KEY_RELEASED = 402;

    public static final int VK_UNDEFINED = 0;
    public static final char CHAR_UNDEFINED = '\uFFFF';

    public static final int KEY_LOCATION_UNKNOWN  = 0;
    public static final int KEY_LOCATION_STANDARD = 1;
    public static final int KEY_LOCATION_LEFT     = 2;
    public static final int KEY_LOCATION_RIGHT    = 3;
    public static final int KEY_LOCATION_NUMPAD   = 4;

    int keyCode;
    char keyChar;
    int keyLocation;

    public KeyEvent(Component source, int id, long when, int modifiers,
                    int keyCode, char keyChar, int keyLocation) {
        super(source, id, when, modifiers);
        this.keyCode = keyCode;
        this.keyChar = keyChar;
        this.keyLocation = keyLocation;
    }

    public int  getKeyCode()     { return keyCode; }
    public char getKeyChar()     { return keyChar; }
    public int  getKeyLocation() { return keyLocation; }
}
