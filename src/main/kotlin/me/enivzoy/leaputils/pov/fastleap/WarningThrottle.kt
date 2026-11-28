package me.enivzoy.leaputils.pov.fastleap

// chat throttle keyed by the warning text, so each rule/reason pair gets its own window
class WarningThrottle(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val last = HashMap<String, Long>()

    fun allow(key: String, nowMs: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMs - prev < windowMs) return false
        last[key] = nowMs
        if (last.size > MAX_KEYS) last.entries.removeIf { nowMs - it.value >= windowMs }
        return true
    }

    fun clear() = last.clear()

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
        private const val MAX_KEYS = 128
    }
}