package me.enivzoy.leaputils.dungeonpack.pack.doors

import me.enivzoy.leaputils.dungeonpack.pack.arr
import me.enivzoy.leaputils.dungeonpack.pack.asArr
import me.enivzoy.leaputils.dungeonpack.pack.asIntOrNull
import me.enivzoy.leaputils.dungeonpack.pack.asObj
import me.enivzoy.leaputils.dungeonpack.pack.asStr
import me.enivzoy.leaputils.dungeonpack.pack.obj
import me.enivzoy.leaputils.dungeonpack.pack.readGzipJson
import me.enivzoy.leaputils.dungeonpack.pack.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.InputStream

class UnitCell(val dd: Int, val a: Int, val y: Int, val state: String)

class DoorUnit(val name: String, val cells: List<UnitCell>, val keyCells: List<UnitCell>)

class PatchCell(val x: Int, val y: Int, val z: Int, val state: String)

class SidePatches(val open: List<PatchCell>?, val closed: List<PatchCell>?, val closedEstimated: List<PatchCell>?)

class DoorPack(

    val styles: List<DoorUnit>,

    val entrance: DoorUnit,
    val blood: DoorUnit,

    val keyHead: String, val bloodHead: String, val plainHead: String,

    private val rooms: Map<String, Map<String, SidePatches>>,

    val gapWalls: Map<String, List<PatchCell>> = emptyMap(),
) {
    fun side(design: String, tx: Int, tz: Int, side: Char): SidePatches? = rooms[design]?.get("$tx,$tz,$side")

    companion object {
        private const val PATH = "/assets/leaputils/dungeonpack/packs/doors.json.gz"

        val bundled: DoorPack by lazy { DoorPack::class.java.getResourceAsStream(PATH)!!.use { parse(it) } }

        fun parse(stream: InputStream): DoorPack = parse(readGzipJson(stream))

        fun parse(j: JsonObject): DoorPack {
            val f = j.obj("frames") ?: error("no frames")
            val st = f.obj("styles") ?: error("no styles")
            val heads = f.obj("heads")
            val rooms = j.obj("rooms")?.entrySet()?.associate { (id, sides) ->
                id to sides.asObj()!!.entrySet().associate { (k, v) ->
                    val o = v.asObj()!!
                    k to SidePatches(patch(o, "open"), patch(o, "closed"), patch(o, "closedEstimated"))
                }
            }.orEmpty()
            return DoorPack(
                styles = st.entrySet().map { (name, s) -> unit(name, s.asObj()!!) },
                entrance = unit("ENTRANCE", f.obj("entrance") ?: error("no entrance")),
                blood = unit("BLOOD", f.obj("blood") ?: error("no blood")),
                keyHead = heads?.str("key").orEmpty(), bloodHead = heads?.str("blood").orEmpty(), plainHead = heads?.str("SB_ACACIA_normal").orEmpty(),
                rooms = rooms,
                gapWalls = j.obj("gapWalls")?.entrySet()?.associate { (id, v) -> id to (patch(v.asObj()!!.let { o -> JsonObject().also { it.add("x", o) } }, "x") ?: emptyList()) }.orEmpty(),
            )
        }

        private fun unit(name: String, o: JsonObject) = DoorUnit(name, unitCells(o.arr("cells")), unitCells(o.arr("keyCells")))

        private fun unitCells(a: JsonArray?): List<UnitCell> = a?.mapNotNull { e ->
            val c = e.asArr() ?: return@mapNotNull null
            UnitCell(c[0].asIntOrNull()!!, c[1].asIntOrNull()!!, c[2].asIntOrNull()!!, state(c[3].asStr()!!))
        }.orEmpty()

        private fun patch(o: JsonObject, key: String): List<PatchCell>? = o.obj(key)?.arr("cells")?.mapNotNull { e ->
            val c = e.asArr() ?: return@mapNotNull null
            PatchCell(c[0].asIntOrNull()!!, c[1].asIntOrNull()!!, c[2].asIntOrNull()!!, state(c[3].asStr()!!))
        }

        private fun state(s: String) = if (':' in s.substringBefore('[')) s else "minecraft:$s"
    }
}
