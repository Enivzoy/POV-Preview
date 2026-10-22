package me.enivzoy.cheaterpov.pov

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import me.enivzoy.cheaterpov.mixin.CameraAccessor
import me.enivzoy.cheaterpov.mixin.GameRendererInvoker
import me.enivzoy.cheaterpov.mixin.GuiGraphicsExtractorAccessor
import me.enivzoy.cheaterpov.mixin.Matrix4fStackAccessor
import net.minecraft.client.Camera
import net.minecraft.client.CameraType
import net.minecraft.client.CloudStatus
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.state.GameRenderState
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import net.minecraft.world.entity.player.Player
import org.joml.Matrix3x2f
import org.joml.Vector4f

object PovCapture {

    private const val FEEDS = 4

    private val feeds = arrayOfNulls<RenderTarget>(FEEDS)

    private val empty = BooleanArray(FEEDS) { true }

    private val unavailable = BooleanArray(FEEDS)

    private var feedWidth = 0
    private var feedHeight = 0

    private var freeRequested = false

    private var nextFeed = 0

    private var warmup = 0

    private var blitsSubmitted = 0

    private var captureDepth = 0
    val capturing: Boolean get() = captureDepth > 0

    @Volatile
    var previewEntity: Entity? = null
        private set

    private val status = arrayOfNulls<String>(FEEDS)

    private var lastReportAt = 0L

    var disabledForSession = false
        private set

    private var lastCostMs = 0.0

    private var camera: Camera? = null
    private var proxy: Marker? = null
    private var proxyLevel: ClientLevel? = null

    private val OUT_OF_RANGE = Vector4f(0x14 / 255f, 0x14 / 255f, 0x14 / 255f, 1f)

    fun requestFree() {
        freeRequested = true
    }

    fun onWorldChange() {
        freeRequested = true
        nextFeed = 0
        proxy = null
        proxyLevel = null
    }

    fun releaseIfUnwanted() {
        if (!PovPreviews.wants()) freeFeedsNow()
    }

    fun costLine(): String? {
        if (!PovPreviews.showCost || !PovPreviews.wants()) return null
        val live = (0 until FEEDS).count { !empty[it] && !unavailable[it] }
        return "§7POV §f${"%.1f".format(lastCostMs)}§7ms  §f$live§7/4"
    }

    fun onScreenExtract(gfx: GuiGraphicsExtractor) {
        blitsSubmitted = 0
        if (disabledForSession) {
            freeFeedsNow()
            return
        }
        val mc = Minecraft.getInstance()
        if (!PovPreviews.wants() || mc.level == null || mc.player == null) {
            freeFeedsNow()
            return
        }
        val main = mc.gameRenderer.mainRenderTarget()
        if (main.width < 32 || main.height < 32) {
            freeFeedsNow()
            return
        }
        val scale = PovPreviews.resolution
        val width = ((main.width / 2) * scale).toInt()
        val height = ((main.height / 2) * scale).toInt()
        if (width < 16 || height < 16) return

        ensureFeeds(width, height)
        refreshSlots()
        submitBlits(gfx)
    }

    private fun refreshSlots() {
        val teammates = DungeonUtils.leapTeammates ?: emptyList()
        for (index in 0 until FEEDS) {
            val slot = teammates.getOrNull(index)
            val isEmpty = slot == null || slot.name == "Empty"
            if (isEmpty && !empty[index]) {
                feeds[index]?.let { clear(it) }
                unavailable[index] = true
            }
            empty[index] = isEmpty
            if (isEmpty) status[index] = "empty"
        }
    }

