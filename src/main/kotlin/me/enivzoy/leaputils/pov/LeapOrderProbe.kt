package me.enivzoy.leaputils.pov

import com.google.gson.GsonBuilder
import me.enivzoy.leaputils.pov.fastleap.LeapOrderCache
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// local diagnostic only: read the real menu heads, never guess an identity from a display name
object LeapOrderProbe {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    // lazy so a unit test can call the pure merge without a fabric runtime
    private val dir: Path by lazy { FabricLoader.getInstance().configDir.resolve("leaputils") }
    private const val MAX_BYTES = 1_000_000L
    // bounded history: old archives are pruned so a long session cannot fill the disk
    private const val MAX_ARCHIVES = 5
    private val lock = Any()

    fun scan(screen: AbstractContainerScreen<*>, classOf: (String) -> String?): List<LeapOrderCache.Entry> {
        val out = ArrayList<LeapOrderCache.Entry>()
        val slots = runCatching { screen.menu.slots }.getOrNull() ?: return out
        // key by the menu-wide index, not the per-container slot: containerSlot repeats between the
        // menu and the player inventory, so two real heads could otherwise share one key
        for ((index, slot) in slots.withIndex()) {
            val stack = runCatching { slot.item }.getOrNull() ?: continue
            if (stack.isEmpty) continue
            // only a real profile component is identity, a hover name alone is not
            val profile = runCatching { stack.get(DataComponents.PROFILE) }.getOrNull() ?: continue
            val uuid = runCatching { profile.partialProfile().id()?.toString() }.getOrNull() ?: continue
            if (uuid.isEmpty()) continue
            val name = runCatching { profile.partialProfile().name() }.getOrNull()
            out.add(
                LeapOrderCache.Entry(
                    uuid = uuid,
                    ordinal = 0,
                    slot = index,
                    clazz = name?.let(classOf) ?: "UNKNOWN",
                )
            )
        }
        // ordinal is the position among the observed entries, the slot stays its own menu-wide number
        return out.sortedBy { it.slot }.mapIndexed { index, entry -> entry.copy(ordinal = index) }
    }

