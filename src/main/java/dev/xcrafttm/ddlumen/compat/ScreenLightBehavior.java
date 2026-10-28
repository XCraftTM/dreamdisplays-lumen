package dev.xcrafttm.ddlumen.compat;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.light.ScreenGeometry;
import dev.xcrafttm.ddlumen.light.ScreenLight;
import net.minecraft.core.BlockPos;

/**
 * A display as a LambDynamicLights light: the block light in front of it follows how bright its picture is, with
 * the same falloff as the colored light (without shadows; block light flows around corners anyway).
 * LambDynamicLights calls this from its own threads, so it only reads the published zone light.
 */
final class ScreenLightBehavior implements DynamicLightBehavior {
    /** At most this often the light counts as changed (every change rebuilds the chunk sections around it). */
    private static final long MIN_CHANGE_INTERVAL_NS = 150_000_000L;

    private final ScreenLight light;
    private final ScreenGeometry geometry;
    private final int zonesX, zonesY;
    private final double[] centers;
    private final double zoneArea;
    private final BoundingBox box;
    private volatile boolean removed;
    private float[] lastLuminance = new float[0];
    private long lastChangeNs;

    ScreenLightBehavior(ScreenLight light) {
        this.light = light;
        this.geometry = light.geometry();
        this.zonesX = light.zonesX();
        this.zonesY = light.zonesY();
        this.centers = geometry.zoneCenters(zonesX, zonesY);
        this.zoneArea = geometry.area() / (zonesX * zonesY);
        BlockPos min = light.regionMin(), max = light.regionMax();
        this.box = new BoundingBox(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    boolean matches(ScreenLight current) {
        return current.geometry() == geometry && current.zonesX() == zonesX && current.zonesY() == zonesY;
    }

    void markRemoved() {
        removed = true;
    }

    @Override
    public double lightAtPos(BlockPos pos, double falloffRatio) {
        float[] radiance = light.publishedRadiance();
        if (radiance.length != zonesX * zonesY * 3) return 0;
        double px = pos.getX() + 0.5, py = pos.getY() + 0.5, pz = pos.getZ() + 0.5;
        if (!geometry.inFront(px, py, pz)) return 0;
        double sum = 0;
        for (int z = 0; z < zonesX * zonesY; z++) {
            double dx = px - centers[z * 3], dy = py - centers[z * 3 + 1], dz = pz - centers[z * 3 + 2];
            double dist2 = dx * dx + dy * dy + dz * dz;
            double cosEmit = (dx * geometry.nx() + dy * geometry.ny() + dz * geometry.nz()) / Math.sqrt(dist2);
            if (cosEmit <= 0) continue;
            double weight = zoneArea * cosEmit / (Math.PI * (dist2 + zoneArea * 0.25));
            sum += weight * luminance(radiance, z);
        }
        LumenConfig cfg = LumenConfig.get();
        return cfg.blockLightMax * (1 - Math.exp(-sum * cfg.blockLightStrength));
    }

    @Override
    public BoundingBox getBoundingBox() {
        return box;
    }

    @Override
    public boolean hasChanged() {
        float[] radiance = light.publishedRadiance();
        int zones = zonesX * zonesY;
        if (radiance.length != zones * 3) return false;
        long now = System.nanoTime();
        if (now - lastChangeNs < MIN_CHANGE_INTERVAL_NS) return false;
        if (lastLuminance.length != zones) lastLuminance = new float[zones];
        boolean changed = false;
        for (int z = 0; z < zones; z++) {
            float lum = luminance(radiance, z);
            // a level step is about 1/15 of the range: smaller changes are not visible in block light
            if (Math.abs(lum - lastLuminance[z]) > 0.02f + lastLuminance[z] * 0.08f) changed = true;
        }
        if (!changed) return false;
        for (int z = 0; z < zones; z++) lastLuminance[z] = luminance(radiance, z);
        lastChangeNs = now;
        return true;
    }

    @Override
    public boolean isRemoved() {
        return removed;
    }

    private static float luminance(float[] rgb, int zone) {
        return 0.2126f * rgb[zone * 3] + 0.7152f * rgb[zone * 3 + 1] + 0.0722f * rgb[zone * 3 + 2];
    }
}
