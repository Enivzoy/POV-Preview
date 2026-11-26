package me.enivzoy.leaputils.pov.fastleap

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import me.enivzoy.leaputils.pov.SpCond
import me.enivzoy.leaputils.pov.SpLandmark
import me.enivzoy.leaputils.pov.SpSubject
import me.enivzoy.leaputils.pov.SpTmate
import kotlin.math.sqrt

data class FastLeapContext(
    val trigger: FastLeapTriggerSource,
    val teammates: List<SpTmate>,
    val myClass: DungeonClass?,
    // the local player's name, used to never offer self as a target
    val myName: String? = null,
    val myX: Double? = null,
    val myY: Double? = null,
    val myZ: Double? = null,
    val landmarks: Map<String, SpLandmark> = emptyMap(),
    val inBoss: Boolean = false,
    // quoi chat phase 1..5, 0 when unknown
    val phase: Int = 0,
    val section: Int = 0,
    val doorOpener: String? = null,
    val ignorePhaseGates: Boolean = false,
    val preferredClass: DungeonClass? = null,
    val preferredName: String? = null,
    val fallbackNearestLandmark: String? = null,
    val defaultErrorMin: Double = SpCond.DEFAULT_ERROR_MIN,
    val defaultErrorMax: Double = SpCond.DEFAULT_ERROR_MAX,
) {
    val phaseAt: Int get() = F7Positions.phaseAt(myY)
    val stageAt: Int get() = F7Positions.stageAt(myX, myZ, phaseAt)
}

object FastLeapEvaluator {

    fun nameKey(name: String): String = name.trim().lowercase()

    // priority desc, then list order for ties
    fun ordered(rules: List<FastLeapRule>): List<FastLeapRule> =
        rules.withIndex()
            .sortedWith(compareByDescending<IndexedValue<FastLeapRule>> { it.value.priority }.thenBy { it.index })
            .map { it.value }

    fun evaluate(
        rules: List<FastLeapRule>,
        ctx: FastLeapContext,
        onlyRuleId: String? = null,
        applyFallback: Boolean = false,
    ): FastLeapResult {
        val failures = ArrayList<RuleFailure>(rules.size)
        for (rule in ordered(rules)) {
            if (!rule.enabled) {
                failures += RuleFailure(rule.id, "rule is disabled")
                continue
            }
            if (ctx.trigger !in rule.allowedTriggers) {
                failures += RuleFailure(rule.id, "trigger ${ctx.trigger.name.lowercase()} is not allowed for this rule")
                continue
            }
            when (val gate = gate(rule, ctx)) {
                is Gate.Fail -> {
                    failures += RuleFailure(rule.id, gate.reason)
                    continue
                }
                is Gate.Pass -> {}
            }
            if (onlyRuleId != null && rule.id != onlyRuleId) continue
            when (val picked = pick(rule, ctx)) {
                is Pick.Found -> when (val checked = checkConditions(rule, picked.tm, ctx)) {
                    is Conditions.Ok -> return FastLeapResult.Plan(
                        rule = rule,
                        teammate = picked.tm,
                        measuredDistance = checked.distance ?: picked.distance,
                        axes = checked.axes,
                        positionSource = checked.source,
                    )
                    is Conditions.Fail -> failures += RuleFailure(rule.id, checked.reason)
                }
                is Pick.Fail -> failures += RuleFailure(rule.id, picked.reason)
            }
        }
        if (applyFallback && onlyRuleId == null) {
            fallback(ctx)?.let { return it }
        }
        return FastLeapResult.NoMatch(failures)
    }

