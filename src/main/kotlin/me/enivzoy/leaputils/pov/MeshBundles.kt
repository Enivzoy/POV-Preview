package me.enivzoy.leaputils.pov

import me.enivzoy.leaputils.dungeonpack.pack.Rot
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object MeshBundles {
    private const val MAGIC = 0x50505632
    private const val MAX_BYTES = 48 shl 20

    class Bundle(val name: String, val names: Array<String>, val places: IntArray, val offs: IntArray, val lens: IntArray, val data: ByteArray, private val visAt: Int, private val visLen: Int) {

        fun sees(from: Int, to: Int): Boolean =
            visLen == 0 || from !in places.indices || to < 0 || to >= visLen * 8 || ((data[visAt + from * visLen + (to shr 3)].toInt() shr (to and 7)) and 1) != 0
        val hasSight get() = visLen > 0
        private val byPlace = HashMap<Int, Int>(places.size * 2).also { m -> places.forEachIndexed { i, p -> m[p] = i } }
        fun find(lx: Int, ly: Int, lz: Int): Int = byPlace[place(lx, ly, lz)] ?: -1

        val id = ids.incrementAndGet()
        @Volatile private var spriteIdx: IntArray? = null
        @Volatile private var spriteGen = -1

        fun spriteIndices(): IntArray? {
            val gen = CompactMesh.SpriteTable.generation
            spriteIdx?.takeIf { spriteGen == gen }?.let { return it }
            if (!CompactMesh.SpriteTable.ready) return null
            val sp = MeshCodec.sprites() ?: return null
            return IntArray(names.size) { CompactMesh.SpriteTable.of(sp.named(names[it])) }.also { spriteIdx = it; spriteGen = gen }
        }
    }

    private val ids = java.util.concurrent.atomic.AtomicInteger()

    fun place(lx: Int, ly: Int, lz: Int) = ((lx and 0xFF) shl 16) or ((ly and 0xFF) shl 8) or (lz and 0xFF)
    fun placeX(p: Int) = (p shr 16) and 0xFF
    fun placeY(p: Int) = (p shr 8).toByte().toInt()
    fun placeZ(p: Int) = p and 0xFF

    fun serialize(names: Map<String, Int>, places: IntArray, pieces: Array<ByteArray>, sight: Array<ByteArray>?): ByteArray {
        val out = ByteArrayOutputStream(pieces.sumOf { it.size } + 4096)
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC)
            val ordered = names.entries.sortedBy { it.value }
            d.writeShort(ordered.size); for (e in ordered) d.writeUTF(e.key)
            d.writeInt(places.size)
            for (i in places.indices) { d.writeInt(places[i]); d.writeInt(pieces[i].size) }
            val visLen = sight?.firstOrNull()?.size ?: 0
            d.writeInt(visLen)
            if (visLen > 0) for (s in sight!!) { require(s.size == visLen); d.write(s) }
            for (p in pieces) d.write(p)
        }
        return out.toByteArray()
    }

    fun parse(name: String, bytes: ByteArray): Bundle {
        val d = DataInputStream(ByteArrayInputStream(bytes))
        require(d.readInt() == MAGIC) { "not a bundle" }
        val names = Array(d.readUnsignedShort()) { d.readUTF() }
        val n = d.readInt()
        require(n in 0..100_000)
        val places = IntArray(n); val lens = IntArray(n)
        for (i in 0 until n) { places[i] = d.readInt(); lens[i] = d.readInt(); require(lens[i] >= 0) }
        val visLen = d.readInt()
        require(visLen in 0..4096)
        val visAt = bytes.size - d.available()
        val start = visAt + visLen * n
        val offs = IntArray(n)
        var at = start
        for (i in 0 until n) { offs[i] = at; at += lens[i] }
        require(at == bytes.size) { "bundle length" }
        return Bundle(name, names, places, offs, lens, bytes, visAt, visLen)
    }

    private const val FETCHING = 1
    private const val BUILD = 2
    private val loaded = ConcurrentHashMap<String, Bundle>()
    private val state = ConcurrentHashMap<String, Int>()
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "pose pov bundles").apply { isDaemon = true } }
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build() }

    @Volatile var offline = false

    @Volatile var version = ""

    private fun key(name: String) = "b/$version/$name"

    fun get(name: String): Bundle? {
        loaded[name]?.let { return it }
        if (version.isEmpty() || !MeshDisk.ready || state.putIfAbsent(name, FETCHING) != null) return null
        io.execute {
            val disk = MeshDisk.read(key(name))?.second?.let { b -> runCatching { parse(name, b) }.getOrNull() }
            if (disk != null) { loaded[name] = disk; state.remove(name); return@execute }
            if (offline) { state[name] = BUILD; return@execute }
            val got = runCatching { download(name) }.getOrNull()
            val b = got?.let { runCatching { parse(name, it) }.getOrNull() }
            if (b != null) { MeshDisk.write(key(name), 0, got); loaded[name] = b; state.remove(name) }
            else state[name] = BUILD
        }
        return null
    }

    private const val PREFETCH_BYTES_PER_S = 4_000_000L
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "pov previews prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var prefetched = ""

    fun prefetch(names: List<String>) {
        if (version.isEmpty() || offline || prefetched == version) return
        prefetched = version
        val v = version
        prefetcher.execute {
            for (name in names) {
                if (version != v || offline) return@execute
                if (loaded.containsKey(name) || MeshDisk.has(key(name)) || state.putIfAbsent(name, FETCHING) != null) continue
                val t0 = System.nanoTime()
                val got = runCatching { download(name) }.getOrNull()
                if (got != null && runCatching { parse(name, got) }.isSuccess) MeshDisk.write(key(name), 0, got)
                state.remove(name)

                val wait = (got?.size ?: 0) * 1000L / PREFETCH_BYTES_PER_S - (System.nanoTime() - t0) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
        }
    }

    private fun download(name: String): ByteArray? {
        val url = PovServer.meshBase + version + "/" + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
        val res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) { res.body().close(); return null }
        return res.body().use { s -> s.readNBytes(MAX_BYTES + 1) }.takeIf { it.size <= MAX_BYTES }
    }

    fun needsBuild(name: String) = state[name] == BUILD

    fun built(name: String, bytes: ByteArray): Bundle {
        val b = parse(name, bytes)
        MeshDisk.write(key(name), 0, bytes)
        loaded[name] = b; state.remove(name)
        return b
    }

    val count get() = loaded.size

    val bytes get() = loaded.values.sumOf { it.data.size.toLong() }

    fun cached(name: String): ByteArray? = MeshDisk.read(key(name))?.second
}

