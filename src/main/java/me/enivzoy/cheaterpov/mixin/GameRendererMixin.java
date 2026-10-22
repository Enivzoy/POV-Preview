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

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
    private void cpAfterLevelRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        PovCapture.INSTANCE.afterLevelRender(deltaTracker);
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void cpHidePlayerOutline(CallbackInfoReturnable<Boolean> cir) {
        if (PovCapture.INSTANCE.getCapturing()) cir.setReturnValue(false);
    }
}
