package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/**
 * Fake Hypixel Spirit Leap chest: opens a real 3-row vanilla chest GUI titled "Spirit Leap",
 * with teammate heads in slots 11..14 and glass pane fillers everywhere else, mirroring the
 * layout the server sends for a real Spirit Leap.
 *
 * While the dummy is open, Odin's LeapMenu is fed a dummy teammate list — its own debug
 * lineup from LeapMenu.kt — by swapping [DungeonListener.leapTeammates], so Odin renders its
 * leap UI over the dummy chest just like on Hypixel. The original list is restored whenever
 * the dummy screen is closed or replaced.
 */
object DummyLeapScreen {

    private const val DUMMY_CONTAINER_ID = 42

    // The commented-out debug lineup in Odin's LeapMenu.kt.
    private val dummyTeammates = listOf(
        DungeonPlayer("Stiviaisd", DungeonClass.HEALER, 50, null),
        DungeonPlayer("Odtheking", DungeonClass.ARCHER, 50, null),
        DungeonPlayer("Bonzi", DungeonClass.MAGE, 47, null),
        DungeonPlayer("Cezar", DungeonClass.TANK, 38, null),
    )

    private var savedTeammates: List<DungeonPlayer>? = null

    fun open(forceGlowIndex: Int = -1) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player == null) {
            modMessage("[LeapUtils] join a world before opening the dummy leap menu")
            return
        }

        savedTeammates = DungeonListener.leapTeammates
        DungeonListener.leapTeammates = dummyTeammates
        Savepoint.guiGlowForceIndex = forceGlowIndex

        val menu = ChestMenu.threeRows(DUMMY_CONTAINER_ID, player.inventory)

        for (i in 0 until 27) menu.slots[i].set(fillerPane())
        dummyTeammates.forEachIndexed { idx, mate -> menu.slots[11 + idx].set(headStack(mate)) }

        mc.setScreenAndShow(DummyLeapChestScreen(menu, player.inventory, Component.literal("Spirit Leap")))
    }

    // Head name must equal the DungeonPlayer name: Odin's LeapMenu.leapTo matches slots
    // 11..15 against it (after stripping any rank prefix).
    private fun headStack(mate: DungeonPlayer): ItemStack = ItemStack(Items.PLAYER_HEAD).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal(mate.name))
        set(DataComponents.LORE, ItemLore(listOf(
            Component.literal("Class: ${mate.clazz.name}"),
            Component.literal("Click to teleport!"),
        )))
    }

    private fun fillerPane(): ItemStack = ItemStack(Items.STAINED_GLASS_PANE.gray()).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal(" "))
    }

    private fun restoreTeammates() {
        val saved = savedTeammates ?: return
        savedTeammates = null
        DungeonListener.leapTeammates = saved
    }

    private fun onDummyClosed() {
        restoreTeammates()
        Savepoint.guiGlowForceIndex = -1
    }

    private class DummyLeapChestScreen(menu: ChestMenu, inventory: Inventory, title: Component) :
        ContainerScreen(menu, inventory, title) {

        // Dummy menu: swallow clicks so nothing tries to click a container the server does not know.
        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean = true

        override fun onClose() {
            onDummyClosed()
            super.onClose()
        }

        override fun removed() {
            onDummyClosed()
            super.removed()
        }
    }
}
