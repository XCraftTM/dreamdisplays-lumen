package dev.xcrafttm.ddlumen.compat;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.xcrafttm.ddlumen.light.ScreenLight;

/** The LambDynamicLights side of {@link LdlCompat}; only created by {@link LdlEntrypoint}. */
final class LdlBridge {
    private final DynamicLightBehaviorManager manager;

    LdlBridge(DynamicLightBehaviorManager manager) {
        this.manager = manager;
    }

    void track(ScreenLight light) {
        ScreenLightBehavior behavior = light.dynamicLight() instanceof ScreenLightBehavior b ? b : null;
        if (behavior != null && behavior.matches(light)) return;
        if (behavior != null) {
            behavior.markRemoved();
            manager.remove(behavior);
        }
        if (light.geometry() == null) return;
        behavior = new ScreenLightBehavior(light);
        light.setDynamicLight(behavior);
        manager.add(behavior);
    }

    void untrack(ScreenLight light) {
        if (light.dynamicLight() instanceof ScreenLightBehavior behavior) {
            behavior.markRemoved();
            manager.remove(behavior);
        }
        light.setDynamicLight(null);
    }
}
