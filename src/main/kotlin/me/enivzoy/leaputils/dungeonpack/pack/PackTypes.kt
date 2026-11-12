package me.enivzoy.leaputils.dungeonpack.pack

import java.nio.file.Path

data class Tile(val x: Int, val z: Int)

data class Pos(val x: Int, val y: Int, val z: Int)

enum class Rot(val q: Int) {
    SOUTH(0), WEST(1), NORTH(2), EAST(3);

    companion object {

        fun fromWord(word: String?): Rot? = word?.let { w -> entries.firstOrNull { it.name.equals(w, ignoreCase = true) } }
    }
}

class PackReport {
    private val lines = ArrayList<Line>()

    class Line(val level: Level, val text: String)
    enum class Level { INFO, WARNING, ERROR }

    @Synchronized fun info(text: String) { lines += Line(Level.INFO, text) }
    @Synchronized fun warn(text: String) { lines += Line(Level.WARNING, text) }
    @Synchronized fun error(text: String) { lines += Line(Level.ERROR, text) }

    @Synchronized fun all(): List<Line> = lines.toList()
    @Synchronized fun errors(): List<String> = lines.filter { it.level == Level.ERROR }.map { it.text }
    @Synchronized fun warnings(): List<String> = lines.filter { it.level == Level.WARNING }.map { it.text }
    @Synchronized fun hasErrors(): Boolean = lines.any { it.level == Level.ERROR }
}

object PackFiles {
    const val ROOMS_INDEX = "rooms.index.json.gz"
    const val ROOMS_BLOCKS = "rooms.blocks.bin"
    const val LAYOUTS = "layouts.json.gz"
    const val MOBS = "mobs.json.gz"
    const val RUNSTATE = "runstate.json.gz"
    const val TRAPS = "traps.json"

    val BUNDLED: List<String> = listOf(ROOMS_INDEX, ROOMS_BLOCKS, LAYOUTS, MOBS, RUNSTATE, TRAPS)

    const val RESOURCE_ROOT = "assets/leaputils/dungeonpack/packs"

    fun cacheRootFor(packsDir: Path): Path = packsDir.resolveSibling("packs-bundled")

    fun dirFor(gameDir: Path): Path = gameDir.resolve("config").resolve("leaputils").resolve("ads").resolve("packs")

    fun defaultDir(): Path = dirFor(net.fabricmc.loader.api.FabricLoader.getInstance().gameDir)
}

class PackFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
