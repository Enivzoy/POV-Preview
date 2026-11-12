package me.enivzoy.leaputils.pov

import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.modMessage
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.TitleScreen
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object SavepointRemote {

    val PRESET_IDS = listOf("adg", "mm7g")

    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/Enivzoy/POV-Preview/LeapUtils",
        "https://cdn.jsdelivr.net/gh/Enivzoy/POV-Preview@LeapUtils",
    )

    private val USER_AGENT: String = runCatching {
        val version = FabricLoader.getInstance().getModContainer("leaputils").orElse(null)?.metadata?.version?.friendlyString ?: "?"
        "LeapUtils/$version (github.com/Enivzoy/POV-Preview)"
    }.getOrDefault("LeapUtils (github.com/Enivzoy/POV-Preview)")

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

    fun checkForUpdates(manual: Boolean = false) {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
        try {
            for (id in PRESET_IDS) fetchOne(client, id, manual)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun fetchOne(client: HttpClient, id: String, manual: Boolean) {
        var lastError: Exception? = null
        var lastUrl = ""
        var loggedDns = false
        for (pass in 1..PASSES) {
            for (base in SOURCES) {
                val url = "$base/presets/$id.json"
                lastUrl = url
                try {
                    val request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build()
                    val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
                    if (body.isNullOrBlank()) {
                        SpLog.warn("presets", "$id: $url returned an empty body")
                        if (manual) modMessage("[Savepoint] §c$id§f: $url returned nothing")
                        return
                    }

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
                    return
                } catch (e: Exception) {
                    lastError = e
                    SpLog.warn("presets", "$id: $url failed (pass $pass/$PASSES): ${e.javaClass.simpleName}: ${e.message}")
                    if (!loggedDns) {

                        val host = runCatching { URI.create(url).host }.getOrDefault(url)
                        val ips = runCatching { InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress } }
                            .getOrDefault("lookup failed")
                        SpLog.warn("presets", "$id: dns $host -> $ips")
                        loggedDns = true
                    }
                    runCatching { Thread.sleep(RETRY_DELAY_MS) }
                }
            }
        }
        SpLog.warn("presets", "$id: all ${SOURCES.size} sources x $PASSES passes failed, last: $lastUrl")
        if (manual) modMessage("[Savepoint] §c$id§f update check failed: ${lastError?.message ?: lastError?.javaClass?.simpleName ?: "unknown error"} - github raw and jsdelivr both unreachable")
    }

    private const val PASSES = 2
    private const val RETRY_DELAY_MS = 1_500L
}