    // nearest teammate to a configured landmark, used when no rule matched
    private fun fallback(ctx: FastLeapContext): FastLeapResult.Plan? {
        val name = ctx.fallbackNearestLandmark?.trim().orEmpty()
        if (name.isEmpty()) return null
        val lm = ctx.landmarks[name] ?: return null
        val pool = eligible(ctx).filter { it.x != null && it.z != null }
        if (pool.isEmpty()) return null
        val best = pool.minByOrNull { tm ->
            SpatialEvaluator.sq(tm.x!! - lm.x, (tm.y ?: lm.y) - lm.y, tm.z!! - lm.z)
        } ?: return null
        val dist = sqrt(SpatialEvaluator.sq(best.x!! - lm.x, (best.y ?: lm.y) - lm.y, best.z!! - lm.z))
        return FastLeapResult.Plan(
            rule = FastLeapRule(
                id = "fallback",
                name = "Fallback nearest to $name",
                priority = Int.MIN_VALUE,
                target = TargetSpec(TargetSelectorType.NEAREST_LANDMARK, landmarkName = name, radius = 128.0),
            ),
            teammate = best,
            measuredDistance = dist,
            axes = if (best.y == null) "XZ" else "3D",
            positionSource = "landmark",
        )
    }

    private sealed interface Gate {
        data object Pass : Gate
        data class Fail(val reason: String) : Gate
    }

    private fun gate(rule: FastLeapRule, ctx: FastLeapContext): Gate {
        rule.inBoss?.let { need ->
            if (ctx.inBoss != need) return Gate.Fail(if (need) "not in boss" else "in boss")
        }
        if (!ctx.ignorePhaseGates) {
            rule.phase?.let { need ->
                if (ctx.phase != need) return Gate.Fail("phase ${ctx.phase} is not $need")
            }
            if (rule.phaseAnyOf.isNotEmpty() && ctx.phase !in rule.phaseAnyOf) {
                return Gate.Fail("phase ${ctx.phase} is not one of ${rule.phaseAnyOf.sorted()}")
            }
            rule.phaseAt?.let { need ->
                if (ctx.phaseAt != need) return Gate.Fail("position phase ${ctx.phaseAt} is not $need")
            }
            rule.stage?.let { need ->
                if (ctx.stageAt != need) return Gate.Fail("stage ${ctx.stageAt} is not $need")
            }
            rule.section?.let { need ->
                if (ctx.section != need) return Gate.Fail("section ${ctx.section} is not $need")
            }
        }
        if (rule.selfRegion != null) {
            if (!rule.selfRegion.complete) return Gate.Fail("self region is incomplete")
            val pos = SpatialEvaluator.selfPosition(ctx.myX, ctx.myY, ctx.myZ)
            if (!pos.known) return Gate.Fail("position unknown")
            if (!SpatialEvaluator.insideRegion(pos, rule.selfRegion, ctx.landmarks)) {
                return Gate.Fail("you are outside ${rule.name}'s region")
            }
        }
        if (rule.excludeRegions.isNotEmpty()) {
            val pos = SpatialEvaluator.selfPosition(ctx.myX, ctx.myY, ctx.myZ)
            // never leap on an unknown position, the exclusions could not be checked
            if (!pos.known) return Gate.Fail("position unknown")
            if (rule.excludeRegions.any { it.complete && SpatialEvaluator.insideRegion(pos, it, ctx.landmarks) }) {
                return Gate.Fail("you are inside an excluded region for ${rule.name}")
            }
        }
        return Gate.Pass
    }

    private sealed interface Pick {
        data class Found(val tm: SpTmate, val distance: Double? = null) : Pick
        data class Fail(val reason: String) : Pick
    }

    private fun eligible(ctx: FastLeapContext): List<SpTmate> =
        ctx.teammates.filter {
            !it.dead &&
                nameKey(it.name).isNotEmpty() &&
                it.clazz != DungeonClass.EMPTY &&
                // never leap to self, even if the caller forgot to strip it
                (ctx.myName == null || nameKey(it.name) != nameKey(ctx.myName)) &&
                nameKey(it.name) != nameKey(ctx.preferredName ?: "")
        }

