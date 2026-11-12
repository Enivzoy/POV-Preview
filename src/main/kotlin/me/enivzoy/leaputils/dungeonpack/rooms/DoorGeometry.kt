package me.enivzoy.leaputils.dungeonpack.rooms

import me.enivzoy.leaputils.dungeonpack.pack.DoorModel
import me.enivzoy.leaputils.dungeonpack.pack.DungeonFrame
import me.enivzoy.leaputils.dungeonpack.pack.layouts.DoorKind

data class Slot(val axis: Char, val tx: Int, val tz: Int)

class Edit(val x: Int, val y: Int, val z: Int, val state: String, val quarterTurns: Int = 0, val texture: String? = null)

data class Block(val state: String, val quarterTurns: Int = 0)

object DoorGeometry {
    const val OPEN_MIN = 14
    const val OPEN_MAX = 16
    const val FRAME_MIN = 13
    const val FRAME_MAX = 17

    private const val SIDES = "NESW"

    fun turn(side: Char, quarterTurns: Int): Char {
        val i = SIDES.indexOf(side)
        require(i >= 0) { "side must be N, E, S or W, not '$side'" }
        return SIDES[(i + quarterTurns).mod(4)]
    }

    fun slotOf(tx: Int, tz: Int, side: Char): Slot? {
        val s = when (side) {
            'E' -> Slot('E', tx, tz)
            'W' -> Slot('E', tx - 1, tz)
            'S' -> Slot('S', tx, tz)
            'N' -> Slot('S', tx, tz - 1)
            else -> throw IllegalArgumentException("side must be N, E, S or W, not '$side'")
        }
        return s.takeIf { DungeonFrame.isSlot(it.axis, it.tx, it.tz) }
    }

    fun sidesOf(slot: Slot): List<Triple<Int, Int, Char>> = when (slot.axis) {
        'E' -> listOf(Triple(slot.tx, slot.tz, 'E'), Triple(slot.tx + 1, slot.tz, 'W'))
        else -> listOf(Triple(slot.tx, slot.tz, 'S'), Triple(slot.tx, slot.tz + 1, 'N'))
    }

    fun sideCell(tx: Int, tz: Int, side: Char, a: Int, depth: Int): Pair<Int, Int> {
        val ox = DungeonFrame.tileMin(tx); val oz = DungeonFrame.tileMin(tz)
        return when (side) {
            'N' -> (ox + a) to (oz + depth)
            'S' -> (ox + a) to (oz + 30 - depth)
            'W' -> (ox + depth) to (oz + a)
            'E' -> (ox + 30 - depth) to (oz + a)
            else -> throw IllegalArgumentException("side must be N, E, S or W, not '$side'")
        }
    }

    fun gapCell(slot: Slot, a: Int): Pair<Int, Int> = when (slot.axis) {
        'E' -> (DungeonFrame.tileMin(slot.tx) + 31) to (DungeonFrame.tileMin(slot.tz) + a)
        else -> (DungeonFrame.tileMin(slot.tx) + a) to (DungeonFrame.tileMin(slot.tz) + 31)
    }

    fun openingCells(tx: Int, tz: Int, side: Char, depth: Int): List<Triple<Int, Int, Int>> = buildList(12) {
        for (a in OPEN_MIN..OPEN_MAX) {
            val (x, z) = sideCell(tx, tz, side, a, depth)
            for (y in DoorModel.BODY_Y_MIN..DoorModel.BODY_Y_MAX) add(Triple(x, y, z))
        }
    }

    fun bodyEdits(slot: Slot, kind: DoorKind): List<Edit> {
        val block = DoorModel.bodyBlock(kind) ?: return emptyList()
        val (cx, cz) = DungeonFrame.doorSpot(slot.axis, slot.tx, slot.tz)
        return DoorModel.bodyCells(cx, cz).map { Edit(it.x, it.y, it.z, block) }
    }
}
