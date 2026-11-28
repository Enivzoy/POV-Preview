package me.enivzoy.leaputils

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import me.enivzoy.leaputils.pov.SpCond
import me.enivzoy.leaputils.pov.SpLandmark
import me.enivzoy.leaputils.pov.SpMode
import me.enivzoy.leaputils.pov.SpSubject
import me.enivzoy.leaputils.pov.SpTmate
import me.enivzoy.leaputils.pov.PresetCondJson
import me.enivzoy.leaputils.pov.SavepointPresets
import me.enivzoy.leaputils.pov.fastleap.FastLeapContext
import me.enivzoy.leaputils.pov.fastleap.FastLeapDefaults
import me.enivzoy.leaputils.pov.fastleap.FastLeapEvaluator
import me.enivzoy.leaputils.pov.fastleap.FastLeapResult
import me.enivzoy.leaputils.pov.fastleap.FastLeapRule
import me.enivzoy.leaputils.pov.fastleap.FastLeapState
import me.enivzoy.leaputils.pov.fastleap.FastLeapTriggerSource
import me.enivzoy.leaputils.pov.fastleap.DEFAULT_RADIUS
import me.enivzoy.leaputils.pov.fastleap.F7Positions
import me.enivzoy.leaputils.pov.fastleap.PositionCondition
import me.enivzoy.leaputils.pov.fastleap.PrefireState
import me.enivzoy.leaputils.pov.fastleap.RegionSpec
import me.enivzoy.leaputils.pov.fastleap.RegionType
import me.enivzoy.leaputils.pov.fastleap.SpatialEvaluator
import me.enivzoy.leaputils.pov.fastleap.TargetSelectorType
import me.enivzoy.leaputils.pov.fastleap.TargetSpec
import me.enivzoy.leaputils.pov.fastleap.WarningThrottle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastLeapTest {

    private fun tm(
        name: String,
        clazz: DungeonClass,
        dead: Boolean = false,
        x: Double? = null,
        y: Double? = null,
        z: Double? = null,
    ) = SpTmate(name, clazz, dead, true, x, y, z)

    private fun ctx(
        teammates: List<SpTmate> = listOf(tm("Alice", DungeonClass.HEALER)),
        myName: String? = null,
        myX: Double? = null,
        myY: Double? = null,
        myZ: Double? = null,
        phase: Int = 0,
        inBoss: Boolean = true,
        trigger: FastLeapTriggerSource = FastLeapTriggerSource.CLICK,
        landmarks: Map<String, SpLandmark> = emptyMap(),
        doorOpener: String? = null,
        blacklist: Set<String> = emptySet(),
        whitelist: Set<String> = emptySet(),
        skipLastLeaped: Boolean = false,
        lastLeapedName: String? = null,
        collectAll: Boolean = false,
        caseInsensitive: Boolean = true,
        fallbackLastLeaped: Boolean = false,
        fallbackNearestLandmark: String? = null,
        menuOpen: Boolean = false,
        skipNotInMenu: Boolean = false,
        ignoreSelf: Boolean = true,
        preferredName: String? = null,
        defaultRadius: Double = DEFAULT_RADIUS,
    ) = FastLeapContext(
        trigger = trigger,
        teammates = teammates,
        myClass = DungeonClass.MAGE,
        myName = myName,
        myX = myX,
        myY = myY,
        myZ = myZ,
        landmarks = landmarks,
        inBoss = inBoss,
        phase = phase,
        doorOpener = doorOpener,
        blacklist = blacklist,
        whitelist = whitelist,
        skipLastLeaped = skipLastLeaped,
        lastLeapedName = lastLeapedName,
        collectAll = collectAll,
        caseInsensitive = caseInsensitive,
        fallbackLastLeaped = fallbackLastLeaped,
        fallbackNearestLandmark = fallbackNearestLandmark,
        menuOpen = menuOpen,
        skipNotInMenu = skipNotInMenu,
        ignoreSelf = ignoreSelf,
        preferredName = preferredName,
        defaultRadius = defaultRadius,
    )

    private fun centre(box: RegionSpec): SpatialEvaluator.Position = SpatialEvaluator.Position(
        (box.minX!! + box.maxX!!) / 2,
        (box.minY!! + box.maxY!!) / 2,
        (box.minZ!! + box.maxZ!!) / 2,
    )

    private fun landmarkRule(
        id: String = "r",
        landmark: String,
        min: Double? = null,
        max: Double? = null,
        horizontal: Boolean = false,
        priority: Int = 100,
    ) = FastLeapRule(
        id = id,
        name = id,
        priority = priority,
        target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
        conditions = listOf(
            PositionCondition(
                subject = SpSubject.TARGET,
                landmarkName = landmark,
                horizontal = horizontal,
                errorMinDistance = min,
                errorMaxDistance = max,
            ),
        ),
    )

    @BeforeTest
    fun resetState() {
        FastLeapState.reset()
    }

    // ---- target / geometry ----

    @Test
    fun targetInsideRadiusMatches() {
        val rules = listOf(landmarkRule(landmark = "spot", max = 8.0).copy(
            conditions = listOf(PositionCondition(landmarkName = "spot", mode = SpMode.NEAR)),
        ))
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val inside = ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER, x = 5.0, y = 0.0, z = 0.0)), landmarks = planes)
        assertIs<FastLeapResult.Plan>(FastLeapEvaluator.evaluate(rules, inside))

        val outside = ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER, x = 9.0, y = 0.0, z = 0.0)), landmarks = planes)
        assertIs<FastLeapResult.NoMatch>(FastLeapEvaluator.evaluate(rules, outside))
    }

    @Test
    fun intervalEndpointsAreInclusive() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val cond = PositionCondition(landmarkName = "spot", errorMinDistance = 3.0, errorMaxDistance = 15.0)

        fun check(x: Double) = SpatialEvaluator.check(
            SpatialEvaluator.Position(x, 0.0, 0.0), planes["spot"], false, cond, 3.0, 15.0, "spot",
        )

        assertIs<SpatialEvaluator.Check.Pass>(check(3.0))
        assertIs<SpatialEvaluator.Check.Pass>(check(15.0))
        assertIs<SpatialEvaluator.Check.Fail>(check(2.9))
        assertIs<SpatialEvaluator.Check.Fail>(check(15.1))
    }

    @Test
    fun horizontalIntervalUsesXZ() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val cond = PositionCondition(landmarkName = "spot", errorMinDistance = 3.0, errorMaxDistance = 15.0)

        val flat = SpatialEvaluator.check(
            SpatialEvaluator.Position(4.0, 100.0, 0.0), planes["spot"], true, cond, 3.0, 15.0, "spot",
        )
        assertIs<SpatialEvaluator.Check.Pass>(flat)

        val full = SpatialEvaluator.check(
            SpatialEvaluator.Position(4.0, 100.0, 0.0), planes["spot"], false, cond, 3.0, 15.0, "spot",
        )
        assertIs<SpatialEvaluator.Check.Fail>(full)
        assertEquals("XZ", (flat as SpatialEvaluator.Check.Pass).axes)
    }

    @Test
    fun verticalSeparationFailsWithoutHorizontal() {
        val anchors = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val cond = PositionCondition(landmarkName = "spot", mode = SpMode.NEAR)
        val res = SpatialEvaluator.check(
            SpatialEvaluator.Position(0.0, 20.0, 0.0), anchors["spot"], false, cond, 3.0, 15.0, "spot",
        )
        assertIs<SpatialEvaluator.Check.Fail>(res)
    }

    @Test
    fun missingLandmarkFailsWithReason() {
        val res = SpatialEvaluator.check(
            SpatialEvaluator.Position(0.0, 0.0, 0.0), null, false, PositionCondition(landmarkName = "acacia"), 3.0, 15.0, "acacia",
        )
        assertIs<SpatialEvaluator.Check.Fail>(res)
        assertTrue(res.reason.contains("acacia"))
    }

    @Test
    fun unknownPositionFailsWithoutGuessing() {
        val res = SpatialEvaluator.check(
            SpatialEvaluator.Position(null, null, null), SpLandmark(0.0, 0.0, 0.0), false,
            PositionCondition(landmarkName = "spot"), 3.0, 15.0, "spot",
        )
        assertIs<SpatialEvaluator.Check.Fail>(res)
        assertTrue(res.reason.contains("position unknown"))
    }

    @Test
    fun ruleWithRegionSkipsUnknownSelfPosition() {
        val rule = FastLeapRule(
            id = "yellow",
            name = "Yellow",
            phase = 2,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = FastLeapDefaults.yellow,
        )
        val res = FastLeapEvaluator.evaluate(listOf(rule), ctx(phase = 2, myX = null, myY = null, myZ = null))
        assertIs<FastLeapResult.NoMatch>(res)
        assertTrue(res.failures.single().reason.contains("position unknown"))
    }

    @Test
    fun sevenRegionsAreExactAndInclusive() {
        assertEquals(7, FastLeapDefaults.regions.size)
        for ((_, box) in FastLeapDefaults.regions) {
            assertTrue(SpatialEvaluator.insideAabb(centre(box), box), "centre of region should be inside")
            assertTrue(
                SpatialEvaluator.insideAabb(SpatialEvaluator.Position(box.minX!!, box.minY!!, box.minZ!!), box),
                "min corner is inclusive",
            )
            assertTrue(
                SpatialEvaluator.insideAabb(SpatialEvaluator.Position(box.maxX!!, box.maxY!!, box.maxZ!!), box),
                "max corner is inclusive",
            )
            assertFalse(
                SpatialEvaluator.insideAabb(SpatialEvaluator.Position(box.minX!! - 0.001, box.minY!!, box.minZ!!), box),
                "just outside min x must fail",
            )
            assertFalse(
                SpatialEvaluator.insideAabb(SpatialEvaluator.Position(box.minX!!, box.maxY!! + 0.001, box.minZ!!), box),
                "just outside max y must fail",
            )
        }
    }

    @Test
    fun aabbMembershipIgnoresErrorInterval() {
        // aabb checks take no interval at all, so wide defaults never widen a pad
        val box = FastLeapDefaults.yellow
        val outside = SpatialEvaluator.Position(box.maxX!! + 1.0, box.minY!! + 1.0, box.minZ!! + 1.0)
        assertFalse(SpatialEvaluator.insideAabb(outside, box))
    }

    @Test
    fun invalidIntervalRejectedClearly() {
        val anchors = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val negative = SpatialEvaluator.check(
            SpatialEvaluator.Position(5.0, 0.0, 0.0), anchors["spot"], false,
            PositionCondition(landmarkName = "spot", errorMinDistance = -1.0, errorMaxDistance = 15.0), 3.0, 15.0, "spot",
        )
        assertIs<SpatialEvaluator.Check.Fail>(negative)
        assertTrue(negative.reason.contains("invalid interval"))

        val inverted = SpatialEvaluator.check(
            SpatialEvaluator.Position(5.0, 0.0, 0.0), anchors["spot"], false,
            PositionCondition(landmarkName = "spot", errorMinDistance = 10.0, errorMaxDistance = 4.0), 3.0, 15.0, "spot",
        )
        assertIs<SpatialEvaluator.Check.Fail>(inverted)
        assertTrue(inverted.reason.contains("invalid interval"))

        assertNotNull(SavepointPresets.validateInterval(-1.0, 15.0))
        assertNotNull(SavepointPresets.validateInterval(10.0, 4.0))
        assertNull(SavepointPresets.validateInterval(3.0, 15.0))
    }

    // ---- rules ----

    @Test
    fun deadTargetRejected() {
        val rules = listOf(landmarkRule(landmark = "spot", max = 8.0).copy(
            conditions = listOf(PositionCondition(landmarkName = "spot", mode = SpMode.NEAR)),
        ))
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val res = FastLeapEvaluator.evaluate(
            rules,
            ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER, dead = true, x = 1.0, y = 0.0, z = 0.0)), landmarks = planes),
        )
        assertIs<FastLeapResult.NoMatch>(res)
        assertEquals("no eligible teammate", res.failures.single().reason)
    }

    @Test
    fun soloDungeonDoesNotLeap() {
        val res = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), ctx(teammates = emptyList(), myY = 240.0))
        assertIs<FastLeapResult.NoMatch>(res)
        assertTrue(res.failures.none { it.reason.startsWith("target") })
    }

    @Test
    fun neverLeapsToSelf() {
        val me = tm("Me", DungeonClass.MAGE, x = 1.0, y = 0.0, z = 0.0)
        val res = FastLeapEvaluator.evaluate(
            listOf(landmarkRule(landmark = "spot")),
            ctx(teammates = listOf(me), myName = "Me", landmarks = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))),
        )
        assertIs<FastLeapResult.NoMatch>(res)
        assertTrue(res.failures.all { it.reason == "no eligible teammate" })
    }

    @Test
    fun higherPriorityWinsAndFailureFallsThrough() {
        val rules = listOf(
            FastLeapRule(
                id = "storm_death",
                name = "Storm death",
                priority = 900,
                phase = 2,
                phaseAt = 2,
                target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
                excludeRegions = listOf(
                    FastLeapDefaults.yellow,
                    FastLeapDefaults.purple,
                    FastLeapDefaults.betweenPads,
                ),
            ),
            FastLeapRule(
                id = "yellow",
                name = "Yellow",
                priority = 100,
                phase = 2,
                target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
                selfRegion = FastLeapDefaults.yellow,
            ),
        )
        // standing on the yellow pad: storm_death is excluded, yellow catches the fallthrough
        val onPad = ctx(phase = 2, myX = 30.0, myY = 171.0, myZ = 95.0)
        val res = FastLeapEvaluator.evaluate(rules, onPad)
        assertIs<FastLeapResult.Plan>(res)
        assertEquals("yellow", res.rule.id)

        // the storm rule on its own must report the pad exclusion
        val excluded = FastLeapEvaluator.evaluate(listOf(rules.first()), onPad)
        assertIs<FastLeapResult.NoMatch>(excluded)
        assertTrue(excluded.failures.single().reason.contains("excluded region"))

        // outside every pad: the higher priority storm rule wins
        val inAir = ctx(phase = 2, myX = 70.0, myY = 160.0, myZ = 60.0)
        val storm = FastLeapEvaluator.evaluate(rules, inAir)
        assertIs<FastLeapResult.Plan>(storm)
        assertEquals("storm_death", storm.rule.id)
    }

    @Test
    fun crystalBeatsP1OnCrystalPlatform() {
        val onCrystal = ctx(phase = 1, myX = 82.0, myY = 240.0, myZ = 50.0)
        val res = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), onCrystal)
        assertIs<FastLeapResult.Plan>(res)
        assertEquals("crystal", res.rule.id)

        val anywhereElse = ctx(phase = 1, myX = 8.0, myY = 240.0, myZ = 45.0)
        val p1 = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), anywhereElse)
        assertIs<FastLeapResult.Plan>(p1)
        assertEquals("p1", p1.rule.id)
    }

    @Test
    fun stormFiresOnlyOutsidePads() {
        val onPurple = ctx(phase = 2, myX = 110.0, myY = 171.0, myZ = 95.0)
        val pad = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), onPurple)
        assertIs<FastLeapResult.Plan>(pad)
        assertEquals("purple", pad.rule.id)

        val outside = ctx(phase = 2, myX = 70.0, myY = 160.0, myZ = 60.0)
        val storm = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), outside)
        assertIs<FastLeapResult.Plan>(storm)
        assertEquals("storm_death", storm.rule.id)
    }

    @Test
    fun p4BeatsMiddleOnPlatform() {
        val onMiddle = ctx(phase = 4, myX = 55.0, myY = 70.0, myZ = 75.0)
        val res = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), onMiddle)
        assertIs<FastLeapResult.Plan>(res)
        assertEquals("p4", res.rule.id)

        val offMiddle = ctx(phase = 4, myX = 30.0, myY = 60.0, myZ = 40.0)
        val middle = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), offMiddle)
        assertIs<FastLeapResult.Plan>(middle)
        assertEquals("middle", middle.rule.id)
    }

    @Test
    fun selfAabbAndTargetLandmarkBothRequired() {
        val rule = FastLeapRule(
            id = "yellow",
            name = "Yellow",
            priority = 100,
            phase = 2,
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            selfRegion = FastLeapDefaults.yellow,
            conditions = listOf(PositionCondition(landmarkName = "spot", errorMinDistance = 3.0, errorMaxDistance = 15.0)),
        )
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val farTarget = listOf(tm("Alice", DungeonClass.HEALER, x = 40.0, y = 0.0, z = 0.0))

        val res = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = farTarget, phase = 2, myX = 30.0, myY = 171.0, myZ = 95.0, landmarks = planes),
        )
        assertIs<FastLeapResult.NoMatch>(res)
    }

    @Test
    fun nearestPicksClosestAndEnforcesRadius() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val mates = listOf(
            tm("Alice", DungeonClass.HEALER, x = 6.0, y = 0.0, z = 0.0),
            tm("Bob", DungeonClass.TANK, x = 2.0, y = 0.0, z = 0.0),
        )
        val rule = FastLeapRule(
            id = "nearest",
            name = "Nearest",
            target = TargetSpec(TargetSelectorType.NEAREST_LANDMARK, landmarkName = "spot", radius = 8.0),
        )
        val res = FastLeapEvaluator.evaluate(listOf(rule), ctx(teammates = mates, landmarks = planes))
        assertIs<FastLeapResult.Plan>(res)
        assertEquals("Bob", res.teammate.name)
        assertEquals(2.0, res.measuredDistance!!, 1e-9)

        val outside = FastLeapRule(
            id = "nearest",
            name = "Nearest",
            target = TargetSpec(TargetSelectorType.NEAREST_LANDMARK, landmarkName = "spot", radius = 1.0),
        )
        assertIs<FastLeapResult.NoMatch>(FastLeapEvaluator.evaluate(listOf(outside), ctx(teammates = mates, landmarks = planes)))
    }

    @Test
    fun unknownPhaseDoesNotLeap() {
        val res = FastLeapEvaluator.evaluate(FastLeapDefaults.pack(), ctx(phase = 0, myY = 240.0))
        assertIs<FastLeapResult.NoMatch>(res)
        assertTrue(res.failures.isNotEmpty())
    }

    @Test
    fun f7PhaseAndStageBandsMatchQuoi() {
        assertEquals(0, F7Positions.phaseAt(null))
        assertEquals(1, F7Positions.phaseAt(210.5))
        assertEquals(2, F7Positions.phaseAt(160.0))
        assertEquals(3, F7Positions.phaseAt(120.0))
        assertEquals(4, F7Positions.phaseAt(50.0))
        assertEquals(5, F7Positions.phaseAt(20.0))
        assertEquals(1, F7Positions.stageAt(100.0, 60.0, 3))
        assertEquals(0, F7Positions.stageAt(100.0, 60.0, 2))
    }

    // ---- Savepoint legacy compatibility ----

    @Test
    fun legacyCondKeepsOldRadiusBehaviour() {
        val legacy = SpCond(mode = SpMode.NEAR)
        assertFalse(legacy.hasErrorInterval)
        assertTrue(legacy.distanceOk(7.9, SpCond.DEFAULT_ERROR_MIN, SpCond.DEFAULT_ERROR_MAX))
        assertFalse(legacy.distanceOk(8.1, SpCond.DEFAULT_ERROR_MIN, SpCond.DEFAULT_ERROR_MAX))

        val at = SpCond(mode = SpMode.AT)
        assertTrue(at.distanceOk(1.9, SpCond.DEFAULT_ERROR_MIN, SpCond.DEFAULT_ERROR_MAX))
        assertFalse(at.distanceOk(2.1, SpCond.DEFAULT_ERROR_MIN, SpCond.DEFAULT_ERROR_MAX))
    }

    @Test
    fun legacyPresetJsonRoundTripsWithoutInterval() {
        val json = SavepointPresets.toCond(PresetCondJson(subject = "TARGET", mode = "NEAR", landmark = "spot"))
        assertFalse(json.hasErrorInterval)
        val back = SavepointPresets.toJson(json)
        assertNull(back.errorMinDistance)
        assertNull(back.errorMaxDistance)

        val interval = SavepointPresets.toCond(
            PresetCondJson(subject = "TARGET", mode = "NEAR", errorMinDistance = 3.0, errorMaxDistance = 15.0),
        )
        assertTrue(interval.hasErrorInterval)
        assertTrue(interval.distanceOk(3.0, 3.0, 15.0))
        assertTrue(interval.distanceOk(15.0, 3.0, 15.0))
        assertFalse(interval.distanceOk(2.9, 3.0, 15.0))
        assertEquals(3.0, SavepointPresets.toJson(interval).errorMinDistance)
    }

    @Test
    fun defaultIntervalIsThreeToFifteen() {
        assertEquals(3.0, SpCond.DEFAULT_ERROR_MIN)
        assertEquals(15.0, SpCond.DEFAULT_ERROR_MAX)
    }

    // ---- two stage executor state machine ----

    private fun request(target: String = "Alice"): FastLeapState.Request = FastLeapState.createRequest(
        ruleId = "yellow",
        targetName = target,
        itemId = "SPIRIT_LEAP",
        swapped = false,
        source = FastLeapTriggerSource.CLICK,
        nowMs = 10_000L,
        nowTick = 100L,
        measuredDistance = 5.0,
        axes = "3D",
        t0 = 1_000_000L,
    )

    @Test
    fun requestTrapsRuleAndTarget() {
        val req = request()
        assertEquals(req.id, FastLeapState.request?.id)
        assertEquals("Alice", FastLeapState.request?.targetName)
        assertEquals("yellow", FastLeapState.request?.ruleId)
        assertFalse(FastLeapState.isDispatched())
    }

    @Test
    fun targetAlreadyInMenuExecutesWithoutDelay() {
        request()
        assertTrue(FastLeapState.canExecute("Alice", FastLeapState.generation, true))
    }

    @Test
    fun menuOpenBeforePopulationDoesNotClickEarly() {
        request()
        assertFalse(FastLeapState.canExecute("Alice", FastLeapState.generation, false))
        assertTrue(FastLeapState.canExecute("Alice", FastLeapState.generation, true))
    }

    @Test
    fun duplicateNotificationsDispatchOnce() {
        request()
        assertTrue(FastLeapState.markDispatched(2_000_000L))
        assertFalse(FastLeapState.markDispatched(2_000_000L))
        assertFalse(FastLeapState.canExecute("Alice", FastLeapState.generation, true))
    }

    @Test
    fun goneOrDeadTargetRejectedBeforeExecution() {
        request()
        assertFalse(FastLeapState.revalidate(null))
        assertFalse(FastLeapState.revalidate(tm("Alice", DungeonClass.HEALER, dead = true)))
        assertTrue(FastLeapState.revalidate(tm("Alice", DungeonClass.HEALER)))

        assertFalse(FastLeapState.canExecute("Bob", FastLeapState.generation, true))
        assertFalse(FastLeapState.canExecute("Alice", FastLeapState.generation + 1, true))
    }

    @Test
    fun worldChangeClearsPendingRequest() {
        request()
        val gen = FastLeapState.bumpGeneration()
        assertNull(FastLeapState.request)
        assertFalse(FastLeapState.canExecute("Alice", gen - 1, true))
    }

    @Test
    fun staleGenerationCannotExecute() {
        request()
        val oldGen = FastLeapState.generation
        FastLeapState.bumpGeneration()
        request()
        assertFalse(FastLeapState.canExecute("Alice", oldGen, true))
    }

    @Test
    fun timeoutExpiresPendingRequest() {
        val req = request()
        assertFalse(FastLeapState.expired(req.deadlineTick))
        assertTrue(FastLeapState.expired(req.deadlineTick + 1))
    }

    @Test
    fun debounceNeverDelaysFirstAcceptedTrigger() {
        assertFalse(FastLeapState.debounced(10_000L))
        FastLeapState.markHandled(10_000L)
        assertTrue(FastLeapState.debounced(10_050L))
        assertFalse(FastLeapState.debounced(10_300L))
    }

    @Test
    fun unmatchedClickKeepsDebounceBudgetFree() {
        // nothing is marked handled until a request exists
        assertFalse(FastLeapState.debounced(10_000L))
        assertFalse(FastLeapState.debounced(10_000L))
    }

    @Test
    fun closeIsOneShotAndStartsAtDispatch() {
        request()
        val dispatchNs = 2_000_000_000L
        FastLeapState.markDispatched(dispatchNs)
        val token = FastLeapState.scheduleClose(dispatchNs, 1L, "screen")
        assertNotNull(token)
        assertEquals(dispatchNs + 1_000_000L, token.deadlineNs)
        assertNull(FastLeapState.closeDue(dispatchNs + 999_999L))
        assertNotNull(FastLeapState.closeDue(dispatchNs + 1_000_000L))
        assertNull(FastLeapState.scheduleClose(dispatchNs, 1L, "other screen"))
        assertEquals("screen", token.screen)
    }

    @Test
    fun closeNeverSchedulesWithoutADispatch() {
        request()
        // no teammate click went out, so nothing may close the menu
        assertNull(FastLeapState.scheduleClose(0L, 1L, "screen"))
        assertNull(FastLeapState.closeToken)
        FastLeapState.markDispatched(1_000_000L)
        assertNotNull(FastLeapState.scheduleClose(1_000_000L, 1L, "screen"))
    }

    @Test
    fun staleCloseIsDroppedAfterGenerationChange() {
        request()
        FastLeapState.markDispatched(1_000_000L)
        val gen = FastLeapState.generation
        val token = assertNotNull(FastLeapState.scheduleClose(1_000_000L, 0L, "screen"))
        // the token must carry the live generation and be due now, so the drop below is the bump
        assertEquals(gen, token.generation)
        assertEquals(token, FastLeapState.closeDue(token.deadlineNs))
        FastLeapState.bumpGeneration()
        // both the generation and the token are gone, so a late close cannot fire
        assertEquals(gen + 1, FastLeapState.generation)
        assertNull(FastLeapState.closeToken)
        assertNull(FastLeapState.closeDue(Long.MAX_VALUE))
    }

    @Test
    fun closeDeadlineIgnoresTheMissingTeleportResult() {
        request()
        FastLeapState.markDispatched(5_000_000L)
        assertNotNull(FastLeapState.scheduleClose(5_000_000L, 1L, "screen"))
        // no result, no close confirmation, still due at dispatch + 1 ms
        assertEquals(6_000_000L, FastLeapState.closeDue(6_000_000L)?.deadlineNs)
        // and it stays due while the request has no teleport acknowledgement
        assertNull(FastLeapState.request?.timing?.closedNs)
    }

    @Test
    fun closeOnlyTargetsTheOriginatingScreenInstance() {
        request()
        FastLeapState.markDispatched(3_000_000L)
        val original = Any()
        val token = assertNotNull(FastLeapState.scheduleClose(3_000_000L, 1L, original))
        assertTrue(token.screen === original)
        // a replacement screen is a different object and must not be closed by this token
        assertFalse(token.screen === Any())
    }

    @Test
    fun readyLeapItemInHandAvoidsSwap() {
        val choice = FastLeapState.chooseItem(
            held = FastLeapState.HeldItem(3, "SPIRIT_LEAP"),
            hotbar = listOf(FastLeapState.HeldItem(3, "SPIRIT_LEAP"), FastLeapState.HeldItem(1, "INFINITE_SPIRIT_LEAP")),
            cooldownOf = { 0L },
            leapIds = FastLeapDefaults.leapItemIds,
            respectCooldown = true,
            autoSwap = true,
        )
        assertNotNull(choice)
        assertEquals(3, choice.slot)
        assertFalse(choice.swap)
    }

    @Test
    fun readsLowestCooldownLeapItemForSwap() {
        val choice = FastLeapState.chooseItem(
            held = FastLeapState.HeldItem(0, "SWORD"),
            hotbar = listOf(
                FastLeapState.HeldItem(0, "SWORD"),
                FastLeapState.HeldItem(2, "SPIRIT_LEAP"),
                FastLeapState.HeldItem(5, "INFINITE_SPIRIT_LEAP"),
            ),
            cooldownOf = { id -> if (id == "SPIRIT_LEAP") 90L else 10L },
            leapIds = FastLeapDefaults.leapItemIds,
            respectCooldown = false,
            autoSwap = true,
        )
        assertNotNull(choice)
        assertEquals(5, choice.slot)
        assertEquals("INFINITE_SPIRIT_LEAP", choice.id)
        assertTrue(choice.swap)
    }

    @Test
    fun neverSelectsNonLeapItem() {
        val choice = FastLeapState.chooseItem(
            held = FastLeapState.HeldItem(0, "SWORD"),
            hotbar = listOf(FastLeapState.HeldItem(0, "SWORD"), FastLeapState.HeldItem(1, "BOW")),
            cooldownOf = { 0L },
            leapIds = FastLeapDefaults.leapItemIds,
            respectCooldown = true,
            autoSwap = true,
        )
        assertNull(choice)
    }

    @Test
    fun cooldownBlocksSelectionWhenRespected() {
        val choice = FastLeapState.chooseItem(
            held = FastLeapState.HeldItem(0, "SPIRIT_LEAP"),
            hotbar = listOf(FastLeapState.HeldItem(0, "SPIRIT_LEAP")),
            cooldownOf = { 30L },
            leapIds = FastLeapDefaults.leapItemIds,
            respectCooldown = true,
            autoSwap = true,
        )
        assertNull(choice)
    }

    @Test
    fun timingsAreMonotonicWithoutTeleportConfirmation() {
        val req = request()
        req.timing.t1 = 1_500_000L
        req.timing.t2 = 2_000_000L
        val ms = FastLeapState.elapsedMs(req.timing.t0, req.timing.t2)
        assertNotNull(ms)
        assertTrue(ms > 0.0)
        // no t7 needed: the summary stops at the dispatch marker
        assertTrue(FastLeapState.summary(req).contains("totalToClick"))
    }

    @Test
    fun normalAndPrefireSummariesShareTheSameLatencyKeys() {
        val req = request()
        assertEquals("normal", req.path)
        req.timing.t3 = 1_000_000_000L
        req.timing.t6 = 1_250_000_000L
        req.timing.l0 = req.timing.t3
        req.timing.l1 = req.timing.t6
        req.timing.l2 = 4_000_000_000L
        req.timing.l3 = 4_100_000_000L
        req.timing.l4 = 9_000_000_000L
        val normal = FastLeapState.summary(req)
        assertTrue(normal.contains("path=normal"))
        assertTrue(normal.contains("useItemToClick=250.0"))
        assertTrue(normal.contains("menuResponse=3000.0"))
        assertTrue(normal.contains("clickLead=2750.0"))
        assertTrue(normal.contains("dispatchToClose=2850.0"))
        assertTrue(normal.contains("result=7750.0"))

        // the prefire line must expose the same keys so the two can be compared directly
        val prefire = PrefireState().latency.summary()
        for (key in listOf("useItemToClick=", "menuResponse=", "clickLead=", "dispatchToClose=", "result=")) {
            assertTrue(normal.contains(key), "normal summary missing $key")
            assertTrue(prefire.contains(key), "prefire summary missing $key")
        }
    }

    @Test
    fun regionTypePointAcceptsLandmarkOrRaw() {
        val planes = mapOf("spot" to SpLandmark(1.0, 2.0, 3.0))
        val byName = RegionSpec(type = RegionType.POINT, landmarkName = "spot", radius = 2.0)
        assertTrue(SpatialEvaluator.insideRegion(SpatialEvaluator.Position(1.0, 2.0, 3.0), byName, planes))
        assertFalse(SpatialEvaluator.insideRegion(SpatialEvaluator.Position(5.0, 2.0, 3.0), byName, planes))

        val raw = RegionSpec(type = RegionType.POINT, minX = 1.0, minY = 2.0, minZ = 3.0, radius = 2.0)
        assertTrue(SpatialEvaluator.insideRegion(SpatialEvaluator.Position(1.0, 2.0, 3.0), raw, emptyMap()))
    }

    @Test
    fun disabledRuleNeverMatches() {
        val rule = landmarkRule(landmark = "spot").copy(enabled = false)
        val res = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(landmarks = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))),
        )
        assertIs<FastLeapResult.NoMatch>(res)
        assertEquals("rule is disabled", res.failures.single().reason)
    }

    @Test
    fun doorOpenerRuleOnlyOutsideBoss() {
        val rule = FastLeapRule(
            id = "door",
            name = "Door",
            target = TargetSpec(TargetSelectorType.DOOR_OPENER),
        )
        val inBoss = FastLeapEvaluator.evaluate(listOf(rule), ctx(inBoss = true, doorOpener = "Alice"))
        assertIs<FastLeapResult.NoMatch>(inBoss)

        val clear = FastLeapEvaluator.evaluate(listOf(rule), ctx(inBoss = false, doorOpener = "Alice"))
        assertIs<FastLeapResult.Plan>(clear)
        assertEquals("Alice", clear.teammate.name)

        val unknown = FastLeapEvaluator.evaluate(listOf(rule), ctx(inBoss = false, doorOpener = "Unknown"))
        assertIs<FastLeapResult.NoMatch>(unknown)
    }

    // ---- section 9 filters ----

    @Test
    fun blacklistNeverTargetsListedName() {
        val two = listOf(tm("Alice", DungeonClass.HEALER), tm("Bob", DungeonClass.TANK))
        val rule = FastLeapRule(id = "first", name = "First", target = TargetSpec(TargetSelectorType.FIRST_IN_MENU))

        assertEquals("Alice", FastLeapEvaluator.evaluate(listOf(rule), ctx(teammates = two)).let {
            (it as FastLeapResult.Plan).teammate.name
        })
        val blocked = FastLeapEvaluator.evaluate(listOf(rule), ctx(teammates = two, blacklist = setOf("Alice")))
        assertEquals("Bob", (blocked as FastLeapResult.Plan).teammate.name)
        val all = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = two, blacklist = setOf("alice", "BOB")),
        )
        assertIs<FastLeapResult.NoMatch>(all)
    }

    @Test
    fun whitelistLimitsEligibleTargets() {
        val two = listOf(tm("Alice", DungeonClass.HEALER), tm("Bob", DungeonClass.TANK))
        val rule = FastLeapRule(id = "first", name = "First", target = TargetSpec(TargetSelectorType.FIRST_IN_MENU))

        val onlyBob = FastLeapEvaluator.evaluate(listOf(rule), ctx(teammates = two, whitelist = setOf("bob")))
        assertEquals("Bob", (onlyBob as FastLeapResult.Plan).teammate.name)

        val neither = FastLeapEvaluator.evaluate(listOf(rule), ctx(teammates = two, whitelist = setOf("Carol")))
        assertIs<FastLeapResult.NoMatch>(neither)
    }

    @Test
    fun skipLastLeapedAvoidsRepeat() {
        val two = listOf(tm("Alice", DungeonClass.HEALER), tm("Bob", DungeonClass.TANK))
        val rule = FastLeapRule(id = "first", name = "First", target = TargetSpec(TargetSelectorType.FIRST_IN_MENU))
        val skip = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = two, skipLastLeaped = true, lastLeapedName = "Alice"),
        )
        assertEquals("Bob", (skip as FastLeapResult.Plan).teammate.name)

        // every target already leaped: nothing left, and the default keeps the old behaviour
        val others = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER)), skipLastLeaped = true, lastLeapedName = "Alice"),
        )
        assertIs<FastLeapResult.NoMatch>(others)
        val allowed = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER)), lastLeapedName = "Alice"),
        )
        assertIs<FastLeapResult.Plan>(allowed)
    }

    @Test
    fun collectAllPicksLowestDistance() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val teammates = listOf(
            tm("Alice", DungeonClass.HEALER, x = 9.0, y = 0.0, z = 0.0),
            tm("Bob", DungeonClass.TANK, x = 4.0, y = 0.0, z = 0.0),
        )
        val byName = { name: String ->
            FastLeapRule(
                id = name.lowercase(),
                name = name,
                priority = if (name == "Alice") 200 else 100,
                target = TargetSpec(TargetSelectorType.NAME, targetName = name),
                conditions = listOf(
                    PositionCondition(landmarkName = "spot", errorMinDistance = 3.0, errorMaxDistance = 15.0),
                ),
            )
        }
        val rules = listOf(byName("Alice"), byName("Bob"))

        // first match wins by default, even when the other target is closer
        val first = FastLeapEvaluator.evaluate(rules, ctx(teammates = teammates, landmarks = planes))
        assertEquals("Alice", (first as FastLeapResult.Plan).teammate.name)

        val best = FastLeapEvaluator.evaluate(rules, ctx(teammates = teammates, landmarks = planes, collectAll = true))
        assertEquals("Bob", (best as FastLeapResult.Plan).teammate.name)
        assertEquals(4.0, best.measuredDistance!!, 0.001)
    }

    @Test
    fun caseSensitiveModeStopsNormalizingNames() {
        val teammate = tm("Alice", DungeonClass.HEALER)
        val rule = landmarkRule(landmark = "spot").copy(
            conditions = listOf(PositionCondition(landmarkName = "spot", radius = 2.0)),
        )
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val withPos = listOf(teammate.copy(x = 1.0, y = 0.0, z = 0.0))

        val insensitive = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = withPos, landmarks = planes, blacklist = setOf("ALICE")),
        )
        assertIs<FastLeapResult.NoMatch>(insensitive)

        val sensitive = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = withPos, landmarks = planes, blacklist = setOf("ALICE"), caseInsensitive = false),
        )
        assertIs<FastLeapResult.Plan>(sensitive)
    }

    @Test
    fun skipNotInMenuOnlyFiltersWhileMenuIsOpen() {
        val offMenu = tm("Alice", DungeonClass.HEALER, x = 1.0, y = 0.0, z = 0.0).copy(inMenu = false)
        val rule = landmarkRule(landmark = "spot").copy(
            conditions = listOf(PositionCondition(landmarkName = "spot", radius = 2.0)),
        )
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))

        val preMenu = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = listOf(offMenu), landmarks = planes, skipNotInMenu = true),
        )
        assertIs<FastLeapResult.Plan>(preMenu)

        val inMenu = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = listOf(offMenu), landmarks = planes, skipNotInMenu = true, menuOpen = true),
        )
        assertIs<FastLeapResult.NoMatch>(inMenu)
    }

    @Test
    fun fallbackLastLeapedRepeatsPreviousTarget() {
        val two = listOf(tm("Alice", DungeonClass.HEALER), tm("Bob", DungeonClass.TANK))
        val res = FastLeapEvaluator.evaluate(
            emptyList(),
            ctx(teammates = two, lastLeapedName = "Bob", fallbackLastLeaped = true),
            applyFallback = true,
        )
        assertEquals("Bob", (res as FastLeapResult.Plan).teammate.name)
        assertEquals("fallback", res.rule.id)

        val missing = FastLeapEvaluator.evaluate(
            emptyList(),
            ctx(teammates = two, lastLeapedName = "Carol", fallbackLastLeaped = true),
            applyFallback = true,
        )
        assertIs<FastLeapResult.NoMatch>(missing)
    }

    @Test
    fun warningThrottleAllowsEachReasonOncePerWindow() {
        val throttle = WarningThrottle(windowMs = 5_000L)
        assertTrue(throttle.allow("rule a: no eligible teammate", 1_000L))
        assertFalse(throttle.allow("rule a: no eligible teammate", 1_100L))
        // a different reason keeps its own window
        assertTrue(throttle.allow("rule b: missing landmark acacia", 1_200L))
        assertFalse(throttle.allow("rule a: no eligible teammate", 5_900L))
        assertTrue(throttle.allow("rule a: no eligible teammate", 6_100L))
    }

    // ---- acacia template ----

    @Test
    fun acaciaTemplateShipsDisabledAndNeedsTheLandmark() {
        val pack = FastLeapDefaults.pack()
        val rule = pack.firstOrNull { it.id == "mage-at-acacia" }
        assertNotNull(rule)
        assertFalse(rule.enabled)
        // ships without coordinates, the landmark itself is never created
        assertFalse(rule.conditions.single().hasCustomPoint)
        assertEquals("acacia", rule.conditions.single().landmarkName)
        assertEquals(3.0, rule.conditions.single().errorMinDistance)
        assertEquals(15.0, rule.conditions.single().errorMaxDistance)
        assertEquals(TargetSelectorType.CLASS, rule.target.type)
        assertEquals(DungeonClass.MAGE, rule.target.targetClass)

        val mage = tm("Alice", DungeonClass.MAGE, x = 0.0, y = 0.0, z = 0.0)
        // shipped defaults never create the landmark
        val off = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = listOf(mage), landmarks = mapOf("acacia" to SpLandmark(0.0, 0.0, 0.0))),
        )
        assertIs<FastLeapResult.NoMatch>(off)
        assertEquals("rule is disabled", off.failures.single().reason)

        val onMissing = FastLeapEvaluator.evaluate(listOf(rule.copy(enabled = true)), ctx(teammates = listOf(mage)))
        assertIs<FastLeapResult.NoMatch>(onMissing)
        assertTrue(onMissing.failures.single().reason.contains("acacia"))

        val onCaptured = FastLeapEvaluator.evaluate(
            listOf(rule.copy(enabled = true)),
            ctx(teammates = listOf(mage), landmarks = mapOf("acacia" to SpLandmark(6.0, 0.0, 0.0))),
        )
        assertEquals("Alice", (onCaptured as FastLeapResult.Plan).teammate.name)
    }

    @Test
    fun defaultRadiusIsSixAndRulesCanKeepTheirOwn() {
        assertEquals(6.0, DEFAULT_RADIUS)
        assertNull(TargetSpec(TargetSelectorType.FIRST_IN_MENU).radius)

        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val rule = FastLeapRule(
            id = "near",
            name = "Near",
            target = TargetSpec(TargetSelectorType.NEAREST_LANDMARK, landmarkName = "spot"),
        )
        val inside = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER, x = 5.5, y = 0.0, z = 0.0)), landmarks = planes),
        )
        assertIs<FastLeapResult.Plan>(inside)

        val outside = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER, x = 6.5, y = 0.0, z = 0.0)), landmarks = planes),
        )
        assertIs<FastLeapResult.NoMatch>(outside)
    }

    // ---- configured default radius reaches position conditions ----

    @Test
    fun configuredDefaultRadiusAppliesToPositionConditions() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        // no radius and no mode: the module default must decide, not the hardcoded constant
        val rule = landmarkRule("near", "spot")
        val atSeven = listOf(tm("Alice", DungeonClass.HEALER, x = 7.0, y = 0.0, z = 0.0))

        assertIs<FastLeapResult.NoMatch>(FastLeapEvaluator.evaluate(listOf(rule), ctx(atSeven, landmarks = planes)))
        assertIs<FastLeapResult.Plan>(
            FastLeapEvaluator.evaluate(listOf(rule), ctx(atSeven, landmarks = planes, defaultRadius = 8.0)),
        )
    }

    @Test
    fun perConditionRadiusStillWinsOverTheDefault() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val cond = PositionCondition(subject = SpSubject.TARGET, landmarkName = "spot", radius = 2.0)
        val rule = FastLeapRule(
            id = "near",
            name = "near",
            target = TargetSpec(TargetSelectorType.FIRST_IN_MENU),
            conditions = listOf(cond),
        )
        val atFive = listOf(tm("Alice", DungeonClass.HEALER, x = 5.0, y = 0.0, z = 0.0))
        assertIs<FastLeapResult.NoMatch>(
            FastLeapEvaluator.evaluate(listOf(rule), ctx(atFive, landmarks = planes, defaultRadius = 12.0)),
        )
        // the check itself honours the condition radius with a wide default passed in
        val direct = SpatialEvaluator.check(
            SpatialEvaluator.Position(5.0, 0.0, 0.0), planes["spot"], false, cond, 0.0, Double.MAX_VALUE, "spot", 12.0,
        )
        assertIs<SpatialEvaluator.Check.Fail>(direct)
    }

    @Test
    fun pointRegionUsesTheConfiguredDefaultRadius() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val region = RegionSpec(type = RegionType.POINT, landmarkName = "spot")
        val atSeven = SpatialEvaluator.Position(7.0, 0.0, 0.0)
        assertFalse(SpatialEvaluator.insideRegion(atSeven, region, planes))
        assertTrue(SpatialEvaluator.insideRegion(atSeven, region, planes, 8.0))
    }

    // ---- Ignore self is honoured end to end ----

    @Test
    fun ignoreSelfOffMakesYourselfSelectable() {
        val self = tm("Me", DungeonClass.MAGE)
        val nameRule = FastLeapRule(
            id = "self",
            name = "Self",
            target = TargetSpec(TargetSelectorType.NAME, targetName = "Me"),
        )
        val classRule = FastLeapRule(
            id = "selfclass",
            name = "Self class",
            target = TargetSpec(TargetSelectorType.CLASS, targetClass = DungeonClass.MAGE),
        )

        // default ON: the evaluator drops self even if the caller left it in the roster
        assertIs<FastLeapResult.NoMatch>(FastLeapEvaluator.evaluate(listOf(nameRule), ctx(listOf(self), myName = "Me")))
        assertIs<FastLeapResult.NoMatch>(FastLeapEvaluator.evaluate(listOf(classRule), ctx(listOf(self), myName = "Me")))

        // OFF: self is a legitimate target
        val byName = FastLeapEvaluator.evaluate(listOf(nameRule), ctx(listOf(self), myName = "Me", ignoreSelf = false))
        assertEquals("Me", (byName as FastLeapResult.Plan).teammate.name)
        val byClass = FastLeapEvaluator.evaluate(listOf(classRule), ctx(listOf(self), myName = "Me", ignoreSelf = false))
        assertEquals("Me", (byClass as FastLeapResult.Plan).teammate.name)
    }

    @Test
    fun preferredNameStillResolvesAsANameTarget() {
        val rule = FastLeapRule(id = "n", name = "n", target = TargetSpec(TargetSelectorType.NAME))
        // NAME fallback must not be self-excluded by its own preferredName
        val res = FastLeapEvaluator.evaluate(
            listOf(rule),
            ctx(listOf(tm("Alice", DungeonClass.HEALER)), preferredName = "Alice"),
        )
        assertEquals("Alice", (res as FastLeapResult.Plan).teammate.name)
    }

    @Test
    fun lastLeapedFallbackStillFallsBackToTheLandmark() {
        val planes = mapOf("spot" to SpLandmark(0.0, 0.0, 0.0))
        val res = FastLeapEvaluator.evaluate(
            emptyList(),
            ctx(
                teammates = listOf(tm("Bob", DungeonClass.TANK, x = 1.0, y = 0.0, z = 0.0)),
                lastLeapedName = "Gone",
                fallbackLastLeaped = true,
                fallbackNearestLandmark = "spot",
                landmarks = planes,
            ),
            applyFallback = true,
        )
        assertEquals("Bob", (res as FastLeapResult.Plan).teammate.name)
        assertEquals("Fallback nearest to spot", res.rule.name)
    }

    @Test
    fun lastLeapedFallbackStillWorksWhenSkipLastLeapedIsOn() {
        val res = FastLeapEvaluator.evaluate(
            emptyList(),
            ctx(
                teammates = listOf(tm("Bob", DungeonClass.TANK)),
                lastLeapedName = "Bob",
                skipLastLeaped = true,
                fallbackLastLeaped = true,
            ),
            applyFallback = true,
        )
        // the explicit repeat must not be filtered out by the setting it exists to override
        assertEquals("Bob", (res as FastLeapResult.Plan).teammate.name)
        assertEquals("Fallback last leaped", res.rule.name)
    }
}
