package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.light.EntityLight;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//#if MC >= 1.21.11
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#else
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
//#endif

/**
 * Mobs and players in front of a display take on its light's color where they are (see {@link EntityLight}): through
 * the model tint from 1.21.11, through the color the model is drawn with before.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityTintMixin {
    //#if MC >= 1.21.11
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void ddlumen$rememberTint(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        EntityLight.rememberTint(state, entity);
    }

    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
    private void ddlumen$tint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        int tint = EntityLight.tintOf(state);
        if (tint != -1) cir.setReturnValue(EntityLight.multiply(cir.getReturnValueI(), tint));
    }
    //#elif MC >= 1.21
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void ddlumen$tint(EntityModel<?> model, PoseStack pose, VertexConsumer buffer, int light, int overlay, int color,
                              Operation<Void> original, @Local(argsOnly = true) LivingEntity entity) {
        original.call(model, pose, buffer, light, overlay, EntityLight.multiply(color, EntityLight.tint(entity)));
    }
    //#else
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"))
    private void ddlumen$tint(EntityModel<?> model, PoseStack pose, VertexConsumer buffer, int light, int overlay,
                              float r, float g, float b, float a, Operation<Void> original,
                              @Local(argsOnly = true) LivingEntity entity) {
        int tint = EntityLight.tint(entity);
        if (tint != -1) {
            r *= ((tint >> 16) & 0xFF) / 255f;
            g *= ((tint >> 8) & 0xFF) / 255f;
            b *= (tint & 0xFF) / 255f;
        }
        original.call(model, pose, buffer, light, overlay, r, g, b, a);
    }
    //#endif
}
