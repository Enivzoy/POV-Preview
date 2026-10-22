package me.enivzoy.cheaterpov.pov

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.opengl.GlTexture
import net.minecraft.client.Camera
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.state.GameRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import com.llamalad7.mixinextras.injector.wrapoperation.Operation

/**
 * Multi-view renderer using single large scratch FB + 4 quadrant scissor/viewport.
 * Renders all 4 quadrants in a single FrameGraph execution to avoid Vulkan fence conflicts.
 */
class MultiViewRenderer {

    companion object {
        private val LOG = com.mojang.logging.LogUtils.getLogger()

        private var scratchFb: TextureTarget? = null
        private var scratchW = -1
        private var scratchH = -1
        private var active = false

        fun isActive() = active
        fun setActive(active: Boolean) { this.active = active }
        fun getScratchFb() = scratchFb
        fun getScratchW() = scratchW
        fun getScratchH() = scratchH

        fun ensureScratchFb(mc: Minecraft): TextureTarget {
            val w = mc.getWindow().width * 2
            val h = mc.getWindow().height * 2
            if (scratchFb != null && scratchW == w && scratchH == h) return scratchFb!!
            if (scratchFb != null) {
                try { scratchFb!!.destroyBuffers() } catch (t: Throwable) {}
                scratchFb = null
            }
            scratchFb = TextureTarget("pov-multi-scratch", w, h, true, GpuFormat.RGBA8_UNORM)
            scratchW = w
            scratchH = h
            return scratchFb!!
        }

        fun disposeScratch() {
            scratchFb?.let { it.destroyBuffers() ; scratchFb = null }
            scratchW = -1
            scratchH = -1
        }
    }

    data class QuadrantConfig(
        val index: Int,
        val camera: Camera,
        val marker: Marker,
        val pose: PovPose.Pose,
        val feed: RenderTarget,
        val rig: PovCapture.Rig,
        val partialTicks: Float,
    )

    fun renderAll(
        mc: Minecraft,
        gameRenderer: GameRenderer,
        fogRenderer: FogRenderer,
        state: GameRenderState,
        level: net.minecraft.client.multiplayer.ClientLevel,
        player: net.minecraft.client.player.LocalPlayer,
        deltaTracker: DeltaTracker,
        quadrants: List<QuadrantConfig>,
        partialTicks: Float,
        original: Operation<Void>,
        self: GameRenderer,
    ): Boolean {
        if (quadrants.isEmpty()) return false

        val scratch = ensureScratchFb(mc)
        val w = scratch.width
        val h = scratch.height

        // Pre-extract all views (no renderLevel yet)
        for (q in quadrants) {
            val marker = q.marker
            val rig = q.rig
            val pose = q.pose
            val camera = q.camera

            marker.setPos(pose.x, pose.y, pose.z)
            marker.setYRot(pose.yaw)
            marker.setXRot(pose.pitch)

            rig.access.cpSetEyeHeight(pose.eyeHeight)
            rig.access.cpSetEyeHeightOld(pose.eyeHeight)
            q.camera.setLevel(level)
            q.camera.setEntity(marker)
            q.camera.update(deltaTracker)

            mc.levelExtractor.extract(deltaTracker, camera, q.partialTicks)
        }

        var anySuccess = false

        for (q in quadrants) {
            val marker = q.marker
            val rig = q.rig
            val pose = q.pose
            val feed = q.feed
            val camera = q.camera
            val rigRef = q.rig

            marker.setPos(pose.x, pose.y, pose.z)
            marker.setYRot(pose.yaw)
            marker.setXRot(pose.pitch)

            rigRef.access.cpSetEyeHeight(pose.eyeHeight)
            rigRef.access.cpSetEyeHeightOld(pose.eyeHeight)
            camera.setLevel(level)
            camera.setEntity(marker)
            camera.update(deltaTracker)

            val cameraState = gameRenderer.gameRenderState().levelRenderState.cameraRenderState
            camera.extractRenderState(cameraState, camera.getCameraEntityPartialTicks(deltaTracker))
            val proj = cameraState.projectionMatrix
            if (proj.get(0, 0) == 0f || proj.get(1, 1) == 0f) continue
            neutralize(cameraState)
            cameraState.fogType = camera.getFluidInCamera()
            cameraState.fogData = fogRenderer.setupFog(
                camera,
                mc.options.effectiveRenderDistance,
                deltaTracker,
                gameRenderer.bossOverlayWorldDarkening(deltaTracker.getGameTimeDeltaPartialTick(false)),
                level,
            )

            val optionsState = gameRenderer.gameRenderState().optionsRenderState
            optionsState.bobView = false
            optionsState.screenEffectScale = 0f
            optionsState.damageTiltStrength = 0.0
            optionsState.cloudStatus = net.minecraft.client.CloudStatus.OFF
            optionsState.cloudRange = 0
            optionsState.improvedTransparency = false
            optionsState.cameraType = CameraType.FIRST_PERSON

            // Set camera position for this quadrant
            val markerPos = marker.position()
            val cameraStateRef = gameRenderer.gameRenderState().levelRenderState.cameraRenderState
            cameraStateRef.pos = markerPos

            // Set viewport/scissor for this quadrant on scratch FB
            val vpRect = ViewportMath.quadRect(q.index, scratch.width, scratch.height)
            setViewportAndScissor(vpRect.x, vpRect.y, vpRect.w, vpRect.h)

            // Activate scratch FB as main render target via MinecraftClientMixin
            MultiViewRenderer.active = true
            try {
                original.call(self, deltaTracker)
            } finally {
                MultiViewRenderer.active = false
            }

            // Blit scratch FB quadrant to feed texture
            blitScratchToFeed(scratch, q.feed, q.index, w, h)
            anySuccess = true
        }

        // Reset viewport/scissor
        GlStateManager._viewport(0, 0, scratch.width, scratch.height)
        GlStateManager._disableScissorTest()
        RenderSystem.disableScissorForRenderTypeDraws()

        return anySuccess
    }

