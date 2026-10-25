package me.enivzoy.leaputils.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererInvoker {

    @Invoker("extractWindow")
    void cpExtractWindow();

    @Invoker("extractOptions")
    void cpExtractOptions();

    @Invoker("extractCamera")
    void cpExtractCamera(DeltaTracker deltaTracker, float worldPartialTicks, float cameraEntityPartialTicks);

    @Mutable
    @Accessor("mainRenderTarget")
    void cpSetMainRenderTarget(RenderTarget target);
}
