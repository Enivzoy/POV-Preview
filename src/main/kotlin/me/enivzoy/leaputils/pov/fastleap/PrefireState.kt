package me.enivzoy.leaputils.pov.fastleap

// no minecraft types here: the executor feeds observations and acts on one returned action
class PrefireState(private val delayTicks: Int = 1, private val timeoutTicks: Long = 20L) {

    enum class Action { NONE, WAIT, SEND_CLICK, EXPIRE }

    enum class Phase { IDLE, ARMED, USE_ITEM_SENT, CLICK_SENT, RECONCILED, DONE, ABORTED }

    enum class Reconcile { MATCH, MISMATCH, STALE, BEFORE_CLICK, NOT_READY }

    data class Prepared(
        val requestId: Long,
        val generation: Long,
        val targetUuid: String,
        val containerId: Int,
        val stateId: Int,
        val slot: Int,
    )

    data class Latency(
        val path: String,
        var l0: Long? = null,
        var l1: Long? = null,
        var l2: Long? = null,
        var l3: Long? = null,
        var l4: Long? = null,
        var failure: String? = null,
    ) {
        fun summary(): String {
            fun f(v: Double?): String = v?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "-"
            fun d(a: Long?, b: Long?): Double? =
                if (a == null || b == null) null else (b - a) / 1_000_000.0
            // same keys as the normal path: a negative clickLead means the GUI won the race
            return "path=$path useItemToClick=${f(d(l0, l1))} menuResponse=${f(d(l0, l2))} " +
                "clickLead=${f(d(l1, l2))} dispatchToClose=${f(d(l1, l3))} result=${f(d(l1, l4))} " +
                "total=${f(d(l0, l4))} failure=${failure ?: "-"}"
        }
    }

    var phase: Phase = Phase.IDLE
        private set

    var prepared: Prepared? = null
        private set

    var failure: String? = null
        private set

    var fallbackRequired: Boolean = false
        private set

    var succeeded: Boolean = false
        private set

    var clicksIssued: Int = 0
        private set

    var closePending: Boolean = false
        private set

    val latency = Latency("prefire")

    private var armedTick = 0L
    private var dueTick = 0L
    private var clickHandedOut = false
    private var requestSeq = 0L

    val inFlight: Boolean
        get() = phase == Phase.ARMED || phase == Phase.USE_ITEM_SENT ||
            phase == Phase.CLICK_SENT || phase == Phase.RECONCILED

    // every gate must pass, any unknown value aborts before the item is used
    fun prepare(
        generation: Long,
        targetUuid: String?,
        containerId: Int?,
        stateId: Int?,
        slot: Int?,
        orderingVerified: Boolean,
        nowTick: Long,
    ): Prepared? {
        if (phase != Phase.IDLE) return null
        if (generation < 0) return null
        if (targetUuid.isNullOrBlank()) return refuse("target uuid unknown")
        if (!orderingVerified) return refuse("slot order unverified")
        // a container id is never a state id, both are required on their own
        val cid = containerId ?: return refuse("container id unknown")
        val sid = stateId ?: return refuse("state id unknown")
        if (cid !in 1..100) return refuse("container id out of range")
        if (sid < 0) return refuse("state id out of range")
        val target = slot ?: return refuse("slot unknown")
        if (target < 0) return refuse("slot unknown")

        requestSeq++
        val p = Prepared(requestSeq, generation, targetUuid.trim().lowercase(), cid, sid, target)
        prepared = p
        phase = Phase.ARMED
        failure = null
        fallbackRequired = false
        succeeded = false
        clicksIssued = 0
        clickHandedOut = false
        closePending = false
        latency.l0 = null
        latency.l1 = null
        latency.l2 = null
        latency.l3 = null
        latency.l4 = null
        latency.failure = null
        armedTick = nowTick
        return p
    }

    fun onUseItemSent(nowNs: Long, nowTick: Long): Boolean {
        if (phase != Phase.ARMED) return false
        phase = Phase.USE_ITEM_SENT
        latency.l0 = nowNs
        armedTick = nowTick
        dueTick = nowTick + delayTicks.coerceAtLeast(1)
        return true
    }

    fun nextAction(nowTick: Long, nowNs: Long): Action {
        if (phase != Phase.USE_ITEM_SENT) return Action.NONE
        if (nowTick - armedTick > timeoutTicks) {
            abort("timeout", fallback = true)
            return Action.EXPIRE
        }
        if (nowTick < dueTick) return Action.WAIT
        // handed out once, a repeated tick before the send returns is not a second click
        if (clickHandedOut) return Action.NONE
        clickHandedOut = true
        return Action.SEND_CLICK
    }

    fun onClickSent(nowNs: Long): Boolean {
        if (phase != Phase.USE_ITEM_SENT || !clickHandedOut || clicksIssued > 0) return false
        clicksIssued = 1
        latency.l1 = nowNs
        phase = Phase.CLICK_SENT
        closePending = true
        return true
    }

