package me.enivzoy.leaputils.dungeonpack.pack.secrets

import me.enivzoy.leaputils.dungeonpack.pack.*
import com.google.gson.JsonObject
import java.io.InputStream

enum class SecretSpawn { START, DISCOVERY, APPROACH, USE }

enum class SecretType(val word: String) {
    CHEST("chest"), LOCKED_CHEST("lockedChest"), ESSENCE("essence"), ITEM("item"), BAT("bat"), KEY("key");

    companion object { fun of(w: String?): SecretType? = entries.firstOrNull { it.word == w } }
}

class SecretSite(
    val id: String, val type: SecretType, val pos: Pos, val spawn: SecretSpawn, val radius: Double?, val was: Pos?, val lever: Pos?,
    val worldFacing: List<String>?, val worldRotation: List<Int>?,
)

class SecretLever(val pos: Pos, val worldStates: List<String>)

class KeyRoom(val site: String, val skulls: List<Pair<Pos, List<Int>>>, val socket: Pos, val placeSocket: Boolean, val head: String, val cells: List<Pair<Pos, String>>)

class DesignSecrets(val max: Int, val sites: List<SecretSite>, val levers: List<SecretLever>, val key: KeyRoom?)

class SecretPack(
    val designs: Map<String, DesignSecrets>,

    val items: List<Pair<String, Double>>,

    val stacks: Map<String, JsonObject>,
    val checkEvery: Int,
    val mimicFloors: Set<String>,

    val mimicA: Double,
    val mimicB: Double,
) {
    fun design(id: String): DesignSecrets? = designs[id]

    companion object {
        const val FORMAT = "ads-secrets"
        const val VERSION = 2
        private const val PATH = "/assets/leaputils/dungeonpack/packs/secrets.json.gz"

        val bundled: SecretPack by lazy { SecretPack::class.java.getResourceAsStream(PATH)!!.use { parse(it) } }

        fun parse(stream: InputStream): SecretPack = parse(readGzipJson(stream))

        private fun ints(o: JsonObject, k: String): List<Int>? = o.arr(k)?.mapNotNull { it.asIntOrNull() }?.takeIf { it.size == 4 }

        fun parse(root: JsonObject): SecretPack {
            if (root.str("format") != FORMAT || root.int("version") != VERSION) throw PackFormatException("not an $FORMAT $VERSION pack")
            val designs = root.obj("designs")?.entrySet()?.associate { (id, v) ->
                val o = v.asObj() ?: throw PackFormatException("$id is not an object")
                id to DesignSecrets(
                    o.int("max") ?: 0,
                    o.arr("sites").objects().mapNotNull { s ->
                        val type = SecretType.of(s.str("t")) ?: return@mapNotNull null
                        val spawn = when (s.str("s")) { "s" -> SecretSpawn.START; "d" -> SecretSpawn.DISCOVERY; "u" -> SecretSpawn.USE; else -> SecretSpawn.APPROACH }
                        SecretSite(s.str("id") ?: "?", type, s.get("p").asPos() ?: return@mapNotNull null, spawn, s.dbl("r"), s.get("was").asPos(), s.get("lever").asPos(),
                            s.arr("fw")?.strings()?.takeIf { it.size == 4 }, ints(s, "rw"))
                    },
                    o.arr("levers").objects().mapNotNull { l ->
                        SecretLever(l.get("p").asPos() ?: return@mapNotNull null, l.arr("states").strings().takeIf { it.size == 4 } ?: return@mapNotNull null)
                    },
                    o.obj("key")?.let { k ->
                        KeyRoom(
                            k.str("site") ?: "key",
                            k.arr("skulls").objects().mapNotNull { s -> (s.get("p").asPos() ?: return@mapNotNull null) to (ints(s, "rw") ?: listOf(0, 4, 8, 12)) },
                            k.get("socket").asPos() ?: throw PackFormatException("$id: a key room without a socket"),
                            k.bool("place") ?: false,
                            k.str("head") ?: "top",
                            k.arr("cells")?.mapNotNull { c ->
                                val a = c.asArr() ?: return@mapNotNull null
                                if (a.size() < 4) return@mapNotNull null
                                Pos(a[0].asIntOrNull() ?: return@mapNotNull null, a[1].asIntOrNull() ?: return@mapNotNull null, a[2].asIntOrNull() ?: return@mapNotNull null) to (a[3].asStr() ?: return@mapNotNull null)
                            }.orEmpty(),
                        )
                    },
                )
            }.orEmpty()
            val items = root.arr("items")?.mapNotNull { e -> e.asArr()?.takeIf { it.size() == 2 }?.let { (it[0].asStr() ?: return@mapNotNull null) to (it[1].asDoubleOrNull() ?: 0.0) } }.orEmpty()
            val stacks = root.obj("stacks")?.entrySet()?.mapNotNull { (k, v) -> v.asObj()?.let { k to it } }?.toMap().orEmpty()
            val rules = root.obj("rules")
            val mimic = rules?.obj("mimic")
            return SecretPack(designs, items, stacks, rules?.int("checkEvery") ?: 5, mimic?.arr("floors").strings().toSet(), mimic?.dbl("a") ?: 1.31, mimic?.dbl("b") ?: 2.0)
        }
    }
}
