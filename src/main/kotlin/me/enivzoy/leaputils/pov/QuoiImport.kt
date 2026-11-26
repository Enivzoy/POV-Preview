package me.enivzoy.leaputils.pov

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.odtheking.odin.utils.modMessage
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

// imports a quoi-style plan plus its sibling landmarks file, prefixing every landmark
object QuoiImport {

    const val PREFIX = "quoicnfg-"
    const val PRESET_ID = "quoi"
    const val LANDMARK_FILE = "quoi-landmarks.json"
    const val DEFAULT_URL = "https://raw.githubusercontent.com/Enivzoy/POV-Preview/LeapUtils/presets/quoi.json"

    private val gson: Gson = GsonBuilder().create()

    data class Prepared(
        val preset: PresetFile,
        val landmarks: LinkedHashMap<String, SpLandmark>,
        val conds: Int,
        val condsMissingInterval: Int,
    )

    fun prefixedName(name: String): String {
        val n = name.trim()
        return when {
            n.isEmpty() -> ""
            n.startsWith(PREFIX) -> n
            else -> PREFIX + n
        }
    }

    // quoi-landmarks.json sits next to quoi.json, so importing one fetches the other
    fun landmarksUrl(url: String): String? {
        val base = baseOf(url) ?: return null
        return base + LANDMARK_FILE
    }

    fun baseOf(url: String): String? {
        val trimmed = url.trim().substringBefore('?').substringBefore('#')
        if (trimmed.isEmpty()) return null
        if (trimmed.endsWith("/")) return trimmed
        val cut = trimmed.lastIndexOf('/')
        return if (cut < 0) null else trimmed.substring(0, cut + 1)
    }

