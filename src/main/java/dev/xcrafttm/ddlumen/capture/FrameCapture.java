package dev.xcrafttm.ddlumen.capture;

import dev.xcrafttm.ddlumen.Clamp;
import dev.xcrafttm.ddlumen.DdLumen;
import dev.xcrafttm.ddlumen.LumenConfig;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the decoded video frames Dream Displays uploads into a small grid of average colors, one per zone of the
 * picture. Runs on the render thread right before the upload (see the mixins), so it only reads a few samples.
 */
public final class FrameCapture {
    /** Samples per zone and axis. */
    private static final int SAMPLES = 8;
    /** sRGB byte to linear light. */
    private static final float[] TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private static final Map<UUID, ZoneFrame> FRAMES = new ConcurrentHashMap<>();
    private static final Map<UUID, Stats> STATS = new ConcurrentHashMap<>();
    /** Frames uploaded outside a display's upload (popouts, previews): not ours. */
    private static long foreignUploads;

    /** What arrived for one display, for /ddlumen status and the log. */
    public static final class Stats {
        public long frames, skipped;
        public long lastFrameNs;
        public String format = "-", lastSkip = "-";
    }
    /** The display whose frame is being uploaded right now (set around Dream Displays' upload call). */
    private static UUID uploading;

    private FrameCapture() {
    }

    public static void beginUpload(UUID display) {
        uploading = display;
    }

    public static void endUpload() {
        uploading = null;
    }

    /** The latest color grid of [display], or null when none was captured yet. */
    public static ZoneFrame latest(UUID display) {
        return FRAMES.get(display);
    }

    public static void forget(UUID display) {
        FRAMES.remove(display);
        STATS.remove(display);
    }

    public static void clear() {
        FRAMES.clear();
        STATS.clear();
    }

    public static Stats stats(UUID display) {
        return STATS.get(display);
    }

    /** Whether frames of [display] arrived within the last half second. */
    public static boolean receiving(UUID display, long nowNs) {
        Stats st = STATS.get(display);
        return st != null && st.frames > 0 && nowNs - st.lastFrameNs < 500_000_000L;
    }

    public static long foreignUploads() {
        return foreignUploads;
    }

    private static void skipped(UUID display, String reason) {
        Stats st = STATS.computeIfAbsent(display, id -> new Stats());
        st.skipped++;
        if (!reason.equals(st.lastSkip)) {
            DdLumen.LOGGER.warn("Display {}: frame not used: {}", display, reason);
            st.lastSkip = reason;
        }
    }

    private static void captured(UUID display, String format, ZoneFrame frame) {
        Stats st = STATS.computeIfAbsent(display, id -> new Stats());
        if (st.frames++ == 0 || !format.equals(st.format)) {
            DdLumen.LOGGER.info("Display {}: frames arrive ({})", display, format);
        }
        st.format = format;
        st.lastFrameNs = frame.nanoTime();
        FRAMES.put(display, frame);
    }

    /** An RGB24 / RGBA32 frame, rows top to bottom. */
    public static void captureInterleaved(ByteBuffer src, int width, int height, int bytesPerPixel) {
        UUID display = uploading;
        if (display == null) {
            foreignUploads++;
            return;
        }
        if (bytesPerPixel < 3 || width <= 0 || height <= 0) {
            skipped(display, "RGB " + width + "x" + height + ", " + bytesPerPixel + " bytes per pixel");
            return;
        }
        if (src.capacity() < (long) width * height * bytesPerPixel) {
            skipped(display, "RGB " + width + "x" + height + " but the buffer holds only " + src.capacity() + " bytes");
            return;
        }
        LumenConfig cfg = LumenConfig.get();
        if (!cfg.enabled) return;
        int zx = cfg.zonesX, zy = cfg.zonesY;
        float[] rgb = new float[zx * zy * 3];
        int stride = width * bytesPerPixel;
        for (int j = 0; j < zy; j++) {
            for (int i = 0; i < zx; i++) {
                int x0 = i * width / zx, x1 = (i + 1) * width / zx;
                int y0 = j * height / zy, y1 = (j + 1) * height / zy;
                int sx = Math.min(SAMPLES, Math.max(1, x1 - x0)), sy = Math.min(SAMPLES, Math.max(1, y1 - y0));
                float r = 0, g = 0, b = 0;
                for (int b2 = 0; b2 < sy; b2++) {
                    int y = y0 + (int) ((b2 + 0.5f) * (y1 - y0) / sy);
                    int row = y * stride;
                    for (int a = 0; a < sx; a++) {
                        int x = x0 + (int) ((a + 0.5f) * (x1 - x0) / sx);
                        int p = row + x * bytesPerPixel;
                        r += TO_LINEAR[src.get(p) & 0xFF];
                        g += TO_LINEAR[src.get(p + 1) & 0xFF];
                        b += TO_LINEAR[src.get(p + 2) & 0xFF];
                    }
                }
                float n = 1f / (sx * sy);
                int k = (j * zx + i) * 3;
                rgb[k] = r * n;
                rgb[k + 1] = g * n;
                rgb[k + 2] = b * n;
            }
        }
        captured(display, "RGB " + width + "x" + height, new ZoneFrame(zx, zy, rgb, System.nanoTime()));
    }

