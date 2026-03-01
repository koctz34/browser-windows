package java.awt;

public class Rectangle {
    public int x, y, width, height;

    public Rectangle() {}

    public Rectangle(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
    }

    public int getWidth()  { return width; }
    public int getHeight() { return height; }
}
