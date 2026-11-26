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

    fun write(cache: LeapOrderCache, entries: List<LeapOrderCache.Entry>): Boolean = runCatching {
        Files.createDirectories(dir)
        val file = dir.resolve("leaporder.log")
        val rec = cache.observe(entries) ?: return@runCatching false
        // rotate before reading, so a full log is never truncated in place
        if (Files.exists(file) && Files.size(file) >= MAX_BYTES) {
            Files.move(file, dir.resolve("leaporder.log.old"), StandardCopyOption.REPLACE_EXISTING)
        }
        val existing = if (Files.exists(file)) readRecords(file) else LinkedHashMap()
        val merged = merge(existing, rec.runId, cache.logEntry()?.get(rec.runId)) ?: return@runCatching false
        // atomic: write beside it, then replace, so a crash cannot leave half a record
        val tmp = dir.resolve("leaporder.log.tmp")
        Files.writeString(tmp, gson.toJson(merged))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    // pure merge: an older run record is never dropped when the next run appends its own
    fun merge(existing: Map<String, Any>, runId: String, record: Map<String, Any>?): Map<String, Any>? {
        if (record == null) return null
        val out = LinkedHashMap<String, Any>(existing)
        out[runId] = record
        return out
    }
    private fun readRecords(file: Path): LinkedHashMap<String, Any> = runCatching {
        val type = com.google.gson.reflect.TypeToken.getParameterized(
            LinkedHashMap::class.java, String::class.java, Any::class.java
        ).type
        gson.fromJson<LinkedHashMap<String, Any>>(Files.readString(file), type) ?: LinkedHashMap()
    }.getOrDefault(LinkedHashMap())
}