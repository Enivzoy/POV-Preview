package me.enivzoy.leaputils.pov.fastleap

// pure gate for the queued teammate click: every input must be verified, nothing is guessed
// the vanilla fallback path stays untouched, this only ever allows the earlier flush
object QueueXGate {

    data class Input(
        val enabled: Boolean,
        val requestPresent: Boolean,
        val sameRequest: Boolean,
        val generationOk: Boolean,
        val alreadyDispatched: Boolean,
        val confidence: ContainerIdPredictor.Confidence,
        val confirmedRequestId: Long,
        val requestId: Long,
        val learnedOrder: Boolean,
        val requireLearnedOrder: Boolean,
        val containerMatches: Boolean,
        val leapScreen: Boolean,
        val withinBudget: Boolean,
        val targetAlive: Boolean,
        val slotsApplied: Boolean,
        // off by default: a caller that forgets this must not silently get the early flush
        val flushOnOpenPacket: Boolean = false,
    )

    enum class Deny {
        NONE,
        DISABLED,
        FLUSH_DISABLED,
        NO_REQUEST,
        WRONG_REQUEST,
        STALE_GENERATION,
        ALREADY_DISPATCHED,
        UNSEEDED,
        PREDICTION_NOT_CONFIRMED,
        CONFIRMATION_OTHER_REQUEST,
        ORDER_NOT_LEARNED,
        CONTAINER_MISMATCH,
        NOT_LEAP_SCREEN,
        BUDGET,
        TARGET_GONE,
        SLOTS_NOT_READY,
    }

    data class Decision(val allow: Boolean, val deny: Deny)

    fun decide(i: Input): Decision {
        val deny = when {
            !i.enabled -> Deny.DISABLED
            !i.flushOnOpenPacket -> Deny.FLUSH_DISABLED
            !i.requestPresent -> Deny.NO_REQUEST
            !i.sameRequest -> Deny.WRONG_REQUEST
            !i.generationOk -> Deny.STALE_GENERATION
            i.alreadyDispatched -> Deny.ALREADY_DISPATCHED
            i.confidence == ContainerIdPredictor.Confidence.UNKNOWN ||
                i.confidence == ContainerIdPredictor.Confidence.SEEDED -> Deny.UNSEEDED
            i.confidence != ContainerIdPredictor.Confidence.CONFIRMED -> Deny.PREDICTION_NOT_CONFIRMED
            i.confirmedRequestId != i.requestId -> Deny.CONFIRMATION_OTHER_REQUEST
            i.requireLearnedOrder && !i.learnedOrder -> Deny.ORDER_NOT_LEARNED
            !i.containerMatches -> Deny.CONTAINER_MISMATCH
            !i.leapScreen -> Deny.NOT_LEAP_SCREEN
            !i.withinBudget -> Deny.BUDGET
            !i.targetAlive -> Deny.TARGET_GONE
            !i.slotsApplied -> Deny.SLOTS_NOT_READY
            else -> Deny.NONE
        }
        return Decision(deny == Deny.NONE, deny)
    }
}