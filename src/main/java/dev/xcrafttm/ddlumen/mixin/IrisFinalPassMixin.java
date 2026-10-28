package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.render.PostShaderLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the display light onto the finished picture once Iris' shader pack is done with it. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisFinalPassMixin {
    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"), require = 0)
    private void ddlumen$afterFinalPass(CallbackInfo ci) {
        PostShaderLight.draw();
    }
}
