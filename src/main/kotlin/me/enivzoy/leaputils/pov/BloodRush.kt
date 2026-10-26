package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object BloodRush : Module(
    name = "Blood Rush",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Auto-leaps to the teammate closest to the wither/blood door while the blood key is still unpicked",
) {

    val bloodRushEnabled by BooleanSetting("Enabled", true, desc = "Enable Blood Rush auto-leap")
    val delay by NumberSetting("Delay (ms)", 300, 0..2000, 50, desc = "Normal auto-leap delay; ignored in Schizo Mode")
    val schizoMode by BooleanSetting("Schizo Mode", false, desc = "Use any leap-menu keypress to trigger the current target immediately")

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

    private fun bloodDone(): Boolean =
        OdinAccess.bloodDoorOpened ||
            bloodDoorOpenedChat ||
            runCatching { DungeonUtils.bloodDone }.getOrDefault(false) ||
            OdinAccess.currentRoomType?.toString()?.contains("BLOOD", ignoreCase = true) == true

    private fun isBloodRushActive(): Boolean = !OdinAccess.inBoss && !bloodDone()

    private fun getCurrentClass(): DungeonClass? {
        val currentPlayer = OdinAccess.currentDungeonPlayer
        return if (currentPlayer != null && currentPlayer.clazz != DungeonClass.EMPTY) {
            currentPlayer.clazz
        } else null
    }

    private fun isValidClassForBloodRush(clazz: DungeonClass): Boolean {
        return clazz == DungeonClass.ARCHER || clazz == DungeonClass.MAGE || clazz == DungeonClass.HEALER
    }

    private fun isLeapMenu(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string
        return title == "Spirit Leap" || title == "Teleport to Player"
    }

    private fun shouldWaitForSchizoKeypress(screen: Screen): Boolean {
        val container = screen as? AbstractContainerScreen<*> ?: return false
        if (!enabled || !bloodRushEnabled || !schizoMode || leapedThisMenu || !isLeapMenu(container)) return false
        val currentClass = getCurrentClass() ?: return false
        return isValidClassForBloodRush(currentClass) && isBloodRushActive()
    }

    init {
        SchizoLeapInput.registerHandler(::shouldWaitForSchizoKeypress)

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
            if (!isBloodRushActive()) {
                resetMenuState()
                return@on
            }
            if (!schizoMode && now - menuOpenedAt < delay) return@on
            if (schizoMode && !SchizoLeapInput.consumeKeyPress(screen)) return@on

            val target = OdinAccess.findClosestToWitherDoor() ?: return@on
            if (OdinAccess.autoLeapTo(target, screen)) {
                leapedThisMenu = true
                SpLog.info("bloodrush", "OK leaped to ${target.name}(${target.clazz}) for blood rush")
            } else {
                SpLog.warn("bloodrush", "FAILED to leap to ${target.name}(${target.clazz})")
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!enabled || !bloodRushEnabled) {
                resetMenuState()
                return@register
            }
            if (!isBloodRushActive()) {
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
                    SpLog.info("bloodrush", "blood door opened (chat)")
                    resetMenuState()
                }
            } catch (e: Exception) {
            }
        }

        on<FloorEnterEvent> {
            bloodDoorOpenedChat = false
            resetMenuState()
        }

        on<LevelEvent.Load> {
            bloodDoorOpenedChat = false
            resetMenuState()
        }
    }
}
