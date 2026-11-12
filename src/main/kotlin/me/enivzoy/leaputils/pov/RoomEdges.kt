package me.enivzoy.leaputils.pov

import me.enivzoy.leaputils.dungeonpack.pack.DungeonFrame
import me.enivzoy.leaputils.dungeonpack.pack.Rot
import me.enivzoy.leaputils.dungeonpack.pack.Tile
import me.enivzoy.leaputils.dungeonpack.pack.rooms.OdinCore
import me.enivzoy.leaputils.dungeonpack.pack.rooms.Placement
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomDesign
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomPack
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomVolume
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

object RoomEdges {

    private const val COMMON = 48
    private const val VOTES = 12
    private const val STEP_MS = 200L
    private const val FORMAT = 1

    class Found(val design: RoomDesign, val rot: Rot, val tiles: List<Tile>)

    @Volatile private var hashes: IntArray? = null
    @Volatile private var places: IntArray? = null
    private var designs: List<RoomDesign> = emptyList()
    private var indexing = false
    private val dirty = BooleanArray(36) { true }
    private var stepAt = 0L
    private var next = 0

    fun reset() { dirty.fill(true) }

    fun chunk(cx: Int, cz: Int) {
        for (x in intArrayOf(cx shl 4, (cx shl 4) + 15)) for (z in intArrayOf(cz shl 4, (cz shl 4) + 15)) {
            val tx = Math.floorDiv(x - DungeonFrame.ORIGIN, DungeonFrame.PITCH); val tz = Math.floorDiv(z - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
            if (tx in 0..5 && tz in 0..5) dirty[tz * 6 + tx] = true
        }
    }

    fun step(level: ClientLevel, pack: RoomPack, taken: (Int) -> Boolean): Found? {
        val now = System.currentTimeMillis()
        if (now - stepAt < STEP_MS) return null
        stepAt = now
        if (hashes == null) { index(pack); return null }
        for (k in 0 until 36) {
            val t = (next + k) % 36
            if (!dirty[t] || taken(t)) continue
            next = t + 1
            dirty[t] = false
            return look(level, pack, t % 6, t / 6, taken)
        }
        return null
    }

    private fun look(level: ClientLevel, pack: RoomPack, tx: Int, tz: Int, taken: (Int) -> Boolean): Found? {
        val hs = hashes ?: return null; val ps = places ?: return null
        val x0 = DungeonFrame.ORIGIN + tx * DungeonFrame.PITCH; val z0 = DungeonFrame.ORIGIN + tz * DungeonFrame.PITCH
        val pos = BlockPos.MutableBlockPos()

        val cols = ArrayList<IntArray>()
        for (x in x0..x0 + 30 step 2) for (z in z0..z0 + 30 step 2) {
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            val h = OdinCore.hash { y -> nameOf(level.getBlockState(pos.set(x, y, z)).block) }
            cols += intArrayOf(x, z, h)
        }
        if (cols.size < VOTES) { if (cols.isNotEmpty()) dirty[tz * 6 + tx] = true; return null }
        return identify(cols, pack, hs, ps, designs, taken)
    }

    internal fun identify(cols: List<IntArray>, pack: RoomPack, hs: IntArray, ps: IntArray, designs: List<RoomDesign>, taken: (Int) -> Boolean): Found? {

        val votes = HashMap<Long, Int>()
        for ((wx, wz, h) in cols) {
            var i = lowerBound(hs, h)
            while (i < hs.size && hs[i] == h) {
                val p = ps[i++]
                val di = p ushr 16; val cx = (p shr 8) and 255; val cz = p and 255
                val d = designs[di]
                for (rot in turns(d)) {
                    val (px, pz) = box(d, rot, cx, cz)
                    val ox = wx - px - DungeonFrame.ORIGIN; val oz = wz - pz - DungeonFrame.ORIGIN
                    if (Math.floorMod(ox, DungeonFrame.PITCH) != 0 || Math.floorMod(oz, DungeonFrame.PITCH) != 0) continue
                    val key = (((di.toLong() * 4 + rot.ordinal) * 16 + ox / DungeonFrame.PITCH + 8) * 16) + oz / DungeonFrame.PITCH + 8
                    votes.merge(key, 1, Int::plus)
                }
            }
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        val bestDesign = best.key / (4 * 256)
        val second = votes.filter { it.key / (4 * 256) != bestDesign }.maxOfOrNull { it.value } ?: 0
        if (best.value < VOTES || best.value < 2 * second) return null
        val d = designs[bestDesign.toInt()]
        val rot = Rot.entries[((best.key / 256) % 4).toInt()]
        val otx = ((best.key / 16) % 16).toInt() - 8; val otz = (best.key % 16).toInt() - 8
        val tiles = runCatching { Placement.tilesFor(d.shape, rot, otx, otz) }.getOrNull() ?: return null
        if (tiles.any { it.x !in 0..5 || it.z !in 0..5 || taken(it.z * 6 + it.x) }) return null

        val placed = runCatching { d.place(rot, tiles) }.getOrNull() ?: return null
        val vol = pack.volume(d) ?: return null
        var agree = 0; var disagree = 0
        for ((wx, wz, h) in cols) {
            val (cx, cz) = placed.toCanonical(wx, wz) ?: continue
            if (hashOf(vol, cx, cz) == h) agree++ else disagree++
        }
        if (agree < VOTES || disagree * 4 > agree) return null
        return Found(d, placed.rot, tiles)
    }

    private fun turns(d: RoomDesign): List<Rot> = if (d.type.equals("fairy", true)) listOf(Rot.SOUTH) else d.rotations.ifEmpty { Placement.legalRotations(d.shape) }

    private fun box(d: RoomDesign, rot: Rot, x: Int, z: Int): Pair<Int, Int> = when (rot) {
        Rot.SOUTH -> x to z
        Rot.NORTH -> (d.w - 1 - x) to (d.d - 1 - z)
        Rot.WEST -> (d.d - 1 - z) to x
        Rot.EAST -> z to (d.w - 1 - x)
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    private fun index(pack: RoomPack) {
        if (indexing) return
        indexing = true
        val ds = pack.designs
        val file = FabricLoader.getInstance().configDir.resolve("leaputils").resolve("pov-cache").resolve("edges-$FORMAT-${pack.version}-${ds.size}.idx")
        CompletableFuture.runAsync {
            runCatching {
                if (Files.exists(file)) DataInputStream(Files.newInputStream(file).buffered()).use { inp ->
                    val n = inp.readInt()
                    val h = IntArray(n); val p = IntArray(n)
                    for (i in 0 until n) { h[i] = inp.readInt(); p[i] = inp.readInt() }
                    designs = ds; places = p; hashes = h
                    return@runAsync
                }
                val (h, p) = build(pack, ds)
                Files.createDirectories(file.parent)
                DataOutputStream(Files.newOutputStream(file).buffered()).use { out ->
                    out.writeInt(h.size)
                    for (k in h.indices) { out.writeInt(h[k]); out.writeInt(p[k]) }
                }
                designs = ds; places = p; hashes = h
            }
        }
    }

    internal fun build(pack: RoomPack, ds: List<RoomDesign>): Pair<IntArray, IntArray> {
        val all = ArrayList<Long>()
        for ((di, d) in ds.withIndex()) {
            val v = pack.volumeOnce(d) ?: continue
            for (x in 0 until minOf(v.w, 256)) for (z in 0 until minOf(v.d, 256)) {
                all += (hashOf(v, x, z).toLong() shl 32) or ((di shl 16) or (x shl 8) or z).toLong()
            }
        }
        all.sort()
        val keep = ArrayList<Long>(all.size)
        var i = 0
        while (i < all.size) {
            var j = i
            while (j < all.size && (all[j] shr 32) == (all[i] shr 32)) j++
            if (j - i <= COMMON) for (k in i until j) keep += all[k]
            i = j
        }
        return IntArray(keep.size) { (keep[it] shr 32).toInt() } to IntArray(keep.size) { keep[it].toInt() }
    }

    private val names = IdentityHashMap<Block, String>()
    private fun nameOf(b: Block): String = names.getOrPut(b) { BuiltInRegistries.BLOCK.getKey(b).toString() }

    internal fun hashOf(v: RoomVolume, x: Int, z: Int): Int = OdinCore.ofVolume(v, x, z)
}
