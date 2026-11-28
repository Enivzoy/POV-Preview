package me.enivzoy.leaputils.pov.fastleap

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import me.enivzoy.leaputils.pov.SpCond
import me.enivzoy.leaputils.pov.SpSubject

// quoi's f7 boxes, inclusive on every face, see LeapSettings.kt + impl/FastLeap.kt
object FastLeapDefaults {

    val yellow = RegionSpec(RegionType.AABB, 24.0, 170.0, 86.0, 41.0, 172.0, 103.0)
    val purple = RegionSpec(RegionType.AABB, 106.0, 170.0, 86.0, 125.0, 172.0, 103.0)
    val betweenPads = RegionSpec(RegionType.AABB, 41.0, 163.0, 86.0, 106.0, 172.0, 103.0)
    val middle = RegionSpec(RegionType.AABB, 47.0, 64.0, 69.0, 62.0, 75.0, 84.0)
    val crystalA = RegionSpec(RegionType.AABB, 78.0, 233.0, 46.0, 87.0, 243.0, 55.0)
    val crystalB = RegionSpec(RegionType.AABB, 60.0, 233.0, 46.0, 69.0, 243.0, 55.0)
    val i4 = RegionSpec(RegionType.AABB, 62.0, 127.0, 34.0, 65.0, 130.0, 37.0)

    val regions: List<Pair<String, RegionSpec>> = listOf(
        "yellow_pad" to yellow,
        "purple_pad" to purple,
        "between_pads" to betweenPads,
        "middle_platform" to middle,
        "crystal_a" to crystalA,
        "crystal_b" to crystalB,
        "i4_spot" to i4,
    )

    fun region(id: String): RegionSpec? = regions.firstOrNull { it.first == id }?.second

    // quoi FastLeap order: crystal, predev, relic, p1, p4, yellow, purple, between, storm, middle, i4
    fun pack(): List<FastLeapRule> = listOf(
        FastLeapRule(
            id = "crystal",
            name = "Crystal",
            priority = 1000,
            phase = 1,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = crystalA,
        ),
        FastLeapRule(
            id = "crystal_west",
            name = "Crystal (west)",
            priority = 999,
            phase = 1,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = crystalB,
        ),
        FastLeapRule(
            id = "predev",
            name = "Predev",
            // quoi: position phase p3 while the chat phase is still p1/p2
            priority = 900,
            phaseAt = 3,
            phaseAnyOf = setOf(1, 2),
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        ),
        FastLeapRule(
            id = "relic",
            name = "Relic",
            priority = 850,
            phase = 5,
            phaseAt = 5,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        ),
        FastLeapRule(
            id = "p1",
            name = "P1",
            priority = 800,
            phase = 1,
            phaseAt = 1,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        ),
        FastLeapRule(
            id = "p4",
            name = "P4",
            priority = 750,
            phase = 4,
            phaseAt = 4,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = middle,
        ),
        FastLeapRule(
            id = "yellow",
            name = "Yellow",
            priority = 700,
            phase = 2,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = yellow,
        ),
        FastLeapRule(
            id = "purple",
            name = "Purple",
            priority = 690,
            phase = 2,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = purple,
        ),
        FastLeapRule(
            id = "between_pads",
            name = "Between pads",
            priority = 680,
            phase = 2,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = betweenPads,
        ),
        FastLeapRule(
            id = "storm_death",
            name = "Storm death",
            priority = 670,
            phase = 2,
            phaseAt = 2,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            // storm death is p2 outside every pad box
            excludeRegions = listOf(yellow, purple, betweenPads),
        ),
        FastLeapRule(
            id = "middle",
            name = "Middle",
            priority = 660,
            phase = 4,
            phaseAt = 4,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            excludeRegions = listOf(middle),
        ),
        FastLeapRule(
            id = "i4",
            name = "I4",
            priority = 650,
            phase = 3,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = i4,
        ),
        FastLeapRule(
            id = "s1",
            name = "S1",
            priority = 600,
            phase = 3,
            stage = 1,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        ),
        FastLeapRule(
            id = "s2",
            name = "S2",
            priority = 590,
            phase = 3,
            stage = 2,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        ),
        FastLeapRule(
            id = "s3",
            name = "S3",
            priority = 580,
            phase = 3,
            stage = 3,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        ),
        FastLeapRule(
            id = "s4",
            name = "S4",
            priority = 570,
            phase = 3,
            stage = 4,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        ),
        FastLeapRule(
            id = "mage-at-acacia",
            name = "Mage at acacia",
            // template only: the landmark is the user's to capture, so it ships off
            enabled = false,
            priority = 500,
            target = TargetSpec(TargetSelectorType.CLASS, targetClass = DungeonClass.MAGE),
            conditions = listOf(
                PositionCondition(
                    subject = SpSubject.TARGET,
                    landmarkName = "acacia",
                    errorMinDistance = SpCond.DEFAULT_ERROR_MIN,
                    errorMaxDistance = SpCond.DEFAULT_ERROR_MAX,
                ),
            ),
        ),
    )

    // storm death must not fire while standing on or between the pads
    fun isOutsideStormPads(pos: SpatialEvaluator.Position): Boolean =
        !SpatialEvaluator.insideAabb(pos, yellow) &&
            !SpatialEvaluator.insideAabb(pos, purple) &&
            !SpatialEvaluator.insideAabb(pos, betweenPads)

    val leapItemIds: Set<String> = setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")

    val cooldownTicks: Map<String, Int> = mapOf("SPIRIT_LEAP" to 107, "INFINITE_SPIRIT_LEAP" to 47)

    val emptyClassTargets: List<DungeonClass> = listOf(
        DungeonClass.BERSERK,
        DungeonClass.ARCHER,
        DungeonClass.MAGE,
        DungeonClass.TANK,
        DungeonClass.HEALER,
    )
}
