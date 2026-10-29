package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.light.EntityLight;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.LivingEntity;
//#if MC >= 1.21.11
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//#if MC >= 26.1
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#else
import net.minecraft.client.renderer.state.CameraRenderState;
//#endif
//#endif

/**
 * Mobs and players in front of a display take on its light's color where they are (see {@link EntityLight}): while
 * one is drawn, its tint is set for everything it draws, its body and its layers alike (armor, cape, elytra), see
 * {@link ModelTintMixin}.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityTintMixin {
    //#if MC >= 1.21.11
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void ddlumen$rememberTint(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        EntityLight.rememberTint(state, entity);
    }

    //#if MC >= 26.1
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"))
    //#else
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At("HEAD"))
    //#endif
    private void ddlumen$beginTint(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                   CameraRenderState camera, CallbackInfo ci) {
        EntityLight.beginTint(EntityLight.tintOf(state));
    }

    //#if MC >= 26.1
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("RETURN"))
    //#else
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At("RETURN"))
    //#endif
    private void ddlumen$endTint(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                 CameraRenderState camera, CallbackInfo ci) {
        EntityLight.endTint();
    }
    //#else
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void ddlumen$beginTint(LivingEntity entity, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose,
                                   net.minecraft.client.renderer.MultiBufferSource buffers, int light, CallbackInfo ci) {
        EntityLight.beginTint(EntityLight.tint(entity));
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void ddlumen$endTint(LivingEntity entity, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose,
                                 net.minecraft.client.renderer.MultiBufferSource buffers, int light, CallbackInfo ci) {
        EntityLight.endTint();
    }
    //#endif
}
