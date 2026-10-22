package me.enivzoy.cheaterpov

import com.odtheking.odin.config.ModuleConfig
import com.odtheking.odin.features.ModuleManager
import me.enivzoy.cheaterpov.pov.PovPreviews
import net.fabricmc.api.ClientModInitializer

object CheaterPovAddon : ClientModInitializer {
    override fun onInitializeClient() {
        ModuleManager.registerModules(ModuleConfig("CheaterPov.json"), PovPreviews)
    }
}
