package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

object OdinAccess {
    private val dungeonUtilsClass = DungeonUtils::class.java
    private val leapMenuClass = com.odtheking.odin.features.impl.dungeon.LeapMenu::class.java
    private val roomDataClass = com.odtheking.odin.features.impl.dungeon.map.tile.RoomData::class.java
    private val roomClass = com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom::class.java
    private val doorHighlightClass = Class.forName("com.odtheking.odin.features.impl.dungeon.DoorHighlight")
    private val bloodKeyField = findField(doorHighlightClass, "bloodKey")
    private val bloodOpenedField = findField(doorHighlightClass, "bloodOpened")
    private val dungeonScanClass = Class.forName("com.odtheking.odin.features.impl.dungeon.map.DungeonScan")
    private val doorClass = Class.forName("com.odtheking.odin.features.impl.dungeon.map.tile.DungeonDoor")

    private val dungeonUtilsInstance: Any? = getStaticField(dungeonUtilsClass, "INSTANCE")
    private val leapMenuInstance: Any? = getStaticField(leapMenuClass, "INSTANCE")
    private val dungeonScanInstance: Any? = getStaticField(dungeonScanClass, "INSTANCE")

    private val getInBossMethod = findMethod(dungeonUtilsClass, "getInBoss")
    private val getFloorMethod = findMethod(dungeonUtilsClass, "getFloor")
    private val getCurrentRoomMethod = findMethod(dungeonUtilsClass, "getCurrentRoom")
    private val getCurrentDungeonPlayerMethod = findMethod(dungeonUtilsClass, "getCurrentDungeonPlayer")
    private val getLeapTeammatesMethod = findMethod(dungeonUtilsClass, "getLeapTeammates")
    private val getF7PhaseMethod = findMethod(dungeonUtilsClass, "getF7Phase")
    private val getBossPhaseMethod2 = findMethod(dungeonUtilsClass, "getBossPhase")

    private val terminalsStatusClass by lazy {
        runCatching { Class.forName("com.odtheking.odin.features.impl.boss.TerminalsStatus") }.getOrNull()
    }
    private val getTerminalSectionMethod by lazy {
        runCatching { terminalsStatusClass?.getDeclaredMethod("access\$getSection\$p") }.getOrNull()
    }
    private val terminalSectionField by lazy {
        runCatching { terminalsStatusClass?.getDeclaredField("section")?.apply { isAccessible = true } }.getOrNull()
    }

    private val getPhaseMethod = findMethod(dungeonUtilsClass, "getPhase")
    private val getCurrentPhaseMethod = findMethod(dungeonUtilsClass, "getCurrentPhase")

    private val leapToMethod = findMethod(leapMenuClass, "leapTo", String::class.java, AbstractContainerScreen::class.java)
    private val getDoorsMethod = findMethod(dungeonScanClass, "getDoors")
    private val doorGetTypeMethod = findMethod(doorClass, "getType")
    private val doorGetWorldXMethod = findMethod(doorClass, "getWorldX")
    private val doorGetWorldZMethod = findMethod(doorClass, "getWorldZ")

    private val floorNameMethod = findMethod(java.lang.Enum::class.java, "name")
    private val roomDataMethod = findMethod(roomClass, "getData")
    private val roomDataTypeMethod = findMethod(roomDataClass, "getType")
    private val roomDataNameMethod = findMethod(roomDataClass, "getName")

