package me.enivzoy.leaputils.pov

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

class PoseTrack(private val tuning: Tuning = Tuning()) {

    data class Tuning(val rise: Double = 0.25, val ease: Double = 0.97, val posBufferMaxNs: Long = POS_BUFFER_MAX_NS)

    class Sample(val seq: Long, val time: Long, val arrived: Long, val pos: Vec3, val yaw: Float, val pitch: Float, val flags: Int, val item: Int, val delayMs: Int, val direct: Boolean)

    class Drawn(val pos: Vec3, val yaw: Float, val pitch: Float, val item: Int, val swingAt: Long, val sneaking: Boolean, val via: String?, val direct: Boolean, val delayMs: Int, val bufferNs: Long, val timed: Boolean)

    val samples = ArrayDeque<Sample>()

    var offset = Long.MAX_VALUE
    var lastSeq = -1L

    var timed = false

    val ticks = ArrayDeque<Pair<Long, Vec3>>()

    val phases = IntArray(16)
    var phaseN = 0
    var item = 0
    var sneaking = false
    var lastArrived = 0L

    var jitter = 3_000_000.0
    var gap = TICK_NS.toDouble()
    var buffer = 70_000_000L

    var tickLate = 30_000_000.0
    fun posBuffer() = maxOf(buffer, (tickLate + 3_000_000).toLong()).coerceAtMost(tuning.posBufferMaxNs)
    var source = 0
    var via: String? = null
    var swingAt = 0L

    var arrivals = 0L
    @Volatile var gui: PoseShare.Gui? = null
    fun at(s: Sample) = s.time + offset

    fun due(ms: Long): Long {
        phases[phaseN++ % phases.size] = Math.floorMod(ms, 50L).toInt()
        val n = minOf(phaseN, phases.size)
        val r = phases.copyOf(n).also { it.sort() }
        var start = r[0]; var widest = -1
        for (i in 0 until n) { val gap = if (i == 0) r[0] + 50 - r[n - 1] else r[i] - r[i - 1]; if (gap > widest) { widest = gap; start = r[i] } }
        return ms - Math.floorMod(ms - start, 50L)
    }

    fun reset() { samples.clear(); ticks.clear(); offset = Long.MAX_VALUE; lastSeq = -1; phaseN = 0 }

    var turnLeftAt = 0L
    var leftTurn = false
    fun turnLeft(now: Long) = synchronized(this) { turnLeftAt = now; leftTurn = true }

    fun drawn(now: Long): Drawn? = synchronized(this) {
        val t = this
        val last = t.samples.lastOrNull() ?: return null
        if (now - last.arrived > STALE_NS) return null
        val at = now - t.buffer

        var a = t.samples.first(); var b = last
        for (s in t.samples) { if (t.at(s) <= at) a = s else { b = s; break } }
        var pos: Vec3; val yaw: Float; val pitch: Float
        if (a === b || t.at(b) == t.at(a)) { pos = b.pos; yaw = b.yaw; pitch = b.pitch }
        else {

            val k = ((minOf(at, t.at(b) + EXTRAPOLATE_NS) - t.at(a)).toDouble() / (t.at(b) - t.at(a))).coerceIn(0.0, if (t.timed) 1.0 else Double.MAX_VALUE)
            pos = a.pos.lerp(b.pos, k)
            yaw = a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * k.toFloat()
            pitch = Mth.lerp(k.toFloat().coerceAtMost(1f), a.pitch, b.pitch)
        }
        var itemId = b.item; var sneaking = b.flags and 2 != 0
        if (t.timed) {

            val atPos = now - t.posBuffer()
            val p1 = t.ticks.lastOrNull() ?: return null
            val p0 = t.ticks.lastOrNull { it.first < p1.first }
            val t1 = p1.first + t.offset
            var lo: Pair<Long, Vec3>? = null; var hi = p1
            for (p in t.ticks) { if (p.first + t.offset <= atPos) lo = p else { hi = p; break } }
            pos = when {
                lo == null -> t.ticks.first().second
                atPos < t1 && hi !== lo -> lo.second.lerp(hi.second, ((atPos - lo.first - t.offset).toDouble() / (hi.first - lo.first)).coerceIn(0.0, 1.0))
                p0 == null -> p1.second
                else -> p0.second.lerp(p1.second, 1.0 + (minOf(atPos - t1, CARRY_NS)).coerceAtLeast(0L).toDouble() / (p1.first - p0.first))
            }
            itemId = t.item; sneaking = t.sneaking
        }
        val delay = ((if (t.timed) t.posBuffer() else t.buffer) / 1_000_000).toInt() + b.delayMs
        return Drawn(pos, yaw, pitch, itemId, t.swingAt, sneaking, t.via, b.direct, delay, t.buffer, t.timed)
    }

