package me.enivzoy.leaputils.adr

import me.enivzoy.leaputils.LeapUtils
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomShape
import com.odtheking.odin.utils.IVec2
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus

object RoomKeys {
    private val cores = HashMap<Pair<Int, Int>, Int>()

    fun reset() = cores.clear()

    fun core(room: DungeonRoom): Int? {
        val data = room.data ?: return null
        if (room.shape != RoomShape.OneByOne || data.cores.size < 2) return null
        val tile = room.tiles.firstOrNull() ?: return null
        cores[tile.x to tile.z]?.let { return it }
        val cx = (tile.x - 6) * 2; val cz = (tile.z - 6) * 2
        val chunk = LeapUtils.mc.level?.chunkSource?.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: return null
        val core = WorldScan.getRoomCore(chunk, IVec2(cx * 16 + 7, cz * 16 + 7)).first
        if (core !in data.cores) return null
        cores[tile.x to tile.z] = core
        return core
    }

    fun rotationKnown(room: DungeonRoom): Boolean =
        room.shape != RoomShape.OneByOne || room.clayPos != null || room.data?.name == "Fairy"

    fun key(room: DungeonRoom): String? {
        val name = room.data?.name ?: return null
        val rotation = room.rotation ?: return null
        if (!rotationKnown(room)) return null
        val multi = room.shape == RoomShape.OneByOne && (room.data?.cores?.size ?: 0) > 1
        if (!multi) return "$name|${rotation.name}"
        val core = core(room) ?: return null
        return "$name|${rotation.name}|$core"
    }
}
