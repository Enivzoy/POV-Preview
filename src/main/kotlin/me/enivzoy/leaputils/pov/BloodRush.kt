package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object BloodRush : Module(
    name = "Blood Rush",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Auto-leaps to the teammate closest to the wither/blood door while the blood key is still unpicked",
) {

    val bloodRushEnabled by BooleanSetting("Enabled", true, desc = "Enable Blood Rush auto-leap")
    val delay by NumberSetting("Delay (ms)", 300, 0..2000, 50, desc = "Delay after the leap menu opens before leaping")

    private var menuOpenedAt = 0L
    private var inLeapMenu = false
    private var leapedThisMenu = false
    @Volatile
    private var bloodDoorOpenedChat = false

    override fun onDisable() {
        resetMenuState()
        super.onDisable()
    }

    private fun resetMenuState() {
        menuOpenedAt = 0
        inLeapMenu = false
        leapedThisMenu = false
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
        if (OdinAccess.inBoss) return false
        if (OdinAccess.bloodDoorOpened) return false
        if (bloodDoorOpenedChat) return false
        if (OdinAccess.bloodKeyPickedUp) return false
        try {
            val m = DungeonUtils::class.java.getDeclaredMethod("getBloodDone")
            m.isAccessible = true
            val inst = DungeonUtils::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            if ((m.invoke(inst) as? Boolean) == true) return false
        } catch (e: Exception) {
        }
        try {
            val roomType = OdinAccess.currentRoomType
            if (roomType != null && roomType.toString().contains("BLOOD", ignoreCase = true)) return false
        } catch (e: Exception) {
        }
        return true
    }

    private fun isLeapMenu(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string
        return title == "Spirit Leap" || title == "Teleport to Player"
    }

    private fun autoLeapTo(teammate: DungeonPlayer, screen: AbstractContainerScreen<*>): Boolean {
        if (!OdinAccess.autoLeapTo(teammate, screen)) return false
        println("[LeapUtils BloodRush] Auto-leaped to ${teammate.name} (${teammate.clazz}) for Blood Rush")
        return true
    }

    init {
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 100) {
            if (!enabled || !bloodRushEnabled) {
                resetMenuState()
                return@on
            }

            val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: run {
                resetMenuState()
                return@on
            }
            if (!isLeapMenu(screen)) {
                resetMenuState()
                return@on
            }

            val now = System.currentTimeMillis()
            if (!inLeapMenu) {
                inLeapMenu = true
                menuOpenedAt = now
                leapedThisMenu = false
            }
            if (leapedThisMenu) return@on

            val currentClass = getCurrentClass() ?: return@on
            if (!isValidClassForBloodRush(currentClass)) return@on
            if (OdinAccess.bloodDoorOpened || bloodDoorOpenedChat) {
                resetMenuState()
                return@on
            }
            try {
                val m = DungeonUtils::class.java.getDeclaredMethod("getBloodDone")
                m.isAccessible = true
                val inst = DungeonUtils::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                if ((m.invoke(inst) as? Boolean) == true) {
                    resetMenuState()
                    return@on
                }
            } catch (e: Exception) {
            }
            try {
                val roomType = OdinAccess.currentRoomType
                if (roomType != null && roomType.toString().contains("BLOOD", ignoreCase = true)) {
                    resetMenuState()
                    return@on
                }
            } catch (e: Exception) {
            }
            if (!isBloodRushActive()) return@on
            if (now - menuOpenedAt < delay) return@on

            val target = OdinAccess.findClosestToWitherDoor() ?: return@on
            if (autoLeapTo(target, screen)) {
                leapedThisMenu = true
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!enabled || !bloodRushEnabled) {
                resetMenuState()
                return@register
            }
            if (OdinAccess.bloodDoorOpened || bloodDoorOpenedChat) {
                resetMenuState()
                return@register
            }
            try {
                val m = DungeonUtils::class.java.getDeclaredMethod("getBloodDone")
                m.isAccessible = true
                val inst = DungeonUtils::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                if ((m.invoke(inst) as? Boolean) == true) {
                    resetMenuState()
                    return@register
                }
            } catch (e: Exception) {
            }
            try {
                val roomType = OdinAccess.currentRoomType
                if (roomType != null && roomType.toString().contains("BLOOD", ignoreCase = true)) {
                    resetMenuState()
                    return@register
                }
            } catch (e: Exception) {
            }
            if (OdinAccess.inBoss) {
                resetMenuState()
                return@register
            }
            val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
            if (screen == null || !isLeapMenu(screen)) {
                inLeapMenu = false
                menuOpenedAt = 0
                leapedThisMenu = false
            }
        }

        on<MessageEvent.Chat>(EventPriority.HIGH) {
            try {
                val msg = this.message
                if (msg.contains("BLOOD DOOR", ignoreCase = true) && msg.contains("opened", ignoreCase = true)) {
                    bloodDoorOpenedChat = true
                    resetMenuState()
                }
            } catch (e: Exception) {
            }
        }

        on<LevelEvent.Load> {
            bloodDoorOpenedChat = false
            resetMenuState()
        }
    }
}
