package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.light.EntityLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The tint of the living entity being drawn (see {@link EntityTintMixin}) on every model it draws: where models are
 * handed over for drawing from 1.21.11 (with their tint color), at the model parts' vertices before.
 */
//#if MC >= 1.21.11
@Mixin(net.minecraft.client.renderer.SubmitNodeCollection.class)
//#else
@Mixin(net.minecraft.client.model.geom.ModelPart.class)
//#endif
public abstract class ModelTintMixin {
    //#if MC >= 1.21.11
    // the ints are light, overlay, tint, outline
    @ModifyVariable(method = "submitModel", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int ddlumen$tintModel(int tint) {
        return EntityLight.applyTint(tint);
    }

    //#if MC < 26.2
    @ModifyVariable(method = "submitModelPart", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int ddlumen$tintModelPart(int tint) {
        return EntityLight.applyTint(tint);
    }
    //#endif
    //#elif MC >= 1.21
    // the ints are light, overlay, color
    @ModifyVariable(method = "compile", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int ddlumen$tint(int color) {
        return EntityLight.applyTint(color);
    }
    //#else
    @ModifyVariable(method = "compile", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float ddlumen$red(float red) {
        return red * EntityLight.tintChannel(16);
    }

    @ModifyVariable(method = "compile", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float ddlumen$green(float green) {
        return green * EntityLight.tintChannel(8);
    }

    @ModifyVariable(method = "compile", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private float ddlumen$blue(float blue) {
        return blue * EntityLight.tintChannel(0);
    }
    //#endif
}
