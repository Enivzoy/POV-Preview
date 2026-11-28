package me.enivzoy.leaputils.pov.fastleap

import me.enivzoy.leaputils.pov.SpLandmark
import me.enivzoy.leaputils.pov.SpTmate
import kotlin.math.abs
import kotlin.math.sqrt

object SpatialEvaluator {

    data class Position(val x: Double?, val y: Double?, val z: Double?) {
        // x/z are the minimum needed to gate on a region, y is optional for horizontal checks
        val known: Boolean get() = x != null && z != null
    }

    sealed interface Check {
        data class Pass(val distance: Double?, val axes: String, val source: String) : Check
        data class Fail(val reason: String) : Check
    }

    // inclusive on every face; an aabb is a region, never a tolerance around a point
    fun insideAabb(pos: Position, box: RegionSpec): Boolean {
        val x = pos.x ?: return false
        val y = pos.y ?: return false
        val z = pos.z ?: return false
        return x >= box.minX!! && x <= box.maxX!! &&
            y >= box.minY!! && y <= box.maxY!! &&
            z >= box.minZ!! && z <= box.maxZ!!
    }

    fun insideRegion(
        pos: Position,
        region: RegionSpec,
        landmarks: Map<String, SpLandmark>,
        defaultRadius: Double = DEFAULT_RADIUS,
    ): Boolean {
        return when (region.type) {
            RegionType.AABB -> insideAabb(pos, region)
            RegionType.POINT -> {
                val anchor = region.landmarkName?.let { landmarks[it] }
                    ?: if (region.minX != null && region.minY != null && region.minZ != null) {
                        SpLandmark(region.minX, region.minY, region.minZ)
                    } else {
                        null
                    }
                if (anchor == null) {
                    false
                } else {
                    val cond = PositionCondition(landmarkName = region.landmarkName, radius = region.radius)
                    check(pos, anchor, false, cond, 0.0, Double.MAX_VALUE, region.landmarkName, defaultRadius) is Check.Pass
                }
            }
        }
    }

    fun sq(dx: Double, dy: Double, dz: Double): Double = dx * dx + dy * dy + dz * dz

    fun check(
        pos: Position,
        anchor: SpLandmark?,
        horizontal: Boolean,
        cond: PositionCondition,
        defaultMin: Double,
        defaultMax: Double,
        landmarkLabel: String?,
        // the configured default radius, used when the condition defines neither radius nor mode
        defaultRadius: Double = DEFAULT_RADIUS,
    ): Check {
        val x = pos.x
        val z = pos.z
        if (x == null || z == null) {
            val missing = buildList {
                if (x == null) add("x")
                if (z == null) add("z")
            }.joinToString("+")
            return Check.Fail("position unknown (missing $missing)")
        }
        val y = pos.y
        if (!horizontal && y == null) return Check.Fail("position unknown (missing y)")

        val point = anchor ?: return Check.Fail("missing landmark ${landmarkLabel ?: "?"}")
        val dx = x - point.x
        val dz = z - point.z
        val distSq = if (horizontal || y == null) dx * dx + dz * dz else sq(dx, y - point.y, dz)
        val dist = sqrt(distSq)
        val axes = if (horizontal || y == null) "XZ" else "3D"

        if (cond.hasErrorInterval) {
            val min = cond.errorMinDistance ?: defaultMin
            val max = cond.errorMaxDistance ?: defaultMax
            if (min < 0.0 || max < min) return Check.Fail("invalid interval ${fmt(min)}..${fmt(max)}")
            if (dist < min) return Check.Fail("distance ${fmt(dist)} < ${fmt(min)}")
            if (dist > max) return Check.Fail("distance ${fmt(dist)} > ${fmt(max)}")
        } else {
            // explicit radius wins, then the mode's own radius, then the configured module default
            val radius = cond.radius ?: cond.mode?.defaultRadius ?: defaultRadius
            if (dist > radius) {
                return Check.Fail("distance ${fmt(dist)} > ${fmt(radius)}")
            }
        }
        return Check.Pass(dist, axes, if (landmarkLabel == null) "custom" else "landmark")
    }

    fun resolveAnchor(cond: PositionCondition, landmarks: Map<String, SpLandmark>): Pair<SpLandmark?, String?> {
        if (cond.hasCustomPoint) {
            val y = cond.customY ?: 0.0
            return SpLandmark(cond.customX!!, y, cond.customZ!!) to null
        }
        val name = cond.landmarkName?.trim().orEmpty()
        if (name.isEmpty()) return null to null
        return landmarks[name] to name
    }

    fun fmt(v: Double): String = String.format("%.1f", v)

    fun near(a: Double, b: Double, eps: Double = 1e-9): Boolean = abs(a - b) <= eps

    fun selfPosition(x: Double?, y: Double?, z: Double?): Position = Position(x, y, z)

    fun targetPosition(tm: SpTmate): Position = Position(tm.x, tm.y, tm.z)
}
