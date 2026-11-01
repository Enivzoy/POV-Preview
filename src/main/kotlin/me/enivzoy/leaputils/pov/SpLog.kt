package me.enivzoy.leaputils.pov

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object SpLog {
    val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils")
    private val file: Path = dir.resolve("savepoint.log")
    private val lock = Any()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun session() {
        runCatching {
            Files.createDirectories(dir)
            if (Files.exists(file) && Files.size(file) > 2_000_000) {
                Files.move(file, dir.resolve("savepoint.log.old"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        write("---", "session", "=== log started ${LocalDateTime.now().format(dateFmt)} ===", "savepoint.log")
    }

    fun info(tag: String, msg: String, fileName: String = "savepoint.log") = write("INFO", tag, msg, fileName)

    fun warn(tag: String, msg: String, fileName: String = "savepoint.log") = write("WARN", tag, msg, fileName)

    fun err(tag: String, msg: String, fileName: String = "savepoint.log") = write("ERR!", tag, msg, fileName)

    private fun write(level: String, tag: String, msg: String, fileName: String) {
        synchronized(lock) {
            runCatching {
                Files.createDirectories(dir)
                Files.writeString(
                    dir.resolve(fileName),
                    "${LocalTime.now().format(timeFmt)} [$level/$tag] $msg\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            }
            println("[SpLog/$tag] $msg")
        }
    }
}
