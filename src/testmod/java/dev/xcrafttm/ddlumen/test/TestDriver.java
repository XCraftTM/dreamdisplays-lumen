package dev.xcrafttm.ddlumen.test;

import com.dreamdisplays.api.Display;
import com.dreamdisplays.api.DreamDisplays;
import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.capture.FrameCapture;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.ScreenLight;
import dev.xcrafttm.ddlumen.light.ScreenLights;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Drives the test: watches the captured colors of the test video, and in each of its four parts (red | blue,
 * green, white, black) takes a screenshot with the colored light and one without. Quits when done.
 */
public final class TestDriver implements ClientModInitializer {
    private enum Phase { RED_BLUE, GREEN, WHITE, BLACK }

    private static final long STABLE_MS = 500, OFF_AFTER_MS = 700, TIMEOUT_MS = 240_000;
    private final EnumSet<Phase> done = EnumSet.noneOf(Phase.class);
    private Phase current;
    private long phaseSince, onShotAt, joinedAt, lastLog, doneAt;
    private String prefix = System.getProperty("ddlumen.test.prefix", "lumen");
    /** "colors": the four parts of the test video; "video": any video, from [SEEK_MS], pairs of shots every few seconds. */
    private final String mode = System.getProperty("ddlumen.test.mode", "colors");
    private static final long SEEK_MS = Long.getLong("ddlumen.test.seek", 0L);
    private static final int VIDEO_SHOTS = 6;
    private long playingSince, seekedAt, nextShotAt;
    private int videoShots;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (joinedAt == 0) {
            joinedAt = now;
            TestScene.LOG.info("Joined, shader pack in use: {}", IrisCompat.isShaderPackInUse());
        }
        if (now - lastLog > 2000) {
            lastLog = now;
            StringBuilder sb = new StringBuilder();
            for (ScreenLight light : ScreenLights.all()) {
                FrameCapture.ZoneFrame frame = FrameCapture.latest(light.id);
                sb.append(String.format(Locale.ROOT, " [%s faces=%d lit=%s frame=%s screen=%s]", light.id, light.surface().quads,
                        light.isLit(), frame == null ? "none" : describe(frame), mc.gui.screen() == null ? "-" : mc.gui.screen().getClass().getSimpleName()));
            }
            TestScene.LOG.info("fps={} submits={} drawnFaces={} lights:{}", mc.getFps(),
                    dev.xcrafttm.ddlumen.render.LightRenderer.lastSubmits, dev.xcrafttm.ddlumen.render.LightRenderer.lastFaces,
                    sb.length() == 0 ? " none" : sb);
        }
        if (doneAt != 0) {
            if (now - doneAt > 1500) mc.stop();
            return;
        }
        if (now - joinedAt > TIMEOUT_MS) {
            TestScene.LOG.error("Timed out, phases done: {}", done);
            shot(mc, "timeout");
            doneAt = now;
            return;
        }

        if (mode.equals("video")) {
            videoTick(mc, now);
            return;
        }

        if (mc.gui.screen() != null) return;
        Phase phase = null;
        ScreenLight light = ScreenLights.all().stream().findFirst().orElse(null);
        if (light != null) {
            FrameCapture.ZoneFrame frame = FrameCapture.latest(light.id);
            if (frame != null) phase = classify(frame);
        }
        if (phase != current) {
            current = phase;
            phaseSince = now;
            onShotAt = 0;
            LumenConfig.get().coloredLight = true;
            return;
        }
        if (phase == null || done.contains(phase)) return;
        if (onShotAt == 0 && now - phaseSince > STABLE_MS) {
            shot(mc, phase.name().toLowerCase(Locale.ROOT) + "-on");
            TestScene.LOG.info("{}: zones {}", phase, describe(FrameCapture.latest(light.id)));
            onShotAt = now;
            LumenConfig.get().coloredLight = false;
        } else if (onShotAt != 0 && now - onShotAt > OFF_AFTER_MS) {
            shot(mc, phase.name().toLowerCase(Locale.ROOT) + "-off");
            LumenConfig.get().coloredLight = true;
            done.add(phase);
            if (done.size() == Phase.values().length) {
                TestScene.LOG.info("All phases captured");
                doneAt = now;
            }
        }
    }

    /** Waits for playback, seeks once, then takes a shot with and one without the light every few seconds. */
    private void videoTick(Minecraft mc, long now) {
        if (mc.gui.screen() != null) return;
        Display display = null;
        for (Display d : DreamDisplays.getDisplays()) display = d;
        if (display == null || !display.isPlaying()) return;
        if (playingSince == 0) {
            playingSince = now;
            return;
        }
        if (seekedAt == 0 && now - playingSince > 2000) {
            seekedAt = now;
            TestScene.LOG.info("Seeking to {} ms: {}", SEEK_MS, SEEK_MS > 0 ? display.seek(SEEK_MS) : "skipped");
            nextShotAt = now + 6000;
            return;
        }
        if (seekedAt == 0 || now < nextShotAt) return;
        if (LumenConfig.get().coloredLight) {
            shot(mc, "video" + videoShots + "-on");
            LumenConfig.get().coloredLight = false;
            nextShotAt = now + 400;
        } else {
            shot(mc, "video" + videoShots + "-off");
            LumenConfig.get().coloredLight = true;
            videoShots++;
            nextShotAt = now + 3500;
            if (videoShots >= VIDEO_SHOTS) doneAt = now;
        }
    }

    private void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, prefix + "-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                msg -> TestScene.LOG.info("Screenshot {}: {}", name, msg.getString()));
    }

    /** Which part of the test video the zones show, or null in between. */
    private static Phase classify(FrameCapture.ZoneFrame f) {
        float[] c = f.rgb();
        int zx = f.zonesX(), zy = f.zonesY(), mid = zy / 2;
        float[] left = zone(c, zx, 0, mid), right = zone(c, zx, zx - 1, mid);
        if (left[0] > 0.5f && left[2] < 0.2f && right[2] > 0.5f && right[0] < 0.2f) return Phase.RED_BLUE;
        boolean green = true, white = true, black = true;
        for (int i = 0; i < c.length; i += 3) {
            green &= c[i + 1] > 0.5f && c[i] < 0.2f && c[i + 2] < 0.2f;
            white &= c[i] > 0.7f && c[i + 1] > 0.7f && c[i + 2] > 0.7f;
            black &= c[i] < 0.03f && c[i + 1] < 0.03f && c[i + 2] < 0.03f;
        }
        return green ? Phase.GREEN : white ? Phase.WHITE : black ? Phase.BLACK : null;
    }

    private static float[] zone(float[] c, int zx, int x, int y) {
        int k = (y * zx + x) * 3;
        return new float[]{c[k], c[k + 1], c[k + 2]};
    }

    private static String describe(FrameCapture.ZoneFrame f) {
        float[] c = f.rgb();
        String[] parts = new String[f.zonesX() * f.zonesY()];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = String.format(Locale.ROOT, "%.2f/%.2f/%.2f", c[i * 3], c[i * 3 + 1], c[i * 3 + 2]);
        }
        return f.zonesX() + "x" + f.zonesY() + Arrays.toString(parts);
    }
}