    fun onMenuOpen(containerId: Int, generation: Long, nowNs: Long): Reconcile {
        val p = prepared ?: return Reconcile.NOT_READY
        if (generation != p.generation) {
            // a finished or already aborted request is stale but not a new failure to record
            if (inFlight) abort("stale generation", fallback = false) else reset()
            return Reconcile.STALE
        }
        if (phase == Phase.CLICK_SENT || phase == Phase.RECONCILED) {
            if (containerId != p.containerId) {
                abort("container mismatch", fallback = true)
                return Reconcile.MISMATCH
            }
            latency.l2 = nowNs
            phase = Phase.RECONCILED
            return Reconcile.MATCH
        }
        if (phase == Phase.ARMED || phase == Phase.USE_ITEM_SENT) {
            // the real menu beat the prefire click, so the click is never sent late
            abort("menu opened before prefire", fallback = true)
            return Reconcile.BEFORE_CLICK
        }
        return Reconcile.NOT_READY
    }

    fun onContents(actualSlotByUuid: Map<String, Int>, nowNs: Long): Reconcile {
        val p = prepared ?: return Reconcile.NOT_READY
        if (phase == Phase.ABORTED) return Reconcile.STALE
        val actual = actualSlotByUuid[p.targetUuid] ?: run {
            abort("target missing from menu", fallback = true)
            return Reconcile.MISMATCH
        }
        if (actual != p.slot) {
            abort("slot mismatch", fallback = true)
            return Reconcile.MISMATCH
        }
        if (phase != Phase.ABORTED) {
            latency.l2 = latency.l2 ?: nowNs
            phase = Phase.RECONCILED
        }
        return Reconcile.MATCH
    }

    // only a server/teleport observation may mark the leap successful
    fun onResult(accepted: Boolean, nowNs: Long): Boolean {
        if (phase != Phase.CLICK_SENT && phase != Phase.RECONCILED) return false
        latency.l4 = nowNs
        succeeded = accepted
        closePending = false
        if (accepted) {
            phase = Phase.DONE
        } else {
            abort("server rejected", fallback = true)
            latency.l4 = nowNs
        }
        return true
    }

    // the close token is tied to the predicted container and the request generation
    fun shouldCloseScreen(containerId: Int, generation: Long): Boolean {
        val p = prepared ?: return false
        return closePending && generation == p.generation && containerId == p.containerId
    }

    fun consumeClose(nowNs: Long): Boolean {
        if (!closePending) return false
        closePending = false
        latency.l3 = nowNs
        return true
    }

    // an intervening menu means the prediction is stale, never send the held click
    fun onUnrelatedMenu() {
        if (phase == Phase.ARMED || phase == Phase.USE_ITEM_SENT) {
            abort("unrelated menu", fallback = false)
        } else {
            closePending = false
        }
    }

    fun cancel(reason: String = "cancelled") {
        if (phase == Phase.IDLE) return
        abort(reason, fallback = false)
        closePending = false
    }

    fun onWorldChange() {
        if (phase != Phase.IDLE) {
            abort("world changed", fallback = false)
            closePending = false
        }
    }

    fun onDisconnect() {
        if (phase != Phase.IDLE) {
            abort("disconnected", fallback = false)
            closePending = false
        }
    }

    fun onDeath() {
        if (phase != Phase.IDLE) {
            abort("player died", fallback = false)
            closePending = false
        }
    }

    fun reset() {
        phase = Phase.IDLE
        prepared = null
        failure = null
        fallbackRequired = false
        succeeded = false
        clicksIssued = 0
        clickHandedOut = false
        closePending = false
        armedTick = 0L
        dueTick = 0L
        latency.l0 = null
        latency.l1 = null
        latency.l2 = null
        latency.l3 = null
        latency.l4 = null
        latency.failure = null
    }

    private fun abort(reason: String, fallback: Boolean) {
        failure = reason
        latency.failure = reason
        phase = Phase.ABORTED
        fallbackRequired = fallback
        clickHandedOut = true
    }

    private fun refuse(reason: String): Prepared? {
        failure = reason
        latency.failure = reason
        return null
    }
}

// shadow predictor only: it never authorises a packet without a confirmed observation streak
class ContainerCounter {

    private var last: Int? = null
    private var streak = 0

    fun observe(containerId: Int): Boolean {
        val prev = last
        if (prev != null && containerId == next(prev)) streak++ else streak = 0
        last = containerId
        return predict() != null
    }

    fun predict(): Int? {
        val current = last ?: return null
        if (streak < 1) return null
        return next(current)
    }

    fun reset() {
        last = null
        streak = 0
    }

    companion object {
        // verified server counter: containerCounter = containerCounter % 100 + 1
        fun next(last: Int): Int = last % 100 + 1
    }
}