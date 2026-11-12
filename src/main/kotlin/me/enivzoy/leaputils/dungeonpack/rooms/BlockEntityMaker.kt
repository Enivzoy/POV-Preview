package me.enivzoy.leaputils.dungeonpack.rooms

import me.enivzoy.leaputils.LeapUtils
import me.enivzoy.leaputils.dungeonpack.pack.rooms.RoomBlockEntity
import me.enivzoy.leaputils.dungeonpack.pack.rooms.SkullTexture
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BlockStates {
    private val parsed = ConcurrentHashMap<String, BlockState>()

    fun parse(state: String): BlockState = parsed.computeIfAbsent(state) {
        try { net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, it, false).blockState() }
        catch (t: Throwable) { me.enivzoy.leaputils.LeapUtils.logger.warn("[ads] unknown block state {}", it); net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() }
    }

    fun rotated(state: String, quarterTurns: Int): BlockState {
        val s = parse(state)
        return if (quarterTurns.mod(4) == 0) s else s.rotate(rotation(quarterTurns))
    }

    fun rotation(quarterTurns: Int): Rotation = when (quarterTurns.mod(4)) {
        0 -> Rotation.NONE
        1 -> Rotation.CLOCKWISE_90
        2 -> Rotation.CLOCKWISE_180
        else -> Rotation.COUNTERCLOCKWISE_90
    }
}

class BlockEntityMaker(textures: List<SkullTexture>) {
    private val profiles: List<ResolvableProfile?> = textures.map { t ->
        if (t.value == null) return@map null
        try {

            val id = t.profileId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.nameUUIDFromBytes("ads-skull:${t.id}".toByteArray())

            val name = t.id.take(12).ifEmpty { "ads" }
            val map = PropertyMap(ImmutableMultimap.of("textures", Property("textures", t.value)))
            ResolvableProfile.createResolved(GameProfile(id, name, map))
        } catch (e: Exception) {
            LeapUtils.logger.warn("[ads] skull texture ${t.id} unusable: ${e.message}")
            null
        }
    }

    fun wants(state: BlockState): Boolean = state.block is EntityBlock && state.block !in NEVER

    fun forbids(state: BlockState): Boolean = state.block is EntityBlock && state.block in NEVER

    fun create(pos: BlockPos, state: BlockState, record: RoomBlockEntity?): BlockEntity? {
        val block = state.block as? EntityBlock ?: return null
        val be = block.newBlockEntity(pos, state) ?: return null
        apply(be, record)
        return be
    }

    fun apply(be: BlockEntity, record: RoomBlockEntity?) {
        if (record == null) return
        try {
            when (be) {
                is SkullBlockEntity -> record.tex?.let { profiles.getOrNull(it) }?.let { profile ->

                    be.applyComponents(DataComponentMap.builder().set(DataComponents.PROFILE, profile).build(), DataComponentPatch.EMPTY)
                }
                is SignBlockEntity -> record.text?.let { lines ->
                    val l = (0 until 4).map { Component.literal(lines.getOrNull(it) ?: "") }
                    try { be.setText(SignText(l, l, DyeColor.BLACK, false), SignTextSlot.FRONT) }

                    catch (e: NullPointerException) { if (be.level != null) throw e }
                }
            }
        } catch (e: Exception) {
            LeapUtils.logger.warn("[ads] block entity at ${be.blockPos} unusable: ${e.message}")
        }
    }

    private val byHash = ConcurrentHashMap<String, ResolvableProfile>()

    fun texture(be: BlockEntity, hash: String) {
        if (be !is SkullBlockEntity) return
        val profile = byHash.computeIfAbsent(hash) {
            val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$it\"}}}"
            val value = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
            val map = PropertyMap(ImmutableMultimap.of("textures", Property("textures", value)))
            ResolvableProfile.createResolved(GameProfile(UUID.nameUUIDFromBytes("ads-head:$it".toByteArray()), it.take(12), map))
        }
        try { be.applyComponents(DataComponentMap.builder().set(DataComponents.PROFILE, profile).build(), DataComponentPatch.EMPTY) }
        catch (e: Exception) { LeapUtils.logger.warn("[ads] head at ${be.blockPos} unusable: ${e.message}") }
    }

    companion object {

        private val NEVER: Set<Block> = setOf(
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER,
        )
    }
}