    private fun usable(cameraState: CameraRenderState): Boolean {
        val proj = cameraState.projectionMatrix
        return proj.get(0, 0) != 0f && proj.get(1, 1) != 0f
    }

    private fun neutralize(cameraState: CameraRenderState) {
        // No-op: CameraRenderState in 26.2 doesn't have bobView, effectScale, etc.
    }

    private fun setViewportAndScissor(x: Int, y: Int, w: Int, h: Int) {
        GlStateManager._viewport(x, y, w, h)
        GlStateManager._enableScissorTest()
        GlStateManager._scissorBox(x, y, w, h)
    }

    private fun blitScratchToFeed(scratch: TextureTarget, feed: RenderTarget, index: Int, scratchW: Int, scratchH: Int) {
        val srcTex = (scratch.getColorTexture() as? GlTexture)?.glId() ?: return
        val dstTex = (feed.getColorTexture() as? GlTexture)?.glId() ?: return

        val prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        if (wasScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST)

        val readFbo = GL30.glGenFramebuffers()
        val drawFbo = GL30.glGenFramebuffers()
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo)
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, srcTex, 0)
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0)

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo)
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, dstTex, 0)
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0)

            val rs = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)
            val ds = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)
            if (rs != GL30.GL_FRAMEBUFFER_COMPLETE || ds != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return
            }

            val vpRect = ViewportMath.quadRect(index, scratchW, scratchH)
            GL30.glBlitFramebuffer(
                0, 0, scratchW, scratchH,
                vpRect.x, vpRect.y, vpRect.x + vpRect.w, vpRect.y + vpRect.h,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR
            )
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead)
            GL30.glDeleteFramebuffers(readFbo)
            GL30.glDeleteFramebuffers(drawFbo)
            if (wasScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST)
        }
    }
}

object ViewportMath {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    fun quadRect(index: Int, fbW: Int, fbH: Int): Rect {
        val halfW = fbW / 2
        val halfH = fbH / 2
        val col = index % 2
        val row = index / 2
        return Rect(col * halfW, row * halfH, halfW, halfH)
    }
}