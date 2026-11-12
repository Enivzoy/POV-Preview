package me.enivzoy.leaputils.pov

import me.enivzoy.leaputils.LeapUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

object PoseShare {
    private const val TICK_NS = 50_000_000L

    @Volatile var frameNs = 16_666_666L

    private const val D_TICK = PoseWire.D_TICK
    private const val D_LOOK = PoseWire.D_LOOK

    private val EXTRA_KINDS = setOf(7, 8, 13, 14, 15)

    private const val STALE_NS = PoseTrack.STALE_NS
    private const val GUI_STALE_NS = 3_000_000_000L
    private val LOBBY = Regex("[A-Za-z0-9]{1,24}")
    private val STARTS_WITH = Regex("^What starts with: '([A-Za-z])'\\?$")
    private val SELECT = Regex("^Select all the ([\\w ]+) items!$")

    private val mc get() = Minecraft.getInstance()
    private val http: HttpClient = HttpClient.newHttpClient()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false

    @Volatile private var wanted = false
    private var sending: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    @Volatile private var connecting = false
    private var room: String? = null
    @Volatile private var retryAt = 0L
    private var backoff = 2_000L
    private var seq = 0
    @Volatile private var sentParty: List<String> = emptyList()
    private var sentEntries: List<String> = emptyList()
    @Volatile private var partyAt = 0L
    private var lastPing = 0L
    private var wasSwinging = false
    private var guiFrame: ByteArray? = null
    private var guiAt = 0L
    private var sentWatch = false
    private var watchAt = 0L

    private val own = PoseWire.Sender()
    private var wasWanted = false

    @Volatile var watching = false

    @Volatile var sharing = true

    @Volatile var optimize = true

    @Volatile var skipTiny = true

    @Volatile var debug = false
    @Volatile private var mySlot = 0

    @Volatile private var relayExtras: Set<Int> = emptySet()

    private val turn = TurnLink({ slot, kind, body -> LeapUtils.safely("pose turn") { direct(slot, kind, body) } }, { LeapUtils.logger.info("[pose share] $it") },
        anyOrder = setOf(D_TICK, D_LOOK))

    @Volatile private var turnOffered = false
    @Volatile private var creds: Pair<String, String>? = null
    private var credsAskedAt = 0L

    private var turnAddr = ""
    private var sentAddr = ""
    private var addrAt = 0L

    @Volatile private var colo = ""

    private val viaTurn = ConcurrentHashMap<Int, Boolean>()

    private val roster = ConcurrentHashMap<Int, String>()
    private val subjects = ConcurrentHashMap<Int, String>()

    @Volatile private var teammates: Set<String> = emptySet()
    private val pings = ConcurrentHashMap<Int, Long>()

    @Volatile var rttMs = -1.0; private set

    val sharingCount get() = roster.size
    val connected get() = ready

    class Gui(val type: TerminalTypes, val extra: Int, val solution: IntArray, val at: Long)

    class Grid(val rows: Int, val cols: Int, val startRow: Int, val startCol: Int, val maxCells: Int) {
        operator fun contains(slot: Int): Boolean { val r = slot / 9 - startRow; val c = slot % 9 - startCol; return r in 0 until rows && c in 0 until cols }
    }

    val GRIDS = mapOf(
        TerminalTypes.PANES to Grid(3, 5, 1, 2, 15),
        TerminalTypes.RUBIX to Grid(3, 3, 1, 3, 9),
        TerminalTypes.NUMBERS to Grid(2, 7, 1, 1, 14),

        TerminalTypes.STARTS_WITH to Grid(3, 7, 1, 1, 10),
        TerminalTypes.SELECT to Grid(4, 7, 1, 1, 14),
        TerminalTypes.MELODY to Grid(5, 9, 0, 0, 3),
    )

    private val tracks = ConcurrentHashMap<String, PoseTrack>()

    class Pose(val pos: Vec3, val yaw: Float, val pitch: Float, val item: ItemStack, val swingAt: Long, val sneaking: Boolean, val via: String?, val direct: Boolean, val delayMs: Int, val bufferNs: Long, val timed: Boolean)

