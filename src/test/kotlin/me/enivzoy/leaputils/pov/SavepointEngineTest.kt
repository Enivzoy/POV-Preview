package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SavepointEngineTest {

    private val rules = mapOf(
        SpCell(SpStage.P2_GY, 1, DungeonClass.MAGE) to SpRule(SpTgt.HEALER),
        SpCell(SpStage.P2_GY, 2, DungeonClass.MAGE) to SpRule(SpTgt.TANK),
        SpCell(SpStage.P4, 1, DungeonClass.MAGE) to SpRule(SpTgt.NEAREST, "necron_spot", 64.0),
    )

    private fun tm(
        name: String,
        clazz: DungeonClass,
        dead: Boolean = false,
        inMenu: Boolean = true,
        x: Double? = null,
        y: Double? = null,
        z: Double? = null,
    ) = SpTmate(name, clazz, dead, inMenu, x, y, z)

    private fun ctx(
        stages: List<SpStage> = listOf(SpStage.P2_GY),
        consumed: Set<SpStep> = emptySet(),
        teammates: List<SpTmate> = listOf(tm("Alice", DungeonClass.HEALER)),
        myClass: DungeonClass? = DungeonClass.MAGE,
        timings: Map<String, Long> = emptyMap(),
        landmarks: Map<String, SpLandmark> = emptyMap(),
        myX: Double? = null,
        myY: Double? = null,
        myZ: Double? = null,
    ) = SpCtx(stages, consumed, teammates, myClass, myX, myY, myZ, timings, landmarks)

    @Test
    fun idleWhenNoStages() {
        assertEquals(SpPlan.Idle, SavepointEngine.decide(ctx(stages = emptyList()), rules))
    }

    @Test
    fun idleWhenNoCellForClass() {
        assertEquals(SpPlan.Idle, SavepointEngine.decide(ctx(myClass = DungeonClass.TANK), rules))
    }

    @Test
    fun waitsWhenOwnClassUnknown() {
        val plan = SavepointEngine.decide(ctx(myClass = null), rules)
        assertIs<SpPlan.Wait>(plan)
        assertNull(plan.step)
    }

    @Test
    fun leapsToFirstArmedStep() {
        val plan = SavepointEngine.decide(ctx(), rules)
        assertIs<SpPlan.Leap>(plan)
        assertEquals(SpStep(SpStage.P2_GY, 1), plan.step)
        assertEquals("Alice", plan.target.name)
    }

    @Test
    fun consumedStepsAreSkipped() {
        val teammates = listOf(tm("Alice", DungeonClass.HEALER), tm("Bob", DungeonClass.TANK))
        val plan = SavepointEngine.decide(ctx(teammates = teammates, consumed = setOf(SpStep(SpStage.P2_GY, 1))), rules)
        assertIs<SpPlan.Leap>(plan)
        assertEquals(SpStep(SpStage.P2_GY, 2), plan.step)
        assertEquals("Bob", plan.target.name)
    }

    @Test
    fun deadTargetWaits() {
        val plan = SavepointEngine.decide(ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER, dead = true))), rules)
        assertIs<SpPlan.Wait>(plan)
        assertEquals(SpStep(SpStage.P2_GY, 1), plan.step)
    }

    @Test
    fun absentFromMenuWaits() {
        val plan = SavepointEngine.decide(ctx(teammates = listOf(tm("Alice", DungeonClass.HEALER, inMenu = false))), rules)
        assertIs<SpPlan.Wait>(plan)
    }

    @Test
    fun duplicateClassWaitsAndNeverSubstitutes() {
        val teammates = listOf(tm("Alice", DungeonClass.HEALER), tm("Bob", DungeonClass.HEALER))
        val plan = SavepointEngine.decide(ctx(teammates = teammates), rules)
        assertIs<SpPlan.Wait>(plan)
    }

    @Test
    fun nearestPicksClosestTeammate() {
        val landmarks = mapOf("necron_spot" to SpLandmark(54.0, 65.0, 76.0))
        val teammates = listOf(
            tm("Far", DungeonClass.TANK, x = 100.0, y = 65.0, z = 76.0),
            tm("Near", DungeonClass.ARCHER, x = 55.0, y = 65.0, z = 76.0),
        )
        val plan = SavepointEngine.decide(ctx(stages = listOf(SpStage.P4), teammates = teammates, landmarks = landmarks), rules)
        assertIs<SpPlan.Leap>(plan)
        assertEquals("Near", plan.target.name)
    }

    @Test
    fun missingLandmarkWaits() {
        val plan = SavepointEngine.decide(ctx(stages = listOf(SpStage.P4)), rules)
        assertIs<SpPlan.Wait>(plan)
    }

    @Test
    fun nearestRespectsRadius() {
        val landmarks = mapOf("necron_spot" to SpLandmark(54.0, 65.0, 76.0))
        val teammates = listOf(tm("Far", DungeonClass.TANK, x = 200.0, y = 65.0, z = 76.0))
        val limited = rules + mapOf(
            SpCell(SpStage.P4, 1, DungeonClass.MAGE) to SpRule(SpTgt.NEAREST, "necron_spot", 5.0)
        )
        val plan = SavepointEngine.decide(ctx(stages = listOf(SpStage.P4), teammates = teammates, landmarks = landmarks), limited)
        assertIs<SpPlan.Wait>(plan)
    }

    @Test
    fun condHoldRequiresHoldMsBeforeLeap() {
        val condRules = mapOf(
            SpCell(SpStage.P3_S2, 1, DungeonClass.TANK) to SpRule(
                SpTgt.BERSERK,
                cond = SpCond(SpSubject.TARGET, SpMode.NEAR, "s2_t2", 8.0, holdMs = 400)
            )
        )
        val landmarks = mapOf("s2_t2" to SpLandmark(0.0, 0.0, 0.0))
        val teammates = listOf(tm("Bob", DungeonClass.BERSERK, x = 3.0, y = 0.0, z = 0.0))
        val waiting = SavepointEngine.decide(
            ctx(stages = listOf(SpStage.P3_S2), myClass = DungeonClass.TANK, teammates = teammates, landmarks = landmarks),
            condRules
        )
        assertIs<SpPlan.Hold>(waiting)
        assertEquals(400L, waiting.needMs)

        val held = SavepointEngine.decide(
            ctx(
                stages = listOf(SpStage.P3_S2),
                myClass = DungeonClass.TANK,
                teammates = teammates,
                landmarks = landmarks,
                timings = mapOf(waiting.key to 400L)
            ),
            condRules
        )
        assertIs<SpPlan.Leap>(held)
        assertEquals("Bob", held.target.name)
    }

    @Test
    fun condFailWaitsByDefault() {
        val condRules = mapOf(
            SpCell(SpStage.P3_S2, 1, DungeonClass.TANK) to SpRule(
                SpTgt.BERSERK,
                cond = SpCond(SpSubject.TARGET, SpMode.NEAR, "s2_t2", 8.0)
            )
        )
        val landmarks = mapOf("s2_t2" to SpLandmark(0.0, 0.0, 0.0))
        val teammates = listOf(tm("Bob", DungeonClass.BERSERK, x = 50.0, y = 0.0, z = 0.0))
        val plan = SavepointEngine.decide(
            ctx(stages = listOf(SpStage.P3_S2), myClass = DungeonClass.TANK, teammates = teammates, landmarks = landmarks),
            condRules
        )
        assertIs<SpPlan.Wait>(plan)
    }

    @Test
    fun condFailSkipReturnsSkipStep() {
        val condRules = mapOf(
            SpCell(SpStage.P3_S2, 1, DungeonClass.TANK) to SpRule(
                SpTgt.BERSERK,
                cond = SpCond(SpSubject.TARGET, SpMode.NEAR, "s2_t2", 8.0, onFail = SpOnFail.SKIP)
            )
        )
        val landmarks = mapOf("s2_t2" to SpLandmark(0.0, 0.0, 0.0))
        val teammates = listOf(tm("Bob", DungeonClass.BERSERK, x = 50.0, y = 0.0, z = 0.0))
        val plan = SavepointEngine.decide(
            ctx(stages = listOf(SpStage.P3_S2), myClass = DungeonClass.TANK, teammates = teammates, landmarks = landmarks),
            condRules
        )
        assertIs<SpPlan.SkipStep>(plan)
        assertEquals(SpStep(SpStage.P3_S2, 1), plan.step)
    }

    @Test
    fun condFailAnyAfterLeapsOnceWaited() {
        val condRules = mapOf(
            SpCell(SpStage.P3_S2, 1, DungeonClass.TANK) to SpRule(
                SpTgt.BERSERK,
                cond = SpCond(SpSubject.TARGET, SpMode.NEAR, "s2_t2", 8.0, onFail = SpOnFail.ANY_AFTER, anyAfterMs = 1000)
            )
        )
        val landmarks = mapOf("s2_t2" to SpLandmark(0.0, 0.0, 0.0))
        val teammates = listOf(tm("Bob", DungeonClass.BERSERK, x = 50.0, y = 0.0, z = 0.0))
        val base = { timings: Map<String, Long> ->
            ctx(stages = listOf(SpStage.P3_S2), myClass = DungeonClass.TANK, teammates = teammates, landmarks = landmarks, timings = timings)
        }
        assertIs<SpPlan.Wait>(SavepointEngine.decide(base(emptyMap()), condRules))
        assertIs<SpPlan.Leap>(SavepointEngine.decide(base(mapOf("wait:P3.S2/1" to 1000L)), condRules))
    }

    @Test
    fun selfSubjectUsesOwnPosition() {
        val condRules = mapOf(
            SpCell(SpStage.P3_S1, 1, DungeonClass.HEALER) to SpRule(
                SpTgt.BERSERK,
                cond = SpCond(SpSubject.SELF, SpMode.AT, "s1_spot", 2.0, holdMs = 0)
            )
        )
        val landmarks = mapOf("s1_spot" to SpLandmark(10.0, 65.0, 10.0))
        val teammates = listOf(tm("Bob", DungeonClass.BERSERK))
        val at = SavepointEngine.decide(
            ctx(
                stages = listOf(SpStage.P3_S1),
                myClass = DungeonClass.HEALER,
                teammates = teammates,
                landmarks = landmarks,
                myX = 10.5, myY = 65.0, myZ = 10.5
            ),
            condRules
        )
        assertIs<SpPlan.Leap>(at)

        val away = SavepointEngine.decide(
            ctx(
                stages = listOf(SpStage.P3_S1),
                myClass = DungeonClass.HEALER,
                teammates = teammates,
                landmarks = landmarks,
                myX = 40.0, myY = 65.0, myZ = 40.0
            ),
            condRules
        )
        assertIs<SpPlan.Wait>(away)
    }

    @Test
    fun armedStepFindsFirstArmed() {
        assertEquals(SpStep(SpStage.P2_GY, 1), SavepointEngine.armedStep(listOf(SpStage.P2_GY), emptySet(), rules, DungeonClass.MAGE))
        assertEquals(
            SpStep(SpStage.P2_GY, 2),
            SavepointEngine.armedStep(listOf(SpStage.P2_GY), setOf(SpStep(SpStage.P2_GY, 1)), rules, DungeonClass.MAGE)
        )
        assertNull(SavepointEngine.armedStep(listOf(SpStage.P2_GY), emptySet(), rules, DungeonClass.TANK))
        assertNull(SavepointEngine.armedStep(listOf(SpStage.P2_GY), emptySet(), rules, null))
    }
}