    fun write(cache: LeapOrderCache, entries: List<LeapOrderCache.Entry>): Boolean = synchronized(lock) {
        val rec = cache.observe(entries) ?: return@synchronized false
        runCatching {
            Files.createDirectories(dir)
            val file = dir.resolve("leaporder.log")
            // rotate before reading, so a full log is never truncated in place
            if (Files.exists(file) && Files.size(file) >= MAX_BYTES) rotate(file)
            val existing: LinkedHashMap<String, Any> = if (Files.exists(file)) {
                // an unreadable log is preserved as a side archive instead of being overwritten
                readRecords(file) ?: run {
                    val kept = quarantine(file)
                    SpLog.warn("leaporder", "unreadable $file moved to $kept; starting a new record")
                    LinkedHashMap()
                }
            } else {
                LinkedHashMap()
            }
            val current = cache.logEntry()?.get(rec.runId)
            if (current == null) {
                // the cache lost the run between observe and logEntry, the record cannot be written
                SpLog.warn("leaporder", "no cache entry for ${rec.runId}; keeping the previous records")
                return@runCatching false
            }
            val merged = merge(existing, rec.runId, current)
            if (merged == null) {
                // a null record would silently drop this run; keep the file and report the drop
                SpLog.warn("leaporder", "merge produced no record for ${rec.runId}; keeping the previous records")
                return@runCatching false
            }
            // write beside it then move, so a crash cannot leave half a record; the move is atomic
            // where the filesystem supports it and falls back to a plain replace when it does not
            val tmp = dir.resolve("leaporder.log.tmp")
            Files.writeString(tmp, gson.toJson(merged))
            replaceAtomically(tmp, file)
            true
        }.getOrElse {
            SpLog.warn("leaporder", "write failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    // pure merge: an older run record is never dropped when the next run appends its own
    fun merge(existing: Map<String, Any>, runId: String, record: Map<String, Any>?): Map<String, Any>? {
        if (record == null) return null
        val out = LinkedHashMap<String, Any>(existing)
        out[runId] = record
        return out
    }

    // atomic when the platform allows it, otherwise the same replace semantics without the guarantee
    private fun replaceAtomically(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (atomicFailure: Exception) {
            // some providers reject ATOMIC_MOVE (or an atomic replace of an existing file); the
            // plain move still swaps the whole document, it just cannot promise crash atomicity
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (plainFailure: Exception) {
                plainFailure.addSuppressed(atomicFailure)
                throw plainFailure
            }
        }
    }

    // numbered side archives keep the older audit history instead of overwriting one `.old`
    // a failure here is reported and swallowed: the write still replaces the whole document, so
    // a failed rotation never loses the new run and simply leaves the old file to grow
    private fun rotate(file: Path) {
        runCatching {
            Files.move(file, nextArchive(file, ".old"), StandardCopyOption.REPLACE_EXISTING)
            prune(file, ".old")
        }.onFailure {
            SpLog.warn("leaporder", "rotation failed, rewriting in place: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun quarantine(file: Path): Path {
        val target = nextArchive(file, ".corrupt")
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    // the archive number is monotonic and never reused, so a higher number is always the newer file
    private fun nextArchive(file: Path, suffix: String): Path {
        val highest = archiveNumbers(file, suffix).maxOrNull() ?: 0
        return file.resolveSibling("${file.fileName}.${highest + 1}$suffix")
    }

    // keep only the newest MAX_ARCHIVES side files, so rotation is not an unbounded disk leak
    internal fun prune(file: Path, suffix: String) {
        val ordered = siblingsMatching(file, suffix).sortedByDescending { archiveNumber(it, file) ?: 0 }
        for (old in ordered.drop(MAX_ARCHIVES)) {
            runCatching { Files.deleteIfExists(old) }
        }
    }

    private fun archiveNumbers(file: Path, suffix: String): List<Int> =
        siblingsMatching(file, suffix).mapNotNull { archiveNumber(it, file) }

    // sibling files named `<file>.<n><suffix>`; anything else in the folder is left alone
    internal fun siblingsMatching(file: Path, suffix: String): List<Path> {
        val parent = file.parent ?: return emptyList()
        val prefix = "${file.fileName}."
        return runCatching {
            Files.list(parent).use { stream -> stream.toList() }
        }.getOrDefault(emptyList()).filter {
            val n = it.fileName.toString()
            n.startsWith(prefix) && n.endsWith(suffix) && archiveNumber(it, file) != null
        }
    }

    internal fun archiveNumber(candidate: Path, file: Path): Int? {
        val name = candidate.fileName.toString()
        val prefix = "${file.fileName}."
        if (!name.startsWith(prefix)) return null
        val digits = name.removePrefix(prefix).takeWhile { it.isDigit() }
        return if (digits.isEmpty()) null else digits.toIntOrNull()
    }

    // null means the file exists but could not be parsed, which must never look like "empty"
    // internal so the number round-trip can be tested without a fabric runtime
    internal fun readRecords(file: Path): LinkedHashMap<String, Any>? = runCatching {
        val text = Files.readString(file)
        // a blank file is an empty log, not a corrupt one
        if (text.isBlank()) return@runCatching LinkedHashMap()
        val type = com.google.gson.reflect.TypeToken.getParameterized(
            LinkedHashMap::class.java, String::class.java, Any::class.java
        ).type
        val parsed: LinkedHashMap<String, Any>? = gson.fromJson(text, type)
            ?: return@runCatching null
        normalizeNumbers(parsed) as LinkedHashMap<String, Any>
    }.getOrNull()

    // Gson parses every number as a Double, so a rewrite would turn slot 11 into 11.0
    private fun normalizeNumbers(value: Any?): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().apply {
            for ((k, v) in value) if (k is String) put(k, normalizeNumbers(v))
        }
        is List<*> -> value.map { normalizeNumbers(it) }
        is Double -> if (value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else value
        else -> value
    }
}
