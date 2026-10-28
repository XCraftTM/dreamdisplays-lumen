package dev.xcrafttm.ddlumen.compat;

import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.light.ScreenLight;

/**
 * LambDynamicLights, when installed: each lit display also raises the block light in front of it, which shader
 * packs and entity lighting read. Holds no LambDynamicLights types, so it loads without the mod.
 */
public final class LdlCompat {
    /** Set by {@link LdlEntrypoint} once LambDynamicLights is ready. */
    static volatile LdlBridge bridge;

    private LdlCompat() {
    }

    public static void update(ScreenLight light) {
        LdlBridge b = bridge;
        if (b == null) return;
        if (LumenConfig.get().blockLightActive(IrisCompat.isShaderPackInUse())) b.track(light);
        else b.untrack(light);
    }

    public static void remove(ScreenLight light) {
        LdlBridge b = bridge;
        if (b != null) b.untrack(light);
    }
}
