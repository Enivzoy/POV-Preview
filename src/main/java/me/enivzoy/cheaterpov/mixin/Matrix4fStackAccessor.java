package me.enivzoy.cheaterpov.mixin;

import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Matrix4fStack.class)
public interface Matrix4fStackAccessor {

    @Accessor("curr")
    int cpCurr();
}
