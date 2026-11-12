package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.PosePov;
import me.enivzoy.leaputils.pov.PoseShare;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Gui.class)
public class PosePovUnderGuiMixin {
    @WrapOperation(
        method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V",
        require = 0,
        at = @At(value = "NEW", target = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;II)Lnet/minecraft/client/gui/GuiGraphicsExtractor;")
    )
    private GuiGraphicsExtractor leaputils$posePovUnderGui(Minecraft mc, GuiRenderState state, int mouseX, int mouseY, Operation<GuiGraphicsExtractor> original) {
        GuiGraphicsExtractor gfx = original.call(mc, state, mouseX, mouseY);
        PoseShare.INSTANCE.frameSafely();
        PosePov.INSTANCE.drawUnderGui(gfx);
        return gfx;
    }
}
