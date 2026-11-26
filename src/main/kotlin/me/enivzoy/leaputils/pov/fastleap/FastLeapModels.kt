package me.enivzoy.leaputils.pov.fastleap

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import me.enivzoy.leaputils.pov.SpMode
import me.enivzoy.leaputils.pov.SpSubject
import me.enivzoy.leaputils.pov.SpTmate

enum class TargetSelectorType { NAME, CLASS, NEAREST_LANDMARK, FIRST_IN_MENU, DOOR_OPENER }

data class TargetSpec(
    val type: TargetSelectorType,
    val targetClass: DungeonClass? = null,
    val targetName: String? = null,
    val landmarkName: String? = null,
    // null keeps the module default radius setting
    val radius: Double? = null,
)

enum class RegionType { AABB, POINT }

// inclusive bounds; aabbs are matched exactly and never widened by an error interval
data class RegionSpec(
    val type: RegionType = RegionType.AABB,
    val minX: Double? = null,
    val minY: Double? = null,
    val minZ: Double? = null,
    val maxX: Double? = null,
    val maxY: Double? = null,
    val maxZ: Double? = null,
    val landmarkName: String? = null,
    val radius: Double? = null,
) {
    val complete: Boolean
        get() = when (type) {
            RegionType.AABB ->
                minX != null && minY != null && minZ != null && maxX != null && maxY != null && maxZ != null
            RegionType.POINT ->
                landmarkName?.isNotBlank() == true || (minX != null && minY != null && minZ != null)
        }
}

data class PositionCondition(
    val subject: SpSubject = SpSubject.TARGET,
    val landmarkName: String? = null,
    val customX: Double? = null,
    val customY: Double? = null,
    val customZ: Double? = null,
    // null falls back to the module wide 3D default, per cond overrides win
    val horizontal: Boolean? = null,
    val errorMinDistance: Double? = null,
    val errorMaxDistance: Double? = null,
    val mode: SpMode? = null,
    val radius: Double? = null,
) {
    val hasCustomPoint: Boolean get() = customX != null && customZ != null
    val legacyRadius: Double get() = radius ?: mode?.defaultRadius ?: DEFAULT_RADIUS
    val hasErrorInterval: Boolean get() = errorMinDistance != null || errorMaxDistance != null
}

// spec default for FastLeap rules, Savepoint's own SpMode radii stay untouched
const val DEFAULT_RADIUS = 6.0

enum class FastLeapTriggerSource { CLICK, MENU_OPEN, KEYBIND, COMMAND }

data class FastLeapRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val allowedTriggers: Set<FastLeapTriggerSource> = FastLeapTriggerSource.entries.toSet(),
    val inBoss: Boolean? = null,
    // chat driven quoi phase, 1..5
    val phase: Int? = null,
    val phaseAnyOf: Set<Int> = emptySet(),
    // phase derived from the player's y band, quoi's inPhaseAt
    val phaseAt: Int? = null,
    val stage: Int? = null,
    // goldor section 1..5 when known, 0 skips the gate
    val section: Int? = null,
    val target: TargetSpec,
    val selfRegion: RegionSpec? = null,
    val excludeRegions: List<RegionSpec> = emptyList(),
    val conditions: List<PositionCondition> = emptyList(),
)

sealed interface FastLeapResult {
    data class Plan(
        val rule: FastLeapRule,
        val teammate: SpTmate,
        val measuredDistance: Double?,
        val axes: String,
        val positionSource: String,
    ) : FastLeapResult

    data class NoMatch(val failures: List<RuleFailure>) : FastLeapResult
}

data class RuleFailure(val ruleId: String, val reason: String)
