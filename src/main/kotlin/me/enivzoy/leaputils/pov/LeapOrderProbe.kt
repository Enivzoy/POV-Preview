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
    private val lock = Any()

    fun scan(screen: AbstractContainerScreen<*>, classOf: (String) -> String?): List<LeapOrderCache.Entry> {
        val out = ArrayList<LeapOrderCache.Entry>()
        val slots = runCatching { screen.menu.slots }.getOrNull() ?: return out
        for (slot in slots) {
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
                    slot = slot.containerSlot,
                    clazz = name?.let(classOf) ?: "UNKNOWN",
                )
            )
        }
        // ordinal is the position among the observed entries, the slot stays its own number
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
            val merged = merge(existing, rec.runId, cache.logEntry()?.get(rec.runId)) ?: return@runCatching false
            // atomic: write beside it, then replace, so a crash cannot leave half a record
            val tmp = dir.resolve("leaporder.log.tmp")
            Files.writeString(tmp, gson.toJson(merged))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
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

    private fun quarantine(file: Path): Path {
        var n = 1
        var target = file.resolveSibling("${file.fileName}.$n.corrupt")
        while (Files.exists(target)) {
            n++
            target = file.resolveSibling("${file.fileName}.$n.corrupt")
        }
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        return target
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
