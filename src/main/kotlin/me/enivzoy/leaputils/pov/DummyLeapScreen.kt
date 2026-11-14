package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.modMessage
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
 * with example teammate heads in slots 11..14 and glass pane fillers everywhere else,
 * mirroring the layout the server sends for a real Spirit Leap.
 */
object DummyLeapScreen {

    private const val DUMMY_CONTAINER_ID = 42

    private val dummyMates = listOf(
        "Alpha" to "BERSERK",
        "Beta" to "ARCHER",
        "Gamma" to "MAGE",
        "Delta" to "HEALER",
    )

    fun open() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player == null) {
            modMessage("[LeapUtils] join a world before opening the dummy leap menu")
            return
        }

        val menu = ChestMenu.threeRows(DUMMY_CONTAINER_ID, player.inventory)

        for (i in 0 until 27) menu.slots[i].set(fillerPane())
        dummyMates.forEachIndexed { idx, (name, clazz) ->
            menu.slots[11 + idx].set(
                ItemStack(Items.PLAYER_HEAD).apply {
                    set(DataComponents.CUSTOM_NAME, Component.literal(name))
                    set(DataComponents.LORE, ItemLore(listOf(
                        Component.literal("Class: $clazz"),
                        Component.literal("Click to teleport!"),
                    )))
                }
            )
        }

        mc.setScreenAndShow(DummyLeapChestScreen(menu, player.inventory, Component.literal("Spirit Leap")))
    }

    private fun fillerPane(): ItemStack = ItemStack(Items.STAINED_GLASS_PANE.gray()).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal(" "))
    }

    private class DummyLeapChestScreen(menu: ChestMenu, inventory: Inventory, title: Component) :
        ContainerScreen(menu, inventory, title) {

        // Dummy menu: swallow clicks so nothing tries to click a container the server does not know.
        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean = true
    }
}
