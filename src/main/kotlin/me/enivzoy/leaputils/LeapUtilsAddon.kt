package me.enivzoy.leaputils

import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.ModuleManager
import me.enivzoy.leaputils.pov.BloodRush
import me.enivzoy.leaputils.pov.EarlyEnter
import me.enivzoy.leaputils.pov.LeapUtilsCommands
import me.enivzoy.leaputils.pov.PosePov
import me.enivzoy.leaputils.pov.Savepoint
import me.enivzoy.leaputils.pov.SavepointRemote
import net.fabricmc.api.ClientModInitializer

object LeapUtilsAddon : ClientModInitializer {

    private val MODULES = listOf<Module>(PosePov, BloodRush, Savepoint, EarlyEnter)

    override fun onInitializeClient() {
        ModuleManager.registerModules(ModuleConfig("LeapUtils.json"), *MODULES.toTypedArray())
        subscribeEnabled(MODULES)
        LeapUtilsCommands.register()
        SavepointRemote.register()
    }

    private fun subscribeEnabled(modules: Iterable<Module>) {
        val fixed = modules.filter { it.enabled && !it.alwaysActive && it !in EventBus.activeSubscribers }
        for (m in fixed) EventBus.subscribe(m)
        if (fixed.isNotEmpty()) {
            LeapUtils.logger.info("[leaputils] on but left off Odin's event bus, subscribed: ${fixed.joinToString { it.name }}")
        }
    }
}
