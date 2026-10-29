package me.enivzoy.leaputils.pov

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import me.enivzoy.leaputils.mixin.CameraAccessor
import me.enivzoy.leaputils.mixin.GameRendererInvoker
import me.enivzoy.leaputils.mixin.GuiGraphicsExtractorAccessor
import me.enivzoy.leaputils.mixin.Matrix4fStackAccessor
import net.minecraft.client.CameraType
import net.minecraft.client.CloudStatus
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.joml.Matrix3x2f
import org.joml.Vector4f

object PovCapture {

    private const val FEEDS = 4
    private const val STANDING_EYE = 1.62f
    private const val DUNGEON_FLOOR_Y = 69
    private const val FLOOR_RANGE = 2
    private const val RANGE_GRACE_NANOS = 2_000_000_000L

    private val feeds = arrayOfNulls<RenderTarget>(FEEDS)
    private val empty = BooleanArray(FEEDS) { true }
    private val out = BooleanArray(FEEDS)
    private val status = arrayOfNulls<String>(FEEDS)

    private var feedWidth = 0
    private var feedHeight = 0
    private var freeRequested = false
    private var nextFeed = 0
    private var blitsSubmitted = 0
    private var skippedMainPass = false
    private var carried: Carried? = null
    private var captureDepth = 0

    val capturing: Boolean get() = captureDepth > 0

    @Volatile
    var disabledForSession = false
        private set

    private var lastCostMs = 0.0
    private var lastReportAt = 0L
    private var lastReportSignature: String? = null

    private var standIn: Marker? = null
    private val seenAs = HashMap<String, java.util.UUID>()

    private val OUT_OF_RANGE = Vector4f(0x14 / 255f, 0x14 / 255f, 0x14 / 255f, 1f)

    private val isVulkan by lazy { RenderSystem.getBackendDescription().contains("Vulkan", ignoreCase = true) }

    fun requestFree() {
        freeRequested = true
    }

    fun onWorldChange() {
        freeRequested = true
        standIn = null
        seenAs.clear()
        PovPose.reset()
    }

    fun releaseIfUnwanted() {
        if (!PovPreviews.wants()) freeFeedsNow()
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
        if (!mc.window.isFocused || mc.window.isMinimized || mc.window.isIconified) {
            return
        }
        val main = mc.gameRenderer.mainRenderTarget()
        if (main.width < 32 || main.height < 32) {
            return
        }
        val scale = PovPreviews.resolution
        val width = ((main.width / 2) * scale).toInt()
        val height = ((main.height / 2) * scale).toInt()
        if (width < 16 || height < 16) return

        ensureFeeds(width, height)
        refreshSlots()
        runCatching { report(DungeonUtils.leapTeammates) }
        submitBlits(gfx)
        drawCost(gfx)
    }

