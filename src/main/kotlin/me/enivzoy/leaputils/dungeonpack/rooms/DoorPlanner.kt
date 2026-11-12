package me.enivzoy.leaputils.dungeonpack.rooms

import me.enivzoy.leaputils.dungeonpack.pack.doors.DoorPack
import me.enivzoy.leaputils.dungeonpack.pack.doors.PatchCell
import me.enivzoy.leaputils.dungeonpack.pack.layouts.DoorKind
import me.enivzoy.leaputils.dungeonpack.pack.rooms.Placement
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomDesign
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomVolume

class RoomInstance(val design: RoomDesign, val volume: RoomVolume, val placement: Placement) {

    fun stateAt(x: Int, y: Int, z: Int): String? {
        val (cx, cz) = placement.toCanonical(x, z) ?: return null
        if (!volume.contains(cx, y, cz)) return null
        return volume.stateAt(cx, y, cz).takeIf { it != RoomVolume.AIR }
    }

    fun isSolid(x: Int, y: Int, z: Int): Boolean = stateAt(x, y, z) != null
}

object DoorPlanner {

    fun plan(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, seed: Long = 0L, pack: DoorPack = DoorPack.bundled): List<Edit> {
        val out = ArrayList<Edit>()
        val looks = sides(rooms, doors, pack, out)
        for ((slot, kind) in doors.entries.sortedWith(compareBy({ it.key.axis }, { it.key.tz }, { it.key.tx }))) {
            if (!kind.connected) continue
            out += DoorUnits.edits(pack, slot, kind, seed, looks::keepAt, looks::floorAt)
        }
        return out
    }

    class FloorLooks internal constructor(private val rooms: List<RoomInstance>) {
        internal val pasted = HashMap<Long, Block>()
        internal val closed = HashMap<Long, Block>()
        fun floorAt(x: Int, z: Int): Block? = pasted[cellKey(x, z)] ?: base(x, z)
        fun keepAt(x: Int, z: Int): Block? = closed[cellKey(x, z)] ?: base(x, z)
        private fun base(x: Int, z: Int): Block? =
            rooms.firstNotNullOfOrNull { r -> r.stateAt(x, DoorUnits.FLOOR_Y, z)?.let { Block(it, r.placement.quarterTurns) } }
    }

    fun looks(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, pack: DoorPack = DoorPack.bundled): FloorLooks = sides(rooms, doors, pack, null)

    private fun sides(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, pack: DoorPack, out: MutableList<Edit>?): FloorLooks {
        val looks = FloorLooks(rooms)
        for (room in rooms) {
            val p = room.placement
            for (slot in room.design.slots) {
                val wt = p.worldTileOf(slot.tile.x, slot.tile.z)
                val gap = DoorGeometry.slotOf(wt.x, wt.z, DoorGeometry.turn(slot.side, p.quarterTurns))
                val connected = gap != null && doors[gap]?.connected == true
                val known = pack.side(room.design.id, slot.tile.x, slot.tile.z, slot.side) ?: continue
                val shut = known.closed ?: known.closedEstimated
                val cells = (if (connected) known.open else null) ?: shut ?: continue
                patch(room, cells, out, looks.pasted)
                if (connected && shut != null) patch(room, shut, null, looks.closed)
            }
        }
        return looks
    }

    private fun patch(room: RoomInstance, cells: List<PatchCell>, out: MutableList<Edit>?, floor: MutableMap<Long, Block>) {
        val p = room.placement
        for (c in cells) {
            val w = p.toWorld(c.x, c.y, c.z)
            out?.add(Edit(w.x, w.y, w.z, c.state, p.quarterTurns))
            if (w.y == DoorUnits.FLOOR_Y) floor[cellKey(w.x, w.z)] = Block(c.state, p.quarterTurns)
        }
    }

    private fun cellKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
