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
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val radius: Double? = null,
    val holdMs: Long? = null,
    val anyAfterMs: Long? = null,
    val onFail: String? = null,
    val objective: String? = null,
    val objectiveMin: Int? = null,
    val errorMinDistance: Double? = null,
    val errorMaxDistance: Double? = null,
)

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
    // resolved lazily so pure helpers (toCond/toJson/validateInterval) work without fabric loaded
    private val dir: Path by lazy { FabricLoader.getInstance().configDir.resolve("leaputils") }
    private val presetsDir: Path by lazy { dir.resolve("presets") }
    private val condsFile: Path by lazy { dir.resolve("conds.json") }
    private val condType = object : TypeToken<LinkedHashMap<String, PresetCondJson>>() {}.type

    fun load(id: String): PresetFile? = if (id == "adg" || id == "quoi") {
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

    fun saveRemote(id: String, content: String): Pair<Boolean, Boolean> = runCatching {
        Files.createDirectories(presetsDir)
        val p = presetsDir.resolve("$id.json")
        val existed = Files.exists(p)
        val changed = !existed || Files.readString(p) != content
        if (changed) Files.writeString(p, content)
        existed to changed
    }.getOrDefault(false to false)

    fun remoteInfo(id: String): Pair<Boolean, Long>? = runCatching {
        val p = presetsDir.resolve("$id.json")
        if (!Files.exists(p)) null
        else true to Files.getLastModifiedTime(p).toMillis()
    }.getOrNull()

    fun toCond(json: PresetCondJson): SpCond = SpCond(
        subject = json.subject?.let { runCatching { SpSubject.valueOf(it) }.getOrNull() } ?: SpSubject.TARGET,
        mode = json.mode?.let { runCatching { SpMode.valueOf(it) }.getOrNull() } ?: SpMode.NEAR,
        landmark = json.landmark ?: "",
        x = json.x,
        y = json.y,
        z = json.z,
        radius = json.radius ?: 0.0,
        holdMs = json.holdMs ?: 400,
        anyAfterMs = json.anyAfterMs ?: 0,
        onFail = json.onFail?.let { runCatching { SpOnFail.valueOf(it) }.getOrNull() } ?: SpOnFail.WAIT,
        objective = json.objective?.let { runCatching { SpObjective.valueOf(it) }.getOrNull() },
        objectiveMin = json.objectiveMin ?: 0,
        errorMinDistance = json.errorMinDistance,
        errorMaxDistance = json.errorMaxDistance,
    )

    // gson can't tell "absent" from "explicit default", so only write fields the cond really has
    fun toJson(cond: SpCond): PresetCondJson = PresetCondJson(
        subject = cond.subject.name,
        mode = cond.mode.name,
        landmark = cond.landmark,
        x = cond.x,
        y = cond.y,
        z = cond.z,
        radius = cond.radius,
        holdMs = cond.holdMs,
        anyAfterMs = cond.anyAfterMs,
        onFail = cond.onFail.name,
        objective = cond.objective?.name,
        objectiveMin = cond.objectiveMin,
        errorMinDistance = cond.errorMinDistance,
        errorMaxDistance = cond.errorMaxDistance,
    )

    fun validateInterval(min: Double?, max: Double?): String? {
        if (min != null && min < 0.0) return "error min distance must be >= 0 (got $min)"
        if (max != null && max < 0.0) return "error max distance must be >= 0 (got $max)"
        if (min != null && max != null && max < min) return "error max distance $max is below min $min"
        return null
    }

    fun loadConds(): Map<String, SpCond> = runCatching {
        if (!Files.exists(condsFile)) return emptyMap()
        val read: LinkedHashMap<String, PresetCondJson>? = gson.fromJson(Files.readString(condsFile), condType)
        read?.mapValues { (_, v) -> toCond(v) } ?: emptyMap()
    }.getOrDefault(emptyMap())

    fun saveConds(conds: Map<String, SpCond>) {
        runCatching {
            Files.createDirectories(dir)
            val json = conds.mapValues { (_, c) ->
                val m = LinkedHashMap<String, Any?>()
                m["subject"] = c.subject.name
                m["mode"] = c.mode.name
                m["landmark"] = c.landmark
                m["x"] = c.x
                m["y"] = c.y
                m["z"] = c.z
                m["radius"] = c.radius
                m["holdMs"] = c.holdMs
                m["anyAfterMs"] = c.anyAfterMs
                m["onFail"] = c.onFail.name
                m["objective"] = c.objective?.name
                m["objectiveMin"] = c.objectiveMin
                // only persist an interval when the cond actually carries one
                if (c.errorMinDistance != null) m["errorMinDistance"] = c.errorMinDistance
                if (c.errorMaxDistance != null) m["errorMaxDistance"] = c.errorMaxDistance
                m
            }
            Files.writeString(condsFile, gson.toJson(json))
        }
    }

    private val stepMetaFile: Path by lazy { dir.resolve("steplabels.json") }
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
