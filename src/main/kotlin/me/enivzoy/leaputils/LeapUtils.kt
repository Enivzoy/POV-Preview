package me.enivzoy.leaputils

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object LeapUtils {
    val logger: Logger = LoggerFactory.getLogger("leaputils")
    val mc: Minecraft get() = Minecraft.getInstance()

    inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("[leaputils] $what failed", t)
        }
    }

    fun chat(msg: String) {
        mc.schedule { mc.gui.hud.chat.addClientSystemMessage(Component.literal(msg)) }
    }
}