    private fun getStaticField(clazz: Class<*>, name: String): Any? =
        runCatching { clazz.getDeclaredField(name).apply { isAccessible = true }.get(null) }.getOrNull()

    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? =
        runCatching { clazz.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true } }.getOrNull()

    private fun findField(clazz: Class<*>, name: String): Field? =
        runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun <T> Method?.call(instance: Any?, vararg args: Any?): T? {
        val m = this ?: return null
        return runCatching { m.invoke(instance, *args) as T }.getOrNull()
    }

    val inBoss: Boolean
        get() = getInBossMethod.call<Boolean>(dungeonUtilsInstance) ?: false

    val floor: com.odtheking.odin.utils.skyblock.dungeon.Floor?
        get() = getFloorMethod.call<com.odtheking.odin.utils.skyblock.dungeon.Floor>(dungeonUtilsInstance)

    val floorName: String?
        get() = floor?.let { floorNameMethod.call<String>(it) }

    val currentRoom: Any?
        get() = getCurrentRoomMethod.call<Any>(dungeonUtilsInstance)

    val currentRoomData: Any?
        get() = currentRoom?.let { roomDataMethod.call<Any>(it) }

    val currentRoomType: Any?
        get() = currentRoomData?.let { roomDataTypeMethod.call<Any>(it) }

    val currentDungeonPlayer: DungeonPlayer?
        get() = getCurrentDungeonPlayerMethod.call<DungeonPlayer>(dungeonUtilsInstance)

    val leapTeammates: List<DungeonPlayer>
        get() = getLeapTeammatesMethod.call<List<DungeonPlayer>>(dungeonUtilsInstance).orEmpty()

    private val hoverProgressField = findField(leapMenuClass, "hoverProgress")

    /** LeapMenu's "Render Scale" setting value (1f if unreadable). */
    fun leapMenuRenderScale(): Float = runCatching {
        (com.odtheking.odin.features.impl.dungeon.LeapMenu.settings["Render Scale"]?.value as? Number)?.toFloat()
    }.getOrNull() ?: 1f

    /** LeapMenu's hover-grow animation value (0..1) for a quadrant, 0f if unreadable. */
    fun leapMenuHoverValue(index: Int): Float = runCatching {
        (hoverProgressField?.get(leapMenuInstance) as? List<*>)
            ?.getOrNull(index) as? com.odtheking.odin.utils.ui.compose.Animatable
    }.getOrNull()?.value ?: 0f

    val bloodKeyPickedUp: Boolean
        get() = runCatching { bloodKeyField?.getBoolean(null) }.getOrNull() ?: false

    val bloodDoorOpened: Boolean
        get() = runCatching { bloodOpenedField?.getBoolean(null) }.getOrNull() ?: false

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

    fun isLeapAvailable(teammate: DungeonPlayer, screen: AbstractContainerScreen<*>): Boolean =
        menuHasName(screen, teammate.name)

    fun autoLeapTo(teammate: DungeonPlayer, screen: AbstractContainerScreen<*>): Boolean {
        val leap = leapToMethod
        if (leap == null) {
            if (!leapToMissingLogged) {
                leapToMissingLogged = true
                me.enivzoy.leaputils.LeapUtils.logger.warn("[LeapUtils] Odin's LeapMenu.leapTo is not where it was: auto-leap is off")
            }
            return false
        }
        return try {
            if (!isLeapAvailable(teammate, screen)) return false
            leap.call<Any>(leapMenuInstance, teammate.name, screen) ?: return false
            true
        } catch (e: Exception) {
            println("[LeapUtils] Failed to leap: ${e.message}")
            false
        }
    }

    private var leapToMissingLogged = false

    data class DoorPos(val x: Int, val z: Int)

    private fun findWitherDoorPos(): DoorPos? {
        val doors = getDoorsMethod.call<Map<*, *>>(dungeonScanInstance) ?: return null
        if (doors.isEmpty()) return null

        var best: DoorPos? = null
        var bestDist = Double.MAX_VALUE
        for (door in doors.values) {
            if (door == null) continue
            val type = doorGetTypeMethod.call<Enum<*>>(door) ?: continue
            val name = type.name
            if (name != "Wither" && name != "Blood") continue
            val x = doorGetWorldXMethod.call<Int>(door) ?: continue
            val z = doorGetWorldZMethod.call<Int>(door) ?: continue

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

    fun entityOf(teammate: DungeonPlayer): net.minecraft.world.entity.player.Player? = teammateEntity(teammate)

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
        screen: AbstractContainerScreen<*>,
        excludedNames: Set<String> = emptySet()
    ): DungeonPlayer? {
        var closest: DungeonPlayer? = null
        var closestDistance = Double.MAX_VALUE
        val excluded = excludedNames.mapTo(HashSet()) { it.lowercase() }

        for (teammate in leapTeammates) {
            if (teammate.isDead || teammate.name.lowercase() in excluded || !isLeapAvailable(teammate, screen)) continue
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
        return roomDataNameMethod.call<String>(roomData)
    }
}
