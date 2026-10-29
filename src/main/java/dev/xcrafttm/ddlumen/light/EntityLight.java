package dev.xcrafttm.ddlumen.light;

import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.render.LightRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The light displays give entities (players, mobs, items): what a surface at the entity's middle that faces the
 * picture gets from it (as much as a wall there would), as a block light level and a color tint for the model, so an
 * entity matches the light where it stands. Render thread.
 * <p>
 * How much of each zone reaches the entity is worked out again every few ticks or when it moved; the zones' colors
 * of the current frame are applied every frame.
 */
public final class EntityLight {
    private static final int REFRESH_TICKS = 10;
    private static final double REFRESH_MOVE2 = 0.25 * 0.25;

    /** One entity's light. */
    private static final class Lit {
        double x, y, z;
        long tick = Long.MIN_VALUE;
        long frame = -1;
        final List<ScreenLight> lights = new ArrayList<>();
        final List<ScreenGeometry> geometries = new ArrayList<>();
        final List<double[]> weights = new ArrayList<>();
        int level;
        int tint = -1;
    }

    private static final Map<Integer, Lit> LIT = new HashMap<>();
    /** Model tints between extracting an entity's render state and drawing it (1.21.11 and newer). */
    private static final Map<Object, Integer> STATE_TINTS = new WeakHashMap<>();
    private static long tick, frame;

    private EntityLight() {
    }

    static void tick() {
        tick++;
        if (tick % 200 == 0) LIT.values().removeIf(lit -> tick - lit.tick > 200);
    }

    static void nextFrame() {
        frame++;
    }

    static void clear() {
        LIT.clear();
        STATE_TINTS.clear();
    }

    /** Block light level the displays give [entity] (0: none). */
    public static int level(Entity entity) {
        Lit lit = of(entity);
        return lit == null ? 0 : lit.level;
    }

    /** ARGB color the displays tint [entity]'s model with (-1: none). */
    public static int tint(Entity entity) {
        Lit lit = of(entity);
        return lit == null ? -1 : lit.tint;
    }

    /** Keeps [entity]'s tint for its render state, read back by {@link #tintOf}. */
    public static void rememberTint(Object state, Entity entity) {
        int tint = tint(entity);
        if (tint == -1) STATE_TINTS.remove(state);
        else STATE_TINTS.put(state, tint);
    }

    public static int tintOf(Object state) {
        Integer tint = STATE_TINTS.get(state);
        return tint == null ? -1 : tint;
    }

    /** Two ARGB colors multiplied channel by channel. */
    public static int multiply(int a, int b) {
        if (b == -1) return a;
        int alpha = ((a >>> 24) * (b >>> 24)) / 255;
        int r = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255;
        int g = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255;
        int bl = ((a & 0xFF) * (b & 0xFF)) / 255;
        return alpha << 24 | r << 16 | g << 8 | bl;
    }

