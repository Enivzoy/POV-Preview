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
import net.minecraft.client.gui.Font
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
 * nearest settings) and tooltips draw after the blocks, so their regions win the reversed click check.
 */
class SavepointPlanScreen : Screen(Component.literal("Savepoint Planner")) {

    private class Region(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val action: (Int) -> Unit,
        val tooltip: List<String>? = null,
    ) {
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
    private var helpScroll = 0f
    private var helpContentH = 0

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        runCatching { ModuleManager.saveConfigurations() }
        super.onClose()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (panel == "help") {
            val maxS = (helpContentH - helpViewH()).coerceAtLeast(0)
            helpScroll = (helpScroll - (verticalAmount * 40.0).toFloat()).coerceIn(0f, maxS.toFloat())
            return true
        }
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
        for (btn in listOf(
            Triple("Done", "close the planner and save your edits", { close() }),
            Triple("Import", "replace your Custom matrix with the plan JSON currently in your clipboard", { Savepoint.importPlanFromClipboard() }),
            Triple("Export", "copy your whole Custom plan (cells + conds) as JSON to the clipboard", { Savepoint.exportPlanToClipboard() }),
        )) {
            val (label, tip, action) = btn
            val w = font.width(label) + 14
            bx -= w
            button(graphics, bx, 8, bx + w, 24, label, mouseX, mouseY, tooltip = listOf("§f$label", "§7$tip")) { action() }
            bx -= 6
        }

        // row 2: preset loads + panels + chips mode (built right to left, shown left to right)
        var bx2 = width - MARGIN
        run {
            val w = font.width("Help") + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, "Help", mouseX, mouseY,
                tooltip = listOf("§fHelp", "§7how the planner works, in depth - scrollable")) { panel = if (panel == "help") null else "help" }
            bx2 -= 6
        }
        run {
            val label = if (Savepoint.plannerDepth) "chips: depth" else "chips: simple"
            val w = font.width(label) + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, label, mouseX, mouseY,
                tooltip = listOf("§fChips mode", "§7simple shortens names (§fB > n§7), depth spells them out (§fBerserk > Nearest§7)")) { Savepoint.plannerDepth = !Savepoint.plannerDepth }
            bx2 -= 6
        }
        run {
            val w = font.width("Near") + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, "Near", mouseX, mouseY,
                tooltip = listOf("§fNear settings", "§7landmark + radius for Nearest leaps, and the P4/P5 target mode")) { panel = if (panel == "near") null else "near" }
            bx2 -= 6
        }
        for (id in listOf("mm7g", "adg")) {
            val label = if (armedLoadId == id) "Sure?" else "Load ${id.uppercase()}"
            val w = font.width(label) + 14
            bx2 -= w
            button(graphics, bx2, 28, bx2 + w, 44, label, mouseX, mouseY, danger = armedLoadId == id,
                tooltip = listOf(
                    "§fLoad ${id.uppercase()}",
                    "§7copies the ${id.uppercase()} guide preset into your Custom matrix",
                    "§7overwrites every stage the preset covers - your other cells survive",
                    "§7press twice within 5s to confirm",
                )) {
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
        val chipW = (blockW - 16 - 4 * (SavepointEngine.LEAP_CLASSES.size - 1)) / SavepointEngine.LEAP_CLASSES.size

        graphics.enableScissor(0, HEADER_Y, width, height - 20)
        for (stageInfo in Savepoint.planStages()) {
            val stage = stageInfo.stage
            var blockH = 20
            for (step in 1..stage.steps) blockH += chipH + 19 + (if (Savepoint.condLabel(stage, step) != null) 10 else 0)

            val visible = y + blockH >= HEADER_Y && y <= height - 20
            if (visible) drawBlock(graphics, stageInfo, MARGIN, y, blockW, blockH, chipW, chipH, mouseX, mouseY)
            y += blockH + BLOCK_GAP
        }
        graphics.disableScissor()
        contentHeight = y - HEADER_Y + scroll.roundToInt()

        // footer hint
        graphics.text(
            "§7hover anything for help · left-click a chip cycles its target · right-click cycles back",
            MARGIN, height - 14, Colors.MINECRAFT_DARK_GRAY
        )

        // ============================== overlay panels ==============================
        when (panel) {
            "help" -> drawHelpPanel(graphics, mouseX, mouseY)
            "near" -> drawNearPanel(graphics, mouseX, mouseY)
        }

        // ============================== tooltip ==============================
        // drawn last so it sits over everything; topmost hovered region with a tooltip wins
        val hovered = regions.reversed().firstOrNull { it.tooltip != null && it.contains(mouseX.toDouble(), mouseY.toDouble()) }
        hovered?.let { drawTooltip(graphics, it.tooltip!!, mouseX, mouseY) }
    }

    private fun drawBlock(
        graphics: GuiGraphicsExtractor, info: Savepoint.PlanStageInfo,
        x: Int, y: Int, w: Int, h: Int, chipW: Int, chipH: Int, mouseX: Int, mouseY: Int,
    ) {
        val stage = info.stage
        graphics.roundedRect(x, y, x + w, y + h, GuiTheme.background.rgba, 3f)

        // title bar
        val activeTag = if (info.active) "§a●" else "§8○ off"
        graphics.text("§f§l${stage.id}§r §7${info.title}  $activeTag", x + 8, y + 6, Colors.WHITE)
        info.note?.let { graphics.text(it, x + w - 8 - Minecraft.getInstance().font.width(it), y + 6, Colors.MINECRAFT_DARK_GRAY) }

        var sy = y + 20
        for (step in 1..stage.steps) {
            // step label line: "s1 · 1st crystal" - the preset explains what the moment is
            val label = Savepoint.stepLabel(stage, step)
            graphics.text("§8s$step" + (label?.let { " §7· §f$it" } ?: ""), x + 8, sy + 1, Colors.WHITE)
            // the sN itself is hoverable too - same story the chips in this row carry
            if (sy + 11 > HEADER_Y && sy < height - 20) regions.add(
                Region(x + 4, sy, x + w - 4, sy + 11, action = { }, tooltip = stepTooltip(stage, step))
            )
            sy += 11

            var cx = x + 8
            for (clazz in SavepointEngine.LEAP_CLASSES) {
                chip(graphics, stage, step, clazz, cx, sy, chipW, chipH, mouseX, mouseY)
                cx += chipW + 4
            }
            sy += chipH + 4
            Savepoint.condLabel(stage, step)?.let {
                graphics.text("§8⌐ if ${it.replace("'", "")}", x + 8, sy, Colors.MINECRAFT_DARK_GRAY)
                sy += 10
            }
            sy += 4
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
        if (y + h > HEADER_Y && y < height - 20) regions.add(Region(x, y, x + w, y + h, action = { button ->
            val next = cycleTarget(tgt, back = button == 1, rowClass = clazz)
            if (next != tgt) Savepoint.setCellValue(stage, step, clazz, next)
        }, tooltip = chipTooltip(stage, step, clazz, tgt)))
    }

    /** what a step means - label + description from the preset, or a fallback for steps it doesn't describe */
    private fun stepTooltip(stage: SpStage, step: Int): List<String> {
        val font = Minecraft.getInstance().font
        val label = Savepoint.stepLabel(stage, step)
        val tip = ArrayList<String>()
        tip.add("§f${stage.id} · s$step" + (label?.let { " §7— §f$it" } ?: ""))
        val desc = Savepoint.stepDesc(stage, step)
        if (desc != null) tip.addAll(wrap("§7$desc", TOOLTIP_W, font))
        else tip.add("§8this step isn't described in the preset - the chips below show who leaps where")
        return tip
    }

    private fun chipTooltip(stage: SpStage, step: Int, clazz: DungeonClass, tgt: SpTgt): List<String> {
        val tip = ArrayList(stepTooltip(stage, step))
        val who = clazz.name.lowercase().replaceFirstChar(Char::uppercaseChar)
        tip.add("")
        tip.add(
            when (tgt) {
                SpTgt.NONE -> "§7$who does nothing this step"
                SpTgt.NEAREST -> "§7$who leaps: §6Nearest§7 - whoever stands closest to the stage landmark"
                SpTgt.FIRST -> "§7$who leaps: §bFirst§7 - first alive teammate in the leap menu order"
                else -> "§7$who leaps: §f${tgt.label}"
            }
        )
        tip.add("§8left-click cycle · right-click cycle back")
        return tip
    }

    private fun button(
        graphics: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int,
        label: String, mouseX: Int, mouseY: Int, danger: Boolean = false, tooltip: List<String>? = null, action: () -> Unit,
    ) {
        buttonCycle(graphics, x0, y0, x1, y1, label, mouseX, mouseY, danger, tooltip) { _ -> action() }
    }

    /** like [button] but the action receives the mouse button (0 = left, 1 = right) for cycling */
    private fun buttonCycle(
        graphics: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int,
        label: String, mouseX: Int, mouseY: Int, danger: Boolean = false, tooltip: List<String>? = null, action: (Int) -> Unit,
    ) {
        val hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1
        val base = if (danger) Colors.MINECRAFT_DARK_RED else GuiTheme.surface
        graphics.roundedRect(x0, y0, x1, y1, if (hovered) base.hoverTint(0.25f) else base.rgba, 3f)
        val font = Minecraft.getInstance().font
        graphics.text(label, x0 + (x1 - x0 - font.width(label)) / 2, y0 + (y1 - y0 - font.lineHeight) / 2 + 1, Colors.WHITE)
        regions.add(Region(x0, y0, x1, y1, action = { action(it) }, tooltip = tooltip))
    }

    private fun drawTooltip(graphics: GuiGraphicsExtractor, lines: List<String>, mx: Int, my: Int) {
        val font = Minecraft.getInstance().font
        val w = lines.maxOf { font.width(it) } + 10
        val h = lines.size * 10 + 6
        var x = mx + 14
        var y = my + 12
        if (x + w > width - 4) x = mx - w - 8
        if (y + h > height - 4) y = my - h - 8
        graphics.roundedRect(x, y, x + w, y + h, Color(10, 10, 12, 0.96f).rgba, 3f)
        lines.forEachIndexed { i, line -> graphics.text(line, x + 5, y + 4 + i * 10, Colors.WHITE) }
    }

    /** word-wraps [text] (may contain § codes) to [maxW] pixels */
    private fun wrap(text: String, maxW: Int, font: Font): List<String> {
        val lines = ArrayList<String>()
        var cur = ""
        for (word in text.split(" ")) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (font.width(cand) > maxW && cur.isNotEmpty()) {
                lines.add(cur)
                cur = word
            } else cur = cand
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    // ============================== help panel ==============================

    private fun helpLines(): List<String> = listOf(
        "§l§fWhat this is",
        "§7This planner edits your §fCustom§7 leap plan - the one Savepoint actually runs in game.",
        "§7ADG and MM7G are guide presets: load one into Custom with the header buttons. They",
        "§7auto-update from GitHub every time Minecraft loads, so route fixes reach you for free.",
        "",
        "§l§fStage blocks",
        "§7Each block is a fight phase, top to bottom in fight order: Maxor crystal waves, the",
        "§7active Storm route variant, predev, Goldor S1-S4, Necron and Wither King. The dot next to",
        "§7the title shows whether that phase is enabled in the module settings (§a●§7 on, §8○§7 off).",
        "",
        "§l§fSteps (s1, s2, ...)",
        "§7The rows inside a block are the leap moments of that phase, in order - a timeline. When",
        "§7the fight reaches moment s3, everyone with a target on s3 leaps. A chip set to §fnone§7 just",
        "§7sits that moment out: your class jumps straight to the next row where it has a target, so",
        "§7each class only leaps where it is supposed to. The text next to §fsN§7 names the moment,",
        "§7written by the preset author - hover a chip in that row for the full story.",
        "",
        "§l§fChips",
        "§7One chip per class per step. §fLeft-click§7 cycles the target forward, §fright-click§7 cycles",
        "§7back, and your own class is skipped (you cannot leap to yourself). The chip color tells you",
        "§7the target type at a glance.",
        "",
        "§l§fTarget types",
        "§fclass name §7- leap that specific teammate (berserk, archer, mage, tank, healer).",
        "§6Nearest §7- leap whoever stands closest to this stage's landmark. Set the landmark and",
        "§7  radius in the §fNear§7 panel; capture spots with §f/leaputils landmark <name>§7.",
        "§bFirst §7- leap the first alive, leapable teammate in the leap menu order.",
        "§fnone §7- no leap on this step.",
        "",
        "§l§fConditions (the \"if\" lines)",
        "§7A step with a condition only fires while the condition holds - e.g. the target standing",
        "§7near a landmark, or after a delay. Presets ship without conds; add your own in Odin's",
        "§7clickgui settings for now.",
        "",
        "§l§fNear panel",
        "§7Per-stage landmark + radius for Nearest leaps, plus the P4/P5 target mode: §fnearest§7",
        "§7makes every cell in that phase act as Nearest (it overrides the chips entirely), while",
        "§fper-class§7 uses the chips exactly as configured.",
        "",
        "§l§fClipboard",
        "§fExport§7 copies your whole Custom plan (cells + conds) as JSON. §fImport§7 replaces your",
        "§7Custom matrix with whatever plan JSON is in the clipboard - paste a friend's plan or one",
        "§7from the repo and you are running their route.",
        "",
        "§l§fPresets & updates",
        "§fLoad ADG / MM7G§7 copies that guide into Custom (press twice within 5s to confirm). It",
        "§7overwrites every stage the preset covers - stages it doesn't cover keep your cells,",
        "§7so your own P1 crystal leaps and P5 setup survive a load. Both files are fetched from",
        "§7GitHub on every title screen; §f/leaputils reload§7 fetches updates and refreshes the",
        "§7step labels in game without a restart.",
        "",
        "§l§fGood to know",
        "§7- the §fCustom Planner§7 button on the Minecraft title screen opens this planner",
        "§7- §fDone§7 and §fESC§7 both close it and save your edits",
        "§7- §fchips mode§7: simple shortens names (§fB > n§7), depth spells them out (§fBerserk > Nearest§7)",
        "§7- hover literally anything - chips, buttons, settings - for a per-item explanation",
    )

    private fun drawHelpPanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val lines = helpLines()
        val lineH = 10
        helpContentH = lines.size * lineH
        val pw = (width - MARGIN * 2).coerceAtMost(560)
        val ph = (height - 100).coerceAtLeast(220)
        val px = (width - pw) / 2
        val py = (height - ph) / 2

        graphics.fill(0, 0, width, height, Color(0, 0, 0, 0.55f).rgba)
        regions.add(Region(0, 0, width, height, action = { panel = null }))

        graphics.roundedRect(px, py, px + pw, py + ph, GuiTheme.background.rgba, 5f)
        graphics.text("§lSavepoint Planner - help", px + 12, py + 9, Colors.WHITE)
        graphics.text("§8scroll to read · click anywhere to close", px + pw - 12 - Minecraft.getInstance().font.width("scroll to read · click anywhere to close"), py + 10, Colors.MINECRAFT_DARK_GRAY)

        val viewH = ph - 40
        val maxS = (helpContentH - viewH).coerceAtLeast(0)
        helpScroll = helpScroll.coerceIn(0f, maxS.toFloat())

        graphics.enableScissor(px + 4, py + 26, px + pw - 4, py + ph - 8)
        val font = Minecraft.getInstance().font
        lines.forEachIndexed { i, line ->
            val ly = py + 30 + i * lineH - helpScroll.roundToInt()
            if (ly >= py + 26 - lineH && ly <= py + ph - 8) graphics.text(line, px + 14, ly, Colors.WHITE)
        }
        graphics.disableScissor()

        if (maxS > 0) {
            // slim scrollbar on the right edge of the panel
            val trackH = viewH
            val thumbH = (trackH * viewH / helpContentH).coerceAtLeast(12)
            val thumbY = py + 28 + ((trackH - thumbH) * (helpScroll / maxS)).roundToInt()
            graphics.fill(px + pw - 6, py + 28, px + pw - 4, py + 28 + trackH, Color(255, 255, 255, 0.08f).rgba)
            graphics.fill(px + pw - 6, thumbY, px + pw - 4, thumbY + thumbH, Color(255, 255, 255, 0.35f).rgba)
        }
    }

    private fun helpViewH(): Int = (height - 100).coerceAtLeast(220) - 40

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
            buttonCycle(graphics, cx, y, cx + 88, y + 20, text, mouseX, mouseY,
                tooltip = listOf(
                    "§fTarget mode",
                    "§7nearest §7- every leap in this phase targets the landmark, ignoring the chips",
                    "§7per-class §7- use the chips exactly as configured",
                )) { _ ->
                set(if (current == Savepoint.TargetMode.NEAREST) Savepoint.TargetMode.CLASS else Savepoint.TargetMode.NEAREST)
            }
            cx += 94
        }
        val (lm, setLm) = landmark
        val captured = lm.trim() in Landmarks.all.keys
        val shown = lm.trim().ifEmpty { "(none)" } + if (lm.trim().isNotEmpty() && !captured) " §8*" else ""
        buttonCycle(graphics, cx, y, radX - 6, y + 20, shown, mouseX, mouseY,
            tooltip = listOf(
                "§fLandmark",
                "§7where Nearest leaps look for teammates - cycles through every captured spot",
                "§7left/right click both cycle; capture new ones with §f/leaputils landmark <name>",
            )) { btn ->
            setLm(cycleLandmark(lm, btn))
        }
        val (rad, setRad) = radius
        buttonCycle(graphics, radX, y, radX + radW, y + 20, "r $rad", mouseX, mouseY,
            tooltip = listOf("§fRadius", "§7max distance in blocks for Nearest leaps - click to step up, right-click to step down")) { btn ->
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
        regions.add(Region(0, 0, width, height, action = { panel = null }))

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
        private const val TOOLTIP_W = 190
    }
}
