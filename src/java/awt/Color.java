package java.awt;

public class Color {
    private final int value;

    public Color(int r, int g, int b) { this.value = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF); }
    public Color(int rgb) { this.value = rgb; }
    public int getRGB() { return value; }
    public int getRed()   { return (value >> 16) & 0xFF; }
    public int getGreen() { return (value >> 8) & 0xFF; }
    public int getBlue()  { return value & 0xFF; }
}
