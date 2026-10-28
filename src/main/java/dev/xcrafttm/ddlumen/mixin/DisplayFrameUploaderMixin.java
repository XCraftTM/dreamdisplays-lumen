package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.capture.FrameCapture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Remembers which display is uploading, so {@link GpuFrameUploaderMixin} knows whose frame it sees. */
@Pseudo
@Mixin(targets = "com.dreamdisplays.platform.client.displays.DisplayFrameUploader", remap = false)
public abstract class DisplayFrameUploaderMixin {
    @Shadow
    @Final
    private UUID uuid;

    @Inject(method = "upload", at = @At("HEAD"), require = 0)
    private void ddlumen$beginUpload(CallbackInfo ci) {
        FrameCapture.beginUpload(uuid);
    }

    @Inject(method = "upload", at = @At("RETURN"), require = 0)
    private void ddlumen$endUpload(CallbackInfo ci) {
        FrameCapture.endUpload();
    }
}