object PiecePool {
    private const val POOL_VERTICES = 1 shl 21

    private const val MAX_POOLS = 8
    private const val IDLE_MS = 60_000L

    private const val CROWDED_IDLE_MS = 2_000L
    private const val IN_FLIGHT = 64

    class Resident internal constructor(val buffer: GpuBuffer, val first: Int, val counts: IntArray, internal val pool: Pool, internal val vertices: Int) {
        internal var usedAt = 0L
    }
    internal class Pool(val buffer: GpuBuffer, val vertices: Int) {

        val free = java.util.TreeMap<Int, Int>().apply { put(0, vertices) }
        var used = 0

        fun alloc(n: Int): Int {
            for ((at, len) in free) if (len >= n) {
                free.remove(at)
                if (len > n) free[at + n] = len - n
                used += n
                return at
            }
            return -1
        }

        fun allocAt(at: Int, n: Int): Int {
            val (s, len) = free.floorEntry(at) ?: return -1
            if (s + len < at + n) return -1
            free.remove(s)
            if (at > s) free[s] = at - s
            if (s + len > at + n) free[at + n] = s + len - at - n
            used += n
            return at
        }

        fun release(at: Int, n: Int) {
            used -= n
            var start = at; var len = n
            free.floorEntry(at)?.let { (s, l) -> if (s + l == at) { free.remove(s); start = s; len += l } }
            free[start + len]?.let { l -> free.remove(start + len); len += l }
            free[start] = len
        }
    }
    private class Want(val bundle: MeshBundles.Bundle, val piece: Int, val rot: Rot, val w: Int, val d: Int, val room: KnownWorld.Room?, val pockets: Boolean, var priority: Double)
    private class Done(val key: Long, val epoch: Int, val packed: CompactMesh.Packed?, val room: Long)

    private val pools = ArrayList<Pool>()
    private val resident = HashMap<Long, Resident>()

    private val empty = HashSet<Long>()
    private val wanted = HashMap<Long, Want>()
    private val inFlight = HashSet<Long>()
    private val done = java.util.concurrent.ConcurrentLinkedQueue<Done>()
    private var epoch = 0
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "pose pov decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val quads = ThreadLocal.withInitial { CompactMesh.Quads(1024) }
    private var now = 0L
    private var idleCheckAt = 0L

    var version = 0L; private set

    var mostIndices = 0; private set
    var evictions = 0L; private set

    var asked = 0L; private set

    fun key(bundle: MeshBundles.Bundle, piece: Int, rot: Rot, ox: Int, oz: Int, pockets: Boolean): Long =
        (bundle.id.toLong() shl 44) or (piece.toLong() shl 28) or (rot.ordinal.toLong() shl 26) or ((if (pockets) 1L else 0L) shl 25) or
            (((ox + 512).toLong() and 1023L) shl 10) or ((oz + 512).toLong() and 1023L)

    fun get(bundle: MeshBundles.Bundle, piece: Int, rot: Rot, w: Int, d: Int, room: KnownWorld.Room?, priority: Double): Resident? {
        val pockets = room?.cutPockets ?: false
        val k = key(bundle, piece, rot, room?.x0 ?: 0, room?.z0 ?: 0, pockets)
        resident[k]?.let { it.usedAt = now; return it }
        if (k in empty || bundle.lens[piece] == 0) return null
        asked++
        if (k in inFlight) return null
        val prev = wanted[k]
        if (prev == null) wanted[k] = Want(bundle, piece, rot, w, d, room, pockets, priority) else if (priority < prev.priority) prev.priority = priority
        return null
    }

