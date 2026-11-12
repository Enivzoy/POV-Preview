package me.enivzoy.leaputils.dungeonpack.pack

import me.enivzoy.leaputils.dungeonpack.pack.layouts.DoorKind

object DoorModel {
    const val BODY_Y_MIN = DungeonFrame.DOOR_Y_MIN
    const val BODY_Y_MAX = DungeonFrame.DOOR_Y_MAX

    const val LINTEL_Y = 73

    const val THRESHOLD_Y = 68

    const val HALF = 1

    const val BODY_SIZE = 36

    const val FALLING_BLOCK = "minecraft:barrier"
    const val AIR = "minecraft:air"

    fun bodyBlock(kind: DoorKind): String? = when (kind) {
        DoorKind.WITHER, DoorKind.FAIRY -> "minecraft:coal_block"
        DoorKind.BLOOD -> "minecraft:red_terracotta"
        DoorKind.ENTRANCE -> "minecraft:infested_chiseled_stone_bricks"
        else -> null
    }

    fun bodyCells(cx: Int, cz: Int): List<Pos> = buildList(BODY_SIZE) {
        for (y in BODY_Y_MIN..BODY_Y_MAX) for (z in cz - HALF..cz + HALF) for (x in cx - HALF..cx + HALF) add(Pos(x, y, z))
    }

    fun inBody(cx: Int, cz: Int, x: Int, y: Int, z: Int): Boolean =
        y in BODY_Y_MIN..BODY_Y_MAX && x in cx - HALF..cx + HALF && z in cz - HALF..cz + HALF
}
