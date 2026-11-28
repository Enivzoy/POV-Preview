package me.enivzoy.leaputils.pov

import com.google.gson.GsonBuilder
import me.enivzoy.leaputils.pov.fastleap.ContainerIdPredictor
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

// append-only jsonl: leaporder.log is a rewritten json document, appending to it would corrupt it
// the folder is the same, the file is separate on purpose
object ContainerIdLog {

    // serializeNulls keeps the `predicted` key visible when there was no prediction
    private val gson = GsonBuilder().serializeNulls().create()
    private val dir: Path by lazy { FabricLoader.getInstance().configDir.resolve("leaputils") }
    private const val MAX_BYTES = 1_000_000L
    private val lock = Any()

    fun append(generation: Int, kind: String, title: String, result: ContainerIdPredictor.Result): Boolean =
        synchronized(lock) {
            runCatching {
                Files.createDirectories(dir)
                val file = dir.resolve("containerid.log")
                // rotate before writing so a full log is never truncated in place
                if (Files.exists(file) && Files.size(file) >= MAX_BYTES) rotate(file)
                Files.writeString(
                    file,
                    encode(generation, kind, title, result) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                true
            }.getOrElse {
                // a broken diagnostic log must never fail the leap, but it must leave a trace
                SpLog.warn("containerid", "write failed: ${it.message ?: it.javaClass.simpleName}")
                false
            }
        }

    // one JSONL line per observation; internal so the null handling can be tested without a runtime
    internal fun encode(generation: Int, kind: String, title: String, result: ContainerIdPredictor.Result): String {
        val observation = result.observation
        val record = linkedMapOf(
            "t" to observation.atMs,
            "gen" to generation,
            "id" to observation.id,
            "kind" to kind,
            "title" to title,
            "predicted" to observation.predictedId,
            "match" to observation.matched,
            "inventory" to observation.inventory,
            "confidence" to result.confidence.name.lowercase(),
        )
        return gson.toJson(record)
    }

    // numbered side archives keep the older audit history instead of overwriting one `.old`
    private fun rotate(file: Path) {
        var n = 1
        var target = file.resolveSibling("${file.fileName}.$n.old")
        while (Files.exists(target)) {
            n++
            target = file.resolveSibling("${file.fileName}.$n.old")
        }
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
