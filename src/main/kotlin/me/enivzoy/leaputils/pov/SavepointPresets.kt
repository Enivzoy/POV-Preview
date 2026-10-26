package me.enivzoy.leaputils.pov

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

data class PresetCondJson(
    val subject: String? = null,
    val mode: String? = null,
    val landmark: String? = null,
    val radius: Double? = null,
    val holdMs: Long? = null,
    val anyAfterMs: Long? = null,
)

data class PresetFile(
    val id: String? = null,
    val label: String? = null,
    val source: String? = null,
    val notes: List<String>? = null,
    val cells: Map<String, Map<String, String>>? = null,
    val conds: Map<String, PresetCondJson>? = null,
)

object SavepointPresets {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils")
    private val condsFile: Path = dir.resolve("conds.json")
    private val condType = object : TypeToken<LinkedHashMap<String, PresetCondJson>>() {}.type

    fun load(id: String): PresetFile? = runCatching {
        val stream = SavepointPresets::class.java.classLoader.getResourceAsStream("assets/leaputils/presets/$id.json") ?: return null
        stream.bufferedReader().use { reader -> gson.fromJson(reader, PresetFile::class.java) }
    }.getOrNull()

    fun toCond(json: PresetCondJson): SpCond = SpCond(
        subject = json.subject?.let { runCatching { SpSubject.valueOf(it) }.getOrNull() } ?: SpSubject.TARGET,
        mode = json.mode?.let { runCatching { SpMode.valueOf(it) }.getOrNull() } ?: SpMode.NEAR,
        landmark = json.landmark ?: "",
        radius = json.radius ?: 0.0,
        holdMs = json.holdMs ?: 400,
        anyAfterMs = json.anyAfterMs ?: 0,
    )

    fun loadConds(): Map<String, SpCond> = runCatching {
        if (!Files.exists(condsFile)) return emptyMap()
        val read: LinkedHashMap<String, PresetCondJson>? = gson.fromJson(Files.readString(condsFile), condType)
        read?.mapValues { (_, v) -> toCond(v) } ?: emptyMap()
    }.getOrDefault(emptyMap())

    fun saveConds(conds: Map<String, SpCond>) {
        runCatching {
            Files.createDirectories(dir)
            val json = conds.mapValues { (_, c) ->
                mapOf(
                    "subject" to c.subject.name,
                    "mode" to c.mode.name,
                    "landmark" to c.landmark,
                    "radius" to c.radius,
                    "holdMs" to c.holdMs,
                    "anyAfterMs" to c.anyAfterMs,
                )
            }
            Files.writeString(condsFile, gson.toJson(json))
        }
    }
}