    private fun pick(rule: FastLeapRule, ctx: FastLeapContext): Pick {
        val spec = rule.target
        val pool = eligible(ctx)
        if (pool.isEmpty()) return Pick.Fail("no eligible teammate")
        return when (spec.type) {
            TargetSelectorType.NAME -> {
                val want = spec.targetName?.trim().orEmpty().ifEmpty { ctx.preferredName?.trim().orEmpty() }
                if (want.isEmpty()) return Pick.Fail("blank target name")
                val key = nameKey(want)
                pool.firstOrNull { nameKey(it.name) == key }?.let { Pick.Found(it) }
                    ?: Pick.Fail("target '$want' is not a leapable teammate")
            }
            TargetSelectorType.CLASS -> {
                val cls = spec.targetClass ?: ctx.preferredClass ?: return Pick.Fail("no target class configured")
                val cands = pool.filter { it.clazz == cls }
                when {
                    cands.isEmpty() -> Pick.Fail("no ${cls.name.lowercase()} available")
                    cands.size > 1 -> Pick.Fail("two ${cls.name.lowercase()}s available; ambiguous")
                    else -> Pick.Found(cands.first())
                }
            }
            TargetSelectorType.NEAREST_LANDMARK -> {
                val name = spec.landmarkName?.trim().orEmpty()
                if (name.isEmpty()) return Pick.Fail("nearest needs a landmark")
                val lm = ctx.landmarks[name] ?: return Pick.Fail("missing landmark $name")
                val withPos = pool.filter { it.x != null && it.z != null }
                if (withPos.isEmpty()) return Pick.Fail("no teammate with a known position")
                val best = withPos.minByOrNull { tm ->
                    SpatialEvaluator.sq(tm.x!! - lm.x, (tm.y ?: lm.y) - lm.y, tm.z!! - lm.z)
                } ?: return Pick.Fail("no teammate with a known position")
                val dist = sqrt(SpatialEvaluator.sq(best.x!! - lm.x, (best.y ?: lm.y) - lm.y, best.z!! - lm.z))
                if (dist > spec.radius) {
                    Pick.Fail("nearest teammate is ${SpatialEvaluator.fmt(dist)} > ${SpatialEvaluator.fmt(spec.radius)} from $name")
                } else {
                    Pick.Found(best, dist)
                }
            }
            TargetSelectorType.FIRST_IN_MENU -> Pick.Found(pool.first())
            TargetSelectorType.DOOR_OPENER -> {
                val opener = ctx.doorOpener?.trim().orEmpty()
                if (ctx.inBoss) return Pick.Fail("door opener leap only applies outside boss")
                if (opener.isEmpty() || opener.equals("unknown", ignoreCase = true)) return Pick.Fail("door opener unknown")
                val key = nameKey(opener)
                pool.firstOrNull { nameKey(it.name) == key }?.let { Pick.Found(it) }
                    ?: Pick.Fail("door opener '$opener' is not a leapable teammate")
            }
        }
    }

    private sealed interface Conditions {
        data class Ok(val distance: Double?, val axes: String, val source: String) : Conditions
        data class Fail(val reason: String) : Conditions
    }

    private fun checkConditions(rule: FastLeapRule, tm: SpTmate, ctx: FastLeapContext): Conditions {
        var lastDist: Double? = null
        var axes = "3D"
        var source = "self"
        for (cond in rule.conditions) {
            val pos = when (cond.subject) {
                SpSubject.TARGET -> SpatialEvaluator.targetPosition(tm).also { source = "teammate" }
                SpSubject.SELF -> SpatialEvaluator.selfPosition(ctx.myX, ctx.myY, ctx.myZ).also { source = "self" }
            }
            val (anchor, label) = SpatialEvaluator.resolveAnchor(cond, ctx.landmarks)
            val named = cond.landmarkName?.trim().orEmpty()
            if (anchor == null && named.isNotEmpty()) {
                return Conditions.Fail("missing landmark $named (capture it with /leaputils landmark $named)")
            }
            // a cond with neither point nor landmark is a pure gate, nothing to measure
            if (anchor == null) continue
            when (val res = SpatialEvaluator.check(pos, anchor, cond.horizontal, cond, ctx.defaultErrorMin, ctx.defaultErrorMax, label)) {
                is SpatialEvaluator.Check.Fail -> return Conditions.Fail(res.reason)
                is SpatialEvaluator.Check.Pass -> {
                    lastDist = res.distance
                    axes = res.axes
                }
            }
        }
        return Conditions.Ok(lastDist, axes, source)
    }
}
