package dev.xcrafttm.ddlumen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings in config/ddlumen.json. Missing or broken values fall back to the defaults. */
public final class LumenConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ddlumen.json");

    private static LumenConfig current = new LumenConfig();

    /** Master switch. */
    public boolean enabled = true;
    /** Colored light drawn onto the blocks in front of a display. */
    public boolean coloredLight = true;
    /** How strong the colored light is (1 = a white screen lights a wall right in front of it like full daylight). */
    public float intensity = 2.0f;
    /** How far the light reaches in front of a display, in blocks. */
    public int range = 16;
    /** Columns and rows of the color grid sampled from the video (a 4 x 3 grid lights the room per area). */
    public int zonesX = 4;
    public int zonesY = 3;
    /** How fast the light follows the video, in milliseconds (higher is calmer). */
    public int smoothingMs = 90;
    /** Blocks between the display and a surface cast shadows. */
    public boolean shadows = true;
    /** Displays farther away than this from the camera give no light, in blocks. */
    public int maxDistance = 96;
    /** Upper limit of lit block faces per display. */
    public int maxFaces = 40000;

    /** Raises the block light level through LambDynamicLights (if installed): makes shader packs and entities brighter. */
    public boolean blockLight = true;
    /** How bright the block light gets. */
    public float blockLightStrength = 5.0f;
    /** Highest block light level a display gives. */
    public int blockLightMax = 13;

    /** Colored light while a shader pack is active (the pack decides how it is shaded). */
    public boolean coloredLightWithShaders = true;
    /** Iris program the colored light is drawn with while a shader pack is active. */
    public String irisProgram = "BEACON_BEAM";

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
        range = Math.clamp(range, 2, 48);
        zonesX = Math.clamp(zonesX, 1, 8);
        zonesY = Math.clamp(zonesY, 1, 8);
        smoothingMs = Math.clamp(smoothingMs, 0, 2000);
        maxDistance = Math.clamp(maxDistance, 8, 512);
        maxFaces = Math.clamp(maxFaces, 1000, 400000);
        blockLightStrength = clamp(blockLightStrength, 0f, 100f);
        blockLightMax = Math.clamp(blockLightMax, 0, 15);
        if (irisProgram == null) irisProgram = "BEACON_BEAM";
        return this;
    }

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? min : Math.clamp(v, min, max);
    }
}
