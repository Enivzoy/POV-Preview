package me.enivzoy.leaputils.dungeonpack.pack.runstate

import me.enivzoy.leaputils.dungeonpack.pack.*
import com.google.gson.JsonElement

class Dist(
    val n: Int,
    val pct: DoubleArray,
    val q: DoubleArray,
    val min: Double?,
    val max: Double?,
) {
    init {
        require(pct.size == q.size && q.isNotEmpty()) { "a distribution needs as many values as percents" }
    }

    val advisory: Boolean get() = n < 8

    val median: Double get() = at(50.0)

    fun at(p: Double): Double {
        val last = q.size - 1
        if (p <= pct[0]) {
            val lo = min ?: return q[0]
            if (pct[0] <= 0.0) return q[0]
            return lo + (q[0] - lo) * (p.coerceAtLeast(0.0) / pct[0])
        }
        if (p >= pct[last]) {
            val hi = max ?: return q[last]
            if (pct[last] >= 100.0) return q[last]
            return q[last] + (hi - q[last]) * ((p.coerceAtMost(100.0) - pct[last]) / (100.0 - pct[last]))
        }
        for (i in 1..last) if (p <= pct[i]) {
            val span = pct[i] - pct[i - 1]
            return if (span <= 0.0) q[i] else q[i - 1] + (q[i] - q[i - 1]) * ((p - pct[i - 1]) / span)
        }
        return q[last]
    }

    fun sample(u: Double): Double = at(100.0 * u.coerceIn(0.0, 1.0))

    companion object {

        val DEFAULT_PCT = doubleArrayOf(5.0, 10.0, 25.0, 50.0, 75.0, 90.0, 95.0)

        fun parse(e: JsonElement?, globalPct: DoubleArray = DEFAULT_PCT): Dist? {
            val o = e.asObj() ?: return null
            val q = o.arr("q").doubles()
            if (q.isEmpty()) return null
            val pct = o.arr("pct")?.doubles()?.takeIf { it.isNotEmpty() } ?: globalPct
            if (pct.size != q.size) return null
            return Dist(o.int("n") ?: 0, pct, q, o.dbl("min"), o.dbl("max"))
        }
    }
}
