package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.GuiTheme
import com.odtheking.odin.clickgui.hoverTint
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Block-style planner for the Savepoint leap plan - replaces poking ~60 selector settings in Odin's clickgui.
 * Only the Custom matrix is editable (the one the engine actually reads); ADG/MM7G are guide presets that
 * SavepointRemote refreshes from the GitHub repo every launch, and they are loaded INTO Custom on demand.
 *
 * Layout is immediate-mode: every frame the render pass draws the blocks and records clickable regions,
 * and mouse clicks replay those regions - no widget tree, no state to keep in sync.
 */
class SavepointPlanScreen : Screen(Component.literal("Savepoint Planner")) {

    private class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val action: (Int) -> Unit) {
        fun contains(px: Double, py: Double): Boolean = px >= x0 && px < x1 && py >= y0 && py < y1
    }

    private val regions = ArrayList<Region>()
    private var scroll = 0
    private var contentHeight = 0
    private var armedLoadId: String? = null
    private var armedLoadAt = 0L

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        runCatching { ModuleManager.saveConfigurations() }
        super.onClose()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val maxScroll = (contentHeight - viewHeight()).coerceAtLeast(0)
        scroll = (scroll - (verticalAmount * 28.0).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        for (r in regions.reversed()) if (r.contains(event.x(), event.y())) {
            r.action(event.button())
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        regions.clear()
        val font = Minecraft.getInstance().font
        val now = System.currentTimeMillis()
        if (armedLoadId != null && now - armedLoadAt > CONFIRM_MS) armedLoadId = null

        // content pane
        graphics.fill(0, 0, width, height, Color(8, 8, 10, 0.88f).rgba)

        // ============================== header ==============================
        graphics.text("§lSavepoint Planner§r §7- editing the Custom matrix", MARGIN, 12, Colors.WHITE)
        graphics.text("presets: ${Savepoint.presetStatus()}§7   repo updates on every title screen", MARGIN, 26, Colors.MINECRAFT_GRAY)

        // right-aligned action buttons
        var bx = width - MARGIN
        for (btn in listOf("Done" to { close() }, "Import" to { Savepoint.importPlanFromClipboard() }, "Export" to { Savepoint.exportPlanToClipboard() })) {
            val (label, action) = btn
            val w = font.width(label) + 14
            bx -= w
            button(graphics, bx, 8, bx + w, 24, label, mouseX, mouseY) { action() }
            bx -= 6
        }
        for (id in listOf("mm7g", "adg")) {
            val label = if (armedLoadId == id) "Sure?" else "Load ${id.uppercase()}"
            val w = font.width(label) + 14
            bx -= w
            button(graphics, bx, 8, bx + w, 24, label, mouseX, mouseY, danger = armedLoadId == id) {
                if (armedLoadId == id) {
                    armedLoadId = null
                    val err = Savepoint.loadPresetIntoCustom(id)
                    if (err == null) modMessage("[Savepoint] §a${id.uppercase()}§f loaded into your Custom matrix")
                    else modMessage("[Savepoint] §c${id.uppercase()} load failed§f: $err")
                } else {
                    armedLoadId = id
                    armedLoadAt = now
                }
            }
            bx -= 6
        }

        // ============================== stage blocks ==============================
        val top = HEADER_Y - scroll
        var y = top
        val blockW = width - MARGIN * 2
        val stepLabelW = 26
        val chipW = (blockW - 12 - stepLabelW - 4 * (SavepointEngine.LEAP_CLASSES.size - 1)) / SavepointEngine.LEAP_CLASSES.size

        for (stageInfo in Savepoint.planStages()) {
            val stage = stageInfo.stage
            var blockH = 20
            for (step in 1..stage.steps) blockH += 24 + (if (Savepoint.condLabel(stage, step) != null) 10 else 0)
            blockH += 4

            val visible = y + blockH >= HEADER_Y && y <= height - 20
            if (visible) drawBlock(graphics, stageInfo, MARGIN, y, blockW, blockH, chipW, stepLabelW, mouseX, mouseY)
            y += blockH + BLOCK_GAP
        }
        contentHeight = y - HEADER_Y + scroll

        // footer hint
        graphics.text(
            "§7left-click a chip to cycle its target · right-click cycles back · scroll to pan · ESC closes",
            MARGIN, height - 14, Colors.MINECRAFT_DARK_GRAY
        )
    }

    private fun drawBlock(
        graphics: GuiGraphicsExtractor, info: Savepoint.PlanStageInfo,
        x: Int, y: Int, w: Int, h: Int, chipW: Int, stepLabelW: Int, mouseX: Int, mouseY: Int,
    ) {
        val stage = info.stage
        graphics.roundedRect(x, y, x + w, y + h, GuiTheme.background.rgba, 3f)

        // title bar
        val activeTag = if (info.active) "§a●" else "§8○ off"
        graphics.text("§f§l${stage.id}§r §7${info.title}  $activeTag", x + 8, y + 6, Colors.WHITE)
        info.note?.let { graphics.text(it, x + w - 8 - Minecraft.getInstance().font.width(it), y + 6, Colors.MINECRAFT_DARK_GRAY) }

        var sy = y + 20
        for (step in 1..stage.steps) {
            graphics.text("s$step", x + 8, sy + 5, Colors.MINECRAFT_GRAY)
            var cx = x + 8 + stepLabelW
            for (clazz in SavepointEngine.LEAP_CLASSES) {
                chip(graphics, stage, step, clazz, cx, sy, chipW, CHIP_H, mouseX, mouseY)
                cx += chipW + 4
            }
            sy += CHIP_H + 6
            Savepoint.condLabel(stage, step)?.let {
                graphics.text("§8⌐ if ${it.replace("'", "")}", x + 8 + stepLabelW, sy, Colors.MINECRAFT_DARK_GRAY)
                sy += 10
            }
        }
    }

    private fun chip(
        graphics: GuiGraphicsExtractor, stage: SpStage, step: Int, clazz: DungeonClass,
        x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int,
    ) {
        val tgt = Savepoint.cellValue(stage, step, clazz)
        val hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        val base = when (tgt) {
            SpTgt.NONE -> GuiTheme.surface
            SpTgt.NEAREST -> Colors.MINECRAFT_GOLD
            SpTgt.FIRST -> Colors.MINECRAFT_AQUA
            else -> GuiTheme.accent
        }
        val fill = if (hovered) base.hoverTint(0.25f) else base.rgba
        graphics.roundedRect(x, y, x + w, y + h, fill, 3f)
        val label = "${clazz.name.first()}→${tgt.shortLabel()}"
        val font = Minecraft.getInstance().font
        graphics.text(label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1, Colors.WHITE)

        regions.add(Region(x, y, x + w, y + h) { button ->
            val next = cycleTarget(tgt, back = button == 1, rowClass = clazz)
            if (next != tgt) Savepoint.setCellValue(stage, step, clazz, next)
        })
    }

    private fun button(
        graphics: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int,
        label: String, mouseX: Int, mouseY: Int, danger: Boolean = false, action: () -> Unit,
    ) {
        val hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1
        val base = if (danger) Colors.MINECRAFT_DARK_RED else GuiTheme.surface
        graphics.roundedRect(x0, y0, x1, y1, if (hovered) base.hoverTint(0.25f) else base.rgba, 3f)
        val font = Minecraft.getInstance().font
        graphics.text(label, x0 + (x1 - x0 - font.width(label)) / 2, y0 + (y1 - y0 - font.lineHeight) / 2 + 1, Colors.WHITE)
        regions.add(Region(x0, y0, x1, y1) { action() })
    }

    /** cycles a chip's target; skips the row's own class (leaping to yourself is meaningless) */
    private fun cycleTarget(current: SpTgt, back: Boolean, rowClass: DungeonClass): SpTgt {
        val order = listOf(SpTgt.NONE, SpTgt.BERSERK, SpTgt.ARCHER, SpTgt.MAGE, SpTgt.TANK, SpTgt.HEALER, SpTgt.NEAREST, SpTgt.FIRST)
        var idx = order.indexOf(current).coerceAtLeast(0)
        repeat(order.size) {
            idx = if (back) (idx - 1 + order.size) % order.size else (idx + 1) % order.size
            val next = order[idx]
            val isOwnClass = next.name == rowClass.name
            if (!isOwnClass) return next
        }
        return SpTgt.NONE
    }

    private fun SpTgt.shortLabel(): String = when (this) {
        SpTgt.NONE -> "—"
        SpTgt.NEAREST -> "near"
        SpTgt.FIRST -> "first"
        else -> name.first().toString()
    }

    private fun close() {
        Minecraft.getInstance().gui.setScreen(null)
    }

    private fun viewHeight(): Int = height - HEADER_Y - 20

    companion object {
        private const val MARGIN = 16
        private const val HEADER_Y = 48
        private const val BLOCK_GAP = 8
        private const val CHIP_H = 18
        private const val CONFIRM_MS = 5_000L
    }
}
