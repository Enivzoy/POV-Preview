package me.enivzoy.leaputils

import me.enivzoy.leaputils.pov.fastleap.ContainerIdPredictor
import me.enivzoy.leaputils.pov.fastleap.ContainerIdPredictor.Confidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerIdPredictorTest {

    private val leap = ContainerIdPredictor.KIND_LEAP
    private val chest = ContainerIdPredictor.KIND_CONTAINER

    private fun seeded(id: Int, generation: Int = 0): ContainerIdPredictor {
        val p = ContainerIdPredictor()
        if (generation != 0) p.onConnection(generation)
        p.observe(id, chest, 0L, 0L)
        return p
    }

    @Test
    fun observesTheNextSequenceAndConfirmsThePrediction() {
        val p = seeded(41)
        assertEquals(Confidence.SEEDED, p.confidence)
        assertEquals(42, p.predictNext(7L, leap, 10L, 5L))
        assertTrue(p.observe(42, leap, 1L, 11L).matched)
        assertEquals(Confidence.CONFIRMED, p.confidence)
        assertEquals(7L, p.lastConfirmedRequestId)
        assertEquals(42, p.lastAuthoritativeId)
    }

    @Test
    fun wrapRuleMatchesTheVerifiedServerFormula() {
        assertEquals(2, ContainerIdPredictor.nextContainerId(1))
        assertEquals(100, ContainerIdPredictor.nextContainerId(99))
        assertEquals(1, ContainerIdPredictor.nextContainerId(100))
        val p = seeded(100)
        assertEquals(1, p.predictNext(1L, leap, 0L, 5L))
    }

    @Test
    fun unexpectedOpenInvalidatesAndKeepsTheServerId() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        val res = p.observe(43, chest, 1L, 1L)
        assertTrue(res.mismatch)
        assertEquals(Confidence.INVALID, p.confidence)
        assertEquals(1, p.mismatchCount)
        // the server open is still authoritative even when it contradicted the guess
        assertEquals(43, p.lastAuthoritativeId)
    }

    @Test
    fun reconnectInvalidatesTheSeed() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        p.onConnection(1)
        assertEquals(Confidence.INVALID, p.confidence)
        assertNull(p.pending)
        assertEquals(ContainerIdPredictor.NO_ID, p.lastAuthoritativeId)
        assertFalse(p.confirmedCounterKnown)
        // the next real open is a fresh seed, never a confirmation of the old prediction
        val res = p.observe(42, leap, 1L, 1L)
        assertFalse(res.matched)
        assertEquals(Confidence.SEEDED, p.confidence)
    }

    @Test
    fun missedPacketExpiresAndNeverConfirmsLater() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        assertFalse(p.expireIfDue(4))
        assertTrue(p.expireIfDue(5))
        assertEquals(Confidence.INVALID, p.confidence)
        assertNull(p.pending)
        // a late open is just a new seed, not the queued confirmation
        val res = p.observe(42, leap, 1L, 6L)
        assertFalse(res.matched)
        assertEquals(ContainerIdPredictor.NO_REQUEST, p.lastConfirmedRequestId)
    }

    @Test
    fun inventoryIdZeroIsIgnoredForSeeding() {
        val p = ContainerIdPredictor()
        val res = p.observe(ContainerIdPredictor.ID_INVENTORY, chest, 0L, 0L)
        assertTrue(res.observation.inventory)
        assertFalse(res.seeded)
        assertFalse(p.confirmedCounterKnown)
        assertEquals(ContainerIdPredictor.NO_ID, p.lastAuthoritativeId)
        assertEquals(1, p.observations().size)
    }

    @Test
    fun anUnconfirmedPredictionNeverChangesTheAuthoritativeId() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        assertEquals(41, p.lastAuthoritativeId)
        assertEquals(Confidence.PREDICTED, p.confidence)
        assertEquals(ContainerIdPredictor.NO_REQUEST, p.lastConfirmedRequestId)
    }

    @Test
    fun declinedOrTimedOutUseDiscardsPendingWithoutBurningTheSeed() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        p.cancelPending()
        assertNull(p.pending)
        assertEquals(Confidence.SEEDED, p.confidence)
        assertEquals(41, p.lastAuthoritativeId)
        // the seed still predicts the same next id
        assertEquals(42, p.predictNext(2L, leap, 1L, 5L))
    }

    @Test
    fun aDifferentMenuAtThePredictedIdIsAMismatch() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        val res = p.observe(42, chest, 1L, 1L)
        assertTrue(res.mismatch)
        assertEquals(Confidence.INVALID, p.confidence)
    }

    @Test
    fun onlyOnePredictionCanBePending() {
        val p = seeded(41)
        assertEquals(42, p.predictNext(1L, leap, 0L, 5L))
        assertNull(p.predictNext(2L, leap, 0L, 5L))
        assertEquals(1L, p.pending?.requestId)
        assertEquals(42, p.pending?.predictedId)
    }

    @Test
    fun aStaleGenerationCannotConfirmAnOldPrediction() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        p.onConnection(1)
        val res = p.observe(42, leap, 1L, 1L)
        assertFalse(res.matched)
        assertEquals(ContainerIdPredictor.NO_REQUEST, p.lastConfirmedRequestId)
        assertEquals(1, res.observation.generation)
    }

    @Test
    fun historyIsBounded() {
        val p = ContainerIdPredictor(historyLimit = 4)
        for (id in 1..10) p.observe(id, chest, id.toLong(), id.toLong())
        assertEquals(4, p.observations().size)
        assertEquals(7, p.observations().first().id)
    }

    @Test
    fun aFreshPredictionCannotReuseThePreviousConfirmation() {
        val p = seeded(41)
        p.predictNext(1L, leap, 0L, 5L)
        p.observe(42, leap, 1L, 1L)
        assertEquals(1L, p.lastConfirmedRequestId)
        p.predictNext(2L, leap, 2L, 5L)
        assertEquals(ContainerIdPredictor.NO_REQUEST, p.lastConfirmedRequestId)
        assertNotNull(p.pending)
    }
}