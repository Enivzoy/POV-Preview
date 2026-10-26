package me.enivzoy.leaputils.pov

import net.fabricmc.loader.api.FabricLoader

object EntityCullingBridge {

    private const val VERSIONLESS = "dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase"
    private const val MOD_BASE = "dev.tr7zw.entityculling.EntityCullingModBase"

    private val present: Boolean = FabricLoader.getInstance().isModLoaded("entityculling")

    private val enabledField by lazy {
        if (!present) null
        else runCatching { Class.forName(VERSIONLESS).getField("enabled") }.getOrNull()
    }

    private val modBaseClass by lazy {
        if (!present) null else runCatching { Class.forName(MOD_BASE) }.getOrNull()
    }

    fun disable(): Boolean? {
        val field = enabledField ?: return null
        return runCatching {
            val was = field.getBoolean(null)
            field.setBoolean(null, false)
            was
        }.getOrNull()
    }

    fun restore(previous: Boolean?) {
        val field = enabledField ?: return
        if (previous == null) return
        runCatching { field.setBoolean(null, previous) }
    }

    fun requestRecull() {
        val modClass = modBaseClass ?: return
        runCatching {
            val instance = modClass.getField("instance").get(null) ?: return
            val task = modClass.getField("cullTask").get(instance) ?: return
            task.javaClass.getField("requestCull").setBoolean(task, true)
        }
    }
}
