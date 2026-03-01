package java.awt;

import java.beans.PropertyChangeListener;

public class KeyboardFocusManager {
    private static final KeyboardFocusManager INSTANCE = new KeyboardFocusManager();

    public static KeyboardFocusManager getCurrentKeyboardFocusManager() {
        return INSTANCE;
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {}
    public void removePropertyChangeListener(PropertyChangeListener listener) {}
    public void addPropertyChangeListener(String propertyName, PropertyChangeListener listener) {}
    public void removePropertyChangeListener(String propertyName, PropertyChangeListener listener) {}
}
