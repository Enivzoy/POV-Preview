package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
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
    val goldorMageCoordsText by StringSetting(
        "Mage Coords",
        "0, 0, 0",
        40,
        desc = "Mage early enter coordinates as x, y, z",
        placeholder = "x, y, z",
    )
    val goldorLeniency by NumberSetting("Leniency (blocks)", 2, 0..10, 1, desc = "Coordinate leniency in blocks")

    // Terminals / Core settings (F7/M7)
    val terminalsEnabled by BooleanSetting("Terminals/Core", true, desc = "Auto-leap to person standing at core")
    val coreSpotter by SelectorSetting("Core Spotter", DungeonClass.MAGE, desc = "Class that stands in front of core")
    val coreCoordsText by StringSetting("Core Coords", "53, 115, 50", 30, desc = "Core spot coords x, y, z", placeholder = "x, y, z")
    val coreLeniency by NumberSetting("Core Leniency", 3, 1..6, 1, desc = "Leniency for core spot")
    val allowArcherCore by BooleanSetting("Allow Archer As Spotter", true, desc = "Allow Archer to also stand at core spot")

    // Necron settings
    val necronEnabled by BooleanSetting("Necron", true, desc = "Enable Necron auto-leap")
    val necronWaypoint by SelectorSetting("Necron Waypoint", DungeonClass.MAGE, desc = "Who to leap to during Necron")
    val necronClass by SelectorSetting("Necron Leap If Class", DungeonClass.HEALER, desc = "Class that leaps during Necron")

    private var lastLeapTime = 0L
    private var hasLeapedThisPhase = false
    private var hasLeapedCore = false

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

    private fun isF7(): Boolean {
        val floorName = OdinAccess.floorName
        return floorName == "F7" || floorName == "M7"
    }

    private fun getCurrentBoss(): String? {
        if (!OdinAccess.inBoss) return null
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
        } ?: run {
            // Fallback for F7 boss phases
            if (isF7()) {
                // Could be terminals/devs - not a leap phase
                null
            } else null
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
        
        if (currentClass == maxorRightClass) {
            val target = findTeammateByClass(maxorRightWaypoint) 
                ?: return
            println("[LeapUtils Savepoint] Maxor: ${currentClass} leaping to Right Crystal (${target.name})")
            autoLeapTo(target)
            return
        }
        
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

    private fun parseCoords(input: String): Triple<Int, Int, Int>? {
        val parts = input.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        val z = parts[2].toIntOrNull() ?: return null
        return Triple(x, y, z)
    }

    private fun handleGoldor(currentClass: DungeonClass) {
        if (!goldorEnabled) return
        
        if (goldorMageCoords && currentClass == DungeonClass.MAGE) {
            val coords = parseCoords(goldorMageCoordsText)
            if (coords != null && OdinAccess.isAtCoords(coords.first, coords.second, coords.third, goldorLeniency)) {
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

    private fun inTerminalsSection(): Boolean {
        if (!isF7()) return false
        return runCatching {
            val inBoss = OdinAccess.inBoss
            val boss = getCurrentBoss()
            inBoss && boss == null
        }.getOrDefault(false)
    }

    private fun isAtCore(spotter: DungeonPlayer, coords: Triple<Int, Int, Int>): Boolean {
        val entity = spotter.entity
        if (entity != null) {
            return abs(entity.x - coords.first) <= coreLeniency && 
                   abs(entity.y - coords.second) <= coreLeniency && 
                   abs(entity.z - coords.third) <= coreLeniency
        }
        return false
    }

    private fun handleTerminalsCore(currentClass: DungeonClass) {
        if (!terminalsEnabled) return
        if (hasLeapedCore) return
        
        val myClass = getCurrentClass() ?: currentClass
        val spotterClass = coreSpotter
        if (myClass == spotterClass) return
        if (allowArcherCore && myClass == DungeonClass.ARCHER && spotterClass == DungeonClass.MAGE) {
            // archer might be spotter in some parties, but if I'm archer don't leap to myself
        }
        if (myClass == DungeonClass.ARCHER && allowArcherCore && spotterClass == DungeonClass.ARCHER) return
        
        val coords = parseCoords(coreCoordsText) ?: return
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        
        val spotter = findTeammateByClass(spotterClass) ?: run {
            if (allowArcherCore && spotterClass != DungeonClass.ARCHER) {
                findTeammateByClass(DungeonClass.ARCHER)
            } else null
        } ?: return
        
        if (spotter.isDead) return
        if (spotter.clazz == myClass) return
        if (spotter.clazz != spotterClass && !(allowArcherCore && spotter.clazz == DungeonClass.ARCHER)) return
        if (!isAtCore(spotter, coords)) return
        
        println("[LeapUtils Savepoint] Core: ${myClass} leaping to ${spotter.name} (${spotter.clazz}) at core")
        OdinAccess.autoLeapTo(spotter, screen)
        hasLeapedCore = true
    }

    init {
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            if (!enabled || !savepointEnabled) return@on
            if (onlyWhenLeapMenuOpen && !LeapMenu.enabled) return@on
            
            val mc = Minecraft.getInstance()
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@on
            if (!screen.title.string.equals("Spirit Leap") && !screen.title.string.equals("Teleport to Player")) return@on
            
            val currentClass = getCurrentClass() ?: return@on
            val boss = getCurrentBoss()
            
            val now = System.currentTimeMillis()
            if (now - lastLeapTime < delay) return@on
            if (hasLeapedThisPhase && boss != null) return@on
            
            if (boss != null) {
                when (boss) {
                    "Maxor" -> handleMaxor(currentClass)
                    "Storm" -> handleStorm(currentClass)
                    "Goldor" -> handleGoldor(currentClass)
                    "Necron" -> handleNecron(currentClass)
                }
                lastLeapTime = now
                hasLeapedThisPhase = true
            } else if (isF7() && OdinAccess.inBoss && !hasLeapedCore) {
                handleTerminalsCore(currentClass)
                if (hasLeapedCore) {
                    lastLeapTime = now
                }
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!enabled || !savepointEnabled) return@register
            if (!OdinAccess.inBoss) {
                hasLeapedThisPhase = false
                hasLeapedCore = false
            }
        }

        on<LevelEvent.Load> {
            lastLeapTime = 0
            hasLeapedThisPhase = false
            hasLeapedCore = false
        }
    }
}