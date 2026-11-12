package me.enivzoy.leaputils.dungeonpack.pack.layouts

import me.enivzoy.leaputils.dungeonpack.pack.*
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomPack
import me.enivzoy.leaputils.dungeonpack.pack.rooms.checkHeader
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

enum class DoorKind {
    NONE, NORMAL, WITHER, BLOOD, ENTRANCE, FAIRY, UNKNOWN;

    val connected: Boolean get() = this != NONE && this != UNKNOWN

    companion object {
        fun fromWord(w: String?): DoorKind = entries.firstOrNull { it.name.equals(w, ignoreCase = true) } ?: UNKNOWN
    }
}

data class LayoutRoom(
    val i: Int,
    val name: String,
    val family: String,
    val design: String?,
    val designBy: String?,
    val core: Int?,

    val type: String,

    val shape: String,
    val tiles: List<Tile>,
    val rot: Rot?,
    val rotSource: String?,
    val rotOdin: String?,
    val secretsMax: Int?,
    val secretsFound: Int?,
    val tSeen: Int?,
    val tNone: Int?,
    val tWhite: Int?,
    val tGreen: Int?,
    val tRed: Int?,
)

data class LayoutDoor(
    val axis: Char, val a: Tile, val b: Tile, val ra: Int, val rb: Int,
    val read: String?, val kind: DoorKind, val fix: Boolean,

    val tOpen: Int?,
) {

    val spot: Pair<Int, Int> get() = DungeonFrame.doorSpot(axis, a.x, a.z)
}

data class LayoutKey(val kind: String, val tSpawn: Int?, val x: Double, val z: Double, val room: Int?, val tPickup: Int?, val tGone: Int?)

class Layout(
    val id: String,

    val floor: String,
    val date: String?,
    val source: String?,
    val rooms: List<LayoutRoom>,
    val doors: List<LayoutDoor>,
    val doorsComplete: Boolean,

    val path: List<Int>?,
    val keys: List<LayoutKey>,

    val puzzles: List<String>,
    val secretsTotal: Int,
    val t0Basis: String?,
    val endTick: Int?,

    val party: List<String>,
    val buildable: Boolean?,
    val playable: Boolean?,
    val dupOf: String?,
    val rotSummary: Map<String, Int>,
) {
    private val tileRoom: Map<Tile, LayoutRoom> = buildMap { for (r in rooms) for (t in r.tiles) put(t, r) }
    private val doorBySlot: Map<Triple<Char, Int, Int>, LayoutDoor> = doors.associateBy { Triple(it.axis, it.a.x, it.a.z) }

    fun roomAt(tx: Int, tz: Int): LayoutRoom? = tileRoom[Tile(tx, tz)]

    fun doorAt(axis: Char, tx: Int, tz: Int): LayoutDoor? = doorBySlot[Triple(axis, tx, tz)]

    fun doorSpot(axis: Char, tx: Int, tz: Int): Pair<Int, Int> = DungeonFrame.doorSpot(axis, tx, tz)

    val entrance: LayoutRoom? get() = rooms.firstOrNull { it.type == "entrance" }
    val blood: LayoutRoom? get() = rooms.firstOrNull { it.type == "blood" }
    val fairy: LayoutRoom? get() = rooms.firstOrNull { it.type == "fairy" }

    val inDefaultPool: Boolean get() = playable == true && dupOf == null && floor == "F7"
}

