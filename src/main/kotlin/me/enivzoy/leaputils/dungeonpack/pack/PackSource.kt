package me.enivzoy.leaputils.dungeonpack.pack

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class PackOrigin { OVERRIDE, JAR }

interface BundledPacks {
    fun open(name: String): InputStream?

    companion object {

        val JAR: BundledPacks = object : BundledPacks {
            override fun open(name: String): InputStream? = BundledPacks::class.java.classLoader.getResourceAsStream("${PackFiles.RESOURCE_ROOT}/$name")
        }

        val NONE: BundledPacks = object : BundledPacks {
            override fun open(name: String): InputStream? = null
        }
    }
}

class PackSource private constructor(

    val overrideDir: Path?,
    private val resolved: Map<String, Pair<Path, PackOrigin>>,

    val cacheDir: Path?,

    private val bundledLooked: Boolean = false,

    val unpackFailedIn: Path? = null,
) {

    fun file(name: String): Path? =
        resolved[name]?.first ?: overrideDir?.resolve(name)?.takeIf { Files.isRegularFile(it) }

    fun origin(name: String): PackOrigin? = resolved[name]?.second ?: if (overrideDir?.resolve(name)?.let { Files.isRegularFile(it) } == true) PackOrigin.OVERRIDE else null

    val missingFrom: String get() = if (bundledLooked) "the jar and $overrideDir" else "$overrideDir"

    fun note(name: String): String = if (origin(name) == PackOrigin.JAR) " (the copy bundled in the jar)" else ""

    companion object {

        fun ofDir(dir: Path): PackSource = PackSource(dir, emptyMap(), null)

        fun resolve(
            overrideDir: Path, bundled: BundledPacks, cacheRoot: Path, report: PackReport,
            names: List<String> = PackFiles.BUNDLED,
        ): PackSource {
            val found = LinkedHashMap<String, Pair<Path, PackOrigin>>()
            for (n in names) {
                val p = overrideDir.resolve(n)
                if (Files.isRegularFile(p)) found[n] = p to PackOrigin.OVERRIDE
            }
            val need = names.filter { it !in found }
            var cache: Path? = null
            var failedIn: Path? = null
            if (need.isNotEmpty()) {
                try {
                    cache = unpack(bundled, names, need, cacheRoot, report)?.also { dir -> for (n in need) if (Files.isRegularFile(dir.resolve(n))) found[n] = dir.resolve(n) to PackOrigin.JAR }
                } catch (e: Exception) {

                    report.error("ADS could not unpack its bundled data packs into $cacheRoot: ${e.javaClass.simpleName}: ${e.message ?: "no details"}. Check that this folder is writable and the disk is not full.")
                    failedIn = cacheRoot
                }
            }
            return PackSource(overrideDir, found, cache, bundledLooked = bundled !== BundledPacks.NONE, unpackFailedIn = failedIn)
        }

        private fun unpack(bundled: BundledPacks, all: List<String>, need: List<String>, cacheRoot: Path, report: PackReport): Path? {

            val sizes = HashMap<String, Long>()
            val hashes = HashMap<String, String>()
            val outer = MessageDigest.getInstance("SHA-256")
            for (n in all) {
                val (size, hex) = (bundled.open(n) ?: continue).use { digest(it) }
                sizes[n] = size
                hashes[n] = hex
                outer.update("$n\n$size\n$hex\n".toByteArray())
            }
            if (need.none { it in sizes }) return null
            val id = outer.digest().joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
            val dir = cacheRoot.resolve(id)
            Files.createDirectories(dir)
            for (n in need) {
                val size = sizes[n] ?: continue
                val hex = hashes.getValue(n)
                val target = dir.resolve(n)
                if (isIntact(target, size, hex)) continue
                val tmp = Files.createTempFile(dir, n, ".part")
                try {
                    (bundled.open(n) ?: throw IOException("$n vanished from the jar")).use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
                    if (Files.size(tmp) != size) throw IOException("$n was unpacked with the wrong size")
                    place(tmp, target, size, hex)
                } finally { Files.deleteIfExists(tmp) }
            }
            removeOlder(cacheRoot, id, report)
            return dir
        }

        private fun place(tmp: Path, target: Path, size: Long, sha256: String) {
            try {
                try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(tmp, target) }
            } catch (e: IOException) {
                if (!isIntact(target, size, sha256)) throw e
            }
        }

        private fun isIntact(file: Path, size: Long, sha256: String): Boolean = try {
            Files.isRegularFile(file) && Files.size(file) == size && Files.newInputStream(file).use { digest(it) }.second == sha256
        } catch (_: IOException) { false }

        private fun removeOlder(cacheRoot: Path, keep: String, report: PackReport) {
            try {
                Files.list(cacheRoot).use { s ->
                    for (d in s.toList()) {
                        val name = d.fileName.toString()
                        if (name == keep || !Files.isDirectory(d) || name.length != HASH_CHARS || !name.all { it in "0123456789abcdef" }) continue
                        try { d.toFile().deleteRecursively() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) { report.info("Old unpacked data packs in $cacheRoot were not cleaned up: ${e.message}") }
        }

        private fun digest(input: InputStream): Pair<Long, String> {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); total += n }
            return total to md.digest().joinToString("") { "%02x".format(it) }
        }

        private const val HASH_CHARS = 16
    }
}
