package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import kotlin.math.abs
import kotlin.math.sqrt
import java.lang.reflect.Field
import java.lang.reflect.Method

object OdinAccess {
    private val dungeonUtilsClass = DungeonUtils::class.java
    private val dungeonListenerClass = DungeonListener::class.java
    private val leapMenuClass = com.odtheking.odin.features.impl.dungeon.LeapMenu::class.java
    private val roomDataClass = com.odtheking.odin.features.impl.dungeon.map.tile.RoomData::class.java
    private val dungeonStatsClass = com.odtheking.odin.utils.skyblock.dungeon.DungeonListener.DungeonStats::class.java
    private val floorClass = com.odtheking.odin.utils.skyblock.dungeon.Floor::class.java
    private val roomClass = com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom::class.java
    
    private val dungeonUtilsInstance: Any = getStaticField(dungeonUtilsClass, "INSTANCE")
    private val dungeonListenerInstance: Any = getStaticField(dungeonListenerClass, "INSTANCE")
    private val leapMenuInstance: Any = getStaticField(leapMenuClass, "INSTANCE")
    
    private val getInBossMethod = findMethod(dungeonUtilsClass, "getInBoss")
    private val getFloorMethod = findMethod(dungeonUtilsClass, "getFloor")
    private val getCurrentRoomMethod = findMethod(dungeonUtilsClass, "getCurrentRoom")
    private val getCurrentDungeonPlayerMethod = findMethod(dungeonUtilsClass, "getCurrentDungeonPlayer")
    private val getLeapTeammatesMethod = findMethod(dungeonUtilsClass, "getLeapTeammates")
    private val getDungeonStatsMethod = findMethod(dungeonListenerClass, "getDungeonStats")
    
    private val leapToMethod = findMethod(leapMenuClass, "leapTo", String::class.java, AbstractContainerScreen::class.java)
    
    private val floorNameMethod = findMethod(java.lang.Enum::class.java, "name")
    private val roomDataMethod = findMethod(roomClass, "data")
    private val roomDataTypeMethod = findMethod(roomDataClass, "type")
    private val dungeonStatsDoorOpenerMethod = findMethod(dungeonStatsClass, "getDoorOpener")
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
    
    val floor: Any?
        get() = getFloorMethod.invoke(dungeonUtilsInstance)
    
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
    
    val dungeonStats: Any?
        get() = getDungeonStatsMethod.invoke(dungeonListenerInstance)
    
    val dungeonStatsDoorOpener: String?
        get() = dungeonStats?.let { dungeonStatsDoorOpenerMethod.invoke(it) as String }
    
    fun autoLeapTo(teammate: DungeonPlayer, screen: AbstractContainerScreen<*>) {
        try {
            leapToMethod.invoke(leapMenuInstance, teammate.name, screen)
        } catch (e: Exception) {
            println("[LeapUtils] Failed to leap: ${e.message}")
        }
    }
    
    fun findClosestToWitherDoor(): DungeonPlayer? {
        val teammates = leapTeammates
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val myName = player.getName().getString()
        
        var closest: DungeonPlayer? = null
        var closestDist = Double.MAX_VALUE
        
        for (teammate in teammates) {
            if (teammate.entity == null || teammate.isDead) continue
            if (teammate.name == myName) continue
            
            val entity = teammate.entity!!
            val dx = entity.x - player.x
            val dy = entity.y - player.y
            val dz = entity.z - player.z
            val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            
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