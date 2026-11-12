package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.Savepoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class LeapGuiGlowMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
    private void leaputils$drawGuiGlow(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Savepoint.INSTANCE.drawGuiGlow(gfx);
    }
}