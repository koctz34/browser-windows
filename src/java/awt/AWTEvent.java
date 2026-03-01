package java.awt;

import java.util.EventObject;

public class AWTEvent extends EventObject {
    protected int id;
    protected boolean consumed;

    public AWTEvent(Object source, int id) {
        super(source);
        this.id = id;
    }

    public int getID() { return id; }
    public void consume() { consumed = true; }
    public boolean isConsumed() { return consumed; }
}
