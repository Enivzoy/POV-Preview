package me.enivzoy.leaputils.dungeonpack.rooms

import me.enivzoy.leaputils.dungeonpack.pack.DungeonFrame
import me.enivzoy.leaputils.dungeonpack.pack.doors.DoorPack
import me.enivzoy.leaputils.dungeonpack.pack.doors.DoorUnit
import me.enivzoy.leaputils.dungeonpack.pack.layouts.DoorKind
import kotlin.math.abs
import kotlin.random.Random

object DoorUnits {
    const val FLOOR_Y = 68
    const val DD_MAX = 3
    const val A_MIN = 13
    const val A_MAX = 17

    fun cell(slot: Slot, dd: Int, a: Int): Pair<Int, Int> = when (slot.axis) {
        'S' -> (DungeonFrame.tileMin(slot.tx) + a) to (DungeonFrame.tileMin(slot.tz) + 31 + dd)
        else -> (DungeonFrame.tileMin(slot.tx) + 31 + dd) to (DungeonFrame.tileMin(slot.tz) + 30 - a)
    }

    fun turns(slot: Slot): Int = if (slot.axis == 'S') 0 else 3

    fun random(slot: Slot, seed: Long): Random = Random(seed xor ((slot.axis.code * 36 + slot.tx * 6 + slot.tz + 1).toLong() * -0x61c8864680b583ebL))

    fun unitFor(pack: DoorPack, kind: DoorKind, rnd: Random): DoorUnit = when (kind) {
        DoorKind.ENTRANCE -> pack.entrance
        DoorKind.BLOOD -> pack.blood
        else -> pack.styles[rnd.nextInt(pack.styles.size)]
    }

    fun edits(pack: DoorPack, slot: Slot, kind: DoorKind, seed: Long, keepAt: (Int, Int) -> Block? = { _, _ -> null },
              floorAt: (Int, Int) -> Block?): List<Edit> = buildList {
        val rnd = random(slot, seed)
        val unit = unitFor(pack, kind, rnd)
        val key = kind == DoorKind.WITHER || kind == DoorKind.FAIRY
        val head = when { unit === pack.blood -> pack.bloodHead; key -> pack.keyHead; else -> pack.plainHead }
        val q = turns(slot)
        val cells = if (key) unit.cells.associateBy { Triple(it.dd, it.a, it.y) } + unit.keyCells.associateBy { Triple(it.dd, it.a, it.y) }
            else unit.cells.associateBy { Triple(it.dd, it.a, it.y) }
        for (c in cells.values) {
            val (x, z) = cell(slot, c.dd, c.a)
            add(Edit(x, c.y, z, c.state, q, if ("player_" in c.state) head else null))
        }
        addAll(floor(slot, unit, rnd, keepAt, floorAt))
        addAll(DoorGeometry.bodyEdits(slot, kind))
    }

    const val OUTER_KEEP = 0.535

    const val PILLAR_STYLE = 0.29

    const val OPEN_KEEP = 0.167

    fun styleFloor(unit: DoorUnit, dd: Int): String = "minecraft:" + when (unit.name) {
        "SPRUCE" -> "spruce_planks"
        "BLOOD" -> "nether_bricks"
        "COBBLE_ACACIA" -> "cobblestone"
        "PA_SB" -> if (abs(dd) == 2) "polished_andesite" else "stone_bricks"
        else -> "stone_bricks"
    }

    fun floor(slot: Slot, unit: DoorUnit, rnd: Random, keepAt: (Int, Int) -> Block?, floorAt: (Int, Int) -> Block?): List<Edit> = buildList {
        val sources = buildList {
            for (dd in listOf(-DD_MAX, DD_MAX)) for (a in A_MIN + 1..A_MAX - 1) {
                val (x, z) = cell(slot, dd, a)
                floorAt(x, z)?.let { b -> drawable(b)?.let { add(it) } }
            }
        }.distinctBy { it.kind() }.ifEmpty { listOf(Block(STONE)) }
        for (dd in -2..2) for (a in 12..18) {
            val (x, z) = cell(slot, dd, a)
            val u = rnd.nextDouble()
            val kept = when (a) {
                12, 18 -> u < OUTER_KEEP
                A_MIN, A_MAX -> false
                else -> dd != 0 && u < OPEN_KEEP
            }
            val b = when {
                kept -> if (dd == 0) Block(AIR) else keepAt(x, z) ?: Block(AIR)
                (a == A_MIN || a == A_MAX) && u < PILLAR_STYLE -> Block(styleFloor(unit, dd))
                else -> sources[rnd.nextInt(sources.size)]
            }
            add(Edit(x, FLOOR_Y, z, b.state, b.quarterTurns))
        }
    }

    private fun drawable(b: Block): Block? {
        val name = b.state.substringBefore('[')
        if (name in NOT_SOURCES) return null
        if (!name.endsWith("_slab") || "type=double" in b.state) return b
        val m = name.removePrefix("minecraft:").removeSuffix("_slab")
        return Block("minecraft:" + when {
            m in WOODS -> "${m}_planks"
            m.endsWith("brick") -> "${m}s"
            m == "quartz" -> "quartz_block"
            else -> m
        })
    }

    private fun Block.kind(): String {
        val s = state.replace(SHAPE, "")
        return if (DIRECTED.containsMatchIn(s)) "$s@${quarterTurns.mod(4)}" else s
    }

    private val SHAPE = Regex(",?shape=\\w+")
    private val DIRECTED = Regex("facing=|axis=|rotation=")
    private val WOODS = setOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak")
    private val NOT_SOURCES = setOf("minecraft:air", "minecraft:cave_air", "minecraft:water", "minecraft:lava")

    fun fallResend(slot: Slot): List<Triple<Int, Int, Int>> = buildList {
        for (dd in listOf(-2, 2)) for (a in A_MIN + 1..A_MAX - 1) { val (x, z) = cell(slot, dd, a); add(Triple(x, 72, z)) }
    }

    fun shapeCells(slot: Slot): List<Triple<Int, Int, Int>> = buildList {
        for (dd in -DD_MAX - 1..DD_MAX + 1) for (a in 12..18) {
            val (x, z) = cell(slot, dd, a)
            for (y in 65..74) add(Triple(x, y, z))
        }
    }

    private const val AIR = "minecraft:air"
    private const val STONE = "minecraft:stone"
}
