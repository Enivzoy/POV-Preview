package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import kotlin.math.sqrt

enum class SpStage(val id: String, val steps: Int) {
    P1("P1", 3),
    P2_GY("P2.GY", 5),
    P2_OLDPY("P2.OLDPY", 2),
    P2_NEWPY("P2.NEWPY", 2),
    PREDEV("PREDEV", 1),
    P3_S1_ENTRY("P3.S1.ENTRY", 1),
    P3_S1("P3.S1", 2),
    P3_S2("P3.S2", 2),
    P3_S3("P3.S3", 2),
    P3_S4("P3.S4", 3),
    P4("P4", 3),
    P5("P5", 3),
}

data class SpStep(val stage: SpStage, val step: Int) {
    val key: String get() = "${stage.id}/$step"
}

enum class SpTgt(val label: String) {
    NONE("None"),
    BERSERK("Berserk"),
    ARCHER("Archer"),
    MAGE("Mage"),
    TANK("Tank"),
    HEALER("Healer"),
    NEAREST("Nearest"),
    FIRST("First"),
}

enum class SpSubject(val label: String) {
    TARGET("target"),
    SELF("you"),
}

enum class SpMode(val label: String, val defaultRadius: Double) {
    AT("at", 2.0),
    NEAR("near", 8.0),
    IN("in", 8.0),
}

enum class SpOnFail {
    WAIT,
    SKIP,
    ANY_AFTER,
}

enum class SpObjective(val label: String) {
    PHASE("phase"),
    TERMINALS("terminals"),
    LEVERS("levers"),
    DEVICES("devices"),
    GATE("gate"),
}

data class SpCond(
    val subject: SpSubject = SpSubject.TARGET,
    val mode: SpMode = SpMode.NEAR,
    val landmark: String = "",
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val radius: Double = 0.0,
    val holdMs: Long = 400,
    val anyAfterMs: Long = 0,
    val onFail: SpOnFail = SpOnFail.WAIT,
    val objective: SpObjective? = null,
    val objectiveMin: Int = 0,
) {
    val effectiveRadius: Double get() = if (radius > 0.0) radius else mode.defaultRadius
    val hasRawPos: Boolean get() = x != null && y != null && z != null

    fun describe(): String {
        val parts = ArrayList<String>(2)
        objective?.let { parts += "${it.label}>=${objectiveMin}" }
        if (hasRawPos) parts += "${subject.label} at ($x,$y,$z) <=${effectiveRadius.toInt()}m"
        else if (landmark.isNotBlank()) parts += "${subject.label} ${mode.label} '$landmark' <=${effectiveRadius.toInt()}m"
        if (parts.isEmpty()) return "wait ${holdMs}ms"
        return parts.joinToString(" and ")
    }
}

data class SpRule(
    val target: SpTgt,
    val landmark: String = "",
    val radius: Double = 64.0,
    val cond: SpCond? = null,
)

data class SpLandmark(val x: Double, val y: Double, val z: Double)