    /** An I420 frame: the Y plane, then the U and V planes at half resolution. BT.709, limited range. */
    public static void capturePlanar(ByteBuffer src, int width, int height) {
        UUID display = uploading;
        if (display == null) {
            foreignUploads++;
            return;
        }
        if (width <= 0 || height <= 0) {
            skipped(display, "YUV " + width + "x" + height);
            return;
        }
        int cw = (width + 1) / 2, ch = (height + 1) / 2;
        int uOffset = width * height, vOffset = uOffset + cw * ch;
        if (src.capacity() < vOffset + cw * ch) {
            skipped(display, "YUV " + width + "x" + height + " but the buffer holds only " + src.capacity() + " bytes");
            return;
        }
        LumenConfig cfg = LumenConfig.get();
        if (!cfg.enabled) return;
        int zx = cfg.zonesX, zy = cfg.zonesY;
        float[] rgb = new float[zx * zy * 3];
        for (int j = 0; j < zy; j++) {
            for (int i = 0; i < zx; i++) {
                int x0 = i * width / zx, x1 = (i + 1) * width / zx;
                int y0 = j * height / zy, y1 = (j + 1) * height / zy;
                int sx = Math.min(SAMPLES, Math.max(1, x1 - x0)), sy = Math.min(SAMPLES, Math.max(1, y1 - y0));
                float r = 0, g = 0, b = 0;
                for (int b2 = 0; b2 < sy; b2++) {
                    int y = y0 + (int) ((b2 + 0.5f) * (y1 - y0) / sy);
                    for (int a = 0; a < sx; a++) {
                        int x = x0 + (int) ((a + 0.5f) * (x1 - x0) / sx);
                        float luma = ((src.get(y * width + x) & 0xFF) / 255f - 0.0625f) * 1.164384f;
                        int c = (y >> 1) * cw + (x >> 1);
                        float u = (src.get(uOffset + c) & 0xFF) / 255f - 0.5f;
                        float v = (src.get(vOffset + c) & 0xFF) / 255f - 0.5f;
                        r += TO_LINEAR[toByte(luma + 1.792741f * v)];
                        g += TO_LINEAR[toByte(luma - 0.213249f * u - 0.532909f * v)];
                        b += TO_LINEAR[toByte(luma + 2.112402f * u)];
                    }
                }
                float n = 1f / (sx * sy);
                int k = (j * zx + i) * 3;
                rgb[k] = r * n;
                rgb[k + 1] = g * n;
                rgb[k + 2] = b * n;
            }
        }
        captured(display, "YUV " + width + "x" + height, new ZoneFrame(zx, zy, rgb, System.nanoTime()));
    }

    private static int toByte(float c) {
        return Clamp.of((int) (c * 255f + 0.5f), 0, 255);
    }

    /** Linear RGB per zone (row-major, top row first), captured at [nanoTime]. */
    public record ZoneFrame(int zonesX, int zonesY, float[] rgb, long nanoTime) {
    }
}