    fun parseLandmarks(json: String): LinkedHashMap<String, SpLandmark>? = runCatching {
        val text = json.trim()
        if (text.isEmpty()) return null
        val element = gson.fromJson(text, com.google.gson.JsonElement::class.java)
        if (element == null || !element.isJsonObject) return null
        val root = element.asJsonObject
        val obj = root.get("landmarks")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: root
        val out = LinkedHashMap<String, SpLandmark>()
        for ((name, value) in obj.entrySet()) {
            if (!value.isJsonObject) continue
            val lm = readPoint(value.asJsonObject) ?: continue
            val n = name.trim()
            if (n.isEmpty()) continue
            out[n] = lm
        }
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    // a point needs all three finite coordinates; a partial object is not a landmark
    private fun readPoint(obj: com.google.gson.JsonObject): SpLandmark? {
        val x = obj.get("x")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return null
        val y = obj.get("y")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return null
        val z = obj.get("z")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return null
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        return SpLandmark(x, y, z)
    }

    // pure half of the import: validates the plan, prefixes landmarks and rewrites cond references
    fun prepare(presetJson: String, landmarkJson: String): Prepared {
        val preset = SavepointPresets.fromJson(presetJson)
            ?: throw IllegalArgumentException("quoi.json is not a valid preset JSON")
        val raw = parseLandmarks(landmarkJson)
            ?: throw IllegalArgumentException("$LANDMARK_FILE has no usable landmarks")

        val prefixed = LinkedHashMap<String, SpLandmark>()
        for ((name, lm) in raw) prefixed[prefixedName(name)] = lm
        val known = prefixed.keys

        val cells = preset.cells ?: emptyMap()
        if (cells.isEmpty()) throw IllegalArgumentException("quoi.json has no cells")
        for (stage in SpStage.entries) {
            for (step in 1..stage.steps) {
                val key = "${stage.id}.$step"
                val wholeStage = cells.containsKey(stage.id)
                val row = cells[key]
                val fillsAll = row != null && (
                    row.containsKey("*") ||
                        SavepointEngine.LEAP_CLASSES.all { c -> row.containsKey(c.name) }
                    )
                if (!wholeStage && !fillsAll) {
                    throw IllegalArgumentException("quoi.json leaves $key empty; every planner cell must be filled")
                }
            }
        }

        var missing = 0
        val conds = LinkedHashMap<String, PresetCondJson>()
        for ((key, cond) in preset.conds ?: emptyMap()) {
            val err = SavepointPresets.validateInterval(cond.errorMinDistance, cond.errorMaxDistance)
            if (err != null) throw IllegalArgumentException("cond '$key': $err")
            if (cond.errorMinDistance == null || cond.errorMaxDistance == null) missing++
            val lm = cond.landmark?.trim().orEmpty()
            val mapped = if (lm.isNotEmpty() && prefixedName(lm) in known) {
                cond.copy(landmark = prefixedName(lm))
            } else {
                cond
            }
            conds[key] = mapped
        }

        return Prepared(
            preset = preset.copy(cells = cells, conds = conds),
            landmarks = prefixed,
            conds = conds.size,
            condsMissingInterval = missing,
        )
    }

    fun bundledPreset(): String? = bundled("quoi.json")
    fun bundledLandmarks(): String? = bundled(LANDMARK_FILE)

    private fun bundled(name: String): String? = runCatching {
        QuoiImport::class.java.classLoader
            .getResourceAsStream("assets/leaputils/presets/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
    }.getOrNull()

    // network and disk stay off the render thread; the matrix is applied on the client thread
    fun importAsync(url: String, bundledOnly: Boolean, done: (String?) -> Unit) {
        val worker = Thread {
            val result = runCatching { fetch(url, bundledOnly) }
            Minecraft.getInstance().execute {
                result.fold(
                    onSuccess = { raw ->
                        runCatching { prepare(raw.first, raw.second) }.fold(
                            onSuccess = { prep ->
                                Saving.applyQuoiPlan(prep)
                                done(null)
                            },
                            onFailure = { done(it.message ?: it.javaClass.simpleName) },
                        )
                    },
                    onFailure = { done(it.message ?: it.javaClass.simpleName) },
                )
            }
        }
        worker.isDaemon = true
        worker.name = "LeapUtils-QuoiImport"
        worker.start()
    }

    private fun fetch(url: String, bundledOnly: Boolean): Pair<String, String> {
        if (bundledOnly) {
            val preset = bundledPreset() ?: throw IllegalStateException("bundled quoi.json is missing")
            val marks = bundledLandmarks() ?: throw IllegalStateException("bundled $LANDMARK_FILE is missing")
            return preset to marks
        }
        val target = url.trim()
        if (target.isEmpty()) throw IllegalArgumentException("paste a quoi.json URL or a local path first")
        val landmarksUrl = landmarksUrl(target)
            ?: throw IllegalArgumentException("'$target' is not a full file URL or path")

        return if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build()
            try {
                val preset = download(client, target)
                val marks = download(client, landmarksUrl)
                // keep a cached copy so the bundled fallback and presets list stay in sync
                Saving.cacheQuoiPreset(preset)
                preset to marks
            } finally {
                runCatching { client.close() }
            }
        } else {
            val presetPath = Path.of(target)
            val marksPath = presetPath.resolveSibling(LANDMARK_FILE)
            val preset = Files.readString(presetPath)
            val marks = Files.readString(marksPath)
            preset to marks
        }
    }

    private fun download(client: HttpClient, url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", "LeapUtils (github.com/Enivzoy/POV-Preview)")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("$url returned HTTP ${response.statusCode()}")
        }
        val body = response.body()
        if (body.isNullOrBlank()) throw IllegalStateException("$url returned an empty body")
        return body
    }

    // tiny indirection so the import can touch savepoint state without widening the public API
    private object Saving {
        fun cacheQuoiPreset(json: String) {
            runCatching { SavepointPresets.saveRemote(PRESET_ID, json) }
        }

        fun applyQuoiPlan(prep: Prepared) {
            Landmarks.putAll(prep.landmarks)
            Savepoint.applyPreset(prep.preset)
            val suffix = if (prep.condsMissingInterval > 0) {
                " (${prep.condsMissingInterval} conds carry no error interval)"
            } else {
                ""
            }
            modMessage(
                "[Savepoint] §aquoi§f imported: ${prep.landmarks.size} landmarks as §f${PREFIX}<name>§f, " +
                    "${prep.conds} conds with 3..15 error$suffix"
            )
        }
    }
}