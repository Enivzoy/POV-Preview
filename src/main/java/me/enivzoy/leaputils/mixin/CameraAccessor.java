package me.enivzoy.leaputils.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraAccessor {

    @Accessor("eyeHeight")
    float cpGetEyeHeight();

    @Accessor("eyeHeight")
    void cpSetEyeHeight(float eyeHeight);

    @Accessor("eyeHeightOld")
    float cpGetEyeHeightOld();

    @Accessor("eyeHeightOld")
    void cpSetEyeHeightOld(float eyeHeightOld);

    @Accessor("capturedFrustum")
    Frustum cpGetCapturedFrustum();

    @Accessor("capturedFrustum")
    void cpSetCapturedFrustum(Frustum frustum);
}
