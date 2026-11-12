package me.enivzoy.leaputils.dungeonpack.pack.rooms

import me.enivzoy.leaputils.dungeonpack.pack.DungeonFrame
import me.enivzoy.leaputils.dungeonpack.pack.Rot
import me.enivzoy.leaputils.dungeonpack.pack.Tile

data class WorldPos(val x: Int, val y: Int, val z: Int)

class PlacementException(message: String) : RuntimeException(message)

class Placement private constructor(
    val shape: String,

    val w: Int,
    val d: Int,
    val rot: Rot,

    val worldTiles: List<Tile>,

    val originX: Int,
    val originZ: Int,
) {

    val quarterTurns: Int get() = rot.q

    val boxW: Int get() = if (rot == Rot.SOUTH || rot == Rot.NORTH) w else d
    val boxD: Int get() = if (rot == Rot.SOUTH || rot == Rot.NORTH) d else w

    fun tiles(): List<Tile> = worldTiles

    fun toWorld(x: Int, y: Int, z: Int): WorldPos {
        val px: Int; val pz: Int
        when (rot) {
            Rot.SOUTH -> { px = x; pz = z }
            Rot.NORTH -> { px = w - 1 - x; pz = d - 1 - z }
            Rot.WEST -> { px = d - 1 - z; pz = x }
            Rot.EAST -> { px = z; pz = w - 1 - x }
        }
        return WorldPos(originX + px, y, originZ + pz)
    }

    fun toCanonical(wx: Int, wz: Int): Pair<Int, Int>? {
        val px = wx - originX; val pz = wz - originZ
        if (px !in 0 until boxW || pz !in 0 until boxD) return null
        return when (rot) {
            Rot.SOUTH -> px to pz
            Rot.NORTH -> (w - 1 - px) to (d - 1 - pz)
            Rot.WEST -> pz to (d - 1 - px)
            Rot.EAST -> (w - 1 - pz) to px
        }
    }

    fun ownsColumn(wx: Int, wz: Int): Boolean {
        fun axis(v: Int): List<Int> {
            val t = Math.floorDiv(v - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
            return if (v - DungeonFrame.tileMin(t) == DungeonFrame.TILE_SIZE) listOf(t, t + 1) else listOf(t)
        }
        return axis(wx).all { tx -> axis(wz).all { tz -> Tile(tx, tz) in tileSet } }
    }

    private val tileSet: Set<Tile> = worldTiles.toSet()

    fun worldTileOf(ix: Int, iz: Int): Tile = placeTile(rot, ix, iz, nx, nz, tx0, tz0)

    private val tx0 = worldTiles.minOf { it.x }
    private val tz0 = worldTiles.minOf { it.z }
    private val nx get() = canonicalSize(shape).first
    private val nz get() = canonicalSize(shape).second

    companion object {

        fun canonicalTiles(shape: String): List<Tile> = when (shape) {
            "1x1" -> listOf(Tile(0, 0))
            "1x2" -> listOf(Tile(0, 0), Tile(1, 0))
            "1x3" -> listOf(Tile(0, 0), Tile(1, 0), Tile(2, 0))
            "1x4" -> listOf(Tile(0, 0), Tile(1, 0), Tile(2, 0), Tile(3, 0))
            "2x2" -> listOf(Tile(0, 0), Tile(1, 0), Tile(0, 1), Tile(1, 1))
            "L" -> listOf(Tile(0, 0), Tile(0, 1), Tile(1, 1))
            else -> throw PlacementException("unknown room shape '$shape'")
        }

        fun canonicalSize(shape: String): Pair<Int, Int> {
            val t = canonicalTiles(shape)
            return (t.maxOf { it.x } + 1) to (t.maxOf { it.z } + 1)
        }

        fun footprint(shape: String): Pair<Int, Int> {
            val (nx, nz) = canonicalSize(shape)
            return (31 + 32 * (nx - 1)) to (31 + 32 * (nz - 1))
        }

        fun legalRotations(shape: String): List<Rot> = when (shape) {
            "1x1", "L" -> Rot.entries
            "1x2", "1x3", "1x4" -> listOf(Rot.SOUTH, Rot.WEST)
            "2x2" -> listOf(Rot.SOUTH)
            else -> emptyList()
        }

        fun tilesFor(shape: String, rot: Rot, tx0: Int, tz0: Int): List<Tile> {
            val (nx, nz) = canonicalSize(shape)
            return canonicalTiles(shape).map { placeTile(rot, it.x, it.z, nx, nz, tx0, tz0) }.sortedWith(compareBy({ it.z }, { it.x }))
        }

        private fun placeTile(rot: Rot, ix: Int, iz: Int, nx: Int, nz: Int, tx0: Int, tz0: Int): Tile {
            val a: Int; val b: Int
            when (rot) {
                Rot.SOUTH -> { a = ix; b = iz }
                Rot.NORTH -> { a = nx - 1 - ix; b = nz - 1 - iz }
                Rot.WEST -> { a = nz - 1 - iz; b = ix }
                Rot.EAST -> { a = iz; b = nx - 1 - ix }
            }
            return Tile(tx0 + a, tz0 + b)
        }

        fun of(shape: String, w: Int, d: Int, rotWord: Rot, worldTiles: List<Tile>, fairy: Boolean = false): Placement {
            val rot = if (fairy) Rot.SOUTH else rotWord
            if (rot !in legalRotations(shape)) throw PlacementException("a $shape room is never placed $rot")
            val (fw, fd) = footprint(shape)
            if (w != fw || d != fd) throw PlacementException("a $shape room is ${fw}x$fd blocks, but the design says ${w}x$d")
            if (worldTiles.isEmpty()) throw PlacementException("a room needs at least one tile")
            val sorted = worldTiles.sortedWith(compareBy({ it.z }, { it.x }))
            val tx0 = sorted.minOf { it.x }; val tz0 = sorted.minOf { it.z }
            val (nx, nz) = canonicalSize(shape)
            val image = canonicalTiles(shape).map { placeTile(rot, it.x, it.z, nx, nz, tx0, tz0) }.toSet()
            if (image != sorted.toSet() || sorted.size != image.size) {
                throw PlacementException("a $shape room placed $rot covers $image, not the layout's tiles $sorted")
            }
            for (t in sorted) if (t.x !in 0..5 || t.z !in 0..5) throw PlacementException("tile ${t.x},${t.z} is outside the 6x6 grid")
            return Placement(shape, w, d, rot, sorted, -200 + 32 * tx0, -200 + 32 * tz0)
        }
    }
}
