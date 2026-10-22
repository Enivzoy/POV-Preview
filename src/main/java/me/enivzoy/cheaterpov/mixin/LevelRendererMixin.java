package me.enivzoy.cheaterpov.mixin;

import me.enivzoy.cheaterpov.pov.PovCapture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "repositionCamera(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void cpKeepViewArea(CameraRenderState camera, CallbackInfo ci) {
        if (PovCapture.INSTANCE.getCapturing()) ci.cancel();
    }

    @Inject(method = "scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void cpKeepTranslucentSort(Vec3 cameraPos, CallbackInfo ci) {
        if (PovCapture.INSTANCE.getCapturing()) ci.cancel();
    }
}
