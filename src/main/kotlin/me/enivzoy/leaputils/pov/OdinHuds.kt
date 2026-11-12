package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.clickgui.settings.impl.drawAtHud
import net.minecraft.client.gui.GuiGraphicsExtractor

object OdinHuds {

    class Draw(private val block: (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int>) : (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int> {
        var name = ""

        override fun invoke(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
            if (!example && PosePov.hidesHud(name)) return 0 to 0
            return block(g, example)
        }
    }

    private val draws = HashMap<HUDSetting, Draw>()
    private var pending: Draw? = null

    @JvmStatic
    fun wrap(block: (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int>): (GuiGraphicsExtractor, Boolean) -> Pair<Int, Int> =
        Draw(block).also { pending = it }

    @JvmStatic
    fun made(setting: HUDSetting) {
        val draw = pending ?: return
        pending = null
        draw.name = setting.name
        draws[setting] = draw
    }

    fun redraw(g: GuiGraphicsExtractor, setting: HUDSetting) {
        val draw = draws[setting] ?: return
        val target = g
        g.drawAtHud(setting.value) { draw(target, false) }
    }
}
