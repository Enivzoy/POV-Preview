package me.enivzoy.leaputils.pov.fastleap

import me.enivzoy.leaputils.pov.SpTmate

// request/executor state lives here so tests can drive it without minecraft
object FastLeapState {

    data class Timing(
        val t0: Long,
        var t1: Long? = null,
        var t2: Long? = null,
        var t3: Long? = null,
        var t4: Long? = null,
        var t5: Long? = null,
        var t6: Long? = null,
        var dispatchedNs: Long? = null,
        var closedNs: Long? = null,
        var failure: String? = null,
        // prefire comparison points, monotonic ns like the normal path
        var l0: Long? = null,
        var l1: Long? = null,
        var l2: Long? = null,
        var l3: Long? = null,
        var l4: Long? = null,
        // queuex points: q0 use-item send, q1 open packet, q2 content packet,
        // q3 click attempt, q4 click sent, q5 prediction reconcile
        var q0: Long? = null,
        var q1: Long? = null,
        var q2: Long? = null,
        var q3: Long? = null,
        var q4: Long? = null,
        var q5: Long? = null,
    )

    data class Request(
        val id: Long,
        val ruleId: String,
        val targetName: String,
        val itemId: String,
        val swapped: Boolean,
        val source: FastLeapTriggerSource,
        val generation: Long,
        val createdAt: Long,
        val deadlineTick: Long,
        val measuredDistance: Double?,
        val axes: String,
        val timing: Timing,
        val restoreSlot: Int? = null,
        // "normal" or "prefire", so the same log can compare both attempts
        val path: String = "normal",
    )

    data class CloseToken(
        val requestId: Long,
        val generation: Long,
        val screen: Any?,
        val dispatchedNs: Long,
        val deadlineNs: Long,
    )

    data class HeldItem(val slot: Int, val id: String)

    data class ItemChoice(val slot: Int, val id: String, val swap: Boolean)

    const val DEBOUNCE_MS = 250L
    const val MENU_TIMEOUT_TICKS = 60L
    const val DEFAULT_CLOSE_DELAY_MS = 1L

    var generation: Long = 0L
        private set

    var request: Request? = null
        private set

    var closeToken: CloseToken? = null
        private set

    private var nextId = 1L
    private var lastHandledAt = 0L
    private var dispatched = false

    fun bumpGeneration(): Long {
        generation++
        request = null
        closeToken = null
        dispatched = false
        return generation
    }

    fun reset() {
        request = null
        closeToken = null
        dispatched = false
        nextId = 1L
        lastHandledAt = 0L
        generation = 0L
    }

    fun debounced(nowMs: Long, debounceMs: Long = DEBOUNCE_MS): Boolean = nowMs - lastHandledAt < debounceMs

    // only called once a request exists, so an unmatched click keeps its debounce budget free
    fun markHandled(nowMs: Long) {
        lastHandledAt = nowMs
    }

    fun createRequest(
        ruleId: String,
        targetName: String,
        itemId: String,
        swapped: Boolean,
        source: FastLeapTriggerSource,
        nowMs: Long,
        nowTick: Long,
        measuredDistance: Double?,
        axes: String,
        t0: Long,
        restoreSlot: Int? = null,
        path: String = "normal",
    ): Request {
        val req = Request(
            id = nextId++,
            ruleId = ruleId,
            targetName = targetName,
            itemId = itemId,
            swapped = swapped,
            source = source,
            generation = generation,
            createdAt = nowMs,
            deadlineTick = nowTick + MENU_TIMEOUT_TICKS,
            measuredDistance = measuredDistance,
            axes = axes,
            timing = Timing(t0),
            restoreSlot = restoreSlot,
            path = path,
        )
        request = req
        dispatched = false
        return req
    }

    fun clear(reason: String? = null): Request? {
        val req = request
        if (reason != null && req != null && req.timing.failure == null) req.timing.failure = reason
        request = null
        dispatched = false
        return req
    }

    fun isStale(gen: Long): Boolean = gen != generation

    fun expired(nowTick: Long): Boolean {
        val req = request ?: return false
        return nowTick > req.deadlineTick
    }

    // the request may only run against the screen instance/generation it was built for
    fun canExecute(targetName: String, gen: Long, available: Boolean): Boolean {
        val req = request ?: return false
        if (gen != req.generation) return false
        if (dispatched) return false
        if (!req.targetName.equals(targetName, ignoreCase = true)) return false
        return available
    }

