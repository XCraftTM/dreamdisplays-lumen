package dev.xcrafttm.ddlumen.light;

import com.dreamdisplays.api.Display;
import com.dreamdisplays.api.DreamDisplays;
import com.dreamdisplays.api.display.model.property.DisplayBounds;
import com.dreamdisplays.api.display.model.DisplayInfo;
import com.dreamdisplays.api.display.model.property.DisplayState;
import dev.xcrafttm.ddlumen.DdLumen;
import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.capture.FrameCapture;
import dev.xcrafttm.ddlumen.compat.LdlCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps a {@link ScreenLight} for every Dream Displays display near the camera. */
public final class ScreenLights {
    private static final Map<UUID, ScreenLight> LIGHTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ON = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> BRIGHTNESS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ddlumen-surfaces");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static long lastFrameNs;
    private static Method screensGetter;
    private static boolean screensGetterMissing;

    private ScreenLights() {
    }

    public static Collection<ScreenLight> all() {
        return LIGHTS.values();
    }

    /** Client tick: follows the displays and starts surface rebuilds. */
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LumenConfig cfg = LumenConfig.get();
        if (level == null || !cfg.enabled || !DreamDisplays.isAvailable()) {
            if (!LIGHTS.isEmpty()) clear();
            return;
        }
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        double reach = cfg.maxDistance + cfg.range;
        Set<UUID> seen = new HashSet<>();
        for (Display display : DreamDisplays.getDisplays()) {
            DisplayInfo info = display.getInfo();
            if (info == null) continue;
            DisplayBounds b = info.getBounds();
            UUID id = display.getId().getUuid();
            ScreenGeometry geometry = ScreenGeometry.of((int) Math.floor(b.getX()), (int) Math.floor(b.getY()),
                    (int) Math.floor(b.getZ()), b.getWidth(), b.getHeight(), depthOf(id), b.getFacing());
            double dx = geometry.cx() - camera.x, dy = geometry.cy() - camera.y, dz = geometry.cz() - camera.z;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            seen.add(id);
            ScreenLight light = LIGHTS.computeIfAbsent(id, ScreenLight::new);
            light.setGeometry(geometry);
            DisplayState state = info.getState();
            ON.put(id, state instanceof DisplayState.Playing || state instanceof DisplayState.Paused
                    || state instanceof DisplayState.Buffering);
            BRIGHTNESS.put(id, info.getSettings().getBrightness());
            light.maybeRebuild(level, WORKER);
            LdlCompat.update(light);
        }
        LIGHTS.entrySet().removeIf(e -> {
            if (seen.contains(e.getKey())) return false;
            remove(e.getValue());
            return true;
        });
    }

    /** Render thread, once per frame before drawing: moves every light toward its video's colors. */
    public static void frame() {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0.016f : Math.min(0.25f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;
        for (ScreenLight light : LIGHTS.values()) {
            light.setTarget(ON.getOrDefault(light.id, false), BRIGHTNESS.getOrDefault(light.id, 1f));
            light.smooth(dt);
        }
    }

    public static void onBlockChanged(BlockPos pos) {
        for (ScreenLight light : LIGHTS.values()) {
            if (light.contains(pos)) light.markDirty();
        }
    }

    public static void onChunkLoaded(int chunkX, int chunkZ) {
        for (ScreenLight light : LIGHTS.values()) {
            if (light.intersectsChunk(chunkX, chunkZ)) light.markDirty();
        }
    }

    /** Rebuilds everything (settings changed). */
    public static void reset() {
        clear();
    }

    public static void clear() {
        LIGHTS.values().forEach(ScreenLights::remove);
        LIGHTS.clear();
        ON.clear();
        BRIGHTNESS.clear();
        FrameCapture.clear();
    }

    private static void remove(ScreenLight light) {
        light.cancel();
        LdlCompat.remove(light);
        ON.remove(light.id);
        BRIGHTNESS.remove(light.id);
    }

    /**
     * How many blocks the display reaches out of the wall. The public API has no depth yet, so it is read from Dream
     * Displays' own display list; 1 (a flat display) when that is not possible.
     */
    private static int depthOf(UUID id) {
        if (screensGetterMissing) return 1;
        try {
            if (screensGetter == null) {
                Class<?> registry = Class.forName("com.dreamdisplays.platform.client.displays.DisplayRegistry");
                for (Method m : registry.getMethods()) {
                    if (m.getName().equals("getScreens") && Map.class.isAssignableFrom(m.getReturnType())
                            && m.getParameterCount() == 0) {
                        screensGetter = m;
                    }
                }
                if (screensGetter == null) throw new NoSuchMethodException("DisplayRegistry.getScreens");
            }
            Object registry = screensGetter.getDeclaringClass().getField("INSTANCE").get(null);
            Object screen = ((Map<?, ?>) screensGetter.invoke(registry)).get(id);
            if (screen == null) return 1;
            return Math.max(1, (int) screen.getClass().getMethod("getDepth").invoke(screen));
        } catch (ReflectiveOperationException | RuntimeException e) {
            screensGetterMissing = true;
            DdLumen.LOGGER.warn("Could not read display depths from Dream Displays, assuming flat displays: {}", e.toString());
            return 1;
        }
    }
}
