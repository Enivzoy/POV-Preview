package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.Colors
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class DummyLeapScreen : Screen(Component.literal("Spirit Leap")) {

    private val dummyNames = listOf("Alpha", "Beta", "Gamma", "Delta")
    private val dummyClasses = listOf("BERSERK", "ARCHER", "MAGE", "HEALER")

    override fun extractRenderState(gfx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(gfx, mouseX, mouseY, partialTick)

        val mc = Minecraft.getInstance()
        val font = mc.font
        val width = this.width
        val height = this.height
        val halfW = width / 2
        val halfH = height / 2

        val positions = listOf(
            Pair(10, 10),
            Pair(halfW + 10, 10),
            Pair(10, halfH + 10),
            Pair(halfW + 10, halfH + 10)
        )

        for (idx in positions.indices) {
            if (idx >= dummyNames.size) break
            val pos = positions[idx]
            val x = pos.first
            val y = pos.second
            val boxW = halfW - 20
            val boxH = halfH - 20

            gfx.fill(x, y, x + boxW, y + boxH, 0x60000000.toInt())
            gfx.fill(x, y, x + boxW, y + 4, 0xFF00FF00.toInt())
            gfx.fill(x, y + boxH - 4, x + boxW, y + boxH, 0xFF00FF00.toInt())
            gfx.fill(x, y, x + 4, y + boxH, 0xFF00FF00.toInt())
            gfx.fill(x + boxW - 4, y, x + boxW, y + boxH, 0xFF00FF00.toInt())

            gfx.text(font, dummyNames[idx], x + 8, y + 10, Colors.WHITE.rgba)
            gfx.text(font, dummyClasses[idx % dummyClasses.size], x + 8, y + 24, Colors.MINECRAFT_GRAY.rgba)
        }
    }

    override fun isPauseScreen(): Boolean = false
}