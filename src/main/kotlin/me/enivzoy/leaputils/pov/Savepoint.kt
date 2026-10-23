package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import kotlin.math.abs

object Savepoint : Module(
    name = "Savepoint",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Auto-leap based on class and boss section during dungeons",
) {

    val savepointEnabled by BooleanSetting("Enabled", true, desc = "Enable Savepoint auto-leap")
    val delay by NumberSetting("Delay (ms)", 300, 100..600, 10, desc = "Delay before auto-leaping")
    val onlyWhenLeapMenuOpen by BooleanSetting("Only When Leap Menu Open", true, desc = "Only work when leap menu is open")

    // Maxor settings
    val maxorEnabled by BooleanSetting("Maxor", true, desc = "Enable Maxor auto-leap")
    val maxorRightWaypoint by SelectorSetting("Right Crystal Waypoint", DungeonClass.TANK, desc = "Who should leap to right crystal")
    val maxorRightClass by SelectorSetting("Right Leap If Class", DungeonClass.TANK, desc = "Class that leaps to right crystal")
    val maxorLeftWaypoint by SelectorSetting("Left Crystal Waypoint", DungeonClass.HEALER, desc = "Who should leap to left crystal")
    val maxorLeftClass by SelectorSetting("Left Leap If Class", DungeonClass.BERSERK, desc = "Class that leaps to left crystal")

    // Storm settings
    val stormEnabled by BooleanSetting("Storm", true, desc = "Enable Storm auto-leap")
    val stormWaypoint by SelectorSetting("Storm Waypoint", DungeonClass.MAGE, desc = "Who to leap to during Storm")
    val stormClass by SelectorSetting("Storm Leap If Class", DungeonClass.HEALER, desc = "Class that leaps during Storm")

    // Goldor settings
    val goldorEnabled by BooleanSetting("Goldor", true, desc = "Enable Goldor auto-leap")
    val goldorWaypoint by SelectorSetting("Goldor Waypoint", DungeonClass.MAGE, desc = "Who to leap to during Goldor")
    val goldorClass by SelectorSetting("Goldor Leap If Class", DungeonClass.HEALER, desc = "Class that leaps during Goldor")
    val goldorMageCoords by BooleanSetting("Mage Early Enter Coords", false, desc = "Use predetermined coords for mage early enter")
    val goldorMageX by NumberSetting("Mage X", 0, -1000..1000, 1, desc = "Mage early enter X coordinate")
    val goldorMageY by NumberSetting("Mage Y", 0, -100..256, 1, desc = "Mage early enter Y coordinate")
    val goldorMageZ by NumberSetting("Mage Z", 0, -1000..1000, 1, desc = "Mage early enter Z coordinate")
    val goldorLeniency by NumberSetting("Leniency (blocks)", 2, 0..10, 1, desc = "Coordinate leniency in blocks")

    // Necron settings
    val necronEnabled by BooleanSetting("Necron", true, desc = "Enable Necron auto-leap")
    val necronWaypoint by SelectorSetting("Necron Waypoint", DungeonClass.MAGE, desc = "Who to leap to during Necron")
    val necronClass by SelectorSetting("Necron Leap If Class", DungeonClass.HEALER, desc = "Class that leaps during Necron")

    private var lastLeapTime = 0L
    private var hasLeapedThisPhase = false

    override fun onDisable() {
        lastLeapTime = 0
        hasLeapedThisPhase = false
        super.onDisable()
    }

    private fun getCurrentClass(): DungeonClass? {
        val currentPlayer = OdinAccess.currentDungeonPlayer
        return if (currentPlayer != null && currentPlayer.clazz != DungeonClass.EMPTY) {
            currentPlayer.clazz
        } else null
    }

    private fun getCurrentBoss(): String? {
        val roomName = OdinAccess.currentRoom?.let { 
            val data = OdinAccess.currentRoomData
            data?.let { OdinAccess.getRoomName(it) } 
        }
        return roomName?.let { name ->
            when {
                name.contains("Maxor", ignoreCase = true) -> "Maxor"
                name.contains("Storm", ignoreCase = true) -> "Storm"
                name.contains("Goldor", ignoreCase = true) -> "Goldor"
                name.contains("Necron", ignoreCase = true) -> "Necron"
                else -> null
            }
        }
    }

    private fun isAtCoords(x: Int, y: Int, z: Int, leniency: Int): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        return abs(player.x - x) <= leniency &&
               abs(player.y - y) <= leniency &&
               abs(player.z - z) <= leniency
    }

    private fun findTeammateByClass(targetClass: DungeonClass): DungeonPlayer? {
        return OdinAccess.findTeammateByClass(targetClass)
    }

    private fun autoLeapTo(teammate: DungeonPlayer) {
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        OdinAccess.autoLeapTo(teammate, screen)
        println("[LeapUtils Savepoint] Auto-leaped to ${teammate.name} (${teammate.clazz})")
    }

    private fun handleMaxor(currentClass: DungeonClass) {
        if (!maxorEnabled) return
        
        // Right crystal: if current class matches maxorRightClass, leap to maxorRightWaypoint
        if (currentClass == maxorRightClass) {
            val target = findTeammateByClass(maxorRightWaypoint) 
                ?: return
            println("[LeapUtils Savepoint] Maxor: ${currentClass} leaping to Right Crystal (${target.name})")
            autoLeapTo(target)
            return
        }
        
        // Left crystal: if current class matches maxorLeftClass, leap to maxorLeftWaypoint
        if (currentClass == maxorLeftClass) {
            val target = findTeammateByClass(maxorLeftWaypoint) 
                ?: return
            println("[LeapUtils Savepoint] Maxor: ${currentClass} leaping to Left Crystal (${target.name})")
            autoLeapTo(target)
        }
    }

    private fun handleStorm(currentClass: DungeonClass) {
        if (!stormEnabled) return
        
        if (currentClass == stormClass) {
            val target = findTeammateByClass(stormWaypoint) 
                ?: return
            println("[LeapUtils Savepoint] Storm: ${currentClass} leaping to ${target.name}")
            autoLeapTo(target)
        }
    }

    private fun handleGoldor(currentClass: DungeonClass) {
        if (!goldorEnabled) return
        
        // Check for mage early enter coords
        if (goldorMageCoords && currentClass == DungeonClass.MAGE) {
            if (OdinAccess.isAtCoords(goldorMageX, goldorMageY, goldorMageZ, goldorLeniency)) {
                val target = findTeammateByClass(goldorWaypoint) 
                    ?: return
                println("[LeapUtils Savepoint] Goldor: Mage at early enter coords, leaping to ${target.name}")
                autoLeapTo(target)
                return
            }
        }
        
        if (currentClass == goldorClass) {
            val target = findTeammateByClass(goldorWaypoint) 
                ?: return
            println("[LeapUtils Savepoint] Goldor: ${currentClass} leaping to ${target.name}")
            autoLeapTo(target)
        }
    }

    private fun handleNecron(currentClass: DungeonClass) {
        if (!necronEnabled) return
        
        if (currentClass == necronClass) {
            val target = findTeammateByClass(necronWaypoint) 
                ?: return
            println("[LeapUtils Savepoint] Necron: ${currentClass} leaping to ${target.name}")
            autoLeapTo(target)
        }
    }

    init {
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            if (!enabled || !savepointEnabled) return@on
            if (onlyWhenLeapMenuOpen && !LeapMenu.enabled) return@on
            
            val mc = Minecraft.getInstance()
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@on
            if (!screen.title.string.equals("Spirit Leap") && !screen.title.string.equals("Teleport to Player")) return@on
            
            val currentClass = getCurrentClass() ?: return@on
            val boss = getCurrentBoss() ?: return@on
            
            val now = System.currentTimeMillis()
            if (now - lastLeapTime < delay) return@on
            if (hasLeapedThisPhase) return@on
            
            when (boss) {
                "Maxor" -> handleMaxor(currentClass)
                "Storm" -> handleStorm(currentClass)
                "Goldor" -> handleGoldor(currentClass)
                "Necron" -> handleNecron(currentClass)
            }
            
            lastLeapTime = now
            hasLeapedThisPhase = true
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!OdinAccess.inBoss) {
                hasLeapedThisPhase = false
            }
        }

        on<LevelEvent.Load> {
            lastLeapTime = 0
            hasLeapedThisPhase = false
        }
    }
}