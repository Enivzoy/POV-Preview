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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Block-style planner for the Savepoint leap plan - replaces poking ~60 selector settings in Odin's clickgui.
 * Only the Custom matrix is editable (the one the engine actually reads); ADG/MM7G are guide presets that
 * SavepointRemote refreshes from the GitHub repo every launch, and they are loaded INTO Custom on demand.
 *
 * Layout is immediate-mode: every frame the render pass draws the blocks and records clickable regions,
 * and mouse clicks replay those regions - no widget tree, no state to keep in sync. Overlay panels (help /
 * nearest settings) draw after the blocks, so their regions win the reversed click check.
 */
class SavepointPlanScreen : Screen(Component.literal("Savepoint Planner")) {

    private class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val action: (Int) -> Unit) {
        fun contains(px: Double, py: Double): Boolean = px >= x0 && px < x1 && py >= y0 && py < y1
    }

    private val regions = ArrayList<Region>()
    private var scroll = 0f // eased towards scrollTarget every frame - the smooth part
    private var scrollTarget = 0f
    private var lastFrameAt = 0L
    private var contentHeight = 0
    private var armedLoadId: String? = null
    private var armedLoadAt = 0L
    private var panel: String? = null // "help" | "near" | null

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        runCatching { ModuleManager.saveConfigurations() }
        super.onClose()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (panel != null) return true
        val maxScroll = (contentHeight - viewHeight()).coerceAtLeast(0)
        scrollTarget = (scrollTarget - (verticalAmount * 64.0).toFloat()).coerceIn(0f, maxScroll.toFloat())
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
        val dt = (now - lastFrameAt).coerceAtLeast(1) / 1000f
        lastFrameAt = now
        if (armedLoadId != null && now - armedLoadAt > CONFIRM_MS) armedLoadId = null

        // ease scroll towards its target - exponential, so it's frame-rate independent
        val maxScroll = (contentHeight - viewHeight()).coerceAtLeast(0)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll.toFloat())
        scroll += (scrollTarget - scroll) * (1f - exp(-dt * SCROLL_SPEED))
        if (abs(scrollTarget - scroll) < 0.5f) scroll = scrollTarget

        // content pane
        graphics.fill(0, 0, width, height, Color(8, 8, 10, 0.88f).rgba)

        // ============================== header ==============================
        graphics.text("§lSavepoint Planner§r §7- editing the Custom matrix", MARGIN, 12, Colors.WHITE)
        graphics.text("presets: ${Savepoint.presetStatus()}§7   repo updates on every title screen", MARGIN, 32, Colors.MINECRAFT_GRAY)

        // row 1: plan actions
        var bx = width - MARGIN
        for (btn in listOf("Done" to { close() }, "Import" to { Savepoint.importPlanFromClipboard() }, "Export" to { Savepoint.exportPlanToClipboard() })) {
            val (label, action) = btn
            val w = font.width(label) + 14
            bx -= w
            button(graphics, bx, 8, bx + w, 24, label, mouseX, mouseY) { action() }
            bx -= 6
        }

        // row 2: preset loads + panels + chips mode (built right to left, shown left to right)
        var bx2 = width - MARGIN
        // Help (rightmost)
        run {
            val w = font.width("Help") + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, "Help", mouseX, mouseY) { panel = if (panel == "help") null else "help" }
            bx2 -= 6
        }
        // chips mode
        run {
            val label = if (Savepoint.plannerDepth) "chips: depth" else "chips: simple"
            val w = font.width(label) + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, label, mouseX, mouseY) { Savepoint.plannerDepth = !Savepoint.plannerDepth }
            bx2 -= 6
        }
        // Near settings
        run {
            val w = font.width("Near") + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, "Near", mouseX, mouseY) { panel = if (panel == "near") null else "near" }
            bx2 -= 6
        }
        // preset loads (double-press to confirm)
        for (id in listOf("mm7g", "adg")) {
            val label = if (armedLoadId == id) "Sure?" else "Load ${id.uppercase()}"
            val w = font.width(label) + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, label, mouseX, mouseY, danger = armedLoadId == id) {
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
            bx2 -= 6
        }

        // ============================== stage blocks ==============================
        // scissored to the content area so scrolled blocks can never cover the header or footer
        val chipH = chipHeight()
        val top = HEADER_Y - scroll.roundToInt()
        var y = top
        val blockW = width - MARGIN * 2
        val stepLabelW = 26
        val chipW = (blockW - 12 - stepLabelW - 4 * (SavepointEngine.LEAP_CLASSES.size - 1)) / SavepointEngine.LEAP_CLASSES.size

        graphics.enableScissor(0, HEADER_Y, width, height - 20)
        for (stageInfo in Savepoint.planStages()) {
            val stage = stageInfo.stage
            var blockH = 20
            for (step in 1..stage.steps) blockH += chipH + 6 + (if (Savepoint.condLabel(stage, step) != null) 10 else 0)
            blockH += 4

            val visible = y + blockH >= HEADER_Y && y <= height - 20
            if (visible) drawBlock(graphics, stageInfo, MARGIN, y, blockW, blockH, chipW, stepLabelW, chipH, mouseX, mouseY)
            y += blockH + BLOCK_GAP
        }
        graphics.disableScissor()
        contentHeight = y - HEADER_Y + scroll.roundToInt()

        // footer hint
        graphics.text(
            "§7left-click a chip to cycle its target · right-click cycles back · Near = landmark settings · Help explains everything",
            MARGIN, height - 14, Colors.MINECRAFT_DARK_GRAY
        )

        // ============================== overlay panels ==============================
        when (panel) {
            "help" -> drawHelpPanel(graphics, mouseX, mouseY)
            "near" -> drawNearPanel(graphics, mouseX, mouseY)
        }
    }

    private fun drawBlock(
        graphics: GuiGraphicsExtractor, info: Savepoint.PlanStageInfo,
        x: Int, y: Int, w: Int, h: Int, chipW: Int, stepLabelW: Int, chipH: Int, mouseX: Int, mouseY: Int,
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
                chip(graphics, stage, step, clazz, cx, sy, chipW, chipH, mouseX, mouseY)
                cx += chipW + 4
            }
            sy += chipH + 6
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
        val depth = Savepoint.plannerDepth
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
        val font = Minecraft.getInstance().font
        if (depth) {
            // full names, stacked: class on top, target below, both tinted by their class color
            val classLabel = "§${clazz.colorCode}${clazz.name.lowercase().replaceFirstChar(Char::uppercaseChar)}"
            val tgtLabel = when (tgt) {
                SpTgt.NONE -> "§8none"
                SpTgt.NEAREST -> "§6nearest"
                SpTgt.FIRST -> "§bfirst"
                else -> "§${DungeonClass.valueOf(tgt.name).colorCode}${tgt.label}"
            }
            graphics.text(classLabel, x + (w - font.width(classLabel)) / 2, y + 3, Colors.WHITE)
            graphics.text(tgtLabel, x + (w - font.width(tgtLabel)) / 2, y + h - 12, Colors.WHITE)
        } else {
            val label = "§${clazz.colorCode}${clazz.name.first()}§7→${tgt.shortLabel()}"
            graphics.text(label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1, Colors.WHITE)
        }

        // only clickable while inside the content area - clipped chips must not eat clicks meant for the header/footer
        if (y + h > HEADER_Y && y < height - 20) regions.add(Region(x, y, x + w, y + h) { button ->
            val next = cycleTarget(tgt, back = button == 1, rowClass = clazz)
            if (next != tgt) Savepoint.setCellValue(stage, step, clazz, next)
        })
    }

    private fun button(
        graphics: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int,
        label: String, mouseX: Int, mouseY: Int, danger: Boolean = false, action: () -> Unit,
    ) {
        buttonCycle(graphics, x0, y0, x1, y1, label, mouseX, mouseY, danger) { _ -> action() }
    }

    /** like [button] but the action receives the mouse button (0 = left, 1 = right) for cycling */
    private fun buttonCycle(
        graphics: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int,
        label: String, mouseX: Int, mouseY: Int, danger: Boolean = false, action: (Int) -> Unit,
    ) {
        val hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1
        val base = if (danger) Colors.MINECRAFT_DARK_RED else GuiTheme.surface
        graphics.roundedRect(x0, y0, x1, y1, if (hovered) base.hoverTint(0.25f) else base.rgba, 3f)
        val font = Minecraft.getInstance().font
        graphics.text(label, x0 + (x1 - x0 - font.width(label)) / 2, y0 + (y1 - y0 - font.lineHeight) / 2 + 1, Colors.WHITE)
        regions.add(Region(x0, y0, x1, y1) { action(it) })
    }

    // ============================== help panel ==============================

    private fun drawHelpPanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val lines = listOf(
            "§fchips §7- left-click cycles a leap target, right-click cycles back; your own class is skipped",
            "§ftargets §7- gold = Nearest (landmark), aqua = First (menu order), letters = that class",
            "§fpresets §7- Load ADG / MM7G copies a guide into your matrix (press twice to confirm)",
            "§fclipboard §7- Export copies your plan as JSON, Import replaces your matrix from the clipboard",
            "§fnear §7- landmark + radius for Nearest leaps, and the P4/P5 target mode",
            "§flandmarks §7- capture where you stand with /leaputils landmark <name>",
            "§fchips mode §7- simple shortens names (B > n), depth spells them out (Berserk > Nearest)",
            "§fupdates §7- presets auto-refresh from GitHub every time Minecraft loads",
        )
        overlayPanel(graphics, "§lSavepoint Planner - help", lines.size * 11 + 46) { px, py, pw ->
            var ly = py + 30
            for (line in lines) {
                graphics.text(line, px + 14, ly, Colors.WHITE)
                ly += 11
            }
            graphics.text("§8click anywhere to close", px + 14, ly + 6, Colors.MINECRAFT_DARK_GRAY)
        }
    }

    // ============================== near settings panel ==============================

    private fun drawNearPanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        val captured = Landmarks.all.keys.toList()
        val hasUncaptured = listOf(Savepoint.necronLandmark, Savepoint.p5Landmark, Savepoint.stormLandmark)
            .any { it.trim().isNotEmpty() && it.trim() !in captured }

        overlayPanel(graphics, "§lNearest & landmarks", 3 * 26 + 78 + if (hasUncaptured) 11 else 0) { px, py, pw ->
            val radW = 64
            val radX = px + pw - 12 - radW
            var ry = py + 32

            // P4 / P5 rows also carry the target-mode toggle (nearest overrides the rows entirely)
            nearRow(graphics, "P4 necron", px + 12, ry, radX, radW, mouseX, mouseY,
                mode = Savepoint.necronTargetMode to { Savepoint.necronTargetMode = it },
                landmark = Savepoint.necronLandmark to { Savepoint.necronLandmark = it },
                radius = Savepoint.necronRadius to { Savepoint.necronRadius = it })
            ry += 26
            nearRow(graphics, "P5 wither king", px + 12, ry, radX, radW, mouseX, mouseY,
                mode = Savepoint.p5TargetMode to { Savepoint.p5TargetMode = it },
                landmark = Savepoint.p5Landmark to { Savepoint.p5Landmark = it },
                radius = Savepoint.p5Radius to { Savepoint.p5Radius = it })
            ry += 26
            nearRow(graphics, "storm drop-off", px + 12, ry, radX, radW, mouseX, mouseY,
                mode = null,
                landmark = Savepoint.stormLandmark to { Savepoint.stormLandmark = it },
                radius = Savepoint.stormRadius to { Savepoint.stormRadius = it })
            ry += 30

            val capturedLine = if (captured.isEmpty()) "§7captured: §8(none yet)"
            else "§7captured: §f${captured.joinToString("§7, §f")}"
            graphics.text(capturedLine, px + 12, ry, Colors.WHITE)
            graphics.text("§7capture where you stand: §f/leaputils landmark <name>", px + 12, ry + 11, Colors.WHITE)
            if (hasUncaptured) graphics.text("§8* = name isn't captured yet - those leaps wait until it exists", px + 12, ry + 22, Colors.MINECRAFT_DARK_GRAY)
        }
    }

    /** one settings row: label, optional mode toggle, landmark cycler, radius cycler */
    private fun nearRow(
        graphics: GuiGraphicsExtractor, label: String, x: Int, y: Int, radX: Int, radW: Int, mouseX: Int, mouseY: Int,
        mode: Pair<Savepoint.TargetMode, (Savepoint.TargetMode) -> Unit>?,
        landmark: Pair<String, (String) -> Unit>,
        radius: Pair<Int, (Int) -> Unit>,
    ) {
        graphics.text(label, x, y + 5, Colors.MINECRAFT_GRAY)
        var cx = x + 104
        if (mode != null) {
            val (current, set) = mode
            val text = if (current == Savepoint.TargetMode.NEAREST) "nearest" else "per-class"
            buttonCycle(graphics, cx, y, cx + 88, y + 20, text, mouseX, mouseY) { _ ->
                set(if (current == Savepoint.TargetMode.NEAREST) Savepoint.TargetMode.CLASS else Savepoint.TargetMode.NEAREST)
            }
            cx += 94
        }
        val (lm, setLm) = landmark
        val captured = lm.trim() in Landmarks.all.keys
        val shown = lm.trim().ifEmpty { "(none)" } + if (lm.trim().isNotEmpty() && !captured) " §8*" else ""
        buttonCycle(graphics, cx, y, radX - 6, y + 20, shown, mouseX, mouseY) { btn ->
            setLm(cycleLandmark(lm, btn))
        }
        val (rad, setRad) = radius
        buttonCycle(graphics, radX, y, radX + radW, y + 20, "r $rad", mouseX, mouseY) { btn ->
            setRad(cycleRadius(rad, btn))
        }
    }

    /** generic overlay: scrim (click closes) + rounded panel; [content] draws inside and registers its own regions */
    private inline fun overlayPanel(
        graphics: GuiGraphicsExtractor, title: String, contentH: Int, content: (px: Int, py: Int, pw: Int) -> Unit,
    ) {
        val pw = (width - MARGIN * 2).coerceAtMost(540)
        val ph = contentH
        val px = (width - pw) / 2
        val py = ((height - ph) / 2).coerceAtLeast(24)

        graphics.fill(0, 0, width, height, Color(0, 0, 0, 0.55f).rgba)
        regions.add(Region(0, 0, width, height) { panel = null })

        graphics.roundedRect(px, py, px + pw, py + ph, GuiTheme.background.rgba, 5f)
        graphics.text(title, px + 12, py + 9, Colors.WHITE)

        content(px, py, pw)
    }

    /** cycles a landmark setting: "(none)" first, then every captured name; the current value always participates */
    private fun cycleLandmark(current: String, button: Int): String {
        val cur = current.trim()
        val options = (listOf("") + Landmarks.all.keys.toList() + listOf(cur)).distinct()
        val idx = options.indexOf(cur).coerceAtLeast(0)
        return if (button == 1) options[(idx - 1 + options.size) % options.size]
        else options[(idx + 1) % options.size]
    }

    /** radius steps clamp instead of wrapping - spinning past 128 stays at 128 */
    private fun cycleRadius(current: Int, button: Int): Int {
        val steps = listOf(4, 8, 12, 16, 24, 32, 48, 64, 96, 128)
        val idx = steps.indexOf(current)
        if (idx >= 0) {
            val next = if (button == 1) idx - 1 else idx + 1
            return steps[next.coerceIn(0, steps.size - 1)]
        }
        // current isn't a listed step - snap towards it on first interaction
        return if (button == 1) steps.lastOrNull { it <= current } ?: steps.first()
        else steps.firstOrNull { it >= current } ?: steps.last()
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

    private fun chipHeight(): Int = if (Savepoint.plannerDepth) 26 else 18

    private fun close() {
        Minecraft.getInstance().gui.setScreen(null)
    }

    private fun viewHeight(): Int = height - HEADER_Y - 20

    companion object {
        private const val MARGIN = 16
        private const val HEADER_Y = 54
        private const val BLOCK_GAP = 8
        private const val CONFIRM_MS = 5_000L
        private const val SCROLL_SPEED = 16f // higher = snappier ease
    }
}
