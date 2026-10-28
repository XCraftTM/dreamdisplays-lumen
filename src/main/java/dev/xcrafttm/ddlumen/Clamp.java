package dev.xcrafttm.ddlumen;

/** Math.clamp, which Java 17 (Minecraft before 1.20.5) does not have. */
public final class Clamp {
    private Clamp() {
    }

    public static int of(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static float of(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public static double of(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
