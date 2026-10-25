package me.enivzoy.leaputils.pov

import me.enivzoy.leaputils.mixin.LivingEntityAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

object PovPose {

    enum class Mode { VANILLA, RAW }

    fun interface Restore {
        fun restore()
    }

    private val NONE = Restore {}

    data class Pose(
        val x: Double,
        val y: Double,
        val z: Double,
        val headYaw: Float,
        val pitch: Float,
    )

    data class Sample(val pose: Pose, val nanos: Long)

    private const val MAX_HORIZONTAL = 33_554_432.0
    private const val MAX_VERTICAL = 8_388_608.0
    private const val KEEP_NANOS = 10_000_000_000L

    private val samples = HashMap<UUID, Sample>()

    fun begin(entity: LivingEntity, mode: Mode): Restore = when (mode) {
        Mode.VANILLA -> NONE
        Mode.RAW -> apply(entity, packetTarget(entity))
    }

    fun onClientTick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            samples.clear()
            return
        }
        val self = mc.player
        val now = System.nanoTime()
        for (player in level.players()) {
            if (player === self) continue
            samples[player.uuid] = Sample(packetTarget(player), now)
        }
        samples.values.removeIf { now - it.nanos > KEEP_NANOS }
    }

    fun lastSeen(id: UUID): Sample? = samples[id]

    fun reset() = samples.clear()

    fun isSane(entity: Entity): Boolean {
        val x = entity.x
        val y = entity.y
        val z = entity.z
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (x < -MAX_HORIZONTAL || x > MAX_HORIZONTAL) return false
        if (z < -MAX_HORIZONTAL || z > MAX_HORIZONTAL) return false
        if (y < -MAX_VERTICAL || y > MAX_VERTICAL) return false
        return entity.getViewYRot(1f).isFinite() && entity.getViewXRot(1f).isFinite()
    }

    private fun packetTarget(entity: LivingEntity): Pose {
        val interpolation = entity.interpolation
        val active = interpolation.hasActiveInterpolation()
        val pos = if (active) interpolation.position() else entity.position()
        val pitch = if (active) interpolation.xRot() else entity.xRot
        val accessor = entity as LivingEntityAccessor
        val headYaw = if (accessor.cpLerpHeadSteps() > 0) accessor.cpLerpYHeadRot().toFloat() else entity.yHeadRot
        return Pose(pos.x, pos.y, pos.z, headYaw, pitch)
    }

    private fun apply(entity: LivingEntity, pose: Pose): Restore {
        val oldPos = entity.position()
        val oldXo = entity.xo
        val oldYo = entity.yo
        val oldZo = entity.zo
        val oldXRot = entity.xRot
        val oldXRotO = entity.xRotO
        val oldHeadYaw = entity.yHeadRot
        val oldHeadYawO = entity.yHeadRotO

        entity.setPos(pose.x, pose.y, pose.z)
        entity.xo = pose.x
        entity.yo = pose.y
        entity.zo = pose.z
        entity.xRot = pose.pitch
        entity.xRotO = pose.pitch
        entity.yHeadRot = pose.headYaw
        entity.yHeadRotO = pose.headYaw

        return Restore {
            entity.setPos(oldPos.x, oldPos.y, oldPos.z)
            entity.xo = oldXo
            entity.yo = oldYo
            entity.zo = oldZo
            entity.xRot = oldXRot
            entity.xRotO = oldXRotO
            entity.yHeadRot = oldHeadYaw
            entity.yHeadRotO = oldHeadYawO
        }
    }
}
