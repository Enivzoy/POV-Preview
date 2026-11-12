package me.enivzoy.leaputils.dungeonpack.pack.mobs

import me.enivzoy.leaputils.dungeonpack.pack.*
import me.enivzoy.leaputils.dungeonpack.pack.rooms.checkHeader
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

data class MobSkin(val id: String, val value: String?, val signature: String?, val slim: Boolean = false, val variants: List<MobSkin> = emptyList())

data class MobTag(
    val idOffset: Int, val dy: Double, val head: String, val nameColor: String, val bold: Boolean,

    val modSplit: Boolean = false,
)

data class MobWake(val radius: Double, val trigger: String)

data class MobEquipment(val mainhand: String?, val mainhandName: String?, val feet: String?)

class MobArchetype(
    val index: Int,
    val id: String,
    val name: String,

    val cls: String,

    val entity: String,
    val disguise: Boolean,
    val skin: MobSkin?,
    val bodyName: String?,
    val equipment: MobEquipment?,

    val hp: Map<String, Long>,
    val star: Boolean,
    val tag: MobTag?,

    val movement: String,
    val wake: MobWake?,

    val modifierWeights: Map<String, Double>?,
    val seen: Map<String, Int>,
) {
    fun hpFor(floor: String): Long? = hp[floor]
}

data class MobModifier(
    val id: String, val weight: Map<String, Double?>, val hpFactor: Double, val nameColor: String?, val hpColor: String?,

    val headReset: Boolean = true,

    val hpColorHurt: String? = null,
    val hpThreshold: Double? = null,
)

class HpScaleTable(val n: Int, val values: DoubleArray, val counts: IntArray)

data class CountStats(val mean: Double, val p50: Double, val p90: Double, val max: Double, val hist: Map<Int, Int>)

data class MobCell(val x: Int, val y: Int, val z: Int, val arch: Int, val star: Boolean, val occ: Int)

data class MobSpawn(val x: Int, val y: Int, val z: Int, val arch: Int, val star: Boolean)

class MobLayout(val src: String, val fl: String, val mobs: List<MobSpawn>)

class RoomMobs(
    val name: String,
    val designs: List<String>,

    val clear: String,
    val visitsAdr: Int,
    val visitsCd: Int,
    val starred: CountStats?,
    val unstarred: CountStats?,
    val cells: List<MobCell>,
    val layouts: List<MobLayout>,

    val aliases: List<String> = emptyList(),

    val type: String? = null,
) {

    val visits: Int get() = visitsAdr + visitsCd
}

data class Miniboss(val room: String, val arch: Int, val cell: Pos?, val src: String?, val hpFactor: Double?, val loop: List<Pos>?)

class MeleeTable(val n: Int, val perMinute: Double, val quantiles: java.util.SortedMap<Double, Double>, val atoms: List<Atom>, val provisional: Boolean) {
    data class Atom(val value: Double, val p: Double)
}

data class HitQuantiles(val n: Int, val p10: Double, val p50: Double, val p90: Double)

class PlayerDamageTable(
    val unit: String?,

    val immunityTicks: Int,

    val melee: Map<String, MeleeTable>,

    val meleeByArchetype: Map<String, Map<String, HitQuantiles>>,
    val projectile: Map<String, HitQuantiles>,

    val trapPercent: Double,
    val trapKinds: List<String>,
)

