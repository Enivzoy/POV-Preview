package me.enivzoy.leaputils.mixin;

import me.enivzoy.leaputils.pov.FastLeap;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    // the open packet is the earliest authoritative container id, before any screen work
    @Inject(method = "handleOpenScreen", at = @At("HEAD"))
    private void leaputils$openScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        FastLeap.INSTANCE.onOpenScreenPacket(packet.getContainerId(), packet.getTitle().getString());
    }

    // contents are applied by this method, so the click state id is real and render is later
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void leaputils$containerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        FastLeap.INSTANCE.onContainerContentPacket(packet.containerId());
    }
}