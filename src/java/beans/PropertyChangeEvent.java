package java.beans;

import java.util.EventObject;

public class PropertyChangeEvent extends EventObject {
    private final String propertyName;
    private final Object oldValue;
    private final Object newValue;

    public PropertyChangeEvent(Object source, String propertyName, Object oldValue, Object newValue) {
        super(source);
        this.propertyName = propertyName;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String getPropertyName() { return propertyName; }
    public Object getOldValue()     { return oldValue; }
    public Object getNewValue()     { return newValue; }
}
