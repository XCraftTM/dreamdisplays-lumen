package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.light.ScreenLights;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block changes near a display rebuild its lit surfaces. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void ddlumen$blockChanged(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
        if (old != current) ScreenLights.onBlockChanged(pos);
    }
}
