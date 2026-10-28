package dev.xcrafttm.ddlumen.light;

import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.capture.FrameCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
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

    /** Sets what the light should become: the latest frame times [brightness], or dark. */
    void setTarget(boolean on, float brightness) {
        FrameCapture.ZoneFrame frame = on ? FrameCapture.latest(id) : null;
        if (frame == null || frame.zonesX() != zonesX || frame.zonesY() != zonesY) {
            java.util.Arrays.fill(target, 0f);
            return;
        }
        float[] rgb = frame.rgb();
        for (int i = 0; i < target.length; i++) target[i] = rgb[i] * brightness;
    }

    /** Moves the shown light toward the target. */
    void smooth(float dtSeconds) {
        int ms = LumenConfig.get().smoothingMs;
        float k = ms <= 0 ? 1f : (float) (1 - Math.exp(-dtSeconds * 1000.0 / ms));
        boolean any = false;
        for (int i = 0; i < radiance.length; i++) {
            radiance[i] += (target[i] - radiance[i]) * k;
            if (radiance[i] > 1e-4f) any = true;
        }
        lit = any;
        published = radiance.clone();
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
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        building = CompletableFuture.supplyAsync(
                () -> SurfaceBuilder.build(snapshot, g, zx, zy, cfg.shadows, cfg.maxFaces, models), worker);
    }

    void cancel() {
        if (building != null) building.cancel(false);
        building = null;
    }

    private static void applyTints(LitSurface s, ClientLevel level) {
        if (!s.needsTints()) return;
        BlockColors colors = Minecraft.getInstance().getBlockColors();
        for (int q = 0; q < s.quads; q++) {
            int layer = s.tintIndex[q];
            if (layer < 0 || s.tintState[q] == null) continue;
            BlockTintSource source = colors.getTintSource(s.tintState[q], layer);
            if (source != null) s.tints[q] = source.colorInWorld(s.tintState[q], level, s.tintPos(q)) & 0xFFFFFF;
        }
    }
}