    fun pose(name: String, now: Long): Pose? {
        val d = tracks[name.lowercase()]?.drawn(now) ?: return null
        val item = BuiltInRegistries.ITEM.byId(d.item).takeIf { d.item != 0 && it !== Items.AIR }?.let { ItemStack(it) } ?: ItemStack.EMPTY
        val delay = d.delayMs + (if (!d.direct && rttMs > 0) (rttMs / 2).toInt() else 0)
        return Pose(d.pos, d.yaw, d.pitch, item, d.swingAt, d.sneaking, d.via, d.direct, delay, d.bufferNs, d.timed)
    }

    fun gui(name: String, now: Long): Gui? = tracks[name.lowercase()]?.gui?.takeIf { now - it.at < GUI_STALE_NS }

    fun tick() {
        val lobby = LocationUtils.lobbyId?.takeIf { DungeonUtils.inDungeons && LOBBY.matches(it) }
        val me = mc.player
        teammates = DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name.lowercase() }
        ids = idsNow()
        if (lobby == null || me == null) { if (room != null || socket != null) close(); return }
        if (lobby != room) { close(); room = lobby }
        if (socket == null) {
            if (!connecting && System.currentTimeMillis() >= retryAt) connect(lobby)
            return
        }
        if (!ready) return
        seq++
        val nowMs = System.currentTimeMillis()
        val now = System.nanoTime()
        if (debug && nowMs - dbgLogAt > 10_000) debugLines()

        val party = partyNow()
        if (party.map { it.second } != sentEntries && nowMs - partyAt > 2100) {
            sendText(JsonObject().apply { addProperty("t", "p"); add("p", JsonArray().apply { party.forEach { add(it.second) } }) }.toString())
            sentParty = party.map { it.first }; sentEntries = party.map { it.second }; partyAt = nowMs
        }

        if (watching) watchAt = nowMs
        val watch = watching || nowMs - watchAt < 1500
        if (watch != sentWatch) { sentWatch = watch; sendBinary(byteArrayOf(5, if (watch) 1 else 0)) }

        turnTick(nowMs)

        if (nowMs - lastPing > (if (wanted || watch) 2000 else 30_000)) {
            lastPing = nowMs
            val nonce = (nowMs and 0x7FFFFFFF).toInt()
            pings[nonce] = now
            sendBinary(ByteBuffer.allocate(5).put(0).putInt(nonce).array())
        }

        val swinging = me.isSwinging
        val swung = swinging && !wasSwinging
        wasSwinging = swinging

        if (sharing && seq % 10 == 0) shareRooms(nowMs)

        val term = TerminalUtils.currentTerm
        val grid = term?.let { GRIDS[it.type] }
        if (sharing && term != null && grid != null && me.containerMenu !== me.inventoryMenu) {
            val title = mc.gui.screen()?.title?.string ?: ""
            val extra = when (term.type) {
                TerminalTypes.STARTS_WITH -> STARTS_WITH.find(title)?.groupValues?.get(1)?.uppercase()?.get(0)?.minus('A') ?: 0
                TerminalTypes.SELECT -> SELECT.find(title)?.groupValues?.get(1)?.replace("SILVER", "LIGHT GRAY", ignoreCase = true)
                    ?.let { c -> DyeColor.entries.find { it.name.replace("_", " ").equals(c, true) }?.ordinal } ?: 0
                else -> 0
            }
            val sol = solutionFor(term.type, grid, term.solution)
            var f = ByteArray(4 + sol.size)
            f[0] = 3; f[1] = term.type.ordinal.toByte(); f[2] = extra.toByte(); f[3] = sol.size.toByte()
            sol.forEachIndexed { i, s -> f[4 + i] = s.toByte() }
            if (!f.contentEquals(guiFrame) || nowMs - guiAt > 1000) { guiFrame = f; guiAt = nowMs; share(f) }
        } else if (guiFrame != null) { guiFrame = null; share(byteArrayOf(4)) }

        if (!wanted || !sharing) { wasWanted = false; return }
        if (!wasWanted) { wasWanted = true; own.selfAt = 0L; guiAt = 0L }

        var flags = if (swung) 1 else 0
        if (me.isShiftKeyDown) flags = flags or 2
        val frame = pose(1, -1, 0, me.x, me.y, me.z, me.yRot, me.xRot, flags, me.mainHandItem)

