package me.enivzoy.leaputils.pov

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.util.WeakHashMap

object SchizoLeapInput {
    private val lock = Any()
    private val handlers = mutableListOf<(Screen) -> Boolean>()
    private val registeredScreens = WeakHashMap<Screen, Boolean>()
    private val pendingPresses = WeakHashMap<Screen, Boolean>()

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*> && isLeapMenu(screen)) {
                val shouldRegister = synchronized(lock) {
                    pendingPresses.remove(screen)
                    registeredScreens.put(screen, true) == null
                }
                if (shouldRegister) {
                    ScreenKeyboardEvents.allowKeyPress(screen).register { activeScreen, _ ->
                        val handled = synchronized(lock) {
                            handlers.any { handler -> runCatching { handler(activeScreen) }.getOrDefault(false) }
                        }
                        if (handled) synchronized(lock) { pendingPresses[activeScreen] = true }
                        !handled
                    }
                }
            }
        }
    }

    fun registerHandler(handler: (Screen) -> Boolean) {
        synchronized(lock) { handlers += handler }
    }

    fun consumeKeyPress(screen: Screen): Boolean = synchronized(lock) {
        pendingPresses.remove(screen) == true
    }

    private fun isLeapMenu(screen: AbstractContainerScreen<*>): Boolean {
        return when (screen.title.string) {
            "Spirit Leap", "Teleport to Player", "Obsidian" -> true
            else -> false
        }
    }
}
