package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.player.Player
import org.joml.Quaternionf
import org.joml.Vector3f


object PovFeedRig {

    private const val QUADRANTS = 4
    private const val VACANT_LABEL = "Empty"

    private val vacant = BooleanArray(QUADRANTS) { true }
    private val broken = BooleanArray(QUADRANTS)
    private val notes = arrayOfNulls<String>(QUADRANTS)
    private var lastLogStamp = 0L
    private var frameCostMs = 0.0

    var haltedThisSession = false
        private set

    @Volatile
    var activeSubject: Player? = null
        private set

    fun clearAll() {}

    fun resetOnLevelSwitch() {}

    fun dropResourcesIfIdle() {}

    fun costReadout(): String? {
        if (!PovPreviews.showCost || !PovPreviews.wants()) return null
        val live = (0 until QUADRANTS).count { !vacant[it] && notes[it] == "ok" }
        return "\u00a77POV \u00a7f${String.format("%.1f", frameCostMs)}\u00a77ms  \u00a7f$live\u00a77/4"
    }

    fun drawFeeds(gfx: GuiGraphicsExtractor) {
        if (haltedThisSession) return
        if (!PovPreviews.wants()) return
        val client = Minecraft.getInstance()
        if (client.level == null || client.player == null) return
        if (DungeonUtils.leapTeammates.isNullOrEmpty()) return
        try {
            syncSlots()
            paintQuarters(gfx)
        } catch (t: Throwable) {
            onRenderFault(t)
        }
    }

    private fun syncSlots() {
        val roster = DungeonUtils.leapTeammates
        for (slotIndex in 0 until QUADRANTS) {
            val member = roster.getOrNull(slotIndex)
            val missing = member == null || member.name == VACANT_LABEL
            vacant[slotIndex] = missing
            if (missing) notes[slotIndex] = "empty"
        }
    }

    private fun paintQuarters(gfx: GuiGraphicsExtractor) {
        val client = Minecraft.getInstance()
        val world = client.level ?: return
        val self = client.player ?: return
        val viewW = gfx.guiWidth()
        val viewH = gfx.guiHeight()
        if (viewW <= 0 || viewH <= 0) return

        val extractor = client.entityRenderDispatcher
        val roster = DungeonUtils.leapTeammates
        val midX = viewW / 2
        val midY = viewH / 2
        val frameDelta = client.deltaTracker.getGameTimeDeltaPartialTick(true)

        for (slotIndex in 0 until QUADRANTS) {
            val member = roster.getOrNull(slotIndex)
            val missing = member == null || member.name == VACANT_LABEL
            if (missing) {
                vacant[slotIndex] = true
                notes[slotIndex] = "empty"
                continue
            }
            vacant[slotIndex] = false

            val subject = pickTeammate(member.entity, member.name)
            if (subject == null) {
                notes[slotIndex] = "no-entity"
                continue
            }

            val poseResult = PovPose.of(
                subject,
                PovPreviews.mode,
                frameDelta,
                self.position(),
                PovPreviews.maxDistance.toDouble()
            )
            val pose = when (poseResult) {
                is PovPose.Result.Rejected -> {
                    notes[slotIndex] = "pose: ${poseResult.reason}"
                    continue
                }
                is PovPose.Result.Ok -> poseResult.pose
            }
            if (!PovPose.fitsWorld(pose, world)) {
                notes[slotIndex] = "outside level"
                continue
            }

            val snapshot = extractor.extractEntity(subject, frameDelta)
            if (snapshot == null) {
                notes[slotIndex] = "no-state"
                continue
            }
            stripCombatTint(snapshot)

            val side = slotIndex % 2
            val band = slotIndex / 2
            val left = if (side == 0) 0 else midX
            val top = if (band == 0) 0 else midY
            val right = if (side == 0) midX else viewW
            val bottom = if (band == 0) midY else viewH

            val box = Math.min(midX, midY).toFloat()
            val drawSize = (box * PovPreviews.resolution * 0.8f).coerceAtLeast(16f)
            val centerX = (left + right) / 2f
            val centerY = bottom - drawSize * 0.6f

            try {
                activeSubject = subject
                val bodyHeight = try {
                    snapshot.javaClass.getField("bbHeight").getFloat(snapshot)
                } catch (e: Throwable) {
                    1.8f
                }
                gfx.entity(
                    snapshot,
                    drawSize,
                    Vector3f(0f, bodyHeight * 0.5f, 0f),
                    Quaternionf().rotateX(Math.toRadians(5.0).toFloat()),
                    Quaternionf(),
                    Math.round(centerX - drawSize / 2f),
                    Math.round(centerY - drawSize / 2f),
                    Math.round(centerX + drawSize / 2f),
                    Math.round(centerY + drawSize / 2f),
                )
                notes[slotIndex] = "ok"
                broken[slotIndex] = false
            } catch (t: Throwable) {
                notes[slotIndex] = "err"
                onRenderFault(t)
            } finally {
                activeSubject = null
            }
        }
    }

    private fun pickTeammate(entity: Player?, displayName: String): Player? {
        val client = Minecraft.getInstance()
        val self = client.player ?: return null
        val world = client.level
        return sequence {
            if (entity != null) yield(entity)
            yieldAll(locateByName(displayName))
        }.firstOrNull {
            it !== self && !it.isRemoved && it.isAlive && (world == null || it.level() === world)
        }
    }

    private fun locateByName(displayName: String): List<Player> =
        Minecraft.getInstance().level?.players()?.filter {
            it.name.string.equals(displayName, ignoreCase = true) ||
                it.gameProfile.name.equals(displayName, ignoreCase = true) ||
                it.scoreboardName.equals(displayName, ignoreCase = true)
        } ?: emptyList()

    private fun stripCombatTint(state: Any) {
        try {
            val inner = try {
                state.javaClass.getField("entityRenderState").get(state)
            } catch (e: Throwable) {
                state
            }
            if (inner != null) {
                try { inner.javaClass.getField("hurtTime").setFloat(inner, 0f) } catch (_: Throwable) {}
                try { inner.javaClass.getField("hurtDuration").setInt(inner, 0) } catch (_: Throwable) {}
                try { inner.javaClass.getField("deathTime").setFloat(inner, 0f) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        }
    }

    private fun onRenderFault(t: Throwable) {
        if (PovPreviews.disableOnError) haltedThisSession = true
        println("[LeapUtils] POV previews hit an error${if (PovPreviews.disableOnError) " and are off for this session" else ""}: $t")
        t.printStackTrace()
    }
}