    fun heard(via: String?, source: Int, now: Long, direct: Boolean, timed: Boolean, raw: Long, pos: Vec3?, yaw: Float, pitch: Float, flags: Int, item: Int, delayMs: Int): Boolean = synchronized(this) {
        val t = this
        val fresh = t.samples.lastOrNull()?.let { now - it.arrived <= STALE_NS } == true

        if (t.source != source) {
            if (fresh) return false
            t.source = source; reset()
        }

        if (t.timed != timed) {
            val left = t.leftTurn && now - t.turnLeftAt < SWITCH_NS
            if (!timed && fresh && !left) return false

            if (timed && ((pos == null && fresh) || (t.leftTurn && now - t.turnLeftAt < LATE_NS))) return false
            t.timed = timed; reset()
            t.gap = if (timed) 16_000_000.0 else TICK_NS.toDouble()

            if (!timed) t.buffer = maxOf(t.buffer, 70_000_000L)
        }
        t.via = via

        val s = if (t.lastSeq < 0) raw else t.lastSeq + ((raw - (t.lastSeq and 0xFFFF) + 0x8000) and 0xFFFF) - 0x8000

        if (!timed && t.lastSeq >= 0 && s <= t.lastSeq) return false
        if (timed && t.lastSeq >= 0 && s < t.lastSeq - 1000) return false
        val time = if (timed) s * 1_000_000L else s * TICK_NS
        val off = now - time

        if (!fresh || (t.offset != Long.MAX_VALUE && kotlin.math.abs(off - t.offset) > 1_000_000_000L)) reset()
        if (s > t.lastSeq) t.lastSeq = s

        val since = if (t.lastArrived == 0L) 0L else (now - t.lastArrived).coerceIn(0L, 1_000_000_000L)
        t.offset = if (t.offset == Long.MAX_VALUE) off else minOf(off, t.offset + since / 250)

        val late = (off - t.offset).toDouble().coerceAtLeast(0.0)
        t.jitter = t.jitter * 0.9 + late * 0.1
        val gap = now - t.lastArrived
        if (t.lastArrived != 0L && gap < 80_000_000L) t.gap = t.gap * 0.9 + gap * 0.1
        t.lastArrived = now
        val target = (t.gap + 2 * t.jitter + 2_000_000).toLong().coerceIn(BUFFER_MIN_NS, BUFFER_MAX_NS)
        t.buffer = (t.buffer * 0.95 + target * 0.05).toLong()

        val sample = Sample(s, time, now, pos ?: Vec3.ZERO, yaw, pitch.coerceIn(-90f, 90f), flags, maxOf(item, 0), delayMs, direct)
        var i = t.samples.size
        while (i > 0 && t.samples[i - 1].time > time) i--
        t.samples.add(i, sample)
        while (t.samples.size > 32) t.samples.removeFirst()
        if (timed && pos != null) {

            val due = t.due(s)
            val key = (due + 50) * 1_000_000L

            val tickLate = (now - t.offset - due * 1_000_000L).toDouble().coerceAtLeast(0.0)
            t.tickLate = if (tickLate > t.tickLate) t.tickLate + (tickLate - t.tickLate) * tuning.rise else t.tickLate * tuning.ease + tickLate * (1 - tuning.ease)
            var j = t.ticks.size
            while (j > 0 && t.ticks[j - 1].first > key) j--
            if (j > 0 && t.ticks[j - 1].first == key && t.ticks[j - 1].second != pos && (j < 2 || t.ticks[j - 2].first < key - TICK_NS)) {

                t.ticks[j - 1] = (key - TICK_NS) to t.ticks[j - 1].second
            }
            if (j == 0 || t.ticks[j - 1].first != key) t.ticks.add(j, key to pos)
            while (t.ticks.size > 8) t.ticks.removeFirst()
            if (j >= t.ticks.size - 1) { if (item >= 0) t.item = item; t.sneaking = flags and 2 != 0 }
        }
        if (flags and 1 != 0) t.swingAt = now
        t.arrivals++
        return true
    }

    companion object {
        const val TICK_NS = 50_000_000L

        const val BUFFER_MIN_NS = 6_000_000L
        const val BUFFER_MAX_NS = 90_000_000L

        const val POS_BUFFER_MAX_NS = 120_000_000L
        const val EXTRAPOLATE_NS = 100_000_000L

        const val CARRY_NS = 60_000_000L

        const val STALE_NS = 300_000_000L

        const val SWITCH_NS = 2_000_000_000L
        const val LATE_NS = 300_000_000L
    }
}