    private fun submitBlits(gfx: GuiGraphicsExtractor) {
        val guiWidth = gfx.guiWidth()
        val guiHeight = gfx.guiHeight()
        if (guiWidth <= 0 || guiHeight <= 0) return
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val pose = Matrix3x2f(gfx.pose())
        val opacity = PovPreviews.opacity
        val opaque = opacity >= 1f
        val pipeline = if (opaque) RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND else RenderPipelines.GUI_TEXTURED
        val tint = if (opaque) -1 else ((opacity * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        val renderState = (gfx as GuiGraphicsExtractorAccessor).cpGuiRenderState()
        for (index in 0 until FEEDS) {
            val feed = feeds[index] ?: continue
            if (feed.width != feedWidth || feed.height != feedHeight) continue
            val view = feed.getColorTextureView() ?: continue
            val column = index % 2
            val row = index / 2
            val splitX = guiWidth / 2
            val splitY = guiHeight / 2
            val x0 = if (column == 0) 0 else splitX
            val x1 = if (column == 0) splitX else guiWidth
            val y0 = if (row == 0) 0 else splitY
            val y1 = if (row == 0) splitY else guiHeight
            renderState.addGuiElement(
                BlitRenderState(
                    pipeline,
                    TextureSetup.singleTexture(view, sampler),
                    pose,
                    x0, y0, x1, y1,
                    0f, 1f, 1f, 0f,
                    tint,
                    null,
                )
            )
            blitsSubmitted++
        }
    }

    fun takeOver(deltaTracker: DeltaTracker): Boolean {
        if (capturing || disabledForSession) return false
        if (blitsSubmitted == 0 || !PovPreviews.wants()) return false
        blitsSubmitted = 0
        return runFrame(deltaTracker)
    }

    private fun runFrame(deltaTracker: DeltaTracker): Boolean {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        val gameRenderer = mc.gameRenderer
        val invoker = gameRenderer as GameRendererInvoker
        val fog = invoker.cpFogRenderer()
        val state = gameRenderer.gameRenderState()
        val gui = state.guiRenderState
        val options = mc.options

        val saved = Saved(gameRenderer.mainRenderTarget(), gui.isHudHidden, options.cameraType)
        val modelViewDepth = modelViewDepth()
        val started = System.nanoTime()

        captureDepth++
        try {
            options.cameraType = CameraType.FIRST_PERSON
            gui.isHudHidden = true

            val cameraRig = rig(level)
            val perFrame = if (warmup > 0) FEEDS else PovPreviews.previewsPerFrame.coerceIn(1, FEEDS)
            var rendered = 0
            var attempts = 0
            while (rendered < perFrame && attempts < FEEDS) {
                val index = nextFeed
                nextFeed = (nextFeed + 1) % FEEDS
                attempts++
                if (empty[index]) continue
                val feed = feeds[index] ?: continue
                if (renderFeed(index, feed, cameraRig, deltaTracker, mc, gameRenderer, invoker, fog, state, level, player)) rendered++
            }
            if (warmup > 0) warmup--
            return rendered > 0
        } catch (t: Throwable) {
            runCatching { unwindVanillaState(modelViewDepth) }
            fail(t)
            return false
        } finally {
            captureDepth--
            runCatching { invoker.cpSetMainRenderTarget(saved.target) }
            options.cameraType = saved.cameraType
            gui.isHudHidden = saved.hudHidden
            runCatching { restoreRealView(deltaTracker, mc, invoker, fog) }
            lastCostMs = (System.nanoTime() - started) / 1_000_000.0
            runCatching { report(DungeonUtils.leapTeammates ?: emptyList()) }
        }
    }

    private fun report(teammates: List<DungeonPlayer>) {
        val broken = (0 until FEEDS).any { !empty[it] && status[it] != "ok" }
        if (!broken && teammates.size >= FEEDS) return
        val now = System.nanoTime()
        if (now - lastReportAt < 1_000_000_000L) return
        lastReportAt = now
        val slots = (0 until FEEDS).joinToString(" | ") { index ->
            val name = teammates.getOrNull(index)?.name ?: "-"
            "$index:$name=${status[index] ?: "pending"}"
        }
        println("[CheaterPov] team=${teammates.size} $slots")
    }

    private class Rig(val camera: Camera, val access: CameraAccessor, val marker: Marker)

    private fun rig(level: ClientLevel): Rig {
        var cam = camera
        if (cam == null) {
            cam = Camera()
            val access = cam as CameraAccessor
            access.cpSetFovModifier(1f)
            access.cpSetOldFovModifier(1f)
            camera = cam
        }
        var marker = proxy
        if (marker == null || proxyLevel !== level) {
            marker = Marker(EntityTypes.MARKER, level)
            proxy = marker
            proxyLevel = level
        }
        return Rig(cam, cam as CameraAccessor, marker)
    }

    private fun renderFeed(
        index: Int,
        feed: RenderTarget,
        rig: Rig,
        deltaTracker: DeltaTracker,
        mc: Minecraft,
        gameRenderer: GameRenderer,
        invoker: GameRendererInvoker,
        fog: FogRenderer,
        state: GameRenderState,
        level: ClientLevel,
        player: LocalPlayer,
    ): Boolean {
        val slot = DungeonUtils.leapTeammates.getOrNull(index) ?: run {
            status[index] = "no-slot"
            return false
        }
        val target = resolve(slot.entity, slot.name) ?: run {
            status[index] = "no-entity"
            markUnavailable(index, feed)
            return false
        }

        val partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true)
        val result = PovPose.of(target, PovPreviews.mode, partialTicks, player.position(), PovPreviews.maxDistance.toDouble())
        val pose = when (result) {
            is PovPose.Result.Rejected -> {
                status[index] = "pose: ${result.reason}"
                markUnavailable(index, feed)
                return false
            }

            is PovPose.Result.Ok -> result.pose
        }
        if (!PovPose.fitsWorld(pose, level)) {
            status[index] = "outside level"
            markUnavailable(index, feed)
            return false
        }

        val marker = rig.marker
        marker.setPos(pose.x, pose.y, pose.z)
        marker.xo = pose.x
        marker.yo = pose.y
        marker.zo = pose.z
        marker.setYRot(pose.yaw)
        marker.yRotO = pose.yaw
        marker.setXRot(pose.pitch)
        marker.xRotO = pose.pitch

        val camera = rig.camera
        rig.access.cpSetEyeHeight(pose.eyeHeight)
        rig.access.cpSetEyeHeightOld(pose.eyeHeight)
        camera.setLevel(level)
        camera.setEntity(marker)
        camera.update(deltaTracker)

        val cameraState = state.levelRenderState.cameraRenderState
        camera.extractRenderState(cameraState, camera.getCameraEntityPartialTicks(deltaTracker))
        if (!usable(cameraState)) {
            markUnavailable(index, feed)
            return false
        }
        neutralize(cameraState)
        cameraState.fogType = camera.getFluidInCamera()
        cameraState.fogData = fog.setupFog(
            camera,
            mc.options.effectiveRenderDistance,
            deltaTracker,
            gameRenderer.bossOverlayWorldDarkening(deltaTracker.getGameTimeDeltaPartialTick(false)),
            level,
        )

        val optionsState = state.optionsRenderState
        optionsState.bobView = false
        optionsState.screenEffectScale = 0f
        optionsState.damageTiltStrength = 0.0
        optionsState.cloudStatus = CloudStatus.OFF
        optionsState.cloudRange = 0
        optionsState.improvedTransparency = false
        optionsState.cameraType = CameraType.FIRST_PERSON

        invoker.cpSetMainRenderTarget(feed)
        previewEntity = target
        try {
            mc.levelExtractor.extract(deltaTracker, camera, deltaTracker.getGameTimeDeltaPartialTick(false))
            gameRenderer.renderLevel(deltaTracker)
        } finally {
            previewEntity = null
        }

        status[index] = "ok"
        unavailable[index] = false
        return true
    }

    private fun restoreRealView(
        deltaTracker: DeltaTracker,
        mc: Minecraft,
        invoker: GameRendererInvoker,
        fog: FogRenderer,
    ) {
        val level = mc.level ?: return
        if (mc.player == null) return
        val gameRenderer = mc.gameRenderer
        val camera = gameRenderer.mainCamera()
        camera.update(deltaTracker)
        val state = gameRenderer.gameRenderState()
        val cameraState = state.levelRenderState.cameraRenderState
        camera.extractRenderState(cameraState, camera.getCameraEntityPartialTicks(deltaTracker))
        cameraState.fogType = camera.getFluidInCamera()
        cameraState.fogData = fog.setupFog(
            camera,
            mc.options.effectiveRenderDistance,
            deltaTracker,
            gameRenderer.bossOverlayWorldDarkening(deltaTracker.getGameTimeDeltaPartialTick(false)),
            level,
        )
        invoker.cpExtractWindow()
        invoker.cpExtractOptions()
        mc.levelExtractor.extract(deltaTracker, camera, deltaTracker.getGameTimeDeltaPartialTick(false))
    }

    private fun markUnavailable(index: Int, feed: RenderTarget) {
        if (!unavailable[index]) {
            unavailable[index] = true
            clear(feed)
        }
    }

    private class Saved(val target: RenderTarget, val hudHidden: Boolean, val cameraType: CameraType)

    private fun resolve(entity: Player?, name: String): Player? {
        val mc = Minecraft.getInstance()
        val local = mc.player ?: return null
        val level = mc.level
        return sequence {
            if (entity != null) yield(entity)
            yieldAll(findByName(name))
        }.firstOrNull {
            it !== local && !it.isRemoved && it.isAlive && (level == null || it.level() === level)
        }
    }

    private fun findByName(name: String): List<Player> =
        Minecraft.getInstance().level?.players()?.filter {
            it.name.string.equals(name, ignoreCase = true) ||
                it.gameProfile.name.equals(name, ignoreCase = true) ||
                it.scoreboardName.equals(name, ignoreCase = true)
        } ?: emptyList()

    private fun ensureFeeds(width: Int, height: Int) {
        if (freeRequested) {
            freeFeedsNow()
        } else if (feedWidth == width && feedHeight == height && feeds.none { it == null }) {
            return
        }
        freeFeedsNow()
        val usable = width > 0 && height > 0
        if (!usable) return
        feedWidth = width
        feedHeight = height
        for (index in 0 until FEEDS) {
            val feed = TextureTarget("cheaterpov:pov$index", width, height, true, GpuFormat.RGBA8_UNORM)
            clear(feed)
            feeds[index] = feed
        }
        unavailable.fill(false)
        nextFeed = 0
        warmup = 1
    }

    private fun freeFeedsNow() {
        freeRequested = false
        warmup = 0
        if (feeds.all { it == null }) {
            feedWidth = 0
            feedHeight = 0
            return
        }
        for (index in 0 until FEEDS) {
            feeds[index]?.destroyBuffers()
            feeds[index] = null
        }
        feedWidth = 0
        feedHeight = 0
        unavailable.fill(false)
        nextFeed = 0
    }

    private fun clear(feed: RenderTarget) {
        feed.getColorTexture()?.let { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(it, OUT_OF_RANGE) }
    }

    private fun neutralize(state: CameraRenderState) {
        val entity = state.entityRenderState ?: return
        entity.hurtTime = 0f
        entity.hurtDuration = 0
        entity.deathTime = 0f
        entity.hurtDir = 0f
        entity.bob = 0f
        entity.backwardsInterpolatedWalkDistance = 0f
        entity.isSleeping = false
        entity.isDeadOrDying = false
        entity.doesMobEffectBlockSky = false
        entity.isLiving = true
        entity.isPlayer = true
    }

    private fun usable(state: CameraRenderState): Boolean {
        val pos = state.pos
        if (!pos.x.isFinite() || !pos.y.isFinite() || !pos.z.isFinite()) return false
        val matrix = state.projectionMatrix
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                if (!matrix.get(row, column).isFinite()) return false
            }
        }
        val view = state.viewRotationMatrix
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                if (!view.get(row, column).isFinite()) return false
            }
        }
        return true
    }

    private fun unwindVanillaState(modelViewDepth: Int) {
        runCatching { popModelViewTo(modelViewDepth) }
        runCatching { releasePreparedFrame() }
    }

    private fun popModelViewTo(depth: Int) {
        if (depth < 0) return
        val stack = RenderSystem.getModelViewStack()
        var current = modelViewDepth()
        while (current > depth) {
            stack.popMatrix()
            current--
        }
    }

    private fun modelViewDepth(): Int = try {
        (RenderSystem.getModelViewStack() as Matrix4fStackAccessor).cpCurr()
    } catch (_: Throwable) {
        -1
    }

    private fun releasePreparedFrame() {
        val dispatcher = Minecraft.getInstance().gameRenderer.featureRenderDispatcher()
        val frame = dispatcher.preparedFrame ?: return
        if (frame.context == null) return
        try {
            frame.close()
        } catch (_: Throwable) {
            frame.context = null
            frame.submitNodeStorage = null
        }
    }

    private fun fail(t: Throwable) {
        if (PovPreviews.disableOnError) {
            disabledForSession = true
            freeRequested = true
        }
        println("[CheaterPov] POV previews hit an error${if (PovPreviews.disableOnError) " and are off for this session" else ""}: $t")
        t.printStackTrace()
    }
}
