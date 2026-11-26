package me.enivzoy.leaputils.dungeonpack.pack

import me.enivzoy.leaputils.LeapUtils

// opens the two bundled packs once at startup: a missing jar resource must not fail silently
object PackSelfTest {

    private val paths = listOf(
        "/assets/leaputils/dungeonpack/packs/doors.json.gz",
        "/assets/leaputils/dungeonpack/packs/secrets.json.gz",
    )

    fun run(): List<String> {
        val missing = ArrayList<String>()
        for (path in paths) {
            val stream = PackSelfTest::class.java.getResourceAsStream(path)
            if (stream == null) {
                missing += path
                LeapUtils.logger.error("[leaputils] bundled pack missing: $path (doors, gap walls and secrets will not draw)")
                continue
            }
            stream.use { it.readNBytes(2) }
        }
        if (missing.isEmpty()) LeapUtils.logger.info("[leaputils] bundled door and secret packs present")
        return missing
    }
}