class LayoutPack internal constructor(
    val version: Int,
    val roomsPackVersion: Int?,
    val aliases: Map<String, String>,
    val stats: JsonObject?,

    val layouts: List<Layout>,
) {
    private val byId = layouts.associateBy { it.id }

    fun layout(id: String): Layout? = byId[id]

    fun familyOf(name: String): String? = aliases[name.replace("​", "")]

    fun pool(filter: (Layout) -> Boolean = DEFAULT_FILTER): List<Layout> = layouts.filter(filter)

    fun pick(seed: Long, filter: (Layout) -> Boolean = DEFAULT_FILTER): Layout? {
        val pool = pool(filter)
        if (pool.isEmpty()) return null
        return pool[kotlin.random.Random(seed).nextInt(pool.size)]
    }

    companion object {
        const val FORMAT = "ads-layouts"
        const val SUPPORTED_VERSION = 1

        val DEFAULT_FILTER: (Layout) -> Boolean = { it.inDefaultPool }

        fun load(dir: Path, roomPack: RoomPack? = null, report: PackReport = PackReport()): LayoutPack? = load(PackSource.ofDir(dir), roomPack, report)

        fun load(source: PackSource, roomPack: RoomPack? = null, report: PackReport = PackReport()): LayoutPack? {
            val path = source.file(PackFiles.LAYOUTS)
            if (path == null) { report.error("The layout pool ${PackFiles.LAYOUTS} is missing from ${source.missingFrom}."); return null }
            val root = try { Files.newInputStream(path).use { readGzipJson(it) } } catch (e: Exception) {
                report.error("The layout pool ${PackFiles.LAYOUTS} is unreadable${source.note(PackFiles.LAYOUTS)}: ${e.message}"); return null
            }
            return fromJson(root, roomPack, report)
        }

        fun fromJson(root: JsonObject, roomPack: RoomPack? = null, report: PackReport = PackReport()): LayoutPack? {
            if (!checkHeader(root, FORMAT, SUPPORTED_VERSION, PackFiles.LAYOUTS, report)) return null
            val rv = root.int("roomsPackVersion")
            if (rv != null && roomPack != null && rv != roomPack.version) {
                report.warn("The layout pool was made for room library version $rv but version ${roomPack.version} is loaded. Layouts may not match their rooms.")
            }
            val layouts = ArrayList<Layout>()
            for (o in root.arr("layouts").objects()) {
                try { layouts += parseLayout(o) } catch (e: Exception) { report.warn("A layout was skipped (${o.str("id") ?: "no id"}): ${e.message}") }
            }
            layouts.sortBy { it.id }
            if (layouts.isEmpty()) report.warn("The layout pool has no layouts.")
            val aliases = root.obj("aliases")?.entrySet()?.mapNotNull { (k, v) -> v.asStr()?.let { k.replace("​", "") to it } }?.toMap().orEmpty()
            return LayoutPack(root.int("version") ?: 1, rv, aliases, root.obj("stats"), layouts)
        }

        private fun parseLayout(o: JsonObject): Layout {
            val id = o.str("id") ?: throw PackFormatException("no id")
            val rooms = o.arr("rooms").objects().map { r ->
                LayoutRoom(
                    i = r.int("i") ?: throw PackFormatException("a room has no index"),
                    name = (r.str("name") ?: "").replace("​", ""),
                    family = r.str("family") ?: (r.str("name") ?: "").replace(' ', '_'),
                    design = r.str("design"),
                    designBy = r.str("designBy"),
                    core = r.int("core"),
                    type = r.str("type") ?: "normal",
                    shape = r.str("shape") ?: "1x1",
                    tiles = r.get("tiles").asTiles(),
                    rot = Rot.fromWord(r.str("rot")),
                    rotSource = r.str("rotSource"),
                    rotOdin = r.str("rotOdin"),
                    secretsMax = r.int("secretsMax"),
                    secretsFound = r.int("secretsFound"),
                    tSeen = r.int("tSeen"), tNone = r.int("tNone"), tWhite = r.int("tWhite"), tGreen = r.int("tGreen"), tRed = r.int("tRed"),
                )
            }.sortedBy { it.i }
            val doors = o.arr("doors").objects().mapNotNull { d ->
                val axis = d.str("axis")?.firstOrNull() ?: return@mapNotNull null
                LayoutDoor(axis, d.get("a").asTile() ?: return@mapNotNull null, d.get("b").asTile() ?: return@mapNotNull null,
                    d.int("ra") ?: -1, d.int("rb") ?: -1, d.str("read"), DoorKind.fromWord(d.str("kind")), d.bool("fix") ?: false, d.int("tOpen"))
            }
            return Layout(
                id = id,
                floor = o.str("floor") ?: "F7",
                date = o.str("date"),
                source = o.str("source"),
                rooms = rooms,
                doors = doors,
                doorsComplete = o.bool("doorsComplete") ?: false,
                path = o.arr("path")?.ints()?.toList(),
                keys = o.arr("keys").objects().map { k ->
                    LayoutKey(k.str("kind") ?: "wither", k.int("tSpawn"), k.dbl("x") ?: 0.0, k.dbl("z") ?: 0.0, k.int("room"), k.int("tPickup"), k.int("tGone"))
                },
                puzzles = o.arr("puzzles").strings(),
                secretsTotal = o.int("secretsTotal") ?: rooms.sumOf { it.secretsMax ?: 0 },
                t0Basis = o.str("t0Basis"),
                endTick = o.int("endTick"),
                party = o.arr("party").strings(),
                buildable = o.bool("buildable"),
                playable = o.bool("playable"),
                dupOf = o.str("dupOf"),
                rotSummary = o.obj("rotSummary")?.entrySet()?.mapNotNull { (k, v) -> v.asIntOrNull()?.let { k to it } }?.toMap().orEmpty(),
            )
        }
    }
}
