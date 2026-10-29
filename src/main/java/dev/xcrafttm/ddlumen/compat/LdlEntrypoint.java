package dev.xcrafttm.ddlumen.compat;

// LambDynamicLights 2.x (before 1.20) has no light behavior API
//#if MC >= 1.20

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;

/** Entry point LambDynamicLights calls ("lambdynlights:initializer" in fabric.mod.json). */
public final class LdlEntrypoint implements DynamicLightsInitializer {
    @Override
    public void onInitializeDynamicLights(DynamicLightsContext context) {
        LdlCompat.bridge = new LdlBridge(context.dynamicLightBehaviorManager());
    }
}
//#endif
