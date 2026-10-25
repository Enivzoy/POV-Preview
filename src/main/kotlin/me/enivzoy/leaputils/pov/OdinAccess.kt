package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import kotlin.math.abs
import java.lang.reflect.Method

object OdinAccess {
    private val dungeonUtilsClass = DungeonUtils::class.java
    private val leapMenuClass = com.odtheking.odin.features.impl.dungeon.LeapMenu::class.java
    private val roomDataClass = com.odtheking.odin.features.impl.dungeon.map.tile.RoomData::class.java
    private val roomClass = com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom::class.java
    private val doorHighlightClass = Class.forName("com.odtheking.odin.features.impl.dungeon.DoorHighlight")
    private val bloodKeyField = doorHighlightClass.getDeclaredField("bloodKey").apply { isAccessible = true }
    private val bloodOpenedField = doorHighlightClass.getDeclaredField("bloodOpened").apply { isAccessible = true }
    private val dungeonScanClass = Class.forName("com.odtheking.odin.features.impl.dungeon.map.DungeonScan")
    private val doorClass = Class.forName("com.odtheking.odin.features.impl.dungeon.map.tile.DungeonDoor")
    
    private val dungeonUtilsInstance: Any = getStaticField(dungeonUtilsClass, "INSTANCE")
    private val leapMenuInstance: Any = getStaticField(leapMenuClass, "INSTANCE")
    private val dungeonScanInstance: Any = getStaticField(dungeonScanClass, "INSTANCE")
    
    private val getInBossMethod = findMethod(dungeonUtilsClass, "getInBoss")
    private val getFloorMethod = findMethod(dungeonUtilsClass, "getFloor")
    private val getCurrentRoomMethod = findMethod(dungeonUtilsClass, "getCurrentRoom")
    private val getCurrentDungeonPlayerMethod = findMethod(dungeonUtilsClass, "getCurrentDungeonPlayer")
    private val getLeapTeammatesMethod = findMethod(dungeonUtilsClass, "getLeapTeammates")
    private val getF7PhaseMethod = runCatching { findMethod(dungeonUtilsClass, "getF7Phase") }.getOrNull()
    private val getBossPhaseMethod2 = runCatching { findMethod(dungeonUtilsClass, "getBossPhase") }.getOrNull()

    private val terminalsStatusClass by lazy {
        runCatching { Class.forName("com.odtheking.odin.features.impl.boss.TerminalsStatus") }.getOrNull()
    }
    private val getTerminalSectionMethod by lazy {
        runCatching { terminalsStatusClass?.getDeclaredMethod("access\$getSection\$p") }.getOrNull()
    }
    private val terminalSectionField by lazy {
        runCatching { terminalsStatusClass?.getDeclaredField("section")?.apply { isAccessible = true } }.getOrNull()
    }
    
    private val getPhaseMethod = runCatching { findMethod(dungeonUtilsClass, "getPhase") }.getOrNull()
    private val getCurrentPhaseMethod = runCatching { findMethod(dungeonUtilsClass, "getCurrentPhase") }.getOrNull()
    
    private val leapToMethod = findMethod(leapMenuClass, "leapTo", String::class.java, AbstractContainerScreen::class.java)
    private val getDoorsMethod = findMethod(dungeonScanClass, "getDoors")
    private val doorGetTypeMethod = findMethod(doorClass, "getType")
    private val doorGetWorldXMethod = findMethod(doorClass, "getWorldX")
    private val doorGetWorldZMethod = findMethod(doorClass, "getWorldZ")
    
    private val floorNameMethod = findMethod(java.lang.Enum::class.java, "name")
    private val roomDataMethod = findMethod(roomClass, "getData")
    private val roomDataTypeMethod = findMethod(roomDataClass, "getType")
    private val roomDataNameMethod = findMethod(roomDataClass, "getName")
    
