package me.enivzoy.leaputils.pov

import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.modMessage
import net.minecraft.client.gui.screens.TitleScreen
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Keeps the guide presets (ADG, MM7G) fresh from the GitHub repo: once per launch, on the first title
 * screen (not on world join), the raw preset files are fetched and dropped into
 * config/leaputils/presets/<id>.json - SavepointPresets.load prefers that cache over the jar's bundled copy,
 * so repo updates reach the mod without a new build. Chat gets told on first download / update; unchanged
 * and failed checks only go to the log so the title screen never spams.
 */
object SavepointRemote {

    val PRESET_IDS = listOf("adg", "mm7g")

    private var registered = false
    private var checkedThisSession = false

    fun register() {
        if (registered) return
        registered = true
        EventBus.subscribe(this)
        on<ScreenEvent.Open> {
            if (checkedThisSession || screen !is TitleScreen) return@on
            checkedThisSession = true
            val t = Thread { checkForUpdates() }
            t.isDaemon = true
            t.name = "LeapUtils-PresetUpdater"
            t.start()
        }
    }

    /** fetches every preset as raw from the repo; manual=true also reports "up to date"/failures in chat */
    fun checkForUpdates(manual: Boolean = false) {
        val base = Savepoint.presetRepoUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            if (manual) modMessage("[Savepoint] §cno preset repo URL configured")
            return
        }
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        try {
            for (id in PRESET_IDS) fetchOne(client, base, id, manual)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun fetchOne(client: HttpClient, base: String, id: String, manual: Boolean) {
        val url = "$base/presets/$id.json"
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build()
            val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
            if (body.isNullOrBlank()) {
                SpLog.warn("presets", "$id: $url returned an empty body")
                if (manual) modMessage("[Savepoint] §c$id§f: $url returned nothing")
                return
            }
            // never cache something that isn't a valid preset - a broken push must not poison the loader
            val parsed = SavepointPresets.fromJson(body)
            if (parsed == null) {
                SpLog.warn("presets", "$id: $url is not a valid preset JSON, keeping the current copy")
                if (manual) modMessage("[Savepoint] §c$id§f: remote file is not valid preset JSON")
                return
            }
            val (existed, changed) = SavepointPresets.saveRemote(id, body)
            when {
                !existed -> {
                    modMessage("[Savepoint] §a$id§f preset downloaded from GitHub §7($url)§f")
                    SpLog.info("presets", "$id: first download from $url")
                }
                changed -> {
                    modMessage("[Savepoint] §a$id§f preset was §lupdated§r§f on GitHub - the new version is saved")
                    SpLog.info("presets", "$id: remote update detected and saved from $url")
                }
                else -> {
                    SpLog.info("presets", "$id: remote matches the cached copy")
                    if (manual) modMessage("[Savepoint] §a$id§f preset is up to date")
                }
            }
        } catch (e: Exception) {
            SpLog.warn("presets", "$id: update check failed ($url): ${e.javaClass.simpleName}: ${e.message}")
            if (manual) modMessage("[Savepoint] §c$id§f update check failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
