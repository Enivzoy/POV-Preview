package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.PovCapture;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionUpdateTracker.class)
public class SectionUpdateTrackerMixin {

    @Inject(method = "repositionCamera(Lnet/minecraft/core/SectionPos;)V", at = @At("HEAD"), cancellable = true)
    private void cpKeepTrackerOnLocalCamera(SectionPos pos, CallbackInfo ci) {
        if (PovCapture.INSTANCE.getCapturing()) ci.cancel();
    }
}
