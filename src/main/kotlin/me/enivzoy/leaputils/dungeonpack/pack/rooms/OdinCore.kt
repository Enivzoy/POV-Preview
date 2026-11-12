package me.enivzoy.leaputils.dungeonpack.pack.rooms

object OdinCore {
    private const val AIR = "minecraft:air"

    fun hash(name: (Int) -> String?): Int {
        val sb = StringBuilder()
        var found = false
        var bedrock = 0
        var y = 140
        while (y >= 12) {
            val b = name(y)?.takeIf { it != AIR }
            if (!found) {
                if (b != null && b != "minecraft:gold_block") found = true else sb.append('0')
            }
            if (found) {
                if (b == null && bedrock >= 2 && y < 69) { repeat(y - 11) { sb.append('0') }; break }
                if (b == "minecraft:bedrock") bedrock++
                else {
                    bedrock = 0
                    if (b == "minecraft:oak_planks" || b == "minecraft:trapped_chest" || b == "minecraft:chest") { y--; continue }
                }
                sb.append("Block{").append(b ?: AIR).append('}')
            }
            y--
        }
        return sb.toString().hashCode()
    }

    fun ofStates(state: (Int) -> String?): Int = hash { y -> state(y)?.substringBefore('[') }

    fun ofVolume(volume: RoomVolume, x: Int, z: Int): Int = ofStates { y -> if (volume.contains(x, y, z)) volume.stateAt(x, y, z) else null }

    fun ofRoom(volume: RoomVolume, tiles: List<me.enivzoy.leaputils.dungeonpack.pack.Tile>): List<Int> = tiles.map { ofVolume(volume, 32 * it.x + 15, 32 * it.z + 15) }
}
