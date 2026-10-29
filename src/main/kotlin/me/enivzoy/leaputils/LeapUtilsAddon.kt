package me.enivzoy.leaputils

import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.features.ModuleManager
import me.enivzoy.leaputils.pov.PovPreviews
import me.enivzoy.leaputils.pov.BloodRush
import me.enivzoy.leaputils.pov.Savepoint
import me.enivzoy.leaputils.pov.SavepointRemote
import me.enivzoy.leaputils.pov.EarlyEnter
import me.enivzoy.leaputils.pov.LeapUtilsCommands
import net.fabricmc.api.ClientModInitializer

object LeapUtilsAddon : ClientModInitializer {
    override fun onInitializeClient() {
        ModuleManager.registerModules(ModuleConfig("LeapUtils.json"), PovPreviews, BloodRush, Savepoint, EarlyEnter)
        LeapUtilsCommands.register()
        SavepointRemote.register() // title-screen preset updater - must be subscribed before the first title screen
    }
}
