package me.enivzoy.leaputils.pov.fastleap

import java.security.MessageDigest

// uuid is the identity, the slot is only the click path; a class or a name is never a key
class LeapOrderCache(private val minObservations: Int = 2) {

    data class Entry(val uuid: String, val ordinal: Int, val slot: Int, val clazz: String)

    data class Record(val runId: String, val partyKey: String, val entries: List<Entry>)

    private var runId: String = ""
    private var runIndex: Int = Int.MIN_VALUE
    private var partyKey: String = ""
    private var entries: List<Entry> = emptyList()
    private val observations = HashMap<String, MutableList<Map<String, Int>>>()

    val active: Boolean
        get() = runId.isNotEmpty() && entries.isNotEmpty()

    val currentRunId: String
        get() = runId

    // a new run or a changed party changes the identity, an old mapping must never leak into it
    @Synchronized
    fun beginRun(index: Int, partyUuids: Collection<String>): Record? {
        val key = fingerprint(partyUuids)
        // clamp before storing: the run id and the stored index must agree even on negative input
        val run = index.coerceAtLeast(0)
        // the run marker is part of the identity, so two runs of the same party stay separate
        if (runId.isNotEmpty() && (key != partyKey || run != runIndex)) invalidate()
        if (runId.isEmpty()) {
            runId = "run-%06d".format(run)
            runIndex = run
            partyKey = key
            entries = emptyList()
        }
        return record()
    }

    // one observation is never enough to predict a slot
    @Synchronized
    fun observe(raw: List<Entry>): Record? {
        if (runId.isEmpty() || raw.isEmpty()) return null
        val normalized = raw
            .map { it.copy(uuid = it.uuid.trim().lowercase()) }
            .filter { it.uuid.isNotEmpty() && it.slot >= 0 }
            .sortedBy { it.ordinal }
        if (normalized.isEmpty()) return null
        if (normalized.map { it.uuid }.toSet().size != normalized.size) return null
        if (normalized.map { it.slot }.toSet().size != normalized.size) return null

        entries = normalized
        val slots = normalized.associate { it.uuid to it.slot }
        val list = observations.getOrPut(partyKey) { mutableListOf() }
        // a differing layout restarts the evidence, it never inherits the previous confidence
        if (list.isNotEmpty() && list.last() != slots) list.clear()
        list.add(slots)
        // only the latest agreeing observations matter, so a long run cannot grow this forever
        while (list.size > minObservations.coerceAtLeast(1)) list.removeAt(0)
        return record()
    }

    @Synchronized
    fun validated(): Boolean {
        if (partyKey.isEmpty()) return false
        val list = observations[partyKey] ?: return false
        if (list.size < minObservations) return false
        return list.all { it == list.first() }
    }

    // number of agreeing observations retained for the active party, exposed so its bound is testable
    @Synchronized
    internal fun evidenceCount(): Int = observations[partyKey]?.size ?: 0

    // liveUuids is the roster that can actually be clicked right now; null keeps the last observed entries
    @Synchronized
    fun slotFor(uuid: String, liveUuids: Set<String>? = null): Int? =
        resolve(uuid, liveUuids ?: entries.map { it.uuid }.toSet())?.slot

    // ambiguous or stale mappings resolve to null, never to a guessed slot
    @Synchronized
    fun resolve(uuid: String, liveUuids: Set<String>): Entry? {
        if (!validated()) return null
        val key = uuid.trim().lowercase()
        if (key.isEmpty() || key !in liveUuids.map { it.trim().lowercase() }) return null
        val matches = entries.filter { it.uuid == key }
        return matches.singleOrNull()
    }

    @Synchronized
    fun record(): Record? =
        if (runId.isEmpty() || entries.isEmpty()) null else Record(runId, partyKey, entries.toList())

    @Synchronized
    fun invalidate() {
        runId = ""
        runIndex = Int.MIN_VALUE
        partyKey = ""
        entries = emptyList()
        observations.clear()
    }

    // the persisted shape keeps the ordinal and the absolute slot apart on purpose
    @Synchronized
    fun logEntry(): Map<String, Map<String, Any>>? {
        val rec = record() ?: return null
        val players = LinkedHashMap<String, List<String>>()
        val slotByUuid = LinkedHashMap<String, Int>()
        for (entry in rec.entries) {
            players[entry.uuid] = listOf("[${entry.ordinal}]", entry.clazz)
            slotByUuid[entry.uuid] = entry.slot
        }
        return mapOf(rec.runId to mapOf("players" to players, "slotByUuid" to slotByUuid))
    }

    companion object {
        // stable party identity, independent of roster order
        fun fingerprint(uuids: Collection<String>): String {
            val joined = uuids.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
            val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}