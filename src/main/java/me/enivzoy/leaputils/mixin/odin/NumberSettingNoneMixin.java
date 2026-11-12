package me.enivzoy.leaputils.mixin.odin;

import me.enivzoy.leaputils.pov.ShareFps;
import com.odtheking.odin.clickgui.settings.Setting;
import com.odtheking.odin.clickgui.settings.impl.NumberSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NumberSetting.class, remap = false)
public class NumberSettingNoneMixin {
    @Inject(method = "format", at = @At("RETURN"), cancellable = true, remap = false)
    private void leaputils$none(Number value, CallbackInfoReturnable<String> cir) {
        if (ShareFps.showsNone(((Setting<?>) (Object) this).getName(), value)) cir.setReturnValue("off");
    }
}
