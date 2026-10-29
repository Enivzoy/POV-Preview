package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import kotlin.math.sqrt

enum class SpStage(val id: String, val steps: Int) {
    P1_L("P1.L", 1),
    P1_R("P1.R", 1),
    P2_GY("P2.GY", 7),
    P2_OLDPY("P2.OLDPY", 2),
    P2_NEWPY("P2.NEWPY", 2),
    PREDEV("PREDEV", 1),
    P3_S1("P3.S1", 2),
    P3_S2("P3.S2", 1),
    P3_S3("P3.S3", 2),
    P3_S4("P3.S4", 1),
    P4("P4", 1),
    P5("P5", 1),
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

data class SpCond(
    val subject: SpSubject = SpSubject.TARGET,
    val mode: SpMode = SpMode.NEAR,
    val landmark: String = "",
    val radius: Double = 0.0,
    val holdMs: Long = 400,
    val anyAfterMs: Long = 0,
    val onFail: SpOnFail = SpOnFail.WAIT,
) {
    val effectiveRadius: Double get() = if (radius > 0.0) radius else mode.defaultRadius

    fun describe(): String = "${subject.label} ${mode.label} '$landmark' <=${effectiveRadius.toInt()}m"
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
)

sealed interface SpPlan {
    data object Idle : SpPlan
    data class Leap(val step: SpStep, val target: SpTmate, val label: String) : SpPlan
    data class Hold(val step: SpStep, val target: SpTmate, val key: String, val needMs: Long, val heldMs: Long, val label: String) : SpPlan
    data class Wait(val step: SpStep?, val reason: String) : SpPlan
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

            val lm = ctx.landmarks[cond.landmark]
                ?: return SpPlan.Wait(sstep, "landmark '${cond.landmark}' is missing (add it with /leaputils landmark)")

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

            val dx = px - lm.x
            val dy = py - lm.y
            val dz = pz - lm.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist <= cond.effectiveRadius) {
                val key = "hold:${sstep.key}/${target.name}"
                val held = ctx.timings[key] ?: 0L
                return if (held >= cond.holdMs) {
                    SpPlan.Leap(sstep, target, label)
                } else {
                    SpPlan.Hold(sstep, target, key, cond.holdMs, held, "$label while ${cond.describe()}")
                }
            }
            return fail(cond, sstep, "${target.clazz.name.lowercase()} ${target.name} not ${cond.mode.label} '${cond.landmark}'", ctx, target, label)
        }
        return SpPlan.Idle
    }

    private fun fail(cond: SpCond, sstep: SpStep, reason: String, ctx: SpCtx, target: SpTmate, label: String): SpPlan =
        when (cond.onFail) {
            SpOnFail.WAIT -> SpPlan.Wait(sstep, "$reason (${cond.describe()})")
            SpOnFail.SKIP -> SpPlan.SkipStep(sstep, "$reason (${cond.describe()})")
            SpOnFail.ANY_AFTER -> {
                val waited = ctx.timings["wait:${sstep.key}"] ?: 0L
                if (waited >= cond.anyAfterMs) SpPlan.Leap(sstep, target, label)
                else SpPlan.Wait(sstep, "$reason (any leap after ${cond.anyAfterMs}ms)")
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