    fun markDispatched(nowNs: Long): Boolean {
        val req = request ?: return false
        if (dispatched) return false
        dispatched = true
        req.timing.dispatchedNs = nowNs
        req.timing.t6 = nowNs
        return true
    }

    fun isDispatched(): Boolean = dispatched

    // one-shot close: deadline is dispatch + delay, never a later result
    fun scheduleClose(nowNs: Long, delayMs: Long, screen: Any?): CloseToken? {
        val req = request ?: return null
        // a close only ever follows a dispatched teammate click
        if (!dispatched) return null
        if (closeToken != null) return null
        val token = CloseToken(req.id, req.generation, screen, nowNs, nowNs + delayMs * 1_000_000L)
        closeToken = token
        return token
    }

    fun closeDue(nowNs: Long): CloseToken? {
        val token = closeToken ?: return null
        return if (nowNs >= token.deadlineNs) token else null
    }

    fun consumeClose(nowNs: Long = -1L): CloseToken? {
        val token = closeToken ?: return null
        closeToken = null
        if (nowNs >= 0) {
            request?.timing?.closedNs = nowNs
            request?.timing?.l3 = nowNs
        }
        return token
    }

    fun revalidate(tm: SpTmate?): Boolean = tm != null && !tm.dead

    // prefer a ready leap item already in hand, otherwise the lowest cooldown one in slots 0-7
    fun chooseItem(
        held: HeldItem?,
        hotbar: List<HeldItem>,
        cooldownOf: (String) -> Long,
        leapIds: Set<String>,
        respectCooldown: Boolean,
        autoSwap: Boolean,
    ): ItemChoice? {
        if (held != null && held.id in leapIds && (!respectCooldown || cooldownOf(held.id) <= 0)) {
            return ItemChoice(held.slot, held.id, false)
        }
        if (!autoSwap) return null
        var best: ItemChoice? = null
        var bestRemaining = Long.MAX_VALUE
        for (candidate in hotbar) {
            if (candidate.id !in leapIds) continue
            val remaining = cooldownOf(candidate.id)
            if (respectCooldown && remaining > 0) continue
            val swap = held == null || candidate.slot != held.slot
            if (remaining < bestRemaining || (best == null && remaining == bestRemaining)) {
                bestRemaining = remaining
                best = ItemChoice(candidate.slot, candidate.id, swap)
            }
        }
        return best
    }

    fun elapsedMs(from: Long?, to: Long?): Double? {
        if (from == null || to == null) return null
        return (to - from) / 1_000_000.0
    }

    fun summary(req: Request): String {
        val t = req.timing
        fun f(v: Double?): String = v?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "-"
        return "request=${req.id} path=${req.path} rule=${req.ruleId} target=${req.targetName} source=${req.source.name.lowercase()} " +
            "item=${req.itemId.ifEmpty { "-" }} swapped=${req.swapped} failed=${t.failure ?: "-"} " +
            "ms context=${f(elapsedMs(t.t0, t.t1))} evaluate=${f(elapsedMs(t.t1, t.t2))} " +
            "item=${f(elapsedMs(t.t2, t.t3))} menuOpen=${f(elapsedMs(t.t3, t.t4))} " +
            "menuPopulate=${f(elapsedMs(t.t4, t.t5))} click=${f(elapsedMs(t.t5, t.t6))} " +
            "totalToClick=${f(elapsedMs(t.t0, t.t6))} " +
            "latency useItemToClick=${f(elapsedMs(t.l0, t.l1))} menuResponse=${f(elapsedMs(t.l0, t.l2))} " +
            "clickLead=${f(elapsedMs(t.l1, t.l2))} dispatchToClose=${f(elapsedMs(t.l1, t.l3))} " +
            "result=${f(elapsedMs(t.l1, t.l4))} total=${f(elapsedMs(t.l0, t.l4))} " +
            "queue useItemToOpen=${f(elapsedMs(t.q0, t.q1))} openToContent=${f(elapsedMs(t.q1, t.q2))} " +
            "contentToClick=${f(elapsedMs(t.q2, t.q3))} click=${f(elapsedMs(t.q3, t.q4))} " +
            "reconcile=${f(elapsedMs(t.q1, t.q5))} server=unobserved"
    }
}
