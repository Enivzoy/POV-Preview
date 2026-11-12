package me.enivzoy.leaputils.p3sim

import me.enivzoy.leaputils.LeapUtils
import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.UUID
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

object ArenaFixes {
    internal class Run(val init: IntArray, val events: IntArray)

    private class Table(
        val states: Array<BlockState>,
        val runs: List<Run>,
        val root: JsonObject,
    )

    private val table: Table? by lazy {
        try {
            val text = ArenaFixes::class.java.getResourceAsStream("/assets/leaputils/p3sim/arena-fixes.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@lazy null
            val root = JsonParser.parseString(text).asJsonObject
            val states = root.getAsJsonArray("states").map { Arena.parse(it.asString) }.toTypedArray()
            val runs = root.getAsJsonArray("runs").map { r ->
                val o = r.asJsonObject
                fun flat(a: JsonArray): IntArray { val out = ArrayList<Int>(); a.forEach { e -> e.asJsonArray.forEach { out += it.asInt } }; return out.toIntArray() }
                Run(flat(o.getAsJsonArray("init")), flat(o.getAsJsonArray("events")))
            }
            Table(states, runs, root)
        } catch (t: Throwable) {
            LeapUtils.logger.warn("[p3sim] arena-fixes.json unreadable", t)
            null
        }
    }

    @Volatile private var pick = 0

    fun patch(base: Arena.Data): Arena.Data {
        val t = table
        val ext = try { Arena.readBin("arena-ext.bin") } catch (e: Throwable) { LeapUtils.logger.warn("[p3sim] arena-ext.bin unreadable", e); null }
        val x0 = minOf(base.x0, ext?.x0 ?: base.x0); val y0 = minOf(base.y0, ext?.y0 ?: base.y0)
        val z0 = minOf(base.z0, ext?.z0 ?: base.z0)
        val x1 = maxOf(base.x0 + base.w, ext?.let { it.x0 + it.w } ?: 0); val y1 = maxOf(base.y0 + base.h, ext?.let { it.y0 + it.h } ?: 0); val z1 = maxOf(base.z0 + base.d, ext?.let { it.z0 + it.d } ?: 0)
        val w = x1 - x0; val h = y1 - y0; val d = z1 - z0
        val states = ArrayList<BlockState>(base.states.toList())
        val index = HashMap<BlockState, Int>()
        states.forEachIndexed { i, s -> index.putIfAbsent(s, i) }
        fun idx(s: BlockState): Int = index.getOrPut(s) { states += s; states.size - 1 }
        val air = B.AIR.defaultBlockState()
        val airIdx = idx(air).toShort()
        val cells = ShortArray(w * h * d) { airIdx }
        for (y in 0 until base.h) for (z in 0 until base.d) {
            System.arraycopy(base.cells, (y * base.d + z) * base.w, cells, (((y + base.y0 - y0) * d) + (z + base.z0 - z0)) * w + (base.x0 - x0), base.w)
        }
        fun cell(x: Int, y: Int, z: Int) = (((y - y0) * d) + (z - z0)) * w + (x - x0)
        fun inBox(x: Int, y: Int, z: Int) = x >= x0 && x < x1 && y >= y0 && y < y1 && z >= z0 && z < z1
        fun put(x: Int, y: Int, z: Int, s: BlockState) { if (inBox(x, y, z)) cells[cell(x, y, z)] = idx(s).toShort() }
        fun at(x: Int, y: Int, z: Int): BlockState = if (inBox(x, y, z)) states[cells[cell(x, y, z)].toInt()] else air

        if (ext != null) {
            for (y in 0 until ext.h) for (z in 0 until ext.d) for (x in 0 until ext.w) {
                val s = ext.states[ext.cells[(y * ext.d + z) * ext.w + x].toInt()]
                if (s.`is`(B.STRUCTURE_VOID)) continue
                put(ext.x0 + x, ext.y0 + y, ext.z0 + z, s)
            }
        }
        if (t != null) {
            val st = t.states
            val root = t.root

            for (e in root.getAsJsonArray("y63")) {
                val a = e.asJsonArray
                val best = a[2].asJsonArray.map { it.asJsonArray }.filter { !st[it[0].asInt].isAir }.maxByOrNull { it[1].asInt } ?: continue
                put(a[0].asInt, 63, a[1].asInt, st[best[0].asInt])
            }

            pick = Random.nextInt(t.runs.size)
        }
        return Arena.Data(x0, y0, z0, w, h, d, states.toTypedArray(), cells)
    }

    private class Skull(val x: Int, val y: Int, val z: Int, val profile: ResolvableProfile)

    private val skulls: Map<Long, List<Skull>> by lazy {
        val out = HashMap<Long, MutableList<Skull>>()
        LeapUtils.safely("p3sim skulls") {
            val root = table?.root ?: return@safely
            val tex = root.getAsJsonArray("skullTex").map { it.asString }
            for (e in root.getAsJsonArray("skulls")) {
                val a = e.asJsonArray
                val id = a[5].asJsonArray.map { it.asInt }
                val uuid = UUID((id[0].toLong() shl 32) or (id[1].toLong() and 0xffffffffL), (id[2].toLong() shl 32) or (id[3].toLong() and 0xffffffffL))
                val t = tex[a[3].asInt]
                val gp = GameProfile(uuid, a[4].asString, PropertyMap(ImmutableMultimap.of("textures", Property("textures", t))))
                val x = a[0].asInt; val z = a[2].asInt
                out.getOrPut(ChunkPosKey.of(x shr 4, z shr 4)) { ArrayList() } += Skull(x, a[1].asInt, z, ResolvableProfile.createResolved(gp))
            }
        }
        out
    }

    private object ChunkPosKey { fun of(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL) }

    fun reskull(level: net.minecraft.server.level.ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (state.block !is SkullBlock) return
        val s = skulls[ChunkPosKey.of(pos.x shr 4, pos.z shr 4)]?.firstOrNull { it.x == pos.x && it.y == pos.y && it.z == pos.z } ?: return
        val be = level.getBlockEntity(pos) as? SkullBlockEntity ?: return
        val stack = ItemStack(Items.PLAYER_HEAD)
        stack.set(DataComponents.PROFILE, s.profile)
        be.applyComponentsFromItemStack(stack)
        be.setChanged()
        level.sendBlockUpdated(pos, state, state, 3)
    }

    fun skulls(chunk: ChunkAccess) {
        val list = skulls[ChunkPosKey.of(chunk.pos.minBlockX shr 4, chunk.pos.minBlockZ shr 4)] ?: return
        for (s in list) {
            val pos = BlockPos(s.x, s.y, s.z)
            val state = chunk.getBlockState(pos)
            if (state.block !is SkullBlock) continue
            val be = SkullBlockEntity(pos, state)
            val stack = ItemStack(Items.PLAYER_HEAD)
            stack.set(DataComponents.PROFILE, s.profile)
            be.applyComponentsFromItemStack(stack)
            chunk.setBlockEntity(be)
        }
    }
}
