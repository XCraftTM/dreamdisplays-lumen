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

    // older API versions also have a former entry method without the context (LambDynamicLights calls the one above)
    //#if MC >= 1.21 && MC < 1.21.11
    @Override
    public void onInitializeDynamicLights(dev.lambdaurora.lambdynlights.api.item.ItemLightSourceManager items) {
    }
    //#elif MC < 1.21
    @Override
    public void onInitializeDynamicLights() {
    }
    //#endif
}
//#endif
