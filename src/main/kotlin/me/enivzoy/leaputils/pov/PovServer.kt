package me.enivzoy.leaputils.pov

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object PovServer {
    const val DEFAULT_RELAY = "wss://undonecoffee.com/pose/api/"
    const val DEFAULT_MESH = "https://undonecoffee.com/adr/mesh/"

    val relayBase: String by lazy { setting("relay", DEFAULT_RELAY) }
    val meshBase: String by lazy { setting("mesh", DEFAULT_MESH) }

    val configFile: Path by lazy {
        net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("leaputils").resolve("pov-server.properties")
    }

    private fun setting(key: String, fallback: String): String {
        System.getProperty("leaputils.pov.$key")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            val file = configFile
            if (!Files.exists(file)) return fallback
            val props = Properties()
            Files.newBufferedReader(file).use { props.load(it) }
            props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        }.getOrDefault(fallback)
    }
}