class MobPack internal constructor(
    val version: Int,
    val archetypes: List<MobArchetype>,
    val modifiers: List<MobModifier>,
    val hpScale: Map<String, HpScaleTable>,
    val rooms: List<RoomMobs>,
    val minibosses: List<Miniboss>,
    val playerDamage: PlayerDamageTable?,
    val gaps: List<String>,
) {
    private val byArchetypeId = archetypes.associateBy { it.id }
    private val byArchetypeName = archetypes.associateBy { it.name }
    private val byRoomName = rooms.associateBy { it.name }
    private val byNormRoomName = rooms.associateBy { norm(it.name) }
    private val byRoomAlias: Map<String, RoomMobs> = buildMap { for (r in rooms) for (a in r.aliases) putIfAbsent(a.replace("\u200B", ""), r) }

    fun archetype(index: Int): MobArchetype? = archetypes.getOrNull(index)
    fun archetypeById(id: String): MobArchetype? = byArchetypeId[id]
    fun byName(name: String): MobArchetype? = byArchetypeName[name]

    fun room(name: String, aliasOf: ((String) -> String?)? = null): RoomMobs? {
        val n = name.replace("​", "")
        byRoomName[n]?.let { return it }
        byRoomAlias[n]?.let { return it }
        aliasOf?.invoke(n)?.let { a -> byRoomName[a]?.let { return it }; byRoomAlias[a]?.let { return it }; byNormRoomName[norm(a)]?.let { return it } }
        return byNormRoomName[norm(n)]
    }

    fun modifier(id: String): MobModifier? = modifiers.firstOrNull { it.id == id }
    fun hpScaleFor(floor: String): HpScaleTable? = hpScale[floor]
    fun minibossesOf(room: String): List<Miniboss> = minibosses.filter { it.room == room }

    companion object {
        const val FORMAT = "ads-mobs"
        const val SUPPORTED_VERSION = 1

        fun norm(n: String): String = n.replace("​", "").replace(" ", "_").replace("-", "_")

        fun load(dir: Path, report: PackReport = PackReport()): MobPack? = load(PackSource.ofDir(dir), report)

        fun load(source: PackSource, report: PackReport = PackReport()): MobPack? {
            val path = source.file(PackFiles.MOBS)
            if (path == null) { report.error("The mob tables ${PackFiles.MOBS} are missing from ${source.missingFrom}."); return null }
            val root = try { Files.newInputStream(path).use { readGzipJson(it) } } catch (e: Exception) {
                report.error("The mob tables ${PackFiles.MOBS} are unreadable${source.note(PackFiles.MOBS)}: ${e.message}"); return null
            }
            return fromJson(root, report)
        }

        fun fromJson(root: JsonObject, report: PackReport = PackReport()): MobPack? {
            if (!checkHeader(root, FORMAT, SUPPORTED_VERSION, PackFiles.MOBS, report)) return null
            val archetypes = ArrayList<MobArchetype>()
            for ((i, a) in root.arr("archetypes").objects().withIndex()) {
                try { archetypes += parseArchetype(i, a) } catch (e: Exception) {

                    report.warn("Mob archetype #$i was skipped (${a.str("id") ?: "no id"}): ${e.message}")
                    archetypes += MobArchetype(i, a.str("id") ?: "broken_$i", a.str("name") ?: "?", "special", "minecraft:zombie", false, null, null, null, emptyMap(), false, null, "static", null, null, emptyMap())
                }
            }
            val modifiers = root.arr("modifiers").objects().map { m ->
                MobModifier(m.str("id") ?: "", doubleMap(m.obj("weight")), m.dbl("hpFactor") ?: 1.0, m.str("nameColor"), m.str("hpColor"),
                    m.bool("headReset") ?: true, m.str("hpColorHurt"), m.dbl("hpThreshold"))
            }
            val hpScale = HashMap<String, HpScaleTable>()
            root.obj("hpScale")?.entrySet()?.forEach { (k, v) ->
                val o = v.asObj() ?: return@forEach
                val values = o.arr("values").doubles(); val counts = o.arr("counts").ints()
                if (values.isNotEmpty() && values.size == counts.size) hpScale[k] = HpScaleTable(o.int("n") ?: counts.sum(), values, counts)
            }
            val rooms = ArrayList<RoomMobs>()
            for (r in root.arr("rooms").objects()) {
                try { rooms += parseRoom(r, archetypes.size) } catch (e: Exception) { report.warn("Mob data of room ${r.str("name") ?: "?"} was skipped: ${e.message}") }
            }
            val minibosses = root.arr("minibosses").objects().mapNotNull { m ->
                Miniboss(m.str("room") ?: return@mapNotNull null, m.int("arch") ?: return@mapNotNull null, m.get("cell").asPos(), m.str("src"), m.dbl("hpFactor"),
                    m.arr("loop")?.let { l -> l.mapNotNull { it.asPos() } })
            }
            if (archetypes.isEmpty()) report.warn("The mob tables have no archetypes.")
            return MobPack(root.int("version") ?: 1, archetypes, modifiers, hpScale, rooms, minibosses, root.obj("playerDamage")?.let { parseDamage(it) }, root.arr("gaps").strings())
        }

        private fun doubleMap(o: JsonObject?): Map<String, Double?> = o?.entrySet()?.associate { (k, v) -> k to v.asDoubleOrNull() }.orEmpty()

        private fun parseArchetype(index: Int, a: JsonObject): MobArchetype {
            val id = a.str("id") ?: throw PackFormatException("no id")
            val tag = a.obj("tag")?.let { t ->
                MobTag(t.int("idOffset") ?: 1, t.dbl("dy") ?: 2.09, t.str("head") ?: "", t.str("nameColor") ?: "§c", t.bool("bold") ?: false, t.bool("modSplit") ?: false)
            }
            return MobArchetype(
                index = index, id = id, name = a.str("name") ?: id, cls = a.str("cls") ?: "clear",
                entity = a.str("entity") ?: "minecraft:zombie", disguise = a.bool("disguise") ?: false,
                skin = a.obj("skin")?.let { s -> parseSkin(s, withVariants = true) },
                bodyName = a.str("bodyName"),
                equipment = a.obj("equipment")?.let { e -> MobEquipment(e.str("mainhand"), e.str("mainhandName"), e.str("feet")) },
                hp = a.obj("hp")?.entrySet()?.mapNotNull { (k, v) -> v.asLongOrNull()?.let { k to it } }?.toMap().orEmpty(),
                star = a.bool("star") ?: true,
                tag = tag,
                movement = a.str("movement") ?: "static",
                wake = a.obj("wake")?.let { w -> MobWake(w.dbl("radius") ?: 5.0, w.str("trigger") ?: "player-body") },
                modifierWeights = a.obj("modifierWeights")?.entrySet()?.mapNotNull { (k, v) -> v.asDoubleOrNull()?.let { k to it } }?.toMap(),
                seen = a.obj("seen")?.entrySet()?.mapNotNull { (k, v) -> v.asIntOrNull()?.let { k to it } }?.toMap().orEmpty(),
            )
        }

        private fun parseSkin(s: JsonObject, withVariants: Boolean): MobSkin {
            val value = s.str("value")

            val slim = value?.let { v -> try { String(java.util.Base64.getDecoder().decode(v), Charsets.UTF_8).contains("\"slim\"") } catch (e: Exception) { false } } ?: false
            val variants = if (withVariants) s.arr("variants").objects().map { parseSkin(it, withVariants = false) } else emptyList()
            return MobSkin(s.str("id") ?: "", value, s.str("signature"), slim, variants)
        }

        private fun stats(o: JsonObject?): CountStats? = o?.let {
            CountStats(it.dbl("mean") ?: 0.0, it.dbl("p50") ?: 0.0, it.dbl("p90") ?: 0.0, it.dbl("max") ?: 0.0,
                it.obj("hist")?.entrySet()?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { kk -> v.asIntOrNull()?.let { kk to it } } }?.toMap().orEmpty())
        }

        private fun parseRoom(r: JsonObject, archetypeCount: Int): RoomMobs {
            val name = (r.str("name") ?: throw PackFormatException("no name")).replace("​", "")
            val cells = r.arr("cells")?.mapNotNull { c ->
                val a = c.asArr() ?: return@mapNotNull null
                if (a.size() < 6) return@mapNotNull null
                val arch = a[3].asIntOrNull() ?: return@mapNotNull null
                if (arch !in 0 until archetypeCount) return@mapNotNull null
                MobCell(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null,
                    arch, (a[4].asIntOrNull() ?: 0) != 0, a[5].asIntOrNull() ?: 1)
            }.orEmpty()
            val layouts = r.arr("layouts").objects().map { l ->
                MobLayout(l.str("src") ?: "adr", l.str("fl") ?: "?", l.arr("m")?.mapNotNull { m ->
                    val a = m.asArr() ?: return@mapNotNull null
                    if (a.size() < 5) return@mapNotNull null
                    val arch = a[3].asIntOrNull() ?: return@mapNotNull null
                    if (arch !in 0 until archetypeCount) return@mapNotNull null
                    MobSpawn(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null,
                        arch, (a[4].asIntOrNull() ?: 0) != 0)
                }.orEmpty())
            }
            val visits = r.obj("visits")
            return RoomMobs(name, r.arr("designs").strings(), r.str("clear") ?: "none", visits?.int("adr") ?: 0, visits?.int("cd") ?: 0,
                stats(r.obj("starred")), stats(r.obj("unstarred")), cells, layouts, r.arr("aliases").strings().map { it.replace("\u200B", "") }, r.str("type"))
        }

        private fun hit(o: JsonObject?): HitQuantiles? = o?.let { HitQuantiles(it.int("n") ?: 0, it.dbl("p10") ?: 0.0, it.dbl("p50") ?: 0.0, it.dbl("p90") ?: 0.0) }

        private fun meleeTable(o: JsonObject): MeleeTable {
            val q = java.util.TreeMap<Double, Double>()
            o.obj("quantiles")?.entrySet()?.forEach { (k, v) -> val kk = k.toDoubleOrNull(); val vv = v.asDoubleOrNull(); if (kk != null && vv != null) q[kk] = vv }
            return MeleeTable(o.int("n") ?: 0, o.dbl("perMinute") ?: 0.0, q,
                o.arr("atoms").objects().mapNotNull { a -> MeleeTable.Atom(a.dbl("value") ?: return@mapNotNull null, a.dbl("p") ?: return@mapNotNull null) },
                o.bool("provisional") ?: false)
        }

        private fun parseDamage(o: JsonObject): PlayerDamageTable {
            val melee = o.obj("melee")?.entrySet()?.mapNotNull { (k, v) -> v.asObj()?.let { k to meleeTable(it) } }?.toMap().orEmpty()
            val byArch = o.obj("meleeByArchetype")?.entrySet()?.mapNotNull { (k, v) ->
                v.asObj()?.let { fl -> k to fl.entrySet().mapNotNull { (id, t) -> hit(t.asObj())?.let { id to it } }.toMap() }
            }?.toMap().orEmpty()
            val projectile = o.obj("projectile")?.entrySet()?.mapNotNull { (k, v) -> hit(v.asObj())?.let { k to it } }?.toMap().orEmpty()
            val trap = o.obj("trap")
            return PlayerDamageTable(o.str("unit"), o.int("immunityTicks") ?: 10, melee, byArch, projectile, trap?.dbl("percent") ?: 15.0, trap?.arr("kinds").strings())
        }
    }
}
