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

    private val gson = GsonBuilder().create()
    private val dir: Path by lazy { FabricLoader.getInstance().configDir.resolve("leaputils") }
    private const val MAX_BYTES = 1_000_000L

    fun append(generation: Int, kind: String, title: String, result: ContainerIdPredictor.Result): Boolean = runCatching {
        Files.createDirectories(dir)
        val file = dir.resolve("containerid.log")
        // rotate before writing so a full log is never truncated in place
        if (Files.exists(file) && Files.size(file) >= MAX_BYTES) {
            Files.move(file, dir.resolve("containerid.log.old"), StandardCopyOption.REPLACE_EXISTING)
        }
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
        Files.writeString(
            file,
            gson.toJson(record) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        true
    }.getOrDefault(false)
}