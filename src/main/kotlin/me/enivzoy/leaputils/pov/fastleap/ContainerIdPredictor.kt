package me.enivzoy.leaputils.pov.fastleap

// shadow only: the server owns the counter, nothing here can allocate or change an id
// every real open is authoritative and overwrites the local guess
class ContainerIdPredictor(historyLimit: Int = 64) {

    // a negative limit would make the trim loop pull from an empty deque
    private val historyLimit: Int = historyLimit.coerceAtLeast(0)

    enum class Confidence { UNKNOWN, SEEDED, PREDICTED, CONFIRMED, INVALID }

    data class Pending(
        val requestId: Long,
        val generation: Int,
        val predictedId: Int,
        val menuKind: String,
        val issueTick: Long,
        val expiryTick: Long,
    )

    data class Observation(
        val generation: Int,
        val id: Int,
        val menuKind: String,
        val inventory: Boolean,
        val predictedId: Int?,
        val matched: Boolean,
        val atMs: Long,
    )

    data class Result(
        val observation: Observation,
        val confidence: Confidence,
        val mismatch: Boolean,
        val seeded: Boolean,
    ) {
        val matched: Boolean get() = observation.matched
    }

    var confidence: Confidence = Confidence.UNKNOWN
        private set
    var generation: Int = 0
        private set
    var lastAuthoritativeId: Int = NO_ID
        private set
    var confirmedCounterKnown: Boolean = false
        private set
    var pending: Pending? = null
        private set
    var mismatchCount: Int = 0
        private set
    var lastConfirmedRequestId: Long = NO_REQUEST
        private set

    private val history = ArrayDeque<Observation>()

    fun observations(): List<Observation> = history.toList()

    // a new server, session or world invalidates the seed; the next real open re-seeds
    fun onConnection(newGeneration: Int) {
        val hadSeed = confirmedCounterKnown
        generation = newGeneration
        confidence = if (hadSeed) Confidence.INVALID else Confidence.UNKNOWN
        lastAuthoritativeId = NO_ID
        confirmedCounterKnown = false
        pending = null
        lastConfirmedRequestId = NO_REQUEST
    }

    fun observe(id: Int, menuKind: String, atMs: Long, nowTick: Long): Result {
        val inventory = id == ID_INVENTORY
        val expected = pending
        // a pending prediction from an older connection can never confirm this open
        val live = expected != null && expected.generation == generation
        val matched = live && expected.predictedId == id && expected.menuKind == menuKind
        val mismatch = live && !matched
        val seeded = !inventory && id in MIN_ID..MAX_ID
        if (matched) lastConfirmedRequestId = expected.requestId
        if (mismatch) {
            mismatchCount++
            confidence = Confidence.INVALID
        }
        if (seeded) {
            lastAuthoritativeId = id
            confirmedCounterKnown = true
            if (!mismatch) confidence = if (matched) Confidence.CONFIRMED else Confidence.SEEDED
        }
        // an observed open always closes the tentative prediction, matched or not
        pending = null
        // record the prediction only while it belongs to this connection
        val observation = Observation(generation, id, menuKind, inventory, expected?.takeIf { live }?.predictedId, matched, atMs)
        record(observation)
        return Result(observation, confidence, mismatch, seeded)
    }

    // the counter only moves on a real open, a sent use-item never moves it locally
    fun predictNext(requestId: Long, menuKind: String, nowTick: Long, expiryTicks: Long): Int? {
        if (pending != null) return null
        if (!confirmedCounterKnown) return null
        if (confidence != Confidence.SEEDED && confidence != Confidence.CONFIRMED) return null
        if (lastAuthoritativeId !in MIN_ID..MAX_ID) return null
        val next = nextContainerId(lastAuthoritativeId)
        pending = Pending(requestId, generation, next, menuKind, nowTick, nowTick + expiryTicks)
        confidence = Confidence.PREDICTED
        lastConfirmedRequestId = NO_REQUEST
        return next
    }

    // the clean next candidate for a diagnostic, never a confirmation
    fun candidateNext(): Int? {
        if (!confirmedCounterKnown) return null
        if (confidence != Confidence.SEEDED && confidence != Confidence.CONFIRMED) return null
        val last = lastAuthoritativeId
        if (last !in MIN_ID..MAX_ID) return null
        return nextContainerId(last)
    }

    // a declined use did not open a menu, so the seed survives and no id was consumed
    fun cancelPending() {
        if (pending == null) return
        pending = null
        if (confirmedCounterKnown) confidence = Confidence.SEEDED
    }

    // a missed open cannot be told apart from a counter jump, so prediction stays off
    fun expireIfDue(nowTick: Long): Boolean {
        val current = pending ?: return false
        if (nowTick < current.expiryTick) return false
        pending = null
        confidence = Confidence.INVALID
        return true
    }

    fun invalidate() {
        pending = null
        confidence = Confidence.INVALID
        lastConfirmedRequestId = NO_REQUEST
    }

    private fun record(observation: Observation) {
        history.addLast(observation)
        while (history.size > historyLimit) history.removeFirst()
    }

    companion object {
        const val ID_INVENTORY = 0
        const val MIN_ID = 1
        const val MAX_ID = 100
        const val KIND_LEAP = "spirit_leap"
        const val KIND_CONTAINER = "container"
        const val NO_ID = -1
        const val NO_REQUEST = -1L

        // verified ServerPlayer.nextContainerCounter bytecode: containerCounter = containerCounter % 100 + 1
        fun nextContainerId(last: Int): Int = last % 100 + 1
    }
}