data class SpTmate(
    val name: String,
    val clazz: DungeonClass,
    val dead: Boolean,
    val inMenu: Boolean,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
) {
    fun distTo(lm: SpLandmark): Double? {
        val px = x ?: return null
        val py = y ?: return null
        val pz = z ?: return null
        val dx = px - lm.x
        val dy = py - lm.y
        val dz = pz - lm.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

data class SpCell(val stage: SpStage, val step: Int, val clazz: DungeonClass)

data class SpCtx(
    val steps: List<SpStep>,
    val consumed: Set<SpStep>,
    val teammates: List<SpTmate>,
    val myClass: DungeonClass?,
    val myX: Double? = null,
    val myY: Double? = null,
    val myZ: Double? = null,
    val timings: Map<String, Long> = emptyMap(),
    val landmarks: Map<String, SpLandmark> = emptyMap(),
    val phase: Int = 0,
    val terminalsDone: Int = 0,
    val leversDone: Int = 0,
    val devicesDone: Int = 0,
    val gateOpen: Boolean = false,
)

sealed interface SpPlan {
    data object Idle : SpPlan
    data class Leap(val step: SpStep, val target: SpTmate, val label: String) : SpPlan
    data class Hold(val step: SpStep, val target: SpTmate, val key: String, val needMs: Long, val heldMs: Long, val label: String) : SpPlan
    data class Wait(val step: SpStep?, val reason: String, val target: SpTmate? = null) : SpPlan
    data class SkipStep(val step: SpStep, val reason: String) : SpPlan
}

object SavepointEngine {

    val LEAP_CLASSES = listOf(
        DungeonClass.BERSERK,
        DungeonClass.ARCHER,
        DungeonClass.MAGE,
        DungeonClass.TANK,
        DungeonClass.HEALER,
    )

    fun armedStep(steps: List<SpStep>, consumed: Set<SpStep>, rules: Map<SpCell, SpRule>, myClass: DungeonClass?): SpStep? {
        if (myClass == null) return null
        for (sstep in steps) {
            if (sstep in consumed) continue
            val rule = rules[SpCell(sstep.stage, sstep.step, myClass)] ?: continue
            if (rule.target != SpTgt.NONE) return sstep
        }
        return null
    }

    fun decide(ctx: SpCtx, rules: Map<SpCell, SpRule>): SpPlan {
        val myClass = ctx.myClass ?: return SpPlan.Wait(null, "your class is unknown")

        for (sstep in ctx.steps) {
            if (sstep in ctx.consumed) continue
            val rule = rules[SpCell(sstep.stage, sstep.step, myClass)] ?: continue
            if (rule.target == SpTgt.NONE) continue

            val target = when (val res = resolve(rule, ctx)) {
                is Resolved.Found -> res.tm
                is Resolved.Missing -> return SpPlan.Wait(sstep, res.reason)
            }
            val label = "${sstep.stage.id} step ${sstep.step} -> ${target.name} (${target.clazz.name.lowercase()})"
            val cond = rule.cond ?: return SpPlan.Leap(sstep, target, label)

            cond.objective?.let { obj ->
                if (!objectiveMet(obj, cond.objectiveMin, ctx)) {
                    return fail(cond, sstep, "${obj.label} is ${objectiveValue(obj, ctx)}, needs ${cond.objectiveMin}", ctx, target, label)
                }
            }

            val who: String
            val px: Double?
            val py: Double?
            val pz: Double?
            if (cond.subject == SpSubject.SELF) {
                who = "you"
                px = ctx.myX
                py = ctx.myY
                pz = ctx.myZ
            } else {
                who = target.name
                px = target.x
                py = target.y
                pz = target.z
            }
            if (px == null || py == null || pz == null) {
                return fail(cond, sstep, "$who position unknown", ctx, target, label)
            }

            val lm: SpLandmark
            if (cond.hasRawPos) {
                lm = SpLandmark(cond.x!!, cond.y!!, cond.z!!)
            } else {
                if (cond.landmark.isBlank()) {
                    return fail(cond, sstep, "no landmark or coordinates configured", ctx, target, label)
                }
                val named = ctx.landmarks[cond.landmark]
                if (named == null) {
                    return fail(cond, sstep, "landmark '${cond.landmark}' is missing (capture it with /leaputils landmark)", ctx, target, label)
                }
                lm = named
            }

            val dx = px - lm.x
            val dy = py - lm.y
            val dz = pz - lm.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > cond.effectiveRadius) {
                return fail(cond, sstep, "${target.clazz.name.lowercase()} ${target.name} not within ${cond.effectiveRadius.toInt()}m of ${cond.describe()}", ctx, target, label)
            }

            val key = "hold:${sstep.key}/${target.name}"
            val held = ctx.timings[key] ?: 0L
            return if (held >= cond.holdMs) {
                SpPlan.Leap(sstep, target, label)
            } else {
                SpPlan.Hold(sstep, target, key, cond.holdMs, held, "$label while ${cond.describe()}")
            }
        }
        return SpPlan.Idle
    }

    private fun objectiveValue(obj: SpObjective, ctx: SpCtx): Int = when (obj) {
        SpObjective.PHASE -> ctx.phase
        SpObjective.TERMINALS -> ctx.terminalsDone
        SpObjective.LEVERS -> ctx.leversDone
        SpObjective.DEVICES -> ctx.devicesDone
        SpObjective.GATE -> if (ctx.gateOpen) 1 else 0
    }

    private fun objectiveMet(obj: SpObjective, min: Int, ctx: SpCtx): Boolean =
        if (obj == SpObjective.GATE) ctx.gateOpen else objectiveValue(obj, ctx) >= min

    private fun fail(cond: SpCond, sstep: SpStep, reason: String, ctx: SpCtx, target: SpTmate, label: String): SpPlan =
        when (cond.onFail) {
            SpOnFail.WAIT -> SpPlan.Wait(sstep, "$reason (${cond.describe()})", target)
            SpOnFail.SKIP -> SpPlan.SkipStep(sstep, "$reason (${cond.describe()})")
            SpOnFail.ANY_AFTER -> {
                val waited = ctx.timings["wait:${sstep.key}"] ?: 0L
                if (waited >= cond.anyAfterMs) SpPlan.Leap(sstep, target, label)
                else SpPlan.Wait(sstep, "$reason (any leap after ${cond.anyAfterMs}ms)", target)
            }
        }

    private sealed interface Resolved {
        data class Found(val tm: SpTmate) : Resolved
        data class Missing(val reason: String) : Resolved
    }

    private fun resolve(rule: SpRule, ctx: SpCtx): Resolved =
        when (rule.target) {
            SpTgt.NONE -> Resolved.Missing("no target")
            SpTgt.NEAREST -> {
                if (rule.landmark.isBlank()) return Resolved.Missing("Nearest has no landmark configured for this stage (capture one with /leaputils landmark)")
                val lm = ctx.landmarks[rule.landmark]
                    ?: return Resolved.Missing("landmark '${rule.landmark}' is missing (add it with /leaputils landmark)")
                val cands = ctx.teammates.filter { !it.dead && it.inMenu && it.x != null }
                if (cands.isEmpty()) return Resolved.Missing("no leapable teammate with a known position")
                val best = cands.minByOrNull { it.distTo(lm) ?: Double.MAX_VALUE } ?: return Resolved.Missing("no leapable teammate")
                val dist = best.distTo(lm) ?: return Resolved.Missing("no leapable teammate with a known position")
                if (dist > rule.radius) {
                    Resolved.Missing("no teammate within ${rule.radius.toInt()} blocks of '${rule.landmark}'")
                } else {
                    Resolved.Found(best)
                }
            }
            SpTgt.FIRST -> {
                val cands = ctx.teammates.filter { !it.dead && it.inMenu }
                if (cands.isEmpty()) Resolved.Missing("no leapable teammate in the menu")
                else Resolved.Found(cands.first())
            }
            else -> {
                val cls = when (rule.target) {
                    SpTgt.BERSERK -> DungeonClass.BERSERK
                    SpTgt.ARCHER -> DungeonClass.ARCHER
                    SpTgt.MAGE -> DungeonClass.MAGE
                    SpTgt.TANK -> DungeonClass.TANK
                    SpTgt.HEALER -> DungeonClass.HEALER
                    else -> return Resolved.Missing("no target")
                }
                val cands = ctx.teammates.filter { it.clazz == cls && !it.dead && it.inMenu }
                when {
                    cands.isEmpty() -> Resolved.Missing("${rule.target.label} is dead or not in the leap menu")
                    cands.size > 1 -> Resolved.Missing("two ${rule.target.label}s are in the leap menu; pick one manually")
                    else -> Resolved.Found(cands.first())
                }
            }
        }
}
