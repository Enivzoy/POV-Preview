package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.PovCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void cpHideLocalPlayer(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (!PovCapture.INSTANCE.getCapturing()) return;
        if (entity == Minecraft.getInstance().player || entity == PovCapture.INSTANCE.getPreviewEntity()) {
            cir.setReturnValue(false);
        }
    }
}
