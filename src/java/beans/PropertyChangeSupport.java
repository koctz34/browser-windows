package java.beans;

import java.util.concurrent.CopyOnWriteArrayList;

public class PropertyChangeSupport {
    private final Object source;
    private final CopyOnWriteArrayList<PropertyChangeListener> listeners = new CopyOnWriteArrayList<>();

    public PropertyChangeSupport(Object source) {
        this.source = source;
    }

    public void addPropertyChangeListener(PropertyChangeListener l) {
        if (l != null) listeners.add(l);
    }

    public void removePropertyChangeListener(PropertyChangeListener l) {
        listeners.remove(l);
    }

    public void firePropertyChange(String prop, Object oldVal, Object newVal) {
        PropertyChangeEvent e = new PropertyChangeEvent(source, prop, oldVal, newVal);
        for (PropertyChangeListener l : listeners) l.propertyChange(e);
    }

    public void firePropertyChange(String prop, int oldVal, int newVal) {
        firePropertyChange(prop, Integer.valueOf(oldVal), Integer.valueOf(newVal));
    }

    public void firePropertyChange(String prop, boolean oldVal, boolean newVal) {
        firePropertyChange(prop, Boolean.valueOf(oldVal), Boolean.valueOf(newVal));
    }

    public PropertyChangeListener[] getPropertyChangeListeners() {
        return listeners.toArray(new PropertyChangeListener[0]);
    }
}
