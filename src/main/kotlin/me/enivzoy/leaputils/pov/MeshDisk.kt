package me.enivzoy.leaputils.pov

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.Blocks
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object MeshDisk {

    private const val FORMAT = 4

    class Entry(val offset: Long, val count: Int, val length: Int)

    val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils").resolve("pov-cache")
    private val index = ConcurrentHashMap<String, Entry>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pose pov mesh cache").apply { isDaemon = true } }
    @Volatile private var file: Path? = null
    @Volatile private var reader: RandomAccessFile? = null
    @Volatile var ready = false; private set
    private var atlasSeen: String? = null
    private var checkedAt = 0L

    private fun atlasPrint(): String {
        val models = Minecraft.getInstance().modelManager.blockStateModelSet
        val sb = StringBuilder()
        for (b in listOf(Blocks.STONE, Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.GLASS, Blocks.BEDROCK, Blocks.WATER)) {
            val s = models.getParticleMaterial(b.defaultBlockState()).sprite()
            sb.append('|').append(s.u0).append(',').append(s.v0).append(',').append(s.u1).append(',').append(s.v1)
        }
        return sb.toString()
    }

    fun check(): Boolean {

        val now = System.currentTimeMillis()
        if (atlasSeen != null && now - checkedAt < 1000) return false
        checkedAt = now
        val ap = atlasPrint()
        if (ap == atlasSeen) return false
        val first = atlasSeen == null
        atlasSeen = ap
        MeshCodec.atlasChanged()
        if (first) open()
        return !first
    }

    private fun open() {
        val name = Integer.toHexString("$FORMAT|${SharedConstants.getCurrentVersion().name()}".hashCode())
        val path = dir.resolve("$name.bin")
        file = path
        writer.execute {
            runCatching {
                Files.createDirectories(dir)
                if (Files.exists(path)) DataInputStream(Files.newInputStream(path).buffered()).use { inp ->
                    var at = 0L
                    while (true) {
                        val key = runCatching { inp.readUTF() }.getOrNull() ?: break
                        val count = inp.readInt(); val len = inp.readInt()
                        val head = 2 + key.toByteArray(Charsets.UTF_8).size + 8
                        val data = at + head
                        inp.skipNBytes(len.toLong())
                        index[key] = Entry(data, count, len)
                        at = data + len
                    }
                }
                reader = if (Files.exists(path)) RandomAccessFile(path.toFile(), "r") else null
            }

            runCatching { Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".bin") && it != path }.forEach { Files.deleteIfExists(it) } } }
            ready = true
        }
    }

    fun has(key: String) = index.containsKey(key)

    fun read(key: String): Pair<Int, ByteArray>? {
        repeat(2) {
            val e = index[key] ?: return null
            if (e.length == 0) return e.count to ByteArray(0)
            val r = reader ?: return null
            val bytes = ByteArray(e.length)
            if (runCatching { synchronized(r) { r.seek(e.offset); r.readFully(bytes) } }.isSuccess) return e.count to bytes
        }
        return null
    }

    fun compact(live: String) {
        writer.execute {
            val path = file ?: return@execute
            val r = reader ?: return@execute
            runCatching {
                fun head(k: String) = 2L + k.toByteArray(Charsets.UTF_8).size + 8
                val total = Files.size(path)
                val keep = index.entries.filter { !it.key.startsWith("b/") || it.key.startsWith(live) }.map { it.key to it.value }
                if (keep.sumOf { (k, e) -> head(k) + e.length } > total * 3 / 4) return@execute
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                val fresh = HashMap<String, Entry>()
                DataOutputStream(Files.newOutputStream(tmp).buffered()).use { out ->
                    var at = 0L
                    for ((k, e) in keep) {
                        val bytes = ByteArray(e.length)
                        synchronized(r) { r.seek(e.offset); r.readFully(bytes) }
                        out.writeUTF(k); out.writeInt(e.count); out.writeInt(e.length); out.write(bytes)
                        fresh[k] = Entry(at + head(k), e.count, e.length)
                        at += head(k) + e.length
                    }
                }
                synchronized(r) {
                    r.close()
                    Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    index.keys.retainAll(fresh.keys); index.putAll(fresh)
                    reader = RandomAccessFile(path.toFile(), "r")
                }
            }.onFailure {

                me.enivzoy.leaputils.LeapUtils.logger.warn("[pose pov] mesh cache compaction failed", it)
                reader = runCatching { RandomAccessFile(path.toFile(), "r") }.getOrNull()
            }
        }
    }

    fun write(key: String, count: Int, bytes: ByteArray) {
        val path = file ?: return
        writer.execute {
            if (file != path || index.containsKey(key)) return@execute
            runCatching {
                val start = if (Files.exists(path)) Files.size(path) else 0L
                DataOutputStream(Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND).buffered()).use { out ->
                    out.writeUTF(key); out.writeInt(count); out.writeInt(bytes.size); out.write(bytes)
                }
                val head = 2 + key.toByteArray(Charsets.UTF_8).size + 8
                if (reader == null) reader = RandomAccessFile(path.toFile(), "r")
                index[key] = Entry(start + head, count, bytes.size)
            }
        }
    }
}
