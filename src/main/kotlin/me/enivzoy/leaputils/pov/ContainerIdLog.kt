package me.enivzoy.leaputils.pov

import com.google.gson.GsonBuilder
import me.enivzoy.leaputils.pov.fastleap.ContainerIdPredictor
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// append-only jsonl: leaporder.log is a rewritten json document, appending to it would corrupt it
// the folder is the same, the file is separate on purpose
object ContainerIdLog {

    // serializeNulls keeps the `predicted` key visible when there was no prediction
    private val gson = GsonBuilder().serializeNulls().create()
    private val dir: Path by lazy { FabricLoader.getInstance().configDir.resolve("leaputils") }
    private const val MAX_BYTES = 1_000_000L
    // bounded history: old archives are pruned so a long session cannot fill the disk
    private const val MAX_ARCHIVES = 5
    // bounded backlog: a slow disk may not grow an unbounded queue behind the packet thread
    private const val MAX_PENDING = 1024
    private val lock = Any()
    private val pending = AtomicInteger(0)

    // disk work runs on one dedicated writer, never on the packet or render thread
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "leaputils-containerid-log").apply { isDaemon = true }
    }

    init {
        // best effort: let queued lines land when the game closes
        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    writer.shutdown()
                    writer.awaitTermination(1, TimeUnit.SECONDS)
                }
            )
        }
    }

    fun append(generation: Int, kind: String, title: String, result: ContainerIdPredictor.Result): Boolean {
        // encode first: the result is immutable, but keeping the snapshot here bounds the queue payload
        val line = encode(generation, kind, title, result) + "\n"
        // reserve a slot atomically, so several callers can never overshoot the bound together
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            SpLog.warn("containerid", "queue full; dropping observation")
            return false
        }
        return runCatching {
            writer.execute {
                try {
                    write(line)
                } finally {
                    pending.decrementAndGet()
                }
            }
            true
        }.getOrElse {
            pending.decrementAndGet()
            // a broken diagnostic log must never fail the leap, but it must leave a trace
            SpLog.warn("containerid", "queue failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    private fun write(line: String) {
        synchronized(lock) {
            runCatching {
                Files.createDirectories(dir)
                val file = dir.resolve("containerid.log")
                // rotate before writing so a full log is never truncated in place; a failed
                // rotation must never stop the log, so the write continues and the archive is retried later
                if (Files.exists(file) && Files.size(file) >= MAX_BYTES) rotate(file)
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                true
            }.getOrElse {
                SpLog.warn("containerid", "write failed: ${it.message ?: it.javaClass.simpleName}")
                false
            }
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
    // a failure here is reported and swallowed: the caller still appends, so observations keep flowing
    private fun rotate(file: Path) {
        runCatching {
            Files.move(file, nextArchive(file), StandardCopyOption.REPLACE_EXISTING)
            prune(file)
        }.onFailure {
            SpLog.warn("containerid", "rotation failed, appending to the current file: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun nextArchive(file: Path): Path {
        val highest = archived(file).maxOrNull() ?: 0
        return file.resolveSibling("${file.fileName}.${highest + 1}.old")
    }

    // keep only the newest MAX_ARCHIVES side files, so rotation is not an unbounded disk leak
    private fun prune(file: Path) {
        for (old in archived(file).sortedDescending().drop(MAX_ARCHIVES)) {
            runCatching { Files.deleteIfExists(file.resolveSibling("${file.fileName}.$old.old")) }
        }
    }

    private fun archived(file: Path): List<Int> {
        val parent = file.parent ?: return emptyList()
        val prefix = "${file.fileName}."
        return runCatching {
            Files.list(parent).use { stream -> stream.toList() }
                .mapNotNull { p ->
                    val n = p.fileName.toString()
                    if (!n.startsWith(prefix) || !n.endsWith(".old")) return@mapNotNull null
                    n.removePrefix(prefix).removeSuffix(".old").toIntOrNull()
                }
        }.getOrDefault(emptyList())
    }
}