package dev.xcrafttm.ddlumen.api;

import dev.xcrafttm.ddlumen.capture.FrameCapture;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * For mods that draw their own picture on a Dream Displays display instead of its video (Dream Displays Beam's
 * casts): hand Lumen each frame, and the display lights up in its colors. While frames arrive this way (within the
 * last half second), they count instead of Dream Displays' own video, and the display lights even when Dream
 * Displays has no video on it. This class stays as it is; other mods look it up by name.
 */
public final class LumenFrames {
    private LumenFrames() {
    }

    /**
     * Render thread, once per new frame.
     *
     * @param rgba {@code width * height * 4} bytes from index 0, rows top to bottom; only read during the call
     */
    public static void submit(UUID display, ByteBuffer rgba, int width, int height) {
        FrameCapture.captureExternal(display, rgba, width, height);
    }

    /** Render thread: the picture is gone, the display shows Dream Displays' own again. */
    public static void end(UUID display) {
        FrameCapture.endExternal(display);
    }
}
