package me.enivzoy.leaputils.dungeonpack.rooms

import me.enivzoy.leaputils.dungeonpack.pack.layouts.DoorKind
import me.enivzoy.leaputils.dungeonpack.pack.secrets.SecretPack

object RegionEdits {
    private const val AIR = "minecraft:air"

    fun markerRemovals(rooms: List<RoomInstance>): List<Edit> = buildList {
        for (r in rooms) for (b in r.design.blockEntities) {
            if (!b.marker) continue
            if (!r.volume.contains(b.pos.x, b.pos.y, b.pos.z) || r.volume.isAir(b.pos.x, b.pos.y, b.pos.z)) continue

            if (b.pos.x % 32 == 15 && b.pos.z % 32 == 15) continue
            val w = r.placement.toWorld(b.pos.x, b.pos.y, b.pos.z)
            add(Edit(w.x, w.y, w.z, AIR))
        }
    }

    const val CLAY = "minecraft:blue_terracotta"

    fun fairyMarkers(rooms: List<RoomInstance>): List<Edit> = buildList {
        for (r in rooms) {
            if (!r.design.type.equals("fairy", ignoreCase = true) || r.design.roofY <= 0) continue
            val w = r.placement.toWorld(0, r.design.roofY, 0)
            add(Edit(w.x, w.y, w.z, CLAY))
        }
    }

    private val SECRET_BLOCKS = setOf("chest", "trapped_chest", "lever", "player_head", "player_wall_head")

    fun secretRemovals(rooms: List<RoomInstance>, secrets: SecretPack = SecretPack.bundled): List<Edit> = buildList {
        for (r in rooms) {
            val d = secrets.design(r.design.id) ?: continue
            val cells = d.sites.flatMap { listOfNotNull(it.pos, it.was) } + d.levers.map { it.pos } + d.key?.skulls?.map { it.first }.orEmpty()
            for (c in cells.distinct()) {
                if (!r.volume.contains(c.x, c.y, c.z)) continue
                if (r.volume.stateAt(c.x, c.y, c.z).substringBefore('[').removePrefix("minecraft:") !in SECRET_BLOCKS) continue

                if (c.x % 32 == 15 && c.z % 32 == 15) continue
                val w = r.placement.toWorld(c.x, c.y, c.z)
                add(Edit(w.x, w.y, w.z, AIR))
            }
        }
    }

    val FIXED_HOOKS: Map<String, List<Pair<IntArray, String>>> = mapOf(
        "Entrance_Closed" to listOf(intArrayOf(14, 76, 23) to "north", intArrayOf(16, 76, 23) to "north"),
        "Red_Green" to listOf(intArrayOf(16, 83, 12) to "south", intArrayOf(16, 83, 18) to "north"),
        "Ritual" to listOf(intArrayOf(24, 66, 11) to "south", intArrayOf(24, 66, 9) to "north", intArrayOf(6, 66, 11) to "south", intArrayOf(6, 66, 9) to "north"),
        "Purple_Flags" to listOf(intArrayOf(16, 62, 26) to "north", intArrayOf(9, 62, 26) to "north"),
        "Deathmite" to listOf(intArrayOf(28, 67, 15) to "east"),
    )

    fun fixedHooks(rooms: List<RoomInstance>): List<Edit> = buildList {
        for (r in rooms) for ((c, facing) in FIXED_HOOKS[r.design.id].orEmpty()) {
            if (!r.volume.contains(c[0], c[1], c[2])) continue
            val state = r.volume.stateAt(c[0], c[1], c[2]).takeIf { "tripwire_hook" in it } ?: continue
            val w = r.placement.toWorld(c[0], c[1], c[2])
            add(Edit(w.x, w.y, w.z, state.replace(Regex("facing=\\w+"), "facing=$facing")))
        }
    }

    fun gapWalls(rooms: List<RoomInstance>, pack: me.enivzoy.leaputils.dungeonpack.pack.doors.DoorPack = me.enivzoy.leaputils.dungeonpack.pack.doors.DoorPack.bundled): List<Edit> = buildList {
        for (r in rooms) for (c in pack.gapWalls[r.design.id].orEmpty()) {
            val w = r.placement.toWorld(c.x, c.y, c.z)
            add(Edit(w.x, w.y, w.z, c.state, r.placement.quarterTurns))
        }
    }

    fun build(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, seed: Long = 0L): List<Edit> =
        markerRemovals(rooms) + secretRemovals(rooms) + fairyMarkers(rooms) + fixedHooks(rooms) + gapWalls(rooms) + DoorPlanner.plan(rooms, doors, seed)
}
