package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
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

object BloodRush : Module(
    name = "Blood Rush",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Auto-leap to closest teammate near wither/blood door for Archer, Mage, Healer during Blood Rush",
) {

    val bloodRushEnabled by BooleanSetting("Enabled", true, desc = "Enable Blood Rush auto-leap")
    val delay by NumberSetting("Delay (ms)", 300, 100..600, 10, desc = "Delay before auto-leaping")
    val onlyWhenLeapMenuOpen by BooleanSetting("Only When Leap Menu Open", true, desc = "Only work when leap menu is open")

    private var lastLeapTime = 0L
    private var hasLeapedThisRush = false

    override fun onDisable() {
        lastLeapTime = 0
        hasLeapedThisRush = false
        super.onDisable()
    }

    private fun getCurrentClass(): DungeonClass? {
        val currentPlayer = OdinAccess.currentDungeonPlayer
        return if (currentPlayer != null && currentPlayer.clazz != DungeonClass.EMPTY) {
            currentPlayer.clazz
        } else null
    }

    private fun isValidClassForBloodRush(clazz: DungeonClass): Boolean {
        return clazz == DungeonClass.ARCHER || clazz == DungeonClass.MAGE || clazz == DungeonClass.HEALER
    }

    private fun isBloodRushActive(): Boolean {
        return OdinAccess.inBoss && 
               OdinAccess.floorName?.startsWith("M") == true && 
               OdinAccess.currentRoomType == com.odtheking.odin.features.impl.dungeon.map.tile.RoomType.BLOOD
    }

    private fun hasBloodKeyBeenPickedUp(): Boolean {
        val doorOpener = OdinAccess.dungeonStatsDoorOpener
        return doorOpener?.isNotBlank() == true
    }

    private fun autoLeapTo(teammate: DungeonPlayer) {
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        OdinAccess.autoLeapTo(teammate, screen)
        println("[LeapUtils BloodRush] Auto-leaped to ${teammate.name} (${teammate.clazz}) for Blood Rush")
    }

    init {
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            if (!enabled || !bloodRushEnabled) return@on
            if (onlyWhenLeapMenuOpen && !LeapMenu.enabled) return@on
            
            val mc = Minecraft.getInstance()
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@on
            if (!screen.title.string.equals("Spirit Leap") && !screen.title.string.equals("Teleport to Player")) return@on
            
            val currentClass = getCurrentClass() ?: return@on
            if (!isValidClassForBloodRush(currentClass)) return@on
            
            if (!isBloodRushActive()) return@on
            if (hasBloodKeyBeenPickedUp()) {
                hasLeapedThisRush = false
                return@on
            }
            
            val now = System.currentTimeMillis()
            if (now - lastLeapTime < delay) return@on
            if (hasLeapedThisRush) return@on
            
            val target = OdinAccess.findClosestToWitherDoor() ?: return@on
            autoLeapTo(target)
            lastLeapTime = now
            hasLeapedThisRush = true
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!OdinAccess.inBoss) {
                hasLeapedThisRush = false
            }
        }

        on<LevelEvent.Load> {
            lastLeapTime = 0
            hasLeapedThisRush = false
        }
    }
}