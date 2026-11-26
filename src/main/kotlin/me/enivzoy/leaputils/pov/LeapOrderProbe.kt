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
    private val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils")
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
        val existing = if (Files.exists(file) && Files.size(file) < MAX_BYTES) readRecords(file) else LinkedHashMap()
        val rec = cache.observe(entries) ?: return@runCatching false
        val players = LinkedHashMap<String, List<String>>()
        val slotByUuid = LinkedHashMap<String, Int>()
        for (entry in rec.entries) {
            players[entry.uuid] = listOf("[${entry.ordinal}]", entry.clazz)
            slotByUuid[entry.uuid] = entry.slot
        }
        existing[rec.runId] = mapOf("players" to players, "slotByUuid" to slotByUuid)
        // atomic: write beside it, then replace, so a crash cannot leave half a record
        val tmp = dir.resolve("leaporder.log.tmp")
        Files.writeString(tmp, gson.toJson(existing))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    private fun readRecords(file: Path): LinkedHashMap<String, Any> = runCatching {
        val type = com.google.gson.reflect.TypeToken.getParameterized(
            LinkedHashMap::class.java, String::class.java, Any::class.java
        ).type
        gson.fromJson<LinkedHashMap<String, Any>>(Files.readString(file), type) ?: LinkedHashMap()
    }.getOrDefault(LinkedHashMap())
}