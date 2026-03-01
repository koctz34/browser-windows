package java.awt;

public class Container extends Component {
    private static final Component[] EMPTY = new Component[0];

    public Component[] getComponents() { return EMPTY; }
    public int getComponentCount() { return 0; }
    public Component getComponent(int n) { throw new ArrayIndexOutOfBoundsException(n); }
}
