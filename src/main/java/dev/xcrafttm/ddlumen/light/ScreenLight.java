package dev.xcrafttm.ddlumen.light;

import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.capture.FrameCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** The light of one display: its picture's zone colors (smoothed) and the surfaces they reach. */
public final class ScreenLight {
    private static final long REBUILD_DELAY_NS = 250_000_000L;

    public final UUID id;
    private ScreenGeometry geometry;
    private BlockPos regionMin, regionMax;
    private int zonesX, zonesY;

    /** Linear RGB per zone as shown now / as the video wants it. */
    private float[] radiance = new float[0];
    private float[] target = new float[0];
    /** Zone light for other threads (LambDynamicLights), replaced as a whole. */
    private volatile float[] published = new float[0];
    private boolean lit;

    private volatile LitSurface surface = LitSurface.EMPTY;
    private CompletableFuture<LitSurface> building;
    private boolean dirty = true;
    private long dirtySince;

    /** LambDynamicLights' handle for this display, when it is installed. */
    private Object dynamicLight;

    ScreenLight(UUID id) {
        this.id = id;
    }

    public ScreenGeometry geometry() {
        return geometry;
    }

    public LitSurface surface() {
        return surface;
    }

    public int zonesX() {
        return zonesX;
    }

    public int zonesY() {
        return zonesY;
    }

    public BlockPos regionMin() {
        return regionMin;
    }

    public BlockPos regionMax() {
        return regionMax;
    }

    /** Current zone light (linear RGB per zone, brightness applied). Render thread. */
    public float[] radiance() {
        return radiance;
    }

    /** Zone light safe to read from any thread. */
    public float[] publishedRadiance() {
        return published;
    }

    public boolean isLit() {
        return lit;
    }

    public Object dynamicLight() {
        return dynamicLight;
    }

    public void setDynamicLight(Object handle) {
        dynamicLight = handle;
    }

    /** Updates where the display is; a change rebuilds the surfaces. */
    void setGeometry(ScreenGeometry next) {
        LumenConfig cfg = LumenConfig.get();
        boolean zonesChanged = cfg.zonesX != zonesX || cfg.zonesY != zonesY;
        if (next.equals(geometry) && !zonesChanged && regionMin != null) return;
        geometry = next;
        zonesX = cfg.zonesX;
        zonesY = cfg.zonesY;
        BlockPos[] region = next.region(cfg.range);
        regionMin = region[0];
        regionMax = region[1];
        if (zonesChanged) {
            radiance = new float[zonesX * zonesY * 3];
            target = new float[zonesX * zonesY * 3];
        }
        surface = LitSurface.EMPTY;
        markDirty();
    }

    void markDirty() {
        if (!dirty) dirtySince = System.nanoTime();
        dirty = true;
    }

    boolean contains(BlockPos pos) {
        return regionMin != null
                && pos.getX() >= regionMin.getX() - 1 && pos.getX() <= regionMax.getX() + 1
                && pos.getY() >= regionMin.getY() - 1 && pos.getY() <= regionMax.getY() + 1
                && pos.getZ() >= regionMin.getZ() - 1 && pos.getZ() <= regionMax.getZ() + 1;
    }

    boolean intersectsChunk(int chunkX, int chunkZ) {
        return regionMin != null
                && (chunkX << 4) <= regionMax.getX() + 1 && (chunkX << 4) + 15 >= regionMin.getX() - 1
                && (chunkZ << 4) <= regionMax.getZ() + 1 && (chunkZ << 4) + 15 >= regionMin.getZ() - 1;
    }

