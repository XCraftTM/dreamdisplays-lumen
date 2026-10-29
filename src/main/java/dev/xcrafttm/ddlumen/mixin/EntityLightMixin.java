package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.light.EntityLight;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities in front of a display are as bright as its light where they are (see {@link EntityLight}). */
@Mixin(EntityRenderer.class)
public abstract class EntityLightMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private void ddlumen$displayLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int display = EntityLight.level(entity);
        if (display > cir.getReturnValueI()) cir.setReturnValue(display);
    }
}
