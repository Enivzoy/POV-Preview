package me.enivzoy.leaputils.mixin.odin;

import me.enivzoy.leaputils.pov.OdinHuds;
import com.odtheking.odin.clickgui.settings.impl.HUDSetting;
import com.odtheking.odin.features.Module;
import kotlin.Pair;
import kotlin.jvm.functions.Function2;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Module.class, remap = false)
public class HudElementMixin {

    @ModifyVariable(method = "HUD", at = @At("HEAD"), argsOnly = true, remap = false)
    private Function2<GuiGraphicsExtractor, Boolean, Pair<Integer, Integer>> leaputils$wrap(Function2<GuiGraphicsExtractor, Boolean, Pair<Integer, Integer>> block) {
        return OdinHuds.wrap(block);
    }

    @Inject(method = "HUD", at = @At("RETURN"), remap = false)
    private void leaputils$made(CallbackInfoReturnable<HUDSetting> cir) {
        OdinHuds.made(cir.getReturnValue());
    }
}