    /**
     * Sets what the light should become: the latest frame times [brightness]; while the video loads (or no frame
     * arrived yet) the glow of Dream Displays' loading screen; otherwise dark.
     */
    void setTarget(ScreenLights.Mode mode, float brightness, float saturation) {
        FrameCapture.ZoneFrame frame = mode == ScreenLights.Mode.VIDEO ? FrameCapture.latest(id) : null;
        if (frame != null && frame.zonesX() == zonesX && frame.zonesY() == zonesY) {
            float[] rgb = frame.rgb();
            for (int i = 0; i < target.length; i += 3) {
                // push each zone's color away from its own gray (luminance stays the same)
                float r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
                float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                target[i] = Math.max(0f, y + (r - y) * saturation) * brightness;
                target[i + 1] = Math.max(0f, y + (g - y) * saturation) * brightness;
                target[i + 2] = Math.max(0f, y + (b - y) * saturation) * brightness;
            }
        } else if (mode != ScreenLights.Mode.OFF && LumenConfig.get().loadingLight > 0) {
            loadingGlow(brightness * LumenConfig.get().loadingLight);
        } else {
            java.util.Arrays.fill(target, 0f);
        }
    }

    /**
     * Dream Displays' loading screen: a dim warm panel with a blue progress bar along the bottom. A little brighter
     * than the panel itself (linear light), so the room does not go dark while a video starts.
     */
    private void loadingGlow(float strength) {
        for (int j = 0; j < zonesY; j++) {
            boolean bottom = j == zonesY - 1;
            for (int i = 0; i < zonesX; i++) {
                int k = (j * zonesX + i) * 3;
                target[k] = (0.10f + (bottom ? 0.015f : 0f)) * strength;
                target[k + 1] = (0.075f + (bottom ? 0.04f : 0f)) * strength;
                target[k + 2] = (0.065f + (bottom ? 0.07f : 0f)) * strength;
            }
        }
    }

    /** Moves the shown light toward the target. */
    void smooth(float dtSeconds) {
        // quick to brighten (flashes come through), calmer to dim
        float rise = follow(dtSeconds, LumenConfig.get().riseMs), fall = follow(dtSeconds, LumenConfig.get().smoothingMs);
        boolean any = false;
        for (int i = 0; i < radiance.length; i++) {
            radiance[i] += (target[i] - radiance[i]) * (target[i] > radiance[i] ? rise : fall);
            if (radiance[i] > 1e-4f) any = true;
        }
        lit = any;
        published = radiance.clone();
    }

    private static float follow(float dtSeconds, int ms) {
        return ms <= 0 ? 1f : (float) (1 - Math.exp(-dtSeconds * 1000.0 / ms));
    }

    /** Starts a rebuild of the lit surfaces when the region changed a while ago. Client thread. */
    void maybeRebuild(ClientLevel level, Executor worker) {
        if (building != null) {
            if (!building.isDone()) return;
            LitSurface built = building.getNow(null);
            building = null;
            if (built != null) {
                applyTints(built, level);
                surface = built;
            }
        }
        if (!dirty || geometry == null || System.nanoTime() - dirtySince < REBUILD_DELAY_NS) return;
        dirty = false;
        SurfaceBuilder.Snapshot snapshot = SurfaceBuilder.snapshot(level, regionMin, regionMax);
        if (snapshot == null) {
            surface = LitSurface.EMPTY;
            return;
        }
        LumenConfig cfg = LumenConfig.get();
        ScreenGeometry g = geometry;
        int zx = zonesX, zy = zonesY;
        Object models = ModelQuads.models();
        building = CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            LitSurface built = SurfaceBuilder.build(snapshot, g, zx, zy, cfg.range, cfg.shadows, cfg.maxFaces, models);
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= SLOW_BUILD_MS || Boolean.getBoolean("ddlumen.logBuilds")) {
                dev.xcrafttm.ddlumen.DdLumen.LOGGER.info("Display {}: light of {} faces built in {} ms", id, built.quads, ms);
            }
            return built;
        }, worker);
    }

    /** Rebuilds that take this long are logged. */
    private static final long SLOW_BUILD_MS = 250;

    void cancel() {
        if (building != null) building.cancel(false);
        building = null;
    }

    private static void applyTints(LitSurface s, ClientLevel level) {
        if (!s.needsTints()) return;
        for (int q = 0; q < s.quads; q++) {
            int layer = s.tintIndex[q];
            if (layer >= 0 && s.tintState[q] != null) s.tints[q] = ModelQuads.tint(s.tintState[q], level, s.tintPos(q), layer);
        }
    }
}
