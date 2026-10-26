package me.enivzoy.leaputils.pov

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.utils.modMessage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object Landmarks {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils")
    private val file: Path = dir.resolve("landmarks.json")
    private val type = object : TypeToken<LinkedHashMap<String, SpLandmark>>() {}.type

    val all = LinkedHashMap<String, SpLandmark>()

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (Files.exists(file)) {
                val read: LinkedHashMap<String, SpLandmark>? = gson.fromJson(Files.readString(file), type)
                read?.let { all.putAll(it) }
            }
        }
        if (!all.containsKey("necron_spot")) all["necron_spot"] = SpLandmark(54.0, 65.0, 76.0)
        save()
    }

    fun save() {
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(file, gson.toJson(all))
        }
    }

    fun get(name: String): SpLandmark? = all[name.trim()]

    fun put(name: String, lm: SpLandmark): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        all[n] = lm
        SpLog.info("landmark", "$n -> (${lm.x}, ${lm.y}, ${lm.z})")
        save()
        return true
    }

    fun captureHere(name: String): String? {
        val p = Minecraft.getInstance().player ?: return "you are not in a world yet"
        val n = name.trim()
        if (n.isEmpty()) return "landmark name is empty"
        val bp = p.blockPosition()
        put(n, SpLandmark(bp.x.toDouble(), bp.y.toDouble(), bp.z.toDouble()))
        return null
    }
}

object LeapUtilsCommands {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                        ClientCommands.literal("leaputils")
                            .then(
                                ClientCommands.literal("landmark")
                                    .executes {
                                        modMessage("[LeapUtils] landmarks: ${Landmarks.all.keys.joinToString(", ")}")
                                        Command.SINGLE_SUCCESS
                                    }
                                    .then(
                                        ClientCommands.argument("name", StringArgumentType.word())
                                    .executes { ctx ->
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val err = Landmarks.captureHere(name)
                                        if (err == null) modMessage("[LeapUtils] landmark '$name' captured at your position")
                                        else modMessage("[LeapUtils] $err")
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
                    .then(
                        ClientCommands.literal("logs")
                            .executes {
                                val folder: File = SpLog.dir.toFile()
                                runCatching {
                                    if (!folder.exists()) folder.mkdirs()
                                    Desktop.getDesktop().open(folder)
                                    modMessage("[LeapUtils] opened ${folder.absolutePath}")
                                }.onFailure {
                                    modMessage("[LeapUtils] couldn't open folder: ${it.message}")
                                }
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
        }
    }
}
