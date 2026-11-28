package me.enivzoy.leaputils

import me.enivzoy.leaputils.pov.fastleap.ContainerIdPredictor
import me.enivzoy.leaputils.pov.fastleap.QueueXGate
import me.enivzoy.leaputils.pov.fastleap.QueueXGate.Deny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueXGateTest {

    private fun input(
        enabled: Boolean = true,
        requestPresent: Boolean = true,
        sameRequest: Boolean = true,
        generationOk: Boolean = true,
        alreadyDispatched: Boolean = false,
        confidence: ContainerIdPredictor.Confidence = ContainerIdPredictor.Confidence.CONFIRMED,
        confirmedRequestId: Long = 9L,
        requestId: Long = 9L,
        learnedOrder: Boolean = true,
        requireLearnedOrder: Boolean = true,
        containerMatches: Boolean = true,
        leapScreen: Boolean = true,
        withinBudget: Boolean = true,
        targetAlive: Boolean = true,
        slotsApplied: Boolean = true,
    ) = QueueXGate.Input(
        enabled, requestPresent, sameRequest, generationOk, alreadyDispatched,
        confidence, confirmedRequestId, requestId, learnedOrder, requireLearnedOrder,
        containerMatches, leapScreen, withinBudget, targetAlive, slotsApplied,
        // the gate default is deny; tests that expect an allow must opt in explicitly
        flushOnOpenPacket = true,
    )

    @Test
    fun offPathIsUntouched() {
        assertEquals(Deny.DISABLED, QueueXGate.decide(input(enabled = false)).deny)
    }

    @Test
    fun theFlushCanBeDisabledWithoutTouchingTheNormalPath() {
        val denied = QueueXGate.decide(
            QueueXGate.Input(
                enabled = true, requestPresent = true, sameRequest = true, generationOk = true,
                alreadyDispatched = false, confidence = ContainerIdPredictor.Confidence.CONFIRMED,
                confirmedRequestId = 9L, requestId = 9L, learnedOrder = true, requireLearnedOrder = true,
                containerMatches = true, leapScreen = true, withinBudget = true, targetAlive = true,
                slotsApplied = true, flushOnOpenPacket = false,
            )
        )
        assertEquals(Deny.FLUSH_DISABLED, denied.deny)
    }

    @Test
    fun requiresARequestAndTheSameRequest() {
        assertEquals(Deny.NO_REQUEST, QueueXGate.decide(input(requestPresent = false)).deny)
        assertEquals(Deny.WRONG_REQUEST, QueueXGate.decide(input(sameRequest = false)).deny)
    }

    @Test
    fun staleGenerationNeverDispatches() {
        assertEquals(Deny.STALE_GENERATION, QueueXGate.decide(input(generationOk = false)).deny)
    }

    @Test
    fun onlyOneClickAfterReconcile() {
        assertTrue(QueueXGate.decide(input()).allow)
        assertFalse(QueueXGate.decide(input(alreadyDispatched = true)).allow)
        assertEquals(Deny.ALREADY_DISPATCHED, QueueXGate.decide(input(alreadyDispatched = true)).deny)
    }

    @Test
    fun requiresARealSeedBeforeAnyPrefire() {
        assertEquals(Deny.UNSEEDED, QueueXGate.decide(input(confidence = ContainerIdPredictor.Confidence.UNKNOWN)).deny)
        assertEquals(Deny.UNSEEDED, QueueXGate.decide(input(confidence = ContainerIdPredictor.Confidence.SEEDED)).deny)
    }

    @Test
    fun onlyAConfirmedPredictionMayFire() {
        assertEquals(Deny.PREDICTION_NOT_CONFIRMED, QueueXGate.decide(input(confidence = ContainerIdPredictor.Confidence.PREDICTED)).deny)
        assertEquals(Deny.PREDICTION_NOT_CONFIRMED, QueueXGate.decide(input(confidence = ContainerIdPredictor.Confidence.INVALID)).deny)
    }

    @Test
    fun confirmationMustBelongToThisRequest() {
        assertEquals(Deny.CONFIRMATION_OTHER_REQUEST, QueueXGate.decide(input(confirmedRequestId = 8L)).deny)
    }

    @Test
    fun learnedOrderIsRequiredByDefault() {
        assertEquals(Deny.ORDER_NOT_LEARNED, QueueXGate.decide(input(learnedOrder = false)).deny)
        assertTrue(QueueXGate.decide(input(learnedOrder = false, requireLearnedOrder = false)).allow)
    }

    @Test
    fun unrelatedContainerOrScreenBlocksTheFlush() {
        assertEquals(Deny.CONTAINER_MISMATCH, QueueXGate.decide(input(containerMatches = false)).deny)
        assertEquals(Deny.NOT_LEAP_SCREEN, QueueXGate.decide(input(leapScreen = false)).deny)
    }

    @Test
    fun budgetTargetAndSlotsAreHardGates() {
        assertEquals(Deny.BUDGET, QueueXGate.decide(input(withinBudget = false)).deny)
        assertEquals(Deny.TARGET_GONE, QueueXGate.decide(input(targetAlive = false)).deny)
        assertEquals(Deny.SLOTS_NOT_READY, QueueXGate.decide(input(slotsApplied = false)).deny)
    }

    @Test
    fun aMismatchIsNeverRetried() {
        // exercise the real predictor, not just one hand-written deny
        val predictor = ContainerIdPredictor()
        predictor.observe(41, ContainerIdPredictor.KIND_CONTAINER, 0L)
        assertEquals(42, predictor.predictNext(9L, ContainerIdPredictor.KIND_LEAP, 0L, 5L))
        // a different menu arrives, so the guess is a mismatch and must be discarded
        assertTrue(predictor.observe(43, ContainerIdPredictor.KIND_LEAP, 1L).mismatch)

        fun attempt() = QueueXGate.decide(
            input(
                confidence = predictor.confidence,
                confirmedRequestId = predictor.lastConfirmedRequestId,
                requestId = 9L,
            )
        )
        // the first retry is denied because the mismatch invalidated the prediction ...
        assertEquals(Deny.PREDICTION_NOT_CONFIRMED, attempt().deny)
        // ... and a second attempt on the same stale request is denied again
        assertEquals(Deny.PREDICTION_NOT_CONFIRMED, attempt().deny)
        // a duplicate content callback after a real click is also denied
        assertEquals(Deny.ALREADY_DISPATCHED, QueueXGate.decide(input(alreadyDispatched = true)).deny)
    }

    @Test
    fun aCallerThatForgetsTheFlushFlagIsDenied() {
        val forgot = QueueXGate.Input(
            enabled = true, requestPresent = true, sameRequest = true, generationOk = true,
            alreadyDispatched = false, confidence = ContainerIdPredictor.Confidence.CONFIRMED,
            confirmedRequestId = 9L, requestId = 9L, learnedOrder = true, requireLearnedOrder = true,
            containerMatches = true, leapScreen = true, withinBudget = true, targetAlive = true,
            slotsApplied = true,
        )
        // the default must be the safe one: no early flush unless the caller opts in
        assertEquals(Deny.FLUSH_DISABLED, QueueXGate.decide(forgot).deny)
    }
}