        val fast = turnSlots()
        val direct = if (fast.isNotEmpty()) tickDirect(me, flags, now, fast) else emptySet()
        val relay = !direct.containsAll(roster.keys)
        for (f in own.relayFrames(frame, now, relay)) sendBinary(f)

        val sharers = roster.values.toSet()
        val level = mc.level
        if (level != null) for ((i, name) in sentParty.withIndex()) {
            if (name in sharers) continue
            val p = level.players().firstOrNull { it.name.string.equals(name, ignoreCase = true) } ?: continue
            val f = if (PosePov.swungSince(p.id, now - TICK_NS)) 1 else 0
            sendBinary(pose(2, i, 0, p.x, p.y, p.z, p.yHeadRot, p.xRot, f, p.mainHandItem))
        }

    }

    fun frameSafely() = LeapUtils.safely("pose share frame") { frame() }

    fun frame() {
        if (!ready || !wanted || !sharing || !turn.ready) return
        val fast = turnSlots()
        if (fast.isEmpty()) return
        val me = mc.player ?: return
        val now = System.nanoTime()
        val bodies = own.look(now, msNow(), me.yRot, me.xRot, frameNs, skipTiny) ?: return
        for (b in bodies) turn.broadcast(D_LOOK, b, fast)
    }

    private fun msNow() = (System.nanoTime() / 1_000_000).toInt() and 0xFFFF

    private fun tickDirect(me: net.minecraft.client.player.LocalPlayer, flags: Int, now: Long, to: Set<Int>): Set<Int> {
        if (!turn.ready) return emptySet()
        val item = if (me.mainHandItem.isEmpty) 0 else BuiltInRegistries.ITEM.getId(me.mainHandItem.item)

        val bodies = own.tick(now, msNow(), me.x, me.y, me.z, me.yRot, me.xRot, flags, item) ?: return to.filterTo(HashSet()) { turn.sure(it) }
        var out = emptySet<Int>()
        for (b in bodies) out = turn.broadcast(D_TICK, b, to)
        return out
    }

    private fun share(frame: ByteArray) {
        val direct = turn.broadcast(PoseWire.turnKind(frame[0].toInt()), frame.copyOfRange(1, frame.size), turnSlots())
        if (!direct.containsAll(roster.keys)) sendBinary(frame)
    }

    private val extraReaders = ConcurrentHashMap<Int, (String, ByteBuffer) -> Unit>()

    fun onExtra(kind: Int, read: (String, ByteBuffer) -> Unit) {
        require(kind in EXTRA_KINDS) { "kind $kind isn't free for extras" }
        extraReaders[kind] = read
    }

    private fun extra(sender: String, kind: Int, b: ByteBuffer) { extraReaders[kind]?.invoke(sender, b) }

    fun shareExtra(frame: ByteArray): Int {
        if (!ready || roster.isEmpty()) return 0
        val direct = turn.broadcast(PoseWire.turnKind(frame[0].toInt()), frame.copyOfRange(1, frame.size), turnSlots())
        if (relayTakes(frame[0].toInt() and 255) && !direct.containsAll(roster.keys)) sendBinary(frame)
        return direct.size
    }

    fun relayTakes(kind: Int) = kind in relayExtras

    val linkedCount get() = roster.size

    private fun turnTick(nowMs: Long) {
        turn.myRelayMs = if (rttMs > 0) (rttMs / 2).toInt() else -1
        val c = creds
        if (turnOffered && roster.isNotEmpty() && (c == null || turn.needsCredentials) && nowMs - credsAskedAt > 301_000L) {
            credsAskedAt = nowMs
            sendText("""{"t":"tc"}""")
        }
        if (c != null && !turn.needsCredentials) turn.start(c.first, c.second, mySlot)
        turn.tick { turnAddr = it }
        if (turnAddr != sentAddr && nowMs - addrAt > 3100) {
            sentAddr = turnAddr; addrAt = nowMs
            sendText(JsonObject().apply { addProperty("t", "e"); addProperty("a", turnAddr) }.toString())
        }
        routes()
    }

    private fun routes() {
        val now = System.currentTimeMillis()
        for (slot in roster.keys) {
            val p = turn.peer(slot)
            val q = quick.getOrPut(slot) { PoseWire.Route() }.update(now, p?.let { it.rttMs / 2 } ?: -1.0, p?.let { relayPathMs(it) } ?: -1.0)
            val live = p != null && p.bound && p.live(now)
            val via = PoseWire.viaTurn(q, live, optimize)

            if (viaTurn[slot] == true && !via && live) turn.broadcast(PoseWire.D_ROUTE, byteArrayOf(0), setOf(slot))
            viaTurn[slot] = via
        }
        viaTurn.keys.retainAll(roster.keys); quick.keys.retainAll(roster.keys)
    }

    private val quick = ConcurrentHashMap<Int, PoseWire.Route>()

    private fun relayPathMs(p: TurnLink.Peer) = if (rttMs > 0 && p.theirRelayMs >= 0) rttMs / 2 + p.theirRelayMs else -1.0

    private fun turnSlots(): Set<Int> = viaTurn.filterValues { it }.keys

    private fun solutionFor(type: TerminalTypes, grid: Grid, solution: List<Int>): List<Int> {
        val counts = HashMap<Int, Int>()
        val out = ArrayList<Int>()
        for (s in solution) {
            if (s !in grid) continue
            val n = (counts[s] ?: 0) + 1
            if (n > (if (type == TerminalTypes.RUBIX) 4 else 1)) continue
            if (n == 1 && counts.size >= grid.maxCells) continue
            counts[s] = n; out += s
            if (out.size >= 35) break
        }
        return out
    }

    private fun pose(kind: Int, index: Int, age: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, flags: Int, held: ItemStack): ByteArray =
        PoseWire.relay(kind, index, age, seq, x, y, z, yaw, pitch, flags, rttMs, if (held.isEmpty) 0 else BuiltInRegistries.ITEM.getId(held.item))

    private val version by lazy {
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("leaputils").map { it.metadata.version.friendlyString }.orElse("dev")
            .replace(Regex("[^\\w.+-]"), "").take(64).ifEmpty { "dev" }
    }

    private fun connect(lobby: String) {
        connecting = true

        val v = java.net.URLEncoder.encode(version, Charsets.UTF_8)
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(PovServer.relayBase + lobby + "?v=$v"), Listener(lobby))
            .whenComplete { _, err ->
                connecting = false
                if (err != null) { problem("couldn't connect: ${err.cause?.message ?: err.message}"); retryLater() }
            }
    }

    private fun opened(ws: WebSocket, lobby: String) {
        if (room != lobby) { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }; return }
        sending = CompletableFuture.completedFuture(null)
        ready = false; wanted = false; sentWatch = false; guiFrame = null; own.forgetRelay()
        stopTurn()
        roster.clear(); subjects.clear(); sentParty = emptyList(); sentEntries = emptyList(); partyAt = 0L
        socket = ws
    }

    private var lastProblem: String? = null

    private fun problem(what: String) {
        if (what == lastProblem) return
        lastProblem = what
        LeapUtils.logger.warn("[pose share] $what")
    }

    private val eids = HashMap<String, Int>()

    @Volatile private var ids: Map<String, String> = emptyMap()

    private fun idsNow(): Map<String, String> {
        val level = mc.level ?: return emptyMap()
        val names = DungeonUtils.dungeonTeammates.mapTo(HashSet()) { it.name.lowercase() }
        eids.keys.retainAll(names)
        for (p in level.players()) { val n = p.name.string.lowercase(); if (n in names) eids[n] = p.id }
        return eids.entries.associate { (n, id) -> "$n:$id" to n }
    }

    private fun partyNow(): List<Pair<String, String>> {
        val me = mc.user.name.lowercase()
        return DungeonUtils.dungeonTeammates.mapNotNull { t ->
            val n = t.name.lowercase()
            if (n == me) null else eids[n]?.let { n to "$n:$it" }
        }.take(6)
    }

    private fun signIn(ws: WebSocket, @Suppress("UNUSED_PARAMETER") challenge: String) {
        val me = mc.player ?: return
        val party = partyNow()
        sentParty = party.map { it.first }; sentEntries = party.map { it.second }; partyAt = System.currentTimeMillis()
        if (socket === ws) sendText(JsonObject().apply {
            addProperty("t", "a"); addProperty("u", "${me.name.string.lowercase()}:${me.id}")
            add("p", JsonArray().apply { party.forEach { add(it.second) } })
        }.toString())
    }

    private fun retryLater(minMs: Long = 0L) {
        socket = null; ready = false; wanted = false

        retryAt = System.currentTimeMillis() + maxOf(backoff, minMs) + (Math.random() * 1000).toLong()
        backoff = (backoff * 2).coerceAtMost(60_000L)
    }

    fun close() {
        val ws = socket
        eids.clear()
        socket = null; ready = false; wanted = false; room = null
        roster.clear(); subjects.clear(); tracks.clear()
        guiFrame = null; sentWatch = false
        stopTurn()
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
    }

    private fun stopTurn() {
        turn.stop(); creds = null; credsAskedAt = 0L; turnAddr = ""; sentAddr = ""; viaTurn.clear(); quick.clear()
    }

    private fun sendText(text: String) {
        val ws = socket ?: return
        synchronized(this) { sending = sending.handle { _, _ -> Unit }.thenCompose { ws.sendText(text, true) } }
    }

    private fun sendBinary(bytes: ByteArray) {
        val ws = socket ?: return
        synchronized(this) { sending = sending.handle { _, _ -> Unit }.thenCompose { ws.sendBinary(ByteBuffer.wrap(bytes), true) } }
    }

    private fun onText(ws: WebSocket, text: String) {
        val m = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (m["t"]?.asString) {
            "c" -> m["s"]?.asString?.takeIf { Regex("[0-9a-f]{40}").matches(it) }?.let { s -> mc.execute { LeapUtils.safely("pose share") { signIn(ws, s) } } }
            "ok" -> {
                ready = true; backoff = 2_000L
                lastProblem = null
                LeapUtils.logger.info("[pose share] signed in (slot ${m["slot"]?.asInt})")
                mySlot = m["slot"]?.asInt ?: 0
                turnOffered = m["turn"]?.asBoolean == true
                colo = m["colo"]?.asString?.filter { it in 'A'..'Z' }?.take(8) ?: ""
                relayExtras = m["x"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> runCatching { e.asInt }.getOrNull() }?.toSet() ?: emptySet()
            }
            "w" -> wanted = m["w"]?.asInt == 1
            "tc" -> {
                val u = m["u"]?.asString ?: return; val p = m["p"]?.asString ?: return
                if (u.length in 1..512 && p.length in 1..512) creds = u to p
            }
            "pe" -> {
                val s = m["s"]?.asInt ?: return
                if (roster.containsKey(s)) turn.add(s, m["a"]?.asString ?: return, m["k"]?.asString ?: return)
            }
            "px" -> m["s"]?.asInt?.let { turn.remove(it) }
            "r" -> {
                roster.clear()
                m["m"]?.asJsonArray?.forEach { e ->
                    val a = e.asJsonArray
                    val slot = a[0].asInt; val name = ids[a[1].asString] ?: return@forEach
                    if (slot in 1..255 && name in teammates) roster[slot] = name
                }
                turn.retain(roster.keys)
            }
            "s" -> {
                val i = m["i"]?.asInt ?: return; val n = m["n"]?.asString?.let { ids[it] } ?: return
                if (i in 1..255 && n in teammates) subjects[i] = n
            }
        }
    }

    private fun onBinary(data: ByteBuffer) {
        val now = System.nanoTime()
        if (data.remaining() == 5 && data.get(data.position()).toInt() == 0) {
            data.get(); val sent = pings.remove(data.getInt()) ?: return
            val ms = (now - sent) / 1e6
            rttMs = if (rttMs < 0) ms else rttMs * 0.7 + ms * 0.3
            return
        }
        if (data.remaining() < 2) return
        val from = data.get().toInt() and 255
        val sender = roster[from] ?: return
        when (val kind = data.get().toInt()) {
            1 -> if (data.remaining() == 19) heardRelayed(sender, null, from, data, 0, now)
            2 -> if (data.remaining() == 21) {
                val subject = subjects[data.get().toInt() and 255] ?: return
                val age = data.get().toInt() and 255
                if (subject == mc.user.name.lowercase()) return
                heardRelayed(subject, sender, from, data, age * 4, now)
            }
            3 -> gui(sender, data, now)
            9 -> rooms(sender, data)
            4 -> tracks[sender]?.gui = null
            else -> if (kind in EXTRA_KINDS) extra(sender, kind, data)
        }
    }

    fun direct(slot: Int, kind: Int, body: ByteArray) {
        val sender = roster[slot] ?: return
        val now = System.nanoTime()
        val b = ByteBuffer.wrap(body)
        when (kind) {
            D_TICK -> PoseWire.readTick(body)?.let { h -> heard(sender, null, slot, now, true, true, h) }
            D_LOOK -> PoseWire.readLook(body)?.let { h -> heard(sender, null, slot, now, true, true, h) }
            PoseWire.D_ROUTE -> if (body.size == 1) tracks[sender]?.turnLeft(now)
            3 -> gui(sender, b, now)
            PoseWire.D_ROOMS -> rooms(sender, b)
            4 -> if (body.isEmpty()) tracks[sender]?.gui = null
            else -> if (kind in EXTRA_KINDS) extra(sender, kind, b)
        }
    }

    private fun heardRelayed(name: String, via: String?, source: Int, b: ByteBuffer, ageMs: Int, now: Long) {
        val h = PoseWire.readRelay(b)
        heard(name, via, source, now, false, false, h, h.halfMs + ageMs)
    }

    private fun heard(name: String, via: String?, source: Int, now: Long, direct: Boolean, timed: Boolean, h: PoseWire.Heard, delayMs: Int = 0) {
        if (name !in teammates) return
        if (h.pos != null && (h.pos.y < -64 || h.pos.y > 400)) return
        val delay = if (direct) turn.rttMs(source).let { if (it > 0) (it / 2).toInt() else 0 } else delayMs
        tracks.getOrPut(name) { PoseTrack() }.heard(via, source, now, direct, timed, h.raw, h.pos, h.yaw, h.pitch, h.flags, h.item, delay)
    }

    private fun gui(sender: String, b: ByteBuffer, now: Long) {
        if (b.remaining() < 3) return
        val type = TerminalTypes.entries.getOrNull(b.get().toInt() and 255) ?: return
        val grid = GRIDS[type] ?: return
        val extra = b.get().toInt() and 255
        when (type) {
            TerminalTypes.STARTS_WITH -> if (extra > 25) return
            TerminalTypes.SELECT -> if (extra >= DyeColor.entries.size) return
            else -> if (extra != 0) return
        }
        val n = b.get().toInt() and 255

        if (n > 35 || (b.remaining() != n && b.remaining() != n + 2)) return
        val sol = IntArray(n) { b.get().toInt() and 255 }
        if (solutionFor(type, grid, sol.asList()).size != n) return
        tracks.getOrPut(sender) { PoseTrack() }.gui = Gui(type, extra, sol, now)
    }

    private class Listener(private val lobby: String) : WebSocket.Listener {
        private val text = StringBuilder()
        private var bin: ByteBuffer? = null
        private var tooBig = false

        override fun onOpen(ws: WebSocket) {
            LeapUtils.safely("pose share") { opened(ws, lobby) }
            ws.request(1)
        }

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (text.length + data.length <= 4096) text.append(data)
            if (last) { val whole = text.toString(); text.setLength(0); LeapUtils.safely("pose share") { onText(ws, whole) } }
            ws.request(1)
            return null
        }

        override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {

            val prev = bin
            if (prev == null && last && !tooBig) LeapUtils.safely("pose share") { onBinary(data) }
            else if (!tooBig) {
                if ((prev?.remaining() ?: 0) + data.remaining() > 64) tooBig = true
                else bin = ByteBuffer.allocate((prev?.remaining() ?: 0) + data.remaining()).also { n -> prev?.let { n.put(it) }; n.put(data); n.flip() }
                if (last && !tooBig) bin?.let { LeapUtils.safely("pose share") { onBinary(it) } }
            }
            if (last) { bin = null; tooBig = false }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (socket !== ws) return null
            if (statusCode != WebSocket.NORMAL_CLOSURE) problem("closed by the relay: $statusCode")
            when (statusCode) {

                4002 -> retryLater(60_000L)
                4001 -> retryLater(10_000L)

                4005 -> retryLater(30_000L)
                else -> retryLater()
            }
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) { if (socket === ws) { problem("socket error: ${error.message}"); retryLater() } }
    }

    private var dbgAt = 0L
    private var dbgLogAt = 0L
    private val dbgCounts = LongArray(5)
    private val dbgRates = DoubleArray(5)
    private val dbgArrivals = HashMap<String, Long>()
    private val dbgPoseRate = HashMap<String, Double>()
    private val dbgTurnIn = HashMap<Int, Pair<Long, Long>>()
    private val dbgLoss = HashMap<Int, Double>()

    private fun debugRates(nowMs: Long) {
        if (nowMs - dbgAt < 1000) return
        val dt = if (dbgAt == 0L) 1.0 else (nowMs - dbgAt) / 1000.0
        dbgAt = nowMs
        val counts = longArrayOf(turn.framesOut.get(), turn.bytesOut.get(), turn.bytesIn.get(), own.looksSent, own.looksSkipped)
        for (i in 0 until 5) { dbgRates[i] = (counts[i] - dbgCounts[i]) / dt; dbgCounts[i] = counts[i] }
        for ((name, t) in tracks) {
            val n = synchronized(t) { t.arrivals }
            dbgPoseRate[name] = (n - (dbgArrivals[name] ?: n)) / dt
            dbgArrivals[name] = n
        }
        for (slot in roster.keys) {
            val p = turn.peer(slot) ?: continue
            val (inBefore, lostBefore) = dbgTurnIn[slot] ?: (p.framesIn to p.lost)
            val got = p.framesIn - inBefore; val lost = p.lost - lostBefore
            dbgLoss[slot] = if (got + lost > 0) lost * 100.0 / (got + lost) else 0.0
            dbgTurnIn[slot] = p.framesIn to p.lost
        }
    }

    private fun ms(v: Double) = if (v < 0) "?" else "%.0f ms".format(v)

    fun debugLines(): List<Pair<String, Int>> {
        val nowMs = System.currentTimeMillis()
        val now = System.nanoTime()
        debugRates(nowMs)
        val grey = 0xFFB0B0B0.toInt(); val green = 0xFF60E080.toInt(); val yellow = 0xFFE0C040.toInt(); val red = 0xFFFF6060.toInt()
        val out = ArrayList<Pair<String, Int>>()
        out += when {
            socket == null -> "relay: offline" + (lastProblem?.let { " ($it)" } ?: "") to red
            !ready -> "relay: signing in" to yellow
            else -> "relay: connected" + (if (colo.isNotEmpty()) " via $colo" else "") + " · slot $mySlot · round trip ${ms(rttMs)} · ${roster.size} linked · " +
                (if (wanted) "a teammate is watching" else "nobody watching") to green
        }
        out += when {
            !ready -> "TURN: waiting for the relay" to grey
            !turnOffered -> "TURN: the relay has no TURN key yet (relay only)" to yellow
            creds == null -> (if (roster.isEmpty()) "TURN: waiting for a linked teammate" else "TURN: asking for credentials") to grey
            turn.state == TurnLink.State.ALLOCATING -> "TURN: allocating at turn.cloudflare.com" to yellow
            turn.state == TurnLink.State.FAILED -> "TURN: failed (${turn.problem ?: "?"})" + (if (turn.needsCredentials) ", asking for new credentials" else ", retrying") to red
            turn.ready -> "TURN: ready · server ${ms(turn.serverRttMs)} round trip · allocation ${turn.lifetimeS}s · out %.0f frames/s · up %.1f kB/s · down %.1f kB/s"
                .format(dbgRates[0], dbgRates[1] / 1000, dbgRates[2] / 1000) to green
            else -> "TURN: off" to grey
        }
        out += ("optimize server location: ${if (optimize) "on" else "off"} · looks up to ${1_000_000_000L / frameNs} fps over TURN: %.0f/s sent, %.0f/s skipped as tiny" +
            if (skipTiny) "" else " (skipping off)").format(dbgRates[3], dbgRates[4]) to grey
        val me = mc.user.name.lowercase()
        for (name in teammates.sorted()) {
            if (name == me) continue
            val slot = roster.entries.firstOrNull { it.value == name }?.key
            val t = tracks[name]
            val sb = StringBuilder(name).append(": ")
            var color = grey
            if (slot == null) sb.append("not linked (no mod, or not in sight yet)")
            else {
                val p = turn.peer(slot)
                val fast = viaTurn[slot] == true
                color = if (fast) green else yellow
                sb.append(if (!fast) "relay" else if (turn.sure(slot)) "TURN" else "TURN, unsure (the relay too)")
                if (p != null) {
                    sb.append(" · TURN ").append(if (p.bound) ms(if (p.rttMs < 0) -1.0 else p.rttMs / 2) + " one way" else "binding")
                    sb.append(" · relay ").append(ms(relayPathMs(p))).append(" one way")
                    sb.append(" · lost %.1f%%".format(dbgLoss[slot] ?: 0.0))
                } else sb.append(if (turn.ready) " · no TURN address from them yet" else " · no TURN")
            }
            if (t != null) synchronized(t) {
                val last = t.samples.lastOrNull()
                if (last != null) {
                    t.via?.let { sb.append(" · relayed by ").append(it) }
                    sb.append(" · %.0f poses/s · buffer %.0f ms (positions %.0f) · jitter %.1f ms · %s · heard %d ms ago".format(
                        dbgPoseRate[name] ?: 0.0, t.buffer / 1e6, t.posBuffer() / 1e6, t.jitter / 1e6, if (t.timed) "timed" else "ticks", (now - last.arrived) / 1_000_000))
                    if (now - last.arrived > STALE_NS) color = red
                }
                if (t.gui != null) sb.append(" · terminal open")
            }
            out += sb.toString() to color
        }
        if (debug && nowMs - dbgLogAt > 10_000) { dbgLogAt = nowMs; LeapUtils.logger.info("[pose share] debug: " + out.joinToString(" | ") { it.first }) }
        return out
    }

    private var roomsSig = ""
    private var roomsRoster: Set<Int> = emptySet()
    private var roomsAt = 0L
    private const val ROOMS_AGAIN_MS = 5_000L

    private fun shareRooms(nowMs: Long) {
        if (!ready || roster.isEmpty() || nowMs - roomsAt < 1100) return
        val mine = KnownWorld.mine().filter { it.tiles.size in 1..4 }
        val sig = mine.joinToString(";") { "${it.core},${it.rot.ordinal},${it.tiles}" }
        val linked = roster.keys.toSet()
        if (mine.isEmpty() || (sig == roomsSig && linked == roomsRoster && nowMs - roomsAt < ROOMS_AGAIN_MS)) return
        roomsSig = sig; roomsRoster = linked; roomsAt = nowMs
        val b = ByteBuffer.allocate(256)
        b.put(9).put(0)
        var n = 0
        for (r in mine) {
            if (b.remaining() < 6 + r.tiles.size || n >= 36) break
            b.putInt(r.core).put(r.rot.ordinal.toByte()).put(r.tiles.size.toByte())
            for (t in r.tiles) b.put((t.z * 6 + t.x).toByte())
            n++
        }
        b.put(1, n.toByte())
        shareExtra(b.array().copyOf(b.position()))
    }

    private fun rooms(sender: String, b: ByteBuffer) {
        if (sender !in teammates || !b.hasRemaining()) return
        val n = b.get().toInt() and 255
        if (n > 36) return
        val out = ArrayList<KnownWorld.SharedRoom>()
        val used = HashSet<Int>()
        repeat(n) {
            if (b.remaining() < 6) return
            val core = b.int
            val rot = me.enivzoy.leaputils.dungeonpack.pack.Rot.entries.getOrNull(b.get().toInt() and 255) ?: return
            val count = b.get().toInt() and 255
            if (count !in 1..4 || b.remaining() < count) return
            val tiles = List(count) { b.get().toInt() and 255 }
            if (tiles.any { it !in 0 until 36 || !used.add(it) }) return
            out += KnownWorld.SharedRoom(core, rot, tiles.map { me.enivzoy.leaputils.dungeonpack.pack.Tile(it % 6, it / 6) })
        }
        if (b.hasRemaining()) return
        KnownWorld.shared(sender, out)
    }
}
