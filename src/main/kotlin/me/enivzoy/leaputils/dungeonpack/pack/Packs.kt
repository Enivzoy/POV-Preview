package me.enivzoy.leaputils.dungeonpack.pack

import me.enivzoy.leaputils.dungeonpack.pack.layouts.LayoutPack
import me.enivzoy.leaputils.dungeonpack.pack.mobs.MobPack
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomPack
import me.enivzoy.leaputils.dungeonpack.pack.runstate.RunStatePack
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class PackSet(

    val dir: Path,
    val rooms: RoomPack?,
    val layouts: LayoutPack?,
    val mobs: MobPack?,
    val runstate: RunStatePack?,
    val report: PackReport,

    val source: PackSource = PackSource.ofDir(dir),
) : java.io.Closeable {

    val canBuildDungeon: Boolean get() = rooms != null && layouts != null

    fun missing(): List<String> = buildList {
        if (rooms == null) add("room library (${PackFiles.ROOMS_INDEX} + ${PackFiles.ROOMS_BLOCKS})")
        if (layouts == null) add("layout pool (${PackFiles.LAYOUTS})")
        if (mobs == null) add("mob tables (${PackFiles.MOBS})")
        if (runstate == null) add("run-state tables (${PackFiles.RUNSTATE})")
    }

    fun userLines(): List<String> = Packs.userLines(this)

    fun originLines(): List<String> = PackFiles.BUNDLED.map { name ->
        name + ": " + when (source.origin(name)) {
            PackOrigin.JAR -> "the copy bundled in the jar"
            PackOrigin.OVERRIDE -> "your copy in $dir (overrides the jar)"
            null -> "not found"
        }
    }

    private var pins = 0
    private var retired = false

    @Synchronized fun pin() { pins++ }

    @Synchronized fun unpin() { pins--; if (pins <= 0 && retired) close() }

    @Synchronized fun retire() { retired = true; if (pins <= 0) close() }

    override fun close() { rooms?.close() }
}

object Packs {
    private val log = org.slf4j.LoggerFactory.getLogger("leaputils")
    private val loggedOverrides = HashSet<String>()

    @Volatile var current: PackSet? = null
        private set

    @Synchronized fun load(dir: Path = PackFiles.defaultDir(), bundled: BundledPacks = BundledPacks.JAR, cacheRoot: Path? = null): PackSet {
        val report = PackReport()
        val source = try {
            PackSource.resolve(dir, bundled, cacheRoot ?: PackFiles.cacheRootFor(dir), report)
        } catch (t: Throwable) {
            report.error("ADS could not look for its data packs: ${t.message ?: t.javaClass.simpleName}")
            PackSource.ofDir(dir)
        }
        for (name in PackFiles.BUNDLED) {
            if (source.origin(name) == PackOrigin.OVERRIDE && loggedOverrides.add("$dir/$name")) {
                log.info("[ads] using your copy of {} in {} instead of the one bundled in the jar", name, dir)
                report.info("Using your copy of $name from $dir instead of the bundled one.")
            }
        }
        val rooms = RoomPack.load(source, report)
        val layouts = LayoutPack.load(source, rooms, report)
        val mobs = MobPack.load(source, report)
        val runstate = RunStatePack.load(source, report)
        val set = PackSet(dir, rooms, layouts, mobs, runstate, report, source)
        val old = current
        current = set
        if (old != null && old !== set) old.retire()
        return set
    }

    fun loadAsync(dir: Path = PackFiles.defaultDir(), bundled: BundledPacks = BundledPacks.JAR, cacheRoot: Path? = null): CompletableFuture<PackSet> =
        CompletableFuture.supplyAsync { load(dir, bundled, cacheRoot) }

    fun ensureLoaded(): PackSet = current ?: load()

    fun unload() { val c = current; current = null; c?.retire() }

    fun missingMessage(dir: Path): String =
        "ADS could not load its data packs. They are built into the Engineer Client jar (${PackFiles.ROOMS_INDEX}, ${PackFiles.ROOMS_BLOCKS}, ${PackFiles.LAYOUTS}, ${PackFiles.MOBS}, ${PackFiles.RUNSTATE}), " +
            "so this copy of the mod is damaged or incomplete; installing it again should fix that. While developing you can put your own copies in $dir instead. The boss practice works without them."

    fun unpackFailedMessage(folder: Path, dir: Path): String =
        "ADS could not load its data packs. They are built into the Engineer Client jar, but copying them out into $folder failed. " +
            "While developing you can put your own copies in $dir instead. The boss practice works without them."

    fun userLines(set: PackSet): List<String> {
        val lines = ArrayList<String>()
        val missing = set.missing()
        val unpackFailedIn = set.source.unpackFailedIn
        if (missing.size == 4) return listOf(if (unpackFailedIn != null) unpackFailedMessage(unpackFailedIn, set.dir) else missingMessage(set.dir)) +
            set.report.errors().filter { !it.contains("missing from") }
        if (missing.isNotEmpty()) lines += "ADS could not load the " + missing.joinToString(", ") + ". " +
            (if (!set.canBuildDungeon) "A full run needs the room library and the layout pool." else "The run goes on with built-in defaults for what is missing.")
        for (e in set.report.errors()) if (lines.none { it.contains(e) }) lines += e
        for (w in set.report.warnings()) lines += w
        return lines
    }
}
