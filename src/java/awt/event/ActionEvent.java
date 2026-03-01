package java.awt.event;

import java.awt.AWTEvent;

public class ActionEvent extends AWTEvent {
    public static final int ACTION_PERFORMED = 1001;
    private String command;
    private int modifiers;

    public ActionEvent(Object source, int id, String command) {
        super(source, id);
        this.command = command;
    }

    public ActionEvent(Object source, int id, String command, int modifiers) {
        super(source, id);
        this.command = command;
        this.modifiers = modifiers;
    }

    public String getActionCommand() { return command; }
    public int getModifiers() { return modifiers; }
}
