package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.light.EntityLight;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The first-person hand takes on the display light's color like the player's body (see {@link EntityTintMixin}). */
//#if MC >= 26.3
@Mixin(net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class)
//#else
@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
//#endif
public abstract class HandTintMixin {
    //#if MC >= 26.2
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    //#else
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    //#endif
    private void ddlumen$beginTint(CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player != null) EntityLight.beginTint(EntityLight.tint(player));
    }

    //#if MC >= 26.2
    @Inject(method = "submitHandsWithItems", at = @At("RETURN"))
    //#else
    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    //#endif
    private void ddlumen$endTint(CallbackInfo ci) {
        EntityLight.endTint();
    }
}
