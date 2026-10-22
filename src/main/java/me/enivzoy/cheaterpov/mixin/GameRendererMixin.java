package me.enivzoy.cheaterpov.mixin;

import me.enivzoy.cheaterpov.pov.PovCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), cancellable = true)
    private void cpTakeOverLevelRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (PovCapture.INSTANCE.getCapturing()) return;
        if (PovCapture.INSTANCE.takeOver(deltaTracker)) ci.cancel();
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void cpHidePlayerOutline(CallbackInfoReturnable<Boolean> cir) {
        if (PovCapture.INSTANCE.getCapturing()) cir.setReturnValue(false);
    }
}