    private fun refreshSlots() {
        val teammates = DungeonUtils.leapTeammates
        for (index in 0 until FEEDS) {
            val slot = teammates.getOrNull(index)
            val isEmpty = slot == null || slot.name == "Empty"
            if (isEmpty && !empty[index]) {
                feeds[index]?.let { clear(it) }
                out[index] = true
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
            if (empty[index]) continue
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

    private fun drawCost(gfx: GuiGraphicsExtractor) {
        val line = costLine() ?: return
        gfx.text(line, 4, 4, Colors.WHITE)
    }

    fun beforeLevelRender(deltaTracker: DeltaTracker): Boolean {
        if (capturing || disabledForSession) return false
        skippedMainPass = false
        if (!PovPreviews.skipOwnView || PovPreviews.opacity < 1f || !canCapture()) return false
        if (blitsSubmitted < FEEDS) return false
        carried = Carried(Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState)
        val ran = runPasses(deltaTracker)
        if (carried != null) {
            carried = null
            return false
        }
        if (ran == 0) return false

        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val color = main.colorTexture
        val depth = main.depthTexture
        if (color != null && depth != null) {
            RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(color, Vector4f(0f, 0f, 0f, 1f), depth, 0.0)
        }
        blitsSubmitted = 0
        skippedMainPass = true
        return true
    }

    private fun canCapture(): Boolean {
        val mc = Minecraft.getInstance()
        val window = mc.window
        return PovPreviews.wants() && mc.level != null && mc.player != null && feeds[0] != null &&
            window.isFocused && !window.isMinimized && !window.isIconified
    }

    fun afterLevelRender(deltaTracker: DeltaTracker) {
        if (capturing || disabledForSession) return
        if (skippedMainPass) {
            skippedMainPass = false
            return
        }
        if (blitsSubmitted == 0 || !canCapture()) return
        blitsSubmitted = 0
        runPasses(deltaTracker)
    }

    private fun runPasses(deltaTracker: DeltaTracker): Int {
        val started = System.nanoTime()
        var ran = 0
        captureDepth++
        try {
            val perFrame = if (isVulkan) 1 else PovPreviews.previewsPerFrame.coerceIn(1, 2)
            var attempts = 0
            while (ran < perFrame && attempts < FEEDS) {
                val index = nextFeed
                nextFeed = (nextFeed + 1) % FEEDS
                attempts++
                if (empty[index]) continue
                val feed = feeds[index] ?: continue
                if (renderFeed(index, feed, deltaTracker)) ran++
            }
            if (ran > 0) restoreMainCamera(deltaTracker)
            EntityCullingBridge.requestRecull()
        } catch (t: Throwable) {
            fail(t)
            runCatching { restoreMainCamera(deltaTracker) }
        } finally {
            captureDepth--
            lastCostMs = (System.nanoTime() - started) / 1_000_000.0
            runCatching { report(DungeonUtils.leapTeammates) }
        }
        return ran
    }

    private fun renderFeed(index: Int, feed: RenderTarget, deltaTracker: DeltaTracker): Boolean {
        val mc = Minecraft.getInstance()
        val gameRenderer = mc.gameRenderer
        val camera = gameRenderer.mainCamera()
        val cameraAccess = camera as CameraAccessor
        val invoker = gameRenderer as GameRendererInvoker
        val window = mc.window
        val options = mc.options
        val gui = gameRenderer.gameRenderState().guiRenderState
        val slot = DungeonUtils.leapTeammates.getOrNull(index) ?: run {
            status[index] = "no-slot"
            return false
        }

        val loaded: Player? = (slot.entity?.takeIf { !it.isRemoved } ?: findByName(slot.name).firstOrNull())?.takeIf { !it.isRemoved }
        if (loaded != null) seenAs[slot.name] = loaded.uuid
        var how = "ok"
        val target: Entity? = when {
            loaded == null -> (recentStandIn(slot)?.also { how = "last" }) ?: (mapStandIn(slot)?.also { how = "map" })
            loaded === mc.player || !loaded.isAlive -> null
            !PovPose.isSane(loaded) -> (recentStandIn(slot)?.also { how = "last" }) ?: (mapStandIn(slot)?.also { how = "map" })
            else -> loaded
        }
        if (target == null) {
            status[index] = "out"
            markOut(index, feed)
            return false
        }
        out[index] = false

        val saved = Saved(
            cameraEntity = mc.cameraEntity,
            windowWidth = window.width,
            windowHeight = window.height,
            cameraType = options.cameraType,
            hudHidden = gui.isHudHidden,
            capturedFrustum = cameraAccess.cpGetCapturedFrustum(),
            eyeHeight = cameraAccess.cpGetEyeHeight(),
            eyeHeightOld = cameraAccess.cpGetEyeHeightOld(),
            target = gameRenderer.mainRenderTarget(),
        )
        var pose: PovPose.Restore? = null
        var cullingWas: Boolean? = null
        var renderStarted = false
        var modelViewDepth = -1

        try {
            val worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false)
            if (target is Player) pose = PovPose.begin(target, PovPreviews.mode)
            val eyeHeight = if (target is Player) target.eyeHeight else STANDING_EYE

            mc.setCameraEntity(target)
            options.cameraType = CameraType.FIRST_PERSON
            gui.isHudHidden = true
            window.setWidth(feed.width)
            window.setHeight(feed.height)
            cameraAccess.cpSetEyeHeight(eyeHeight)
            cameraAccess.cpSetEyeHeightOld(eyeHeight)
            invoker.cpSetMainRenderTarget(feed)

            camera.update(deltaTracker)
            if (!(PovPreviews.recullTerrain && SodiumBridge.available)) {
                cameraAccess.cpSetCapturedFrustum(camera.cullFrustum)
            }
            cullingWas = EntityCullingBridge.disable()

            invoker.cpExtractWindow()
            invoker.cpExtractOptions()
            invoker.cpExtractCamera(deltaTracker, worldPartialTicks, camera.getCameraEntityPartialTicks(deltaTracker))

            val optionsState = gameRenderer.gameRenderState().optionsRenderState
            optionsState.bobView = false
            optionsState.screenEffectScale = 0f
            optionsState.damageTiltStrength = 0.0
            optionsState.cloudStatus = CloudStatus.OFF
            optionsState.cloudRange = 0
            optionsState.improvedTransparency = false

            mc.levelExtractor.extract(deltaTracker, camera, worldPartialTicks)
            carried?.into(gameRenderer.gameRenderState().levelRenderState)
            carried = null

            modelViewDepth = modelViewDepth()
            if (window.width != feed.width || window.height != feed.height ||
                gameRenderer.mainRenderTarget() !== feed) {
                status[index] = "size"
                return false
            }
            renderStarted = true
            gameRenderer.renderLevel(deltaTracker)
            status[index] = how
            return true
        } catch (t: Throwable) {
            status[index] = "err"
            throw t
        } finally {
            if (renderStarted) runCatching { unwindLevelRender(modelViewDepth) }
            invoker.cpSetMainRenderTarget(saved.target)
            window.setWidth(saved.windowWidth)
            window.setHeight(saved.windowHeight)
            options.cameraType = saved.cameraType
            gui.isHudHidden = saved.hudHidden
            cameraAccess.cpSetCapturedFrustum(saved.capturedFrustum)
            cameraAccess.cpSetEyeHeight(saved.eyeHeight)
            cameraAccess.cpSetEyeHeightOld(saved.eyeHeightOld)
            mc.setCameraEntity(saved.cameraEntity)
            pose?.restore()
            EntityCullingBridge.restore(cullingWas)
        }
    }

    private fun restoreMainCamera(deltaTracker: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        val gameRenderer = mc.gameRenderer
        val camera = gameRenderer.mainCamera()
        val invoker = gameRenderer as GameRendererInvoker
        camera.update(deltaTracker)
        val worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false)
        invoker.cpExtractWindow()
        invoker.cpExtractOptions()
        invoker.cpExtractCamera(deltaTracker, worldPartialTicks, camera.getCameraEntityPartialTicks(deltaTracker))
    }

    private fun markOut(index: Int, feed: RenderTarget) {
        if (!out[index]) {
            out[index] = true
            clear(feed)
        }
    }

    private class Saved(
        val cameraEntity: Entity?,
        val windowWidth: Int,
        val windowHeight: Int,
        val cameraType: CameraType,
        val hudHidden: Boolean,
        val capturedFrustum: Frustum?,
        val eyeHeight: Float,
        val eyeHeightOld: Float,
        val target: RenderTarget,
    )

    private class Carried(state: LevelRenderState) {
        private val sectionUpdates = ArrayList(state.sectionUpdateRenderStates)
        private val loading = state.chunkLoadingRenderState.let {
            listOf(
                it.addedEmptySections,
                it.removedEmptySections,
                it.addedLoadedChunks,
                it.removedLoadedChunks,
                it.loadedExpectedChunks,
            ).map(::LongOpenHashSet)
        }
        private val resetChunkLayerSampler = state.shouldResetChunkLayerSampler
        private val resetSkyRenderer = state.shouldResetSkyRenderer

        fun into(state: LevelRenderState) {
            state.sectionUpdateRenderStates.addAll(0, sectionUpdates)
            state.chunkLoadingRenderState.let {
                listOf(
                    it.addedEmptySections,
                    it.removedEmptySections,
                    it.addedLoadedChunks,
                    it.removedLoadedChunks,
                    it.loadedExpectedChunks,
                )
            }.forEachIndexed { index, set -> set.addAll(loading[index]) }
            state.shouldResetChunkLayerSampler = state.shouldResetChunkLayerSampler || resetChunkLayerSampler
            state.shouldResetSkyRenderer = state.shouldResetSkyRenderer || resetSkyRenderer
        }
    }

    private fun recentStandIn(p: DungeonPlayer): Entity? {
        if (p.isDead) return null
        val sample = PovPose.lastSeen(seenAs[p.name] ?: return null) ?: return null
        if (System.nanoTime() - sample.nanos > RANGE_GRACE_NANOS) return null
        val pose = sample.pose
        return standIn(pose.x, pose.y, pose.z, pose.headYaw, pose.pitch)
    }

    private fun standIn(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Entity? {
        val level = Minecraft.getInstance().level ?: return null
        val entity = standIn?.takeIf { it.level() === level } ?: Marker(EntityTypes.MARKER, level).also { standIn = it }
        entity.setPos(x, y, z)
        entity.xo = x
        entity.yo = y
        entity.zo = z
        entity.setYRot(yaw)
        entity.yRotO = yaw
        entity.setXRot(pitch)
        entity.xRotO = pitch
        return entity
    }

    private fun mapStandIn(p: DungeonPlayer): Entity? {
        if (!DungeonUtils.inDungeons || p.isDead) return null
        val mapPos = p.mapPos
        if (mapPos.x == 0 && mapPos.z == 0) return null
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val x = ((mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val z = ((mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        val blockX = Math.floor(x).toInt()
        val blockZ = Math.floor(z).toInt()
        val seedY = seenAs[p.name]?.let { PovPose.lastSeen(it)?.pose?.y?.toInt() }
            ?: when {
                GoldorTracker.section in 1..4 -> 112
                GoldorTracker.section >= 5 -> 64
                else -> 221
            }
        val y = bossGroundY(level, blockX, blockZ, seedY)
        return standIn(x, y.toDouble(), z, p.yaw, 0f)
    }

    private fun bossGroundY(level: Level, x: Int, z: Int, seedY: Int): Int {
        if (!level.isLoaded(BlockPos(x, seedY, z))) return seedY
        if (standable(level, x, seedY, z)) return seedY
        for (d in 1..64) if (standable(level, x, seedY - d, z)) return seedY - d
        for (d in 1..16) if (standable(level, x, seedY + d, z)) return seedY + d
        return seedY
    }

    private fun standable(level: Level, x: Int, y: Int, z: Int): Boolean {
        val floor = level.getBlockState(BlockPos(x, y - 1, z))
        if (floor.isAir || !floor.fluidState.isEmpty) return false
        val at = level.getBlockState(BlockPos(x, y, z))
        if (at.isAir) return true
        if (at.isCollisionShapeFullBlock(level, BlockPos(x, y, z))) return false
        return !level.getBlockState(BlockPos(x, y + 1, z)).isCollisionShapeFullBlock(level, BlockPos(x, y + 1, z))
    }

    private fun findByName(name: String): List<AbstractClientPlayer> =
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
        if (width <= 0 || height <= 0) return
        feedWidth = width
        feedHeight = height
        for (index in 0 until FEEDS) {
            val feed = TextureTarget("leaputils:pov$index", width, height, true, GpuFormat.RGBA8_UNORM)
            clear(feed)
            feeds[index] = feed
        }
        out.fill(false)
        nextFeed = 0
    }

    private fun freeFeedsNow() {
        freeRequested = false
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
        out.fill(false)
        nextFeed = 0
    }

    private fun clear(feed: RenderTarget) {
        feed.getColorTexture()?.let { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(it, OUT_OF_RANGE) }
    }

    private fun report(teammates: List<DungeonPlayer>) {
        val signature = (0 until FEEDS).joinToString("|") { index ->
            val name = teammates.getOrNull(index)?.name ?: "-"
            "$index:$name=${status[index] ?: "pending"}"
        }
        val now = System.nanoTime()
        if (signature == lastReportSignature && now - lastReportAt < 5_000_000_000L) return
        lastReportSignature = signature
        lastReportAt = now
        println("[LeapUtils][PovCapture] team=${teammates.size} $signature")
    }

    fun costLine(): String? {
        if (!PovPreviews.showCost || !PovPreviews.wants()) return null
        val live = (0 until FEEDS).count { !empty[it] && status[it] == "ok" }
        val line = "\u00a77POV \u00a7f${"%.1f".format(lastCostMs)}\u00a77ms  \u00a7f$live\u00a77/4"
        if (live == FEEDS) return line
        val codes = (0 until FEEDS).joinToString(",") { shortCode(it) }
        return "$line \u00a78[$codes]"
    }

    private fun shortCode(index: Int): String = when {
        empty[index] -> "-"
        else -> when (val s = status[index]) {
            null -> "?"
            "ok" -> "ok"
            "out" -> "out"
            "no-slot" -> "noslot"
            "err" -> "ERR"
            else -> s
        }
    }

    private fun unwindLevelRender(depthBefore: Int) {
        runCatching { releasePreparedFrame() }
        if (depthBefore >= 0) runCatching { popModelViewTo(depthBefore) }
        runCatching { (TARGETS_FIELD.get(Minecraft.getInstance().levelRenderer) as LevelTargetBundle).clear() }
    }

    private fun popModelViewTo(depth: Int) {
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

    private val TARGETS_FIELD by lazy {
        net.minecraft.client.renderer.LevelRenderer::class.java.getDeclaredField("targets").apply { isAccessible = true }
    }

    private var lastFailMsg: String? = null
    private var lastFailAt = 0L

    private fun fail(t: Throwable) {
        if (PovPreviews.disableOnError) {
            disabledForSession = true
            freeRequested = true
        }
        val now = System.nanoTime()
        val msg = t.toString()
        if (msg == lastFailMsg && now - lastFailAt < 5_000_000_000L) return
        lastFailMsg = msg
        lastFailAt = now
        println("[LeapUtils] POV previews hit an error${if (PovPreviews.disableOnError) " and are off for this session" else ""}: $t")
        t.printStackTrace()
    }
}