    fun load(budgetNs: Long) {
        now = System.currentTimeMillis()
        if (!CompactMesh.SpriteTable.ensure()) return
        val start = System.nanoTime()
        while (System.nanoTime() - start < budgetNs) {
            val d = done.poll() ?: break
            if (d.epoch != epoch) continue
            inFlight.remove(d.key)
            val p = d.packed
            if (p == null || p.quads == 0) { empty += d.key; continue }
            upload(d.key, p, d.room)
        }
        if (now - idleCheckAt > 1000) { idleCheckAt = now; evict(IDLE_MS, Int.MAX_VALUE) }
        if (wanted.isEmpty()) return
        val room = IN_FLIGHT - inFlight.size
        if (room > 0) for ((k, w) in wanted.entries.sortedBy { it.value.priority }.take(room)) {
            inFlight += k
            val e = epoch
            workers.execute {
                val packed = runCatching {
                    val b = w.bundle
                    val sprites = b.spriteIndices() ?: return@runCatching null
                    val q = quads.get()
                    MeshCodec.decode(b.data, b.offs[w.piece], b.lens[w.piece], sprites, w.rot, w.w, w.d, q, w.room?.cut(), w.pockets)
                    CompactMesh.pack(q, -(w.room?.x0 ?: 0).toFloat(), -(w.room?.z0 ?: 0).toFloat())
                }.getOrNull()

                done += Done(k, e, packed, k and (0xFFFFL shl 28).inv())
            }
        }

        wanted.clear()
    }

    private val lastOfRoom = HashMap<Long, Resident>()

    private fun upload(k: Long, p: CompactMesh.Packed, room: Long) {
        val r = place(p, lastOfRoom[room]?.takeIf { it.pool in pools }) ?: return
        r.usedAt = now
        resident[k] = r
        if (lastOfRoom.size > 2048) lastOfRoom.clear()
        lastOfRoom[room] = r
    }

    private fun place(p: CompactMesh.Packed, after: Resident? = null): Resident? {
        val vertices = p.quads * 4
        var pool: Pool? = null; var at = -1
        fun tryAlloc(): Boolean {
            if (after != null) { val a = after.pool.allocAt(after.first + after.vertices, vertices); if (a >= 0) { pool = after.pool; at = a; return true } }
            for (pl in pools) { val a = pl.alloc(vertices); if (a >= 0) { pool = pl; at = a; return true } }
            return false
        }
        if (!tryAlloc()) {
            if (pools.size < MAX_POOLS) newPool(vertices)
            else evict(CROWDED_IDLE_MS, vertices)
            if (!tryAlloc()) return null
        }
        val pl = pool!!
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(pl.buffer.slice(at.toLong() * CompactMesh.VERTEX, p.bytes.remaining().toLong()), p.bytes)
        version++
        if (p.quads * 6 > mostIndices) mostIndices = p.quads * 6
        return Resident(pl.buffer, at, p.counts, pl, vertices)
    }

    fun putOwn(p: CompactMesh.Packed): Resident? = place(p)

    fun release(r: Resident) {
        if (r.pool !in pools) return
        r.pool.release(r.first, r.vertices); version++
    }

    private fun evict(idleMs: Long, need: Int) {
        val old = resident.entries.filter { now - it.value.usedAt > idleMs }.sortedBy { it.value.usedAt }
        for ((k, r) in old) {
            resident.remove(k); r.pool.release(r.first, r.vertices)
            evictions++; version++
            if (need != Int.MAX_VALUE && r.pool.free.values.any { it >= need }) return
        }
    }

    private fun newPool(atLeast: Int) {
        val n = maxOf(POOL_VERTICES, atLeast)
        val buf = RenderSystem.getDevice().createBuffer({ "pov previews pieces" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, n.toLong() * CompactMesh.VERTEX)
        if (n / 4 * 6 > mostIndices) mostIndices = n / 4 * 6
        pools += Pool(buf, n)
    }

    fun clear() {
        for (p in pools) p.buffer.close()
        pools.clear(); resident.clear(); empty.clear(); wanted.clear(); inFlight.clear(); done.clear(); lastOfRoom.clear()
        epoch++; version++
    }

    fun allUsed(buffer: GpuBuffer, first: Int, n: Int): Boolean? {
        val pl = pools.firstOrNull { it.buffer === buffer } ?: return null
        if (first < 0 || first + n > pl.vertices) return false
        val e = pl.free.floorEntry(first + n - 1) ?: return true
        return e.key + e.value <= first
    }

    val poolCount get() = pools.size
    val residentCount get() = resident.size

    val usedBytes get() = pools.sumOf { it.used.toLong() } * CompactMesh.VERTEX
    val poolBytes get() = pools.sumOf { it.vertices.toLong() } * CompactMesh.VERTEX
    const val CAP_BYTES = MAX_POOLS.toLong() * POOL_VERTICES * CompactMesh.VERTEX

    val queued get() = inFlight.size
}
