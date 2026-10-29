package dev.xcrafttm.ddlumen;

import dev.xcrafttm.ddlumen.Clamp;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Settings in config/ddlumen.json. Missing or broken values fall back to the defaults. */
public final class LumenConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ddlumen.json");

    private static LumenConfig current = new LumenConfig();
    private static boolean dirty;

    /** Master switch. */
    public boolean enabled = true;
    /** Colored light drawn onto the blocks in front of a display. */
    public boolean coloredLight = true;
    /** How strong the colored light is (1 = a white picture filling a surface's whole view lights it fully). */
    public float intensity = 4.0f;
    /** How far the light reaches in front of a display, in blocks. */
    public int range = 20;
    /** Columns and rows of the color grid sampled from the video (a 4 x 3 grid lights the room per area). */
    public int zonesX = 4;
    public int zonesY = 3;
    /** Light while a video loads (Dream Displays' loading screen), 0 = none, 1 = normal. */
    public float loadingLight = 1.0f;
    /** How fast the light follows the video, in milliseconds (higher is calmer). */
    public int smoothingMs = 90;
    /** Blocks between the display and a surface cast shadows. */
    public boolean shadows = true;
    /** Displays farther away than this from the camera give no light, in blocks. */
    public int maxDistance = 96;
    /** Upper limit of lit block faces per display. */
    public int maxFaces = 40000;

    /**
     * Raises the block light level through LambDynamicLights (if installed), which shader packs and entity lighting
     * read: "shaders" only while a shader pack is active, "always", or "off". Without shaders the colored light already
     * lights the room, and vanilla's warm block light tint would pull its colors toward orange.
     */
    public String blockLight = "shaders";
    /** How bright the block light gets. */
    public float blockLightStrength = 5.0f;
    /** Highest block light level a display gives. */
    public int blockLightMax = 13;

    /**
     * Colored light while a shader pack is active. Packs render deferred: what is drawn before their lighting counts
     * as surface color, so the light is drawn after their final pass instead (see IrisFinalPassMixin).
     */
    public boolean coloredLightWithShaders = true;
    /** Strength of the colored light while a shader pack is active. */
    public float intensityWithShaders = 1.5f;
    /** Light strength per display (display id to 0–2, 1 when missing), set in the display menu. */
    public Map<String, Float> displayLight = new HashMap<>();

    /** Iris program the colored light is drawn with while a shader pack is active. */
    public String irisProgram = "NONE";

    /** Whether the block light is raised right now. */
    public boolean blockLightActive(boolean shaderPackInUse) {
        return enabled && switch (blockLight) {
            case "always" -> true;
            case "off" -> false;
            default -> shaderPackInUse;
        };
    }

    /** The display's own light strength (0 = off, 1 = normal, 2 = double). */
    public float displayLight(UUID display) {
        Float v = displayLight.get(display.toString());
        return v == null ? 1f : v;
    }

    /** Sets the display's light strength; written to disk on the next client tick. */
    public void setDisplayLight(UUID display, float value) {
        value = Clamp.of(value, 0f, 2f);
        if (Math.abs(value - 1f) < 0.001f) displayLight.remove(display.toString());
        else displayLight.put(display.toString(), value);
        dirty = true;
    }

    /** Writes pending changes (client tick). */
    public static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        save();
    }

    public static LumenConfig get() {
        return current;
    }

    public static void load() {
        LumenConfig loaded = null;
        if (Files.exists(FILE)) {
            try {
                loaded = GSON.fromJson(Files.readString(FILE), LumenConfig.class);
            } catch (IOException | RuntimeException e) {
                DdLumen.LOGGER.warn("Could not read {}, using the defaults: {}", FILE, e.toString());
            }
        }
        current = loaded == null ? new LumenConfig() : loaded.sanitized();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(current));
        } catch (IOException e) {
            DdLumen.LOGGER.warn("Could not write {}: {}", FILE, e.toString());
        }
    }

    private LumenConfig sanitized() {
        intensity = clamp(intensity, 0f, 16f);
        intensityWithShaders = clamp(intensityWithShaders, 0f, 16f);
        loadingLight = clamp(loadingLight, 0f, 4f);
        range = Clamp.of(range, 2, 48);
        zonesX = Clamp.of(zonesX, 1, 8);
        zonesY = Clamp.of(zonesY, 1, 8);
        smoothingMs = Clamp.of(smoothingMs, 0, 2000);
        maxDistance = Clamp.of(maxDistance, 8, 512);
        maxFaces = Clamp.of(maxFaces, 1000, 400000);
        blockLightStrength = clamp(blockLightStrength, 0f, 100f);
        blockLightMax = Clamp.of(blockLightMax, 0, 15);
        if (irisProgram == null) irisProgram = "NONE";
        if (displayLight == null) displayLight = new HashMap<>();
        displayLight.replaceAll((id, v) -> v == null || v.isNaN() ? 1f : Clamp.of(v, 0f, 2f));
        if (!"always".equals(blockLight) && !"off".equals(blockLight)) blockLight = "shaders";
        return this;
    }

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? min : Clamp.of(v, min, max);
    }
}
