package me.enivzoy.leaputils.pov

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import java.nio.ByteBuffer

object PoseWire {
    const val D_TICK = 9
    const val D_LOOK = 10

    const val D_ROUTE = 11

    const val D_ROOMS = 12

    fun turnKind(relayKind: Int) = if (relayKind == 9) D_ROOMS else relayKind
    const val TICK_NS = 50_000_000L

    const val STILL_NS = 100_000_000L

    fun yaw16(yaw: Float) = (Mth.wrapDegrees(yaw) / 360f * 65536f).toInt()
    fun pitch16(pitch: Float) = (pitch.coerceIn(-90f, 90f) / 90f * 32767f).toInt()

    fun look(yaw: Float, pitch: Float): Long = ((yaw16(yaw) and 0xFFFF).toLong() shl 16) or (pitch16(pitch) and 0xFFFF).toLong()

    fun put24(b: ByteBuffer, v: Double) {
        val i = (v * 256).toInt().coerceIn(-0x800000, 0x7FFFFF)
        b.put((i shr 16).toByte()).put((i shr 8).toByte()).put(i.toByte())
    }

    fun get24(b: ByteBuffer): Double {
        val i = ((b.get().toInt() shl 16) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() and 255))
        return i / 256.0
    }

    fun tickBody(ms: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, item: Int?): ByteArray {
        val b = ByteBuffer.allocate(if (item != null) 18 else 16)
        b.putShort(ms.toShort())
        put24(b, x); put24(b, y); put24(b, z)
        b.putShort(yaw16(yaw).toShort())
        b.putShort(pitch16(pitch).toShort())
        b.put((flags or (if (item != null) 0x80 else 0)).toByte())
        if (item != null) b.putShort(item.toShort())
        return b.array()
    }

    fun lookBody(ms: Int, look: Long) =
        byteArrayOf((ms shr 8).toByte(), ms.toByte(), (look shr 24).toByte(), (look shr 16).toByte(), (look shr 8).toByte(), look.toByte())

    fun relay(kind: Int, index: Int, age: Int, seq: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, rttMs: Double, item: Int): ByteArray {
        val b = ByteBuffer.allocate(if (kind == 2) 22 else 20)
        b.put(kind.toByte())
        if (kind == 2) b.put(index.toByte()).put(age.toByte())
        b.putShort(seq.toShort())
        put24(b, x); put24(b, y); put24(b, z)
        b.putShort(yaw16(yaw).toShort())
        b.putShort(pitch16(pitch).toShort())
        b.put(flags.toByte())
        b.put((if (rttMs > 0) (rttMs / 4).toInt().coerceIn(0, 255) else 0).toByte())
        b.putShort(item.toShort())
        return b.array()
    }

    class Heard(val raw: Long, val pos: Vec3?, val yaw: Float, val pitch: Float, val flags: Int, val item: Int, val halfMs: Int)

    fun readTick(body: ByteArray): Heard? {
        if (!(body.size == 16 || (body.size == 18 && body[15].toInt() and 0x80 != 0))) return null

        if (body[15].toInt() and 0x7C != 0) return null
        val b = ByteBuffer.wrap(body)
        val ms = b.short.toLong() and 0xFFFF
        val pos = Vec3(get24(b), get24(b), get24(b))
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        val flags = b.get().toInt() and 255
        val item = if (flags and 0x80 != 0) b.short.toInt() and 0xFFFF else -1
        return Heard(ms, pos, yaw, pitch, flags and 0x7F, item, 0)
    }

    fun readLook(body: ByteArray): Heard? {
        if (body.size != 6) return null
        val b = ByteBuffer.wrap(body)
        val ms = b.short.toLong() and 0xFFFF
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        return Heard(ms, null, yaw, pitch, 0, -1, 0)
    }

    fun readRelay(b: ByteBuffer): Heard {
        val seq = b.short.toLong() and 0xFFFF
        val pos = Vec3(get24(b), get24(b), get24(b))
        val yaw = (b.short.toInt() and 0xFFFF) / 65536f * 360f
        val pitch = b.short / 32767f * 90f
        val flags = b.get().toInt() and 255
        val half = (b.get().toInt() and 255) * 2
        val item = b.short.toInt() and 0xFFFF
        return Heard(seq, pos, yaw, pitch, flags, item, half)
    }

    fun viaTurn(quick: Boolean?, live: Boolean, optimize: Boolean) = live && (!optimize || quick != false)

    const val ROUTE_HOLD_MS = 3_000L

    const val ROUTE_DWELL_MS = 15_000L

    class Route {
        var quick: Boolean? = null; private set
        private var changedAt = Long.MIN_VALUE / 2
        private var otherSince = -1L
        var switches = 0; private set

        fun update(nowMs: Long, turnMs: Double, relayMs: Double): Boolean? {
            val was = quick
            if (turnMs <= 0 || relayMs <= 0) return was
            val margin = maxOf(5.0, 0.1 * minOf(turnMs, relayMs))
            val better = if (was != false) turnMs <= relayMs + margin else turnMs + margin < relayMs

            if (was == null) { quick = better; return better }
            if (better == was) { otherSince = -1L; return was }
            if (otherSince < 0) otherSince = nowMs
            if (nowMs - otherSince < ROUTE_HOLD_MS || (was && nowMs - changedAt < ROUTE_DWELL_MS)) return was
            quick = better; changedAt = nowMs; otherSince = -1L; switches++
            return better
        }
    }

    fun tiny(a: Long, b: Long): Boolean {
        val dy = (((a shr 16) - (b shr 16) + 32768) and 0xFFFF) - 32768
        val dp = (a and 0xFFFF).toInt().toShort() - (b and 0xFFFF).toInt().toShort()
        return kotlin.math.abs(dy) < 0.05 / 360 * 65536 && kotlin.math.abs(dp) < 0.05 / 90 * 32767
    }

    class Sender {

        private var lastTick: ByteArray? = null
        private var tickChanged = false
        private var tickSentAt = 0L
        private var tickItem = -1
        private var tickItemAt = 0L

        private var frameAt = 0L
        private var lastLook = -1L
        private var lookChanged = false
        private var lastLookMs = 0

        private var lastSig: ByteArray? = null
        private var prevFrame: ByteArray? = null
        private var prevSent = false
        var selfAt = 0L

        var looksSent = 0L; private set
        var looksSkipped = 0L; private set

        fun tick(now: Long, ms: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, item: Int): List<ByteArray>? {
            val withItem = item != tickItem || now - tickItemAt > 1_000_000_000L
            val body = tickBody(ms, x, y, z, yaw, pitch, flags, if (withItem) item else null)

            val changed = lastTick?.let { !body.copyOfRange(2, 15).contentEquals(it.copyOfRange(2, 15)) || (flags and 3) != 0 } ?: true
            val prev = lastTick
            lastTick = body
            val send = changed || tickChanged || now - tickSentAt > STILL_NS
            val wasChanged = tickChanged
            tickChanged = changed
            if (!send) return null
            val out = ArrayList<ByteArray>(2)
            if (changed && !wasChanged && prev != null && now - tickSentAt > TICK_NS + 5_000_000L) out += prev
            tickSentAt = now
            if (withItem) { tickItem = item; tickItemAt = now }
            out += body
            return out
        }

        fun look(now: Long, ms: Int, yaw: Float, pitch: Float, frameNs: Long, skipTiny: Boolean): List<ByteArray>? {
            if (now - frameAt < frameNs - 1_000_000L) return null
            frameAt = now
            val look = look(yaw, pitch)
            val changed = look != lastLook && !(skipTiny && lastLook >= 0 && tiny(look, lastLook))
            if (changed) looksSent++ else if (look != lastLook) looksSkipped++
            val out = ArrayList<ByteArray>(2)
            if (changed && !lookChanged && lastLook >= 0) out += lookBody(lastLookMs, lastLook)
            if (changed) out += lookBody(ms, look)
            lookChanged = changed
            if (changed) lastLook = look
            lastLookMs = ms
            return out
        }

        fun relayFrames(frame: ByteArray, now: Long, relay: Boolean): List<ByteArray> {
            val sig = frame.copyOfRange(3, 16) + frame.copyOfRange(17, 20)
            val changed = !sig.contentEquals(lastSig)
            val out = ArrayList<ByteArray>(2)
            if (changed && !prevSent && relay) prevFrame?.let { out += it }
            prevSent = changed || now - selfAt > STILL_NS
            if (prevSent) { if (relay) out += frame; selfAt = now }
            prevFrame = frame; lastSig = sig
            return out
        }

        fun forgetRelay() { lastSig = null; prevFrame = null }
    }
}
