package dev.xcrafttm.ddlumen.mixin;

import com.dreamdisplays.api.media.model.FramePixelFormat;
import com.dreamdisplays.api.media.player.GpuTextureRef;
import com.dreamdisplays.platform.client.render.GpuFrameUploader;
import com.dreamdisplays.platform.client.render.GpuTextureHandle;
//#if MC >= 26.3
import com.mojang.renderpearl.api.textures.GpuTexture;
//#else
import com.mojang.blaze3d.textures.GpuTexture;
//#endif
import dev.xcrafttm.ddlumen.capture.FrameCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/** Samples each decoded frame on its way to the GPU (the buffer is only read, never moved). */
@Mixin(value = GpuFrameUploader.class, remap = false)
public abstract class GpuFrameUploaderMixin {
    @Inject(method = "uploadInterleaved", at = @At("HEAD"), require = 0)
    private void ddlumen$captureInterleaved(GpuTextureRef target, ByteBuffer src, FramePixelFormat format,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!(target instanceof GpuTextureHandle handle)) return;
        GpuTexture texture = handle.getTexture();
        FrameCapture.captureInterleaved(src, texture.getWidth(0), texture.getHeight(0), format.getBytesPerPixel());
    }

    @Inject(method = "uploadPlanar", at = @At("HEAD"), require = 0)
    private void ddlumen$capturePlanar(GpuTextureRef y, GpuTextureRef u, GpuTextureRef v, ByteBuffer src,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (!(y instanceof GpuTextureHandle handle)) return;
        GpuTexture texture = handle.getTexture();
        FrameCapture.capturePlanar(src, texture.getWidth(0), texture.getHeight(0));
    }
}
