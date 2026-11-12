package me.enivzoy.leaputils.pov

import me.enivzoy.leaputils.dungeonpack.pack.DungeonFrame
import java.util.BitSet

class RoomCut(val w: Int, val d: Int, val y0: Int, val h: Int, val hidden: BitSet?) {

    fun hiddenAt(x: Int, y: Int, z: Int): Boolean {
        val hid = hidden ?: return false
        if (x !in 0 until w || z !in 0 until d || y - y0 !in 0 until h) return false
        return hid.get(((y - y0) * d + z) * w + x)
    }

    fun drops(g: Int, pos: FloatArray, pockets: Boolean): Boolean {
        val cx = (pos[0] + pos[3] + pos[6] + pos[9]) / 4f
        val cy = (pos[1] + pos[4] + pos[7] + pos[10]) / 4f
        val cz = (pos[2] + pos[5] + pos[8] + pos[11]) / 4f
        fun all(axis: Int, v: Int) = (0 until 4).all { Math.abs(pos[it * 3 + axis] - v) < EPS }
        when (g) {
            0 -> if (all(1, y0)) return true
            2 -> if (all(2, 0) && !nearDoor(cx, cy)) return true
            3 -> if (all(2, d) && !nearDoor(cx, cy)) return true
            4 -> if (all(0, 0) && !nearDoor(cz, cy)) return true
            5 -> if (all(0, w) && !nearDoor(cz, cy)) return true
        }
        if (!pockets || hidden == null) return false

        var fx = Math.floor(cx.toDouble()).toInt(); var fy = Math.floor(cy.toDouble()).toInt(); var fz = Math.floor(cz.toDouble()).toInt()
        if (g < 6) {
            val axis = AXIS[g]
            val p = pos[axis]
            val r = Math.round(p)
            val cell = if (Math.abs(p - r) < EPS) (if (g and 1 == 1) r else r - 1) else Math.floor(p.toDouble()).toInt()
            when (axis) { 0 -> fx = cell; 1 -> fy = cell; else -> fz = cell }
        }
        return hiddenAt(fx, fy, fz)
    }

    companion object {
        private const val EPS = 1e-3f

        private val AXIS = intArrayOf(1, 1, 2, 2, 0, 0)
        private val DOOR_Y = (DungeonFrame.DOOR_Y_MIN - 2).toFloat()..(DungeonFrame.DOOR_Y_MAX + 4).toFloat()

        private fun nearDoor(along: Float, y: Float): Boolean =
            y in DOOR_Y && Math.floorMod(Math.floor(along.toDouble()).toInt(), DungeonFrame.PITCH) in 11..19

        fun pockets(w: Int, d: Int, y0: Int, h: Int, opaque: (Int, Int, Int) -> Boolean): BitSet? {
            val n = w * d * h
            if (n <= 0) return null
            fun idx(x: Int, y: Int, z: Int) = ((y - y0) * d + z) * w + x
            val reached = BitSet(n)
            val queue = IntArray(n)
            var head = 0; var tail = 0
            fun seed(x: Int, y: Int, z: Int) {
                if (x !in 0 until w || z !in 0 until d || y - y0 !in 0 until h || opaque(x, y, z)) return
                val i = idx(x, y, z)
                if (!reached.get(i)) { reached.set(i); queue[tail++] = i }
            }
            for (y in DungeonFrame.DOOR_Y_MIN..DungeonFrame.DOOR_Y_MAX) {
                var c = 15
                while (c < maxOf(w, d)) {
                    for (a in c - 1..c + 1) {
                        if (a < d) { seed(0, y, a); seed(w - 1, y, a) }
                        if (a < w) { seed(a, y, 0); seed(a, y, d - 1) }
                    }
                    c += DungeonFrame.PITCH
                }
            }
            if (tail == 0) return null
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val z = (i / w) % d; val y = i / (w * d) + y0
                seed(x - 1, y, z); seed(x + 1, y, z); seed(x, y - 1, z); seed(x, y + 1, z); seed(x, y, z - 1); seed(x, y, z + 1)
            }
            val hidden = BitSet(n)
            for (y in y0 until y0 + h) for (z in 0 until d) for (x in 0 until w) {
                val i = idx(x, y, z)
                if (!reached.get(i) && !opaque(x, y, z)) hidden.set(i)
            }
            return hidden
        }
    }
}
