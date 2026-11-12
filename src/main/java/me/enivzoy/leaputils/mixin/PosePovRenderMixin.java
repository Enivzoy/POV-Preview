package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.PosePov;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class PosePovRenderMixin {
    @Inject(method = "renderLevel()V", at = @At("HEAD"), cancellable = true)
    private void leaputils$skipHiddenWorld(CallbackInfo ci) {
        if (!PosePov.INSTANCE.coversWorld()) return;
        PosePov.INSTANCE.renderQueued();
        ci.cancel();
    }

    @Inject(method = "renderLevel()V", at = @At("RETURN"))
    private void leaputils$renderPosePov(CallbackInfo ci) {
        PosePov.INSTANCE.renderQueued();
    }
}
