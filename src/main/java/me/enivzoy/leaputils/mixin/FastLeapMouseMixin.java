package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.FastLeap;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class FastLeapMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void leaputils$fastLeapClick(long window, MouseButtonInfo info, int state, CallbackInfo ci) {
        if (info.button() != 0 || state != 1) return;
        if (!FastLeap.INSTANCE.getEnabled()) return;

        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null || mc.level == null) return;

        ItemStack held = mc.player.getMainHandItem();
        String id = com.odtheking.odin.utils.ItemUtilsKt.getItemId(held);
        if (id == null || id.isEmpty()) return;

        if (FastLeap.INSTANCE.onLeftClick(id)) ci.cancel();
    }
}
