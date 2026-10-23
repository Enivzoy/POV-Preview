package me.enivzoy.leaputils.pov

import me.enivzoy.leaputils.mixin.LivingEntityAccessor
import net.minecraft.core.SectionPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

object PovPose {

    enum class Mode { VANILLA, RAW }

    data class Pose(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val eyeHeight: Float,
    )

    sealed interface Result {
        data class Ok(val pose: Pose) : Result
        data class Rejected(val reason: String) : Result
    }

    private const val MAX_HORIZONTAL = 33_554_432.0

    private const val MAX_VERTICAL = 8_388_608.0

    private data class Raw(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    fun of(
        entity: LivingEntity,
        mode: Mode,
        partialTicks: Float,
        origin: Vec3,
        maxDistance: Double,
    ): Result {
        val raw = read(entity, mode, partialTicks)
        val pose = sanitize(raw, origin, maxDistance) ?: return Result.Rejected(reason(raw, origin))
        return Result.Ok(pose.copy(eyeHeight = eyeHeight(entity)))
    }

    private fun read(entity: LivingEntity, mode: Mode, partialTicks: Float): Raw {
        val live = entity.position()
        return when (mode) {
            Mode.VANILLA -> {
                val p = partialTicks.toDouble()
                Raw(
                    Mth.lerp(p, entity.xo, live.x),
                    Mth.lerp(p, entity.yo, live.y),
                    Mth.lerp(p, entity.zo, live.z),
                    entity.getViewYRot(partialTicks),
                    entity.getViewXRot(partialTicks),
                )
            }

            Mode.RAW -> {
                val interpolation = entity.interpolation
                val interpolating = interpolation.hasActiveInterpolation()
                val pos = if (interpolating) interpolation.position() else live
                val pitch = if (interpolating) interpolation.xRot() else entity.xRot
                val accessor = entity as LivingEntityAccessor
                val headYaw = if (accessor.cpLerpHeadSteps() > 0) accessor.cpLerpYHeadRot().toFloat() else entity.yHeadRot
                Raw(pos.x, pos.y, pos.z, headYaw, pitch)
            }
        }
    }

    private fun sanitize(raw: Raw, origin: Vec3, maxDistance: Double): Pose? {
        if (!raw.x.isFinite() || !raw.y.isFinite() || !raw.z.isFinite()) return null
        if (!raw.yaw.isFinite() || !raw.pitch.isFinite()) return null
        if (raw.x < -MAX_HORIZONTAL || raw.x > MAX_HORIZONTAL) return null
        if (raw.z < -MAX_HORIZONTAL || raw.z > MAX_HORIZONTAL) return null
        if (raw.y < -MAX_VERTICAL || raw.y > MAX_VERTICAL) return null
        if (origin.distanceToSqr(raw.x, raw.y, raw.z) > maxDistance * maxDistance) return null
        return Pose(
            raw.x,
            raw.y,
            raw.z,
            Mth.wrapDegrees(raw.yaw),
            raw.pitch.coerceIn(-90f, 90f),
            0f,
        )
    }

    private fun reason(raw: Raw, origin: Vec3): String = when {
        !raw.x.isFinite() || !raw.y.isFinite() || !raw.z.isFinite() -> "not a finite position"
        !raw.yaw.isFinite() || !raw.pitch.isFinite() -> "not a finite rotation"
        raw.x < -MAX_HORIZONTAL || raw.x > MAX_HORIZONTAL -> "outside the coordinate range"
        raw.z < -MAX_HORIZONTAL || raw.z > MAX_HORIZONTAL -> "outside the coordinate range"
        raw.y < -MAX_VERTICAL || raw.y > MAX_VERTICAL -> "outside the coordinate range"
        else -> "%.0f blocks away".format(sqrt(origin.distanceToSqr(raw.x, raw.y, raw.z)))
    }

    private fun eyeHeight(entity: LivingEntity): Float = entity.eyeHeight.coerceIn(0f, 8f)

    fun fitsWorld(pose: Pose, level: Level): Boolean {
        if (pose.y < level.minY - 128.0 || pose.y > level.maxY + 128.0) return false
        if (!level.worldBorder.isWithinBounds(Vec3(pose.x, pose.y, pose.z))) return false
        val chunkX = SectionPos.blockToSectionCoord(Mth.floor(pose.x))
        val chunkZ = SectionPos.blockToSectionCoord(Mth.floor(pose.z))
        return level.hasChunk(chunkX, chunkZ)
    }
}
