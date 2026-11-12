package me.enivzoy.leaputils.dungeonpack.pack

object DungeonFrame {

    const val ORIGIN = -200

    const val PITCH = 32

    const val TILE_SIZE = 31

    const val TILES = 6

    const val TILE_COUNT = TILES * TILES

    const val DUNGEON_MAX = ORIGIN + PITCH * (TILES - 1) + TILE_SIZE - 1

    const val ARENA_MIN = -8

    const val DOOR_Y_MIN = 69
    const val DOOR_Y_MAX = 72

    fun tileMin(t: Int): Int = ORIGIN + PITCH * t

    fun tileCentre(t: Int): Int = tileMin(t) + 15

    fun tileIndex(v: Double): Int = Math.floorDiv(Math.floor(v).toInt() - ORIGIN, PITCH).coerceIn(0, TILES - 1)

    fun roomTileIndexOrNull(v: Double): Int? {
        val b = Math.floor(v).toInt()
        val t = Math.floorDiv(b - ORIGIN, PITCH)
        if (t !in 0 until TILES) return null
        return if (b - tileMin(t) <= TILE_SIZE - 1) t else null
    }

    fun tileOf(x: Double, z: Double): Tile? {
        val tx = roomTileIndexOrNull(x) ?: return null
        val tz = roomTileIndexOrNull(z) ?: return null
        return Tile(tx, tz)
    }

    fun inDungeonBox(x: Int, z: Int): Boolean = x in ORIGIN..DUNGEON_MAX && z in ORIGIN..DUNGEON_MAX

    fun doorSpot(axis: Char, tx: Int, tz: Int): Pair<Int, Int> = when (axis) {
        'E' -> (ORIGIN + 31 + PITCH * tx) to (tileCentre(tz))
        'S' -> tileCentre(tx) to (ORIGIN + 31 + PITCH * tz)
        else -> throw IllegalArgumentException("door axis must be E or S, not '$axis'")
    }

    fun slotTiles(axis: Char, tx: Int, tz: Int): Pair<Tile, Tile> = when (axis) {
        'E' -> Tile(tx, tz) to Tile(tx + 1, tz)
        'S' -> Tile(tx, tz) to Tile(tx, tz + 1)
        else -> throw IllegalArgumentException("door axis must be E or S, not '$axis'")
    }

    fun isSlot(axis: Char, tx: Int, tz: Int): Boolean = when (axis) {
        'E' -> tx in 0..TILES - 2 && tz in 0 until TILES
        'S' -> tx in 0 until TILES && tz in 0..TILES - 2
        else -> false
    }
}