    private fun getStaticField(clazz: Class<*>, name: String): Any {
        val field = clazz.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null)
    }
    
    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method {
        val method = clazz.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        return method
    }
    
    val inBoss: Boolean
        get() = getInBossMethod.invoke(dungeonUtilsInstance) as Boolean
    
    val floor: com.odtheking.odin.utils.skyblock.dungeon.Floor?
        get() = getFloorMethod.invoke(dungeonUtilsInstance) as? com.odtheking.odin.utils.skyblock.dungeon.Floor
    
    val floorName: String?
        get() = floor?.let { floorNameMethod.invoke(it) as String }
    
    val currentRoom: Any?
        get() = getCurrentRoomMethod.invoke(dungeonUtilsInstance)
    
    val currentRoomData: Any?
        get() = currentRoom?.let { roomDataMethod.invoke(it) }
    
    val currentRoomType: Any?
        get() = currentRoomData?.let { roomDataTypeMethod.invoke(it) }
    
    val currentDungeonPlayer: DungeonPlayer?
        get() = getCurrentDungeonPlayerMethod.invoke(dungeonUtilsInstance) as? DungeonPlayer
    
    val leapTeammates: List<DungeonPlayer>
        get() = getLeapTeammatesMethod.invoke(dungeonUtilsInstance) as List<DungeonPlayer>
    
    val bloodKeyPickedUp: Boolean
        get() = runCatching { bloodKeyField.getBoolean(null) }.getOrDefault(false)

    val bloodDoorOpened: Boolean
        get() = runCatching { bloodOpenedField.getBoolean(null) }.getOrDefault(false)

    val hasBloodKeyBeenPickedUp: Boolean
        get() = bloodKeyPickedUp || bloodDoorOpened

    val terminalSection: Int?
        get() {
            getTerminalSectionMethod?.let { m ->
                runCatching { m.invoke(null) as? Int }.getOrNull()?.let { return it }
            }
            terminalSectionField?.let { f ->
                runCatching { f.getInt(null) }.getOrNull()?.let { return it }
            }
            return null
        }
    
    val bossPhase: String?
        get() {
            getF7PhaseMethod?.let { m ->
                runCatching {
                    val obj = m.invoke(dungeonUtilsInstance)
                    if (obj != null) {
                        val s = obj.toString()
                        if (s.isNotEmpty() && s != "null" && s != "Unknown") return s
                    }
                }
            }
            getBossPhaseMethod2?.let { m ->
                runCatching {
                    val obj = m.invoke(dungeonUtilsInstance)
                    if (obj != null) {
                        val s = obj.toString()
                        if (s.isNotEmpty() && s != "null" && s != "Unknown") return s
                    }
                }
            }
            getPhaseMethod?.let { m ->
                runCatching {
                    val obj = m.invoke(dungeonUtilsInstance)
                    if (obj != null) {
                        val s = obj.toString()
                        if (s.isNotEmpty() && s != "null") return s
                    }
                }
            }
            getCurrentPhaseMethod?.let { m ->
                runCatching {
                    val obj = m.invoke(dungeonUtilsInstance)
                    if (obj != null) {
                        val s = obj.toString()
                        if (s.isNotEmpty() && s != "null") return s
                    }
                }
            }
            listOf("bossPhase", "currentPhase", "phase", "dungeonPhase", "f7Phase", "m7Phase").forEach { name ->
                runCatching {
                    val field = dungeonUtilsClass.getDeclaredField(name)
                    field.isAccessible = true
                    val obj = field.get(dungeonUtilsInstance)
                    if (obj != null) {
                        val s = obj.toString()
                        if (s.isNotEmpty() && s != "null" && s != "Unknown") return s
                    }
                }
            }
            return null
        }
    
    private fun stripFormatting(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\u00a7') i += 2 else sb.append(text[i++])
        }
        return sb.toString()
    }

    private fun menuHasName(screen: AbstractContainerScreen<*>, name: String): Boolean {
        val slots = screen.menu.slots
        if (slots.size < 16) return false
        val target = stripFormatting(name)
        for (i in 11 until 16) {
            val hover = slots[i].item.hoverName.string.substringAfter(' ')
            if (hover.equals(target, ignoreCase = true)) return true
        }
        return false
    }

    fun autoLeapTo(teammate: DungeonPlayer, screen: AbstractContainerScreen<*>): Boolean {
        return try {
            if (!menuHasName(screen, teammate.name)) return false
            leapToMethod.invoke(leapMenuInstance, teammate.name, screen)
            true
        } catch (e: Exception) {
            println("[LeapUtils] Failed to leap: ${e.message}")
            false
        }
    }
    
    data class DoorPos(val x: Int, val z: Int)
    
    private fun findWitherDoorPos(): DoorPos? {
        val doors = runCatching { getDoorsMethod.invoke(dungeonScanInstance) as? Map<*, *> }.getOrNull() ?: return null
        if (doors.isEmpty()) return null
        
        var best: DoorPos? = null
        var bestDist = Double.MAX_VALUE
        for (door in doors.values) {
            if (door == null) continue
            val type = runCatching { doorGetTypeMethod.invoke(door) }.getOrNull() as? Enum<*> ?: continue
            val name = type.name
            if (name != "Wither" && name != "Blood") continue
            val x = runCatching { doorGetWorldXMethod.invoke(door) }.getOrNull() as? Int ?: continue
            val z = runCatching { doorGetWorldZMethod.invoke(door) }.getOrNull() as? Int ?: continue
            // Find closest door to player if possible, else just take first found
            val player = Minecraft.getInstance().player
            val dist = if (player != null) {
                val dx = x - player.x
                val dz = z - player.z
                dx * dx + dz * dz
            } else {
                0.0
            }
            if (dist < bestDist) {
                bestDist = dist
                best = DoorPos(x, z)
            }
        }
        return best
    }
    
    private fun teammateEntity(teammate: DungeonPlayer): net.minecraft.world.entity.player.Player? {
        val mc = Minecraft.getInstance()
        val local = mc.player ?: return null
        val level = mc.level
        
        teammate.entity?.let {
            if (it !== local && !it.isRemoved && it.isAlive && (level == null || it.level() === level)) return it
        }
        
        return level?.players()?.firstOrNull {
            it !== local && !it.isRemoved && it.isAlive && (level == null || it.level() === level) &&
                (it.name.string.equals(teammate.name, ignoreCase = true) ||
                    it.gameProfile.name.equals(teammate.name, ignoreCase = true) ||
                    it.scoreboardName.equals(teammate.name, ignoreCase = true))
        }
    }
    
    fun findClosestToWitherDoor(): DungeonPlayer? {
        val door = findWitherDoorPos()
        var closest: DungeonPlayer? = null
        var closestDist = Double.MAX_VALUE
        
        for (teammate in leapTeammates) {
            if (teammate.isDead) continue
            val entity = teammateEntity(teammate) ?: continue
            val dist = if (door != null) {
                val dx = entity.x - door.x
                val dz = entity.z - door.z
                dx * dx + dz * dz
            } else {
                val player = Minecraft.getInstance().player ?: continue
                val dx = entity.x - player.x
                val dz = entity.z - player.z
                dx * dx + dz * dz
            }
            if (dist < closestDist) {
                closestDist = dist
                closest = teammate
            }
        }
        return closest
    }

    fun findTeammateByClass(targetClass: DungeonClass): DungeonPlayer? {
        return leapTeammates.find { it.clazz == targetClass && it.entity != null && !it.isDead }
    }

    fun findClosestTeammateTo(
        x: Double,
        y: Double,
        z: Double,
        screen: AbstractContainerScreen<*>
    ): DungeonPlayer? {
        var closest: DungeonPlayer? = null
        var closestDistance = Double.MAX_VALUE

        for (teammate in leapTeammates) {
            if (teammate.isDead || !menuHasName(screen, teammate.name)) continue
            val entity = teammateEntity(teammate) ?: continue
            val dx = entity.x - x
            val dy = entity.y - y
            val dz = entity.z - z
            val distance = dx * dx + dy * dy + dz * dz
            if (distance < closestDistance) {
                closestDistance = distance
                closest = teammate
            }
        }

        return closest
    }
    
    fun isAtCoords(x: Int, y: Int, z: Int, leniency: Int): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        return abs(player.x - x) <= leniency &&
               abs(player.y - y) <= leniency &&
               abs(player.z - z) <= leniency
    }
    
    fun getRoomName(roomData: Any): String? {
        return roomData?.let { roomDataNameMethod.invoke(it) as String }
    }
}
