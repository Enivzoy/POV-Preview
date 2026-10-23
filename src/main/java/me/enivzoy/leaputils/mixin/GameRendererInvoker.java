package me.enivzoy.leaputils.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
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

    @Mutable
    @Accessor("mainRenderTarget")
    void cpSetMainRenderTarget(RenderTarget target);

    @Accessor("fogRenderer")
    FogRenderer cpFogRenderer();
}
