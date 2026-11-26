package me.enivzoy.leaputils

import me.enivzoy.leaputils.pov.LeapOrderProbe
import me.enivzoy.leaputils.pov.fastleap.ContainerCounter
import me.enivzoy.leaputils.pov.fastleap.LeapOrderCache
import me.enivzoy.leaputils.pov.fastleap.PrefireState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefireStateTest {

    private val parties = listOf("uuid-a", "uuid-b", "uuid-c", "uuid-d")

    private fun observe(
        cache: LeapOrderCache,
        slots: List<Int> = listOf(11, 12, 13, 14),
    ): LeapOrderCache.Record? = cache.observe(
        parties.mapIndexed { i, uuid ->
            LeapOrderCache.Entry(uuid, i, slots[i], "MAGE")
        }
    )

    // ---- ordering cache ----

    @Test
    fun cacheNeedsTwoAgreeingObservationsBeforePredicting() {
        val cache = LeapOrderCache()
        assertFalse(cache.validated())
        assertNull(cache.beginRun(1, parties))
        cache.beginRun(1, parties)
        assertNotNull(observe(cache))
        // one observation is never enough
        assertFalse(cache.validated())
        assertNull(cache.slotFor("uuid-b"))

        observe(cache)
        assertTrue(cache.validated())
        assertEquals(12, cache.slotFor("uuid-b"))
    }

    @Test
    fun cacheInvalidatesWhenObservationsDisagree() {
        val cache = LeapOrderCache()
        cache.beginRun(1, parties)
        observe(cache)
        observe(cache, listOf(14, 13, 12, 11))
        // the layout changed, the streak restarts instead of averaging two layouts
        assertFalse(cache.validated())
        assertNull(cache.slotFor("uuid-a"))
        observe(cache, listOf(14, 13, 12, 11))
        assertTrue(cache.validated())
        assertEquals(14, cache.slotFor("uuid-a"))
    }

    @Test
    fun cacheDropsMappingWhenPartyChanges() {
        val cache = LeapOrderCache()
        cache.beginRun(1, parties)
        observe(cache)
        observe(cache)
        assertTrue(cache.validated())

        cache.beginRun(2, listOf("uuid-a", "uuid-z"))
        assertFalse(cache.active)
        assertFalse(cache.validated())
        assertNull(cache.slotFor("uuid-a"))
    }

    @Test
    fun cacheResolvesOnlyLiveUnambiguousUuids() {
        val cache = LeapOrderCache()
        cache.beginRun(1, parties)
        observe(cache)
        observe(cache)

        assertEquals(13, cache.slotFor("UUID-C"))
        // uuid gone from the live roster must not resolve to the old slot
        assertNull(cache.resolve("uuid-c", setOf("uuid-a", "uuid-b")))
        assertNull(cache.resolve("", parties.toSet()))
    }

    @Test
    fun cacheRejectsDuplicateUuidsAndSlots() {
        val cache = LeapOrderCache()
        cache.beginRun(1, parties)
        val dupUuid = cache.observe(
            listOf(
                LeapOrderCache.Entry("uuid-a", 0, 11, "MAGE"),
                LeapOrderCache.Entry("uuid-a", 1, 12, "TANK"),
            )
        )
        assertNull(dupUuid)
        val dupSlot = cache.observe(
            listOf(
                LeapOrderCache.Entry("uuid-a", 0, 11, "MAGE"),
                LeapOrderCache.Entry("uuid-b", 1, 11, "TANK"),
            )
        )
        assertNull(dupSlot)
        assertFalse(cache.validated())
    }

    @Test
    fun logEntryKeepsOrdinalAndSlotSeparate() {
        val cache = LeapOrderCache()
        cache.beginRun(1, parties)
        observe(cache)
        observe(cache)
        val log = assertNotNull(cache.logEntry())
        val record = log.getValue("run-000001") as Map<*, *>
        val players = record["players"] as Map<*, *>
        val slots = record["slotByUuid"] as Map<*, *>
        assertEquals(listOf("[1]", "MAGE"), players["uuid-b"])
        assertEquals(12, slots["uuid-b"])
        // ordinal is not the slot number
        assertEquals("[3]", (players["uuid-d"] as List<*>)[0])
        assertEquals(14, slots["uuid-d"])
    }

    @Test
    fun partyFingerprintIgnoresOrderAndCase() {
        val first = LeapOrderCache.fingerprint(listOf("A", "b", "c"))
        val second = LeapOrderCache.fingerprint(listOf("c", "B", "a"))
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun aNewRunMarkerStartsAFreshRecord() {
        val cache = LeapOrderCache()
        cache.beginRun(1, parties)
        observe(cache)
        observe(cache)
        assertTrue(cache.validated())
        // same party, next dungeon run: the old slot prediction must not carry over
        assertNull(cache.beginRun(7, parties))
        assertEquals("run-000007", cache.currentRunId)
        assertFalse(cache.validated())
        assertNull(cache.slotFor("uuid-a"))
    }

    @Test
    fun appendingARunKeepsEarlierRecords() {
        val first = mapOf("players" to mapOf("uuid-a" to listOf("[0]", "TANK")), "slotByUuid" to mapOf("uuid-a" to 11))
        val existing: Map<String, Any> = linkedMapOf("run-000001" to first)
        val merged = assertNotNull(LeapOrderProbe.merge(existing, "run-000002", mapOf("players" to emptyMap<String, Any>())))
        assertEquals(setOf("run-000001", "run-000002"), merged.keys)
        assertEquals(first, merged["run-000001"])
        // a missing record is not a reason to rewrite the file
        assertNull(LeapOrderProbe.merge(existing, "run-000003", null))
    }

    // ---- container predictor ----

    @Test
    fun containerCounterWrapsAndNeedsAStreak() {
        assertEquals(1, ContainerCounter.next(100))
        assertEquals(2, ContainerCounter.next(1))
        assertEquals(100, ContainerCounter.next(99))

        val counter = ContainerCounter()
        // a single observation has no confirmed step yet
        assertNull(counter.predict())
        counter.observe(7)
        assertNull(counter.predict())
        counter.observe(8)
        assertEquals(9, counter.predict())
        counter.observe(9)
        assertEquals(10, counter.predict())
        counter.reset()
        assertNull(counter.predict())
    }

    @Test
    fun containerCounterRefusesToPredictAfterAGap() {
        val counter = ContainerCounter()
        counter.observe(5)
        counter.observe(6)
        assertEquals(7, counter.predict())
        // a jump breaks the streak, so the predictor stops claiming the next id
        counter.observe(20)
        assertNull(counter.predict())
    }

    // ---- prefire state machine ----

    private fun armed(state: PrefireState = PrefireState()) {
        val p = state.prepare(
            generation = 3,
            targetUuid = "UUID-A",
            containerId = 7,
            stateId = 42,
            slot = 12,
            orderingVerified = true,
            nowTick = 100,
        )
        assertNotNull(p)
        assertTrue(state.onUseItemSent(1_000_000_000L, 100))
    }

    @Test
    fun prefireRefusesWithoutEveryPrediction() {
        val state = PrefireState()
        assertNull(state.prepare(3, null, 7, 42, 12, true, 100))
        assertEquals("target uuid unknown", state.failure)
        assertNull(state.prepare(3, "uuid-a", 7, 42, 12, false, 100))
        assertEquals("slot order unverified", state.failure)
        assertNull(state.prepare(3, "uuid-a", null, 42, 12, true, 100))
        assertEquals("container id unknown", state.failure)
        // state id is tracked separately from the container id, never substituted
        assertNull(state.prepare(3, "uuid-a", 7, null, 12, true, 100))
        assertEquals("state id unknown", state.failure)
        assertNull(state.prepare(3, "uuid-a", 7, 42, null, true, 100))
        assertEquals("slot unknown", state.failure)
        assertFalse(state.inFlight)
    }

    @Test
    fun prefireSendsExactlyOneClickAfterTheConfiguredDelay() {
        val state = PrefireState(delayTicks = 1)
        armed(state)
        assertEquals(PrefireState.Action.WAIT, state.nextAction(100, 1_000_000L))
        assertEquals(PrefireState.Action.SEND_CLICK, state.nextAction(101, 1_100_000L))
        // repeated ticks before the send returns must not hand out a second click
        assertEquals(PrefireState.Action.NONE, state.nextAction(101, 1_100_000L))
        assertTrue(state.onClickSent(1_100_000L))
        assertEquals(1, state.clicksIssued)
        assertFalse(state.onClickSent(1_200_000L))
        assertEquals(1, state.clicksIssued)
    }

    @Test
    fun prefireHonoursADelayGreaterThanOneTick() {
        val state = PrefireState(delayTicks = 3)
        armed(state)
        assertEquals(PrefireState.Action.WAIT, state.nextAction(101, 1_000_000L))
        assertEquals(PrefireState.Action.WAIT, state.nextAction(102, 1_000_000L))
        assertEquals(PrefireState.Action.SEND_CLICK, state.nextAction(103, 1_000_000L))
    }

    @Test
    fun prefireTimesOutWithoutSending() {
        val state = PrefireState(timeoutTicks = 5)
        armed(state)
        assertEquals(PrefireState.Action.EXPIRE, state.nextAction(120, 9_000_000L))
        assertEquals(0, state.clicksIssued)
        assertTrue(state.fallbackRequired)
        assertEquals("timeout", state.failure)
    }

    @Test
    fun prefireIsNotSuccessfulUntilAServerResultArrives() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        // a local send is not a result
        assertFalse(state.succeeded)
        assertTrue(state.onResult(accepted = true, nowNs = 1_900_000L))
        assertTrue(state.succeeded)
        assertEquals(PrefireState.Phase.DONE, state.phase)
    }

    @Test
    fun prefireFallsBackWhenTheServerRejects() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        assertTrue(state.onResult(accepted = false, nowNs = 1_500_000L))
        assertFalse(state.succeeded)
        assertTrue(state.fallbackRequired)
        assertEquals("server rejected", state.failure)
    }

    @Test
    fun menuBeatingTheClickCancelsThePrefire() {
        val state = PrefireState()
        armed(state)
        val res = state.onMenuOpen(containerId = 7, generation = 3, nowNs = 1_050_000L)
        assertEquals(PrefireState.Reconcile.BEFORE_CLICK, res)
        // the click is never sent late, the normal menu path takes over
        assertEquals(PrefireState.Action.NONE, state.nextAction(101, 1_100_000L))
        assertTrue(state.fallbackRequired)
        assertEquals("menu opened before prefire", state.failure)
    }

    @Test
    fun matchingContainerReconcilesAndKeepsOneClick() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        assertEquals(
            PrefireState.Reconcile.MATCH,
            state.onMenuOpen(containerId = 7, generation = 3, nowNs = 1_200_000L),
        )
        assertEquals(PrefireState.Phase.RECONCILED, state.phase)
        // a later screen event never duplicates the dispatch
        assertEquals(1, state.clicksIssued)
        assertEquals(PrefireState.Action.NONE, state.nextAction(102, 1_300_000L))
    }

    @Test
    fun wrongContainerIdAbortsWithoutRetry() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        assertEquals(
            PrefireState.Reconcile.MISMATCH,
            state.onMenuOpen(containerId = 8, generation = 3, nowNs = 1_200_000L),
        )
        assertEquals("container mismatch", state.failure)
        assertTrue(state.fallbackRequired)
        assertEquals(1, state.clicksIssued)
        // no blind retry, the request is done
        assertEquals(PrefireState.Action.NONE, state.nextAction(103, 1_400_000L))
    }

    @Test
    fun staleGenerationIsDroppedSilently() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        assertEquals(
            PrefireState.Reconcile.STALE,
            state.onMenuOpen(containerId = 7, generation = 4, nowNs = 1_200_000L),
        )
        assertFalse(state.fallbackRequired)
        assertEquals("stale generation", state.failure)
    }

    @Test
    fun contentsMismatchNeverRedirectsTheClick() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        val res = state.onContents(mapOf("uuid-a" to 13), 1_200_000L)
        assertEquals(PrefireState.Reconcile.MISMATCH, res)
        assertEquals("slot mismatch", state.failure)
        assertEquals(1, state.clicksIssued)
    }

    @Test
    fun contentsMatchConfirmsThePredictedSlot() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        assertEquals(PrefireState.Reconcile.MATCH, state.onContents(mapOf("uuid-a" to 12), 1_200_000L))
        assertEquals(PrefireState.Phase.RECONCILED, state.phase)
        assertNull(state.failure)
    }

    @Test
    fun missingTargetInContentsAborts() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        assertEquals(PrefireState.Reconcile.MISMATCH, state.onContents(emptyMap(), 1_200_000L))
        assertEquals("target missing from menu", state.failure)
    }

    @Test
    fun closeTokenOnlyClosesThePredictedContainer() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000L)
        state.onClickSent(1_100_000L)
        assertTrue(state.closePending)
        assertFalse(state.shouldCloseScreen(containerId = 8, generation = 3))
        assertFalse(state.shouldCloseScreen(containerId = 7, generation = 4))
        assertTrue(state.shouldCloseScreen(containerId = 7, generation = 3))
        assertTrue(state.consumeClose(1_150_000L))
        // the token is one-shot
        assertFalse(state.consumeClose(1_200_000L))
        assertFalse(state.shouldCloseScreen(containerId = 7, generation = 3))
    }

    @Test
    fun worldChangeDisconnectDeathAndCancelClearTheRequest() {
        for (action in listOf("world", "disconnect", "death", "cancel")) {
            val state = PrefireState()
            armed(state)
            when (action) {
                "world" -> state.onWorldChange()
                "disconnect" -> state.onDisconnect()
                "death" -> state.onDeath()
                else -> state.cancel()
            }
            assertEquals(PrefireState.Phase.ABORTED, state.phase)
            assertFalse(state.inFlight)
            assertFalse(state.closePending)
            assertEquals(0, state.clicksIssued)
        }
    }

    @Test
    fun unrelatedMenuDropsHeldWorkWithoutFallback() {
        val state = PrefireState()
        armed(state)
        state.onUnrelatedMenu()
        assertEquals("unrelated menu", state.failure)
        assertFalse(state.fallbackRequired)
        assertEquals(PrefireState.Action.NONE, state.nextAction(101, 1_000_000L))
    }

    @Test
    fun onlyOnePrefireCanBeInFlight() {
        val state = PrefireState()
        armed(state)
        assertNull(state.prepare(3, "uuid-b", 8, 43, 13, true, 100))
        assertEquals(1, state.prepared?.requestId)
    }

    @Test
    fun latencySummarySeparatesDispatchFromResult() {
        val state = PrefireState()
        armed(state)
        state.nextAction(101, 1_000_000_000L)
        state.onClickSent(1_050_000_000L)
        state.onMenuOpen(containerId = 7, generation = 3, nowNs = 1_200_000_000L)
        state.consumeClose(1_260_000_000L)
        state.onResult(accepted = true, nowNs = 1_400_000_000L)
        val out = state.latency.summary()
        assertTrue(out.contains("path=prefire"))
        assertTrue(out.contains("useItemToClick=50.0"))
        assertTrue(out.contains("dispatchToClose=210.0"))
        assertTrue(out.contains("result=350.0"))
        assertTrue(out.contains("failure=-"))
    }

    @Test
    fun latencyFailureIsRecorded() {
        val state = PrefireState()
        state.prepare(3, null, 7, 42, 12, true, 100)
        assertTrue(state.latency.summary().contains("failure=target uuid unknown"))
    }
}