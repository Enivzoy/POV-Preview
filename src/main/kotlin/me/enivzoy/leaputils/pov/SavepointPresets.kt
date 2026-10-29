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

/** what a single step means - shown as the row label and the hover tooltip in the planner */
data class PresetStepMeta(
    val label: String? = null,
    val desc: String? = null,
)

data class PresetFile(
    val id: String? = null,
    val label: String? = null,
    val source: String? = null,
    val notes: List<String>? = null,
    val cells: Map<String, Map<String, String>>? = null,
    val conds: Map<String, PresetCondJson>? = null,
    val stepMeta: Map<String, PresetStepMeta>? = null,
)

object SavepointPresets {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils")
    private val presetsDir: Path = dir.resolve("presets")
    private val condsFile: Path = dir.resolve("conds.json")
    private val condType = object : TypeToken<LinkedHashMap<String, PresetCondJson>>() {}.type

    /**
     * The current ADG POV is bundled and authoritative; this prevents an older cached ADG
     * plan from restoring obsolete rows. Other presets can still use the remote cache first.
     */
    // ADG is the project-maintained POV preset. Prefer the bundled copy so a stale
    // downloaded ADG JSON cannot restore the retired crystal rows or old role map.
    fun load(id: String): PresetFile? = if (id == "adg") {
        loadBundled(id) ?: loadCached(id)
    } else {
        loadCached(id) ?: loadBundled(id)
    }

    private fun loadCached(id: String): PresetFile? = runCatching {
        val p = presetsDir.resolve("$id.json")
        if (!Files.exists(p)) return null
        gson.fromJson(Files.readString(p), PresetFile::class.java)
    }.getOrNull()

    internal fun loadBundled(id: String): PresetFile? = runCatching {
        val stream = SavepointPresets::class.java.classLoader.getResourceAsStream("assets/leaputils/presets/$id.json") ?: return null
        stream.bufferedReader().use { reader -> gson.fromJson(reader, PresetFile::class.java) }
    }.getOrNull()

    fun fromJson(json: String): PresetFile? = runCatching { gson.fromJson(json, PresetFile::class.java) }.getOrNull()

    fun toJson(preset: PresetFile): String = gson.toJson(preset)

    /**
     * Writes a fetched remote preset into the cache dir.
     * Returns (existed, changed) so the updater can tell "first download" from "update" from "unchanged".
     */
    fun saveRemote(id: String, content: String): Pair<Boolean, Boolean> = runCatching {
        Files.createDirectories(presetsDir)
        val p = presetsDir.resolve("$id.json")
        val existed = Files.exists(p)
        val changed = !existed || Files.readString(p) != content
        if (changed) Files.writeString(p, content)
        existed to changed
    }.getOrDefault(false to false)

    /** (exists on disk, last modified epoch ms) for the planner's preset status line */
    fun remoteInfo(id: String): Pair<Boolean, Long>? = runCatching {
        val p = presetsDir.resolve("$id.json")
        if (!Files.exists(p)) null
        else true to Files.getLastModifiedTime(p).toMillis()
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

    private val stepMetaFile: Path = dir.resolve("steplabels.json")
    private val stepMetaType = object : TypeToken<LinkedHashMap<String, PresetStepMeta>>() {}.type

    fun loadStepMeta(): Map<String, PresetStepMeta> = runCatching {
        if (!Files.exists(stepMetaFile)) return emptyMap()
        val read: LinkedHashMap<String, PresetStepMeta>? = gson.fromJson(Files.readString(stepMetaFile), stepMetaType)
        read ?: emptyMap()
    }.getOrDefault(emptyMap())

    fun saveStepMeta(meta: Map<String, PresetStepMeta>) {
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(stepMetaFile, gson.toJson(meta))
        }
    }
}
