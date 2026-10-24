package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.player.Player
import org.joml.Quaternionf
import org.joml.Vector3f

object PovPip {
    private const val FEEDS = 4
    private val empty = BooleanArray(FEEDS) { true }
    var disabledForSession = false
        private set

    fun onScreenExtract(gfx: GuiGraphicsExtractor) {
        if (disabledForSession) return
        val mc = Minecraft.getInstance()
        if (!PovPreviews.wants() || mc.level == null || mc.player == null) return
        val guiWidth = gfx.guiWidth()
        val guiHeight = gfx.guiHeight()
        if (guiWidth <= 0 || guiHeight <= 0) return

        refreshSlots()
        renderPip(gfx)
    }

    private fun refreshSlots() {
        val teammates = DungeonUtils.leapTeammates ?: emptyList()
        for (index in 0 until FEEDS) {
            val slot = teammates.getOrNull(index)
            empty[index] = slot == null || slot.name == "Empty"
        }
    }

    private fun renderPip(gfx: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val guiWidth = gfx.guiWidth()
        val guiHeight = gfx.guiHeight()
        val dispatcher = mc.entityRenderDispatcher
        val teammates = DungeonUtils.leapTeammates ?: emptyList()
        val splitX = guiWidth / 2
        val splitY = guiHeight / 2
        val partialTicks = mc.deltaTracker.getGameTimeDeltaPartialTick(true)

        for (index in 0 until FEEDS) {
            val slot = teammates.getOrNull(index)
            if (slot == null || slot.name == "Empty") {
                empty[index] = true
                continue
            }
            empty[index] = false
            val target = resolve(slot.entity, slot.name) ?: continue
            val result = PovPose.of(target, PovPreviews.mode, partialTicks, player.position(), PovPreviews.maxDistance.toDouble())
            val pose = when (result) {
                is PovPose.Result.Rejected -> continue
                is PovPose.Result.Ok -> result.pose
            }
            if (!PovPose.fitsWorld(pose, level)) continue
            val renderState = dispatcher.extractEntity(target, partialTicks) ?: continue
            neutralize(renderState)

            val column = index % 2
            val row = index / 2
            val x0 = if (column == 0) 0 else splitX
            val y0 = if (row == 0) 0 else splitY
            val x1 = if (column == 0) splitX else guiWidth
            val y1 = if (row == 0) splitY else guiHeight
            val size = Math.min(splitX, splitY).toFloat()
            val scale = (size * PovPreviews.resolution * 0.8f).coerceAtLeast(16f)
            val cx = (x0 + x1) / 2f
            val cy = y1 - scale * 0.6f

            try {
                val bbHeight = try { renderState.javaClass.getField("bbHeight").getFloat(renderState) } catch (e: Throwable) { 1.8f }
                gfx.entity(
                    renderState,
                    scale,
                    Vector3f(0f, bbHeight * 0.5f, 0f),
                    Quaternionf().rotateX(Math.toRadians(5.0).toFloat()),
                    Quaternionf(),
                    Math.round(cx - scale / 2f),
                    Math.round(cy - scale / 2f),
                    Math.round(cx + scale / 2f),
                    Math.round(cy + scale / 2f),
                )
            } catch (t: Throwable) {
                if (PovPreviews.disableOnError) {
                    disabledForSession = true
                }
                println("[LeapUtils] PiP preview error: $t")
            }
        }
    }

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

    private fun neutralize(state: Any) {
        try {
            val entity = try { state.javaClass.getField("entityRenderState").get(state) } catch (e: Throwable) { state }
            if (entity != null) {
                try { entity.javaClass.getField("hurtTime").setFloat(entity, 0f) } catch (_: Throwable) {}
                try { entity.javaClass.getField("hurtDuration").setInt(entity, 0) } catch (_: Throwable) {}
                try { entity.javaClass.getField("deathTime").setFloat(entity, 0f) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