    private static Lit of(Entity entity) {
        LumenConfig cfg = LumenConfig.get();
        if (!cfg.enabled || !cfg.coloredLight || !cfg.entityLight || ScreenLights.all().isEmpty()) return null;
        boolean shaders = IrisCompat.isShaderPackInUse();
        // before 1.21.11 the colored light is off with shader packs: entities stay as dark as the blocks then
        //#if MC >= 1.21.11
        if (shaders && !cfg.coloredLightWithShaders) return null;
        //#else
        if (shaders) return null;
        //#endif
        Lit lit = LIT.computeIfAbsent(entity.getId(), id -> new Lit());
        if (lit.frame == frame) return lit;
        lit.frame = frame;
        AABB box = entity.getBoundingBox();
        double x = (box.minX + box.maxX) / 2, y = (box.minY + box.maxY) / 2, z = (box.minZ + box.maxZ) / 2;
        if (stale(lit, x, y, z)) weigh(lit, entity, cfg, x, y, z);

        float intensity = shaders ? cfg.intensityWithShaders : cfg.intensity;
        float r = 0, g = 0, b = 0;
        for (int i = 0; i < lit.lights.size(); i++) {
            ScreenLight light = lit.lights.get(i);
            float[] radiance = light.radiance();
            double[] w = lit.weights.get(i);
            if (!light.isLit() || radiance.length != w.length * 3) continue;
            for (int k = 0; k < w.length; k++) {
                r += (float) (w[k] * radiance[k * 3]);
                g += (float) (w[k] * radiance[k * 3 + 1]);
                b += (float) (w[k] * radiance[k * 3 + 2]);
            }
        }
        r *= intensity;
        g *= intensity;
        b *= intensity;
        float peak = Math.max(r, Math.max(g, b));
        // as bright as the light looks on a dark block there
        float shown = Math.min(1f, peak > 0 ? LightRenderer.shown(peak) : 0f);
        if (shown <= 0.004f) {
            lit.level = 0;
            lit.tint = -1;
            return lit;
        }
        // the block light level the game shows that bright (its curve: f / (4 - 3 f) for f = level / 15)
        lit.level = Math.min(15, (int) Math.ceil(15 * 4 * shown / (1 + 3 * shown)));
        // the color, as strong as the displays' share of all the light there
        float ambient = levelBrightness(levelOf(entity).getMaxLocalRawBrightness(entity.blockPosition()));
        float share = shown / (shown + ambient);
        int tr = channel(r / peak, share), tg = channel(g / peak, share), tb = channel(b / peak, share);
        lit.tint = 0xFF000000 | tr << 16 | tg << 8 | tb;
        return lit;
    }

    private static int channel(float color, float share) {
        return Math.round((1 - share + share * color) * 255);
    }

    /** How bright the game shows a light level (no gamma setting). */
    private static float levelBrightness(int level) {
        float f = level / 15f;
        return f / (4 - 3 * f);
    }

    private static boolean stale(Lit lit, double x, double y, double z) {
        if (tick - lit.tick >= REFRESH_TICKS) return true;
        double dx = x - lit.x, dy = y - lit.y, dz = z - lit.z;
        if (dx * dx + dy * dy + dz * dz > REFRESH_MOVE2) return true;
        // a display moved, came or went
        int n = 0;
        for (ScreenLight light : ScreenLights.all()) {
            int i = lit.lights.indexOf(light);
            if (i >= 0 && lit.geometries.get(i) != light.geometry()) return true;
            if (i >= 0) n++;
        }
        return n != lit.lights.size();
    }

    private static void weigh(Lit lit, Entity entity, LumenConfig cfg, double x, double y, double z) {
        lit.x = x;
        lit.y = y;
        lit.z = z;
        lit.tick = tick;
        lit.lights.clear();
        lit.geometries.clear();
        lit.weights.clear();
        Level level = levelOf(entity);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        SurfaceBuilder.BlockAt blocks = (bx, by, bz) -> level.getBlockState(pos.set(bx, by, bz));
        BlockPos at = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        for (ScreenLight light : ScreenLights.all()) {
            ScreenGeometry g = light.geometry();
            if (g == null || !light.contains(at) || !g.inFront(x, y, z)) continue;
            int zx = light.zonesX(), zy = light.zonesY();
            SurfaceBuilder.Scratch scratch = new SurfaceBuilder.Scratch(zx * zy);
            // the side of the entity that faces the picture
            double[] n = {-g.nx(), -g.ny(), -g.nz()};
            SurfaceBuilder.pointLight(blocks, g, g.zoneCenters(zx, zy), g.zoneCorners(zx, zy), zx, zy, cfg.range,
                    cfg.shadows, x, y, z, n, scratch);
            lit.lights.add(light);
            lit.geometries.add(g);
            lit.weights.add(scratch.light.clone());
        }
    }

    private static Level levelOf(Entity entity) {
        //#if MC >= 1.20
        return entity.level();
        //#else
        return entity.level;
        //#endif
    }
}
