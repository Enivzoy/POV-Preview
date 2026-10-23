package me.enivzoy.leaputils.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraAccessor {

    @Accessor("eyeHeight")
    void cpSetEyeHeight(float eyeHeight);

    @Accessor("eyeHeightOld")
    void cpSetEyeHeightOld(float eyeHeightOld);

    @Accessor("fovModifier")
    void cpSetFovModifier(float fovModifier);

    @Accessor("oldFovModifier")
    void cpSetOldFovModifier(float oldFovModifier);
}
