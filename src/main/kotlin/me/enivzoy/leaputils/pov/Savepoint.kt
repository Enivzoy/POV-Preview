package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

// hated memorising who to leap and when to leap? introducing savepoint, a mod so good it might as well be cheats, granted you dont toggle off schizo mode. if you toggle schizo mode this module will auto /wdr you and run a autoclicker at 7000cps
// this module also has a non leap(dry mode) which only shows u who ur supposed to leap to next instead of cheating
object Savepoint : Module(
    name = "Savepoint",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Straight Cheats, nothing else to say about it"
) {
    enum class FloorFilter { F7, M7, BOTH }
    enum class TargetMode { NEAREST, CLASS }
    enum class PresetChoice { ADG, MM7G, CUSTOM }
    enum class StormVariant { GY, OLD_PY, NEW_PY }

    private var paused = false
    private var migrationChecked = false
    private var inLeapMenu = false
    private var menuOpenedAt = 0L
    private var leapedThisMenu = false
    private var warnedMissing = false
    private val consumed = mutableSetOf<SpStep>()
    private val timings = mutableMapOf<String, Long>()
    private var holdKey: String? = null
    private var holdSince = 0L
    private var waitKey: String? = null
    private var waitSince = 0L
    private var lastTrackerSection = 0
    private var lastPhase: M7Phases? = null // this flah is also used for floor7 but not seperated since both floors have the same maps.
    private var lastStatus: String? = null
    private var lastTraceKey = ""
    private var myClassCached: DungeonClass? = null
    private var presetConds: Map<String, SpCond> = emptyMap()
    private var confirmAt = 0L

    val savepointEnabled by BooleanSetting("Enabled", true, desc = "")
    val delay by NumberSetting("Delay", 250, 0..1000, 10, desc = "Normal auto-leap delay; ignored in Schizo Mode")
    val schizoMode by BooleanSetting("Schizo Mode", true, desc = "Press any Key in Leap menu to leap to savepointed")
    val dryRun by BooleanSetting("Dry run", false, desc = "Log the leap decision trace without clicking")
    val verboseLog by BooleanSetting("Verbose logging", true, desc = "Write decisions, leaps and tracker events to config/leaputils/savepoint.log pls enable this, test it and send over to witherbl_de")
    val pauseKey by KeybindSetting("Pause key", -1, desc = "Pauses or resumes Savepoint").onPress { togglePause() }
    val skipKey by KeybindSetting("Skip key", -1, desc = "Skips the currently armed leap step").onPress { skipStep() }
    val showAllClasses by BooleanSetting("Show all classes", false, desc = "Show every class row instead of only your current class [PLEASE TURN THIS ON IF YOU ARE FOLLOWING ASCENT'S CUSTOM TERMINALS THINGY]")
    val floorFilter by SelectorSetting("Floor filter", FloorFilter.BOTH, desc = "Which floors Savepoint runs on", listOf(FloorFilter.F7, FloorFilter.M7, FloorFilter.BOTH))
    val presetChoice by SelectorSetting("Preset", PresetChoice.CUSTOM, desc = "Guide presets are seeds; loading one overwrites the matrix after a confirm", listOf(PresetChoice.ADG, PresetChoice.MM7G, PresetChoice.CUSTOM))
    val loadPreset by ActionSetting("Load preset", "Writes the selected preset's cells into the matrix; press twice within 5s to confirm") { loadPresetAction() }

    val maxor by BooleanSetting("Maxor", true, desc = "P1 crystal leap rules")
    val maxorLeftClass by SelectorSetting("Leap if left", DungeonClass.BERSERK, desc = "Your class assigned to the left crystal route", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val maxorLeftTo by SelectorSetting("To left", DungeonClass.TANK, desc = "Target class for the left crystal route", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val maxorRightClass by SelectorSetting("Leap if right", DungeonClass.ARCHER, desc = "Your class assigned to the right crystal route", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val maxorRightTo by SelectorSetting("To right", DungeonClass.HEALER, desc = "Target class for the right crystal route", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()

    val storm by BooleanSetting("Storm", true, desc = "P2 drop-off and purple spotter leap rules")
    val stormVariant by SelectorSetting("Storm variant", StormVariant.GY, desc = "Each variant owns its own leap rows", listOf(StormVariant.GY, StormVariant.OLD_PY, StormVariant.NEW_PY)).withDependency { storm }
    val dropOffSpotter by SelectorSetting("Drop off spotter", DungeonClass.BERSERK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val purpleSpotter by SelectorSetting("Purple Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()

    val goldor by BooleanSetting("Goldor", true, desc = "P3 section leap rules")
    val goldorS1 by BooleanSetting("Goldor S1", true, desc = "Section 1 leap rules").withDependency { goldor }
    val goldorS2 by BooleanSetting("Goldor S2", true, desc = "Section 2 leap rules").withDependency { goldor }
    val goldorS3 by BooleanSetting("Goldor S3", true, desc = "Section 3 and core leap rules").withDependency { goldor }
    val goldorS4 by BooleanSetting("Goldor S4", true, desc = "Early Necron spotter leap rules").withDependency { goldor }
    val flagI4 by BooleanSetting("I4", false, desc = "Profile flag: Berserk does the S4 device during S1").withDependency { goldor }
    val flagEarlyEntry by BooleanSetting("Early entry", false, desc = "Profile flag: EE2/EE3 early entry routes").withDependency { goldor }
    val flagMageCore by BooleanSetting("Mage core / re-core", false, desc = "Profile flag: Mage takes goes into core to await patry leaping").withDependency { goldor }
    val goldorIf by SelectorSetting("Goldor Leap If Class", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val goldorTo by SelectorSetting("Goldor Waypoint", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()

    val necron by BooleanSetting("Necron", true, desc = "P4 leap rules")
    var necronTargetMode by SelectorSetting("Necron target mode", TargetMode.NEAREST, desc = "Leap to the nearest teammate at a landmark, or use per-class rows", listOf(TargetMode.NEAREST, TargetMode.CLASS)).withDependency { necron }
    var necronLandmark by StringSetting("Necron landmark", "necron_spot", 24, "name", "Landmark used by Nearest mode; capture with /leaputils landmark <name>").withDependency { necron && necronTargetMode == TargetMode.NEAREST }
    val necronRadius by NumberSetting("Necron radius", 64, 2..128, 2, desc = "Max distance in blocks for Nearest mode").withDependency { necron && necronTargetMode == TargetMode.NEAREST }

    val witherKing by BooleanSetting("Wither King", false, desc = "P5 leap rules")
    val p5TargetMode by SelectorSetting("P5 target mode", TargetMode.NEAREST, desc = "Leap to the nearest teammate at a landmark, or use per-class rows", listOf(TargetMode.NEAREST, TargetMode.CLASS)).withDependency { witherKing }
    val p5Landmark by StringSetting("P5 landmark", "", 24, "name", "Landmark used by Nearest mode; capture with /leaputils landmark <name>").withDependency { witherKing && p5TargetMode == TargetMode.NEAREST }
    val p5Radius by NumberSetting("P5 radius", 64, 2..128, 2, desc = "Max distance in blocks for Nearest mode").withDependency { witherKing && p5TargetMode == TargetMode.NEAREST }

    val soloCrush by BooleanSetting("SoloCrush", true, desc = "Superseded by the Goldor section rows; kept for migration").hide()
    val section1Spotter by SelectorSetting("Section 1 Spotter", DungeonClass.BERSERK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val section2Spotter by SelectorSetting("Section 2 Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val section3Spotter by SelectorSetting("Section 3 Spotter", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val coreSpotter by SelectorSetting("Core Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()
    val earlyNecronSpotter by SelectorSetting("Early Necron Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER)).hide()

    var landmarkName by StringSetting("Landmark name", "", 24, "name", "Name to save when pressing Capture here")
    val captureLandmark by ActionSetting("Capture here", "Stores your current position under Landmark name") { captureHere() }

    private var legacyMigrated by BooleanSetting("Matrix migrated", false, desc = "").hide()

    private class CellEntry(val setting: SelectorSetting<SpTgt>, val stage: SpStage, val step: Int, val clazz: DungeonClass)

    private val cellList = mutableListOf<CellEntry>()
    private val cellSettings = LinkedHashMap<String, SelectorSetting<SpTgt>>()
    private val cellProp: kotlin.reflect.KProperty<*> = ::dryRun

    private fun cell(stage: SpStage, step: Int, c: DungeonClass, vis: () -> Boolean): SelectorSetting<SpTgt> {
        val name = "${stage.id}/$step ${c.name}"
        val setting = +SelectorSetting(name, SpTgt.NONE, desc = "When you are ${c.name.lowercase()}: leap target for ${stage.id} step $step", SpTgt.entries.toList()).withDependency(vis)
        cellSettings[name] = setting
        cellList.add(CellEntry(setting, stage, step, c))
        return setting
    }

    private val statusHud by HUD(
        "Savepoint Status",
        desc = "Shows the armed next leap or why Savepoint is waiting",
        x = 491,
        y = 427,
        scale = 1f
    ) { example ->
        val line = if (example) "\u00a77Next: \u00a7fHealer \u00a77(P2.GY step 1)" else statusLine()
        if (line.isNullOrEmpty()) return@HUD 0 to 0
        val width = textDim(line, 0, 0, Colors.WHITE).first
        text(line, 0, 0, Colors.WHITE)
        width to 9
    }

    init {
        for (c in SavepointEngine.LEAP_CLASSES) {
            cell(SpStage.P1_L, 1, c) { maxor && (showAllClasses || myClassCached == c) }
            cell(SpStage.P1_R, 1, c) { maxor && (showAllClasses || myClassCached == c) }
        }
        for ((stage, variant) in listOf(
            SpStage.P2_GY to StormVariant.GY,
            SpStage.P2_OLDPY to StormVariant.OLD_PY,
            SpStage.P2_NEWPY to StormVariant.NEW_PY,
        )) {
            for (step in 1..stage.steps) {
                for (c in SavepointEngine.LEAP_CLASSES) {
                    cell(stage, step, c) { storm && stormVariant == variant && (showAllClasses || myClassCached == c) }
                }
            }
        }
        for (step in 1..SpStage.P3_S1.steps) {
            for (c in SavepointEngine.LEAP_CLASSES) cell(SpStage.P3_S1, step, c) { goldor && goldorS1 && (showAllClasses || myClassCached == c) }
        }
        for (c in SavepointEngine.LEAP_CLASSES) cell(SpStage.P3_S2, 1, c) { goldor && goldorS2 && (showAllClasses || myClassCached == c) }
        for (step in 1..SpStage.P3_S3.steps) {
            for (c in SavepointEngine.LEAP_CLASSES) cell(SpStage.P3_S3, step, c) { goldor && goldorS3 && (showAllClasses || myClassCached == c) }
        }
        for (c in SavepointEngine.LEAP_CLASSES) cell(SpStage.P3_S4, 1, c) { goldor && goldorS4 && (showAllClasses || myClassCached == c) }
        for (c in SavepointEngine.LEAP_CLASSES) cell(SpStage.P4, 1, c) { necron && (showAllClasses || myClassCached == c) }
        for (c in SavepointEngine.LEAP_CLASSES) cell(SpStage.P5, 1, c) { witherKing && (showAllClasses || myClassCached == c) }
    }

    init {
        statusHud.enabled = true
        Landmarks.load()
        presetConds = SavepointPresets.loadConds()
        SchizoLeapInput.registerHandler(::shouldWaitForSchizoKeypress)
        SpLog.session()
        SpLog.info("init", "settings: filter=$floorFilter delay=$delay schizo=$schizoMode dryRun=$dryRun variants=$stormVariant s1..s4=$goldorS1/$goldorS2/$goldorS3/$goldorS4 flags=$flagI4/$flagEarlyEntry/$flagMageCore landmarks=${Landmarks.all.keys.joinToString(",")}")

        on<ScreenEvent.Render>(EventPriority.HIGHEST + 150) {
            if (!enabled || !savepointEnabled) {
                resetMenuState()
                return@on
            }
            val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: run {
                resetMenuState()
                return@on
            }
            if (!isLeapScreen(screen)) {
                resetMenuState()
                return@on
            }
            val now = System.currentTimeMillis()
            if (!inLeapMenu) {
                inLeapMenu = true
                menuOpenedAt = now
                leapedThisMenu = false
                warnedMissing = false
                timings.clear()
                holdKey = null
                waitKey = null
                lastTraceKey = ""
                if (verboseLog) {
                    SpLog.info("menu", "open title='${screen.title.string}' class=$myClassCached phase=${phase()} section=${GoldorTracker.section}")
                    runCatching { DungeonUtils.leapTeammates }.getOrDefault(emptyList()).forEach { t ->
                        val e = OdinAccess.entityOf(t)
                        SpLog.info("menu", "  ${t.name} ${t.clazz} dead=${t.isDead} inMenu=${OdinAccess.isLeapAvailable(t, screen)} pos=${e?.x},${e?.y},${e?.z}")
                    }
                }
            }
            if (!runCatching { DungeonUtils.inBoss }.getOrDefault(false)) return@on
            if (!floorOk()) return@on
            if (leapedThisMenu || paused) return@on
            if (!schizoMode && now - menuOpenedAt < delay) return@on
            if (schizoMode && !SchizoLeapInput.consumeKeyPress(screen)) return@on

            val stages = activeStages()
            if (stages.isEmpty()) {
                val key = "E:${phase()}:${GoldorTracker.section}"
                if (key != lastTraceKey) {
                    lastTraceKey = key
                    if (verboseLog) SpLog.info("plan", "no stages armed phase=${phase()} section=${GoldorTracker.section}")
                }
                lastStatus = if (phase() == M7Phases.P3 && GoldorTracker.section == 0) "Waiting: Goldor section unknown" else null
                return@on
            }

            val rules = buildRules()
            val plan = SavepointEngine.decide(buildCtx(stages, screen), rules)
            updateTimings(plan, now)
            lastStatus = when (plan) {
                is SpPlan.Hold -> "Holding: ${plan.label}"
                is SpPlan.Wait -> "Waiting: ${plan.reason}"
                else -> null
            }
            if (dryRun) trace(plan) else logPlan(plan)
            when (plan) {
                is SpPlan.Leap -> executeLeap(plan, screen)
                is SpPlan.SkipStep -> {
                    consumed += plan.step
                    modMessage("[Savepoint] skipping ${plan.step.key}: ${plan.reason}")
                }
                else -> {}
            }
        }

        on<FloorEnterEvent> {
            if (verboseLog) SpLog.info("floor", "enter floor=${runCatching { DungeonUtils.floor }.getOrNull()}")
            resetFightState()
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!migrationChecked) {
                migrationChecked = true
                migrateIfNeeded()
            }
            myClassCached = runCatching { DungeonUtils.currentDungeonPlayer }.getOrNull()?.let {
                if (it.clazz != DungeonClass.EMPTY) it.clazz else null
            }
            val ph = runCatching { DungeonUtils.getF7Phase() }.getOrNull()
            if (ph != null && ph != lastPhase) {
                if (verboseLog) SpLog.info("phase", "$lastPhase -> $ph (section=${GoldorTracker.section})")
                lastPhase = ph
            }
            if (!runCatching { DungeonUtils.inBoss }.getOrDefault(false)) {
                if (consumed.isNotEmpty()) resetFightState()
                leapedThisMenu = false
            }
            val sec = GoldorTracker.section
            if (sec != lastTrackerSection) {
                val prev = lastTrackerSection
                lastTrackerSection = sec
                if (prev in 1..4 && sec in 1..4) {
                    consumed.removeAll { it.stage.id.startsWith("P3.") }
                    timings.clear()
                    holdKey = null
                    waitKey = null
                }
            }
        }
    }

    override fun onDisable() {
        paused = false
        resetMenuState()
        resetFightState()
        super.onDisable()
    }

    private fun togglePause() {
        if (!enabled || !savepointEnabled) return
        paused = !paused
        SpLog.info("key", "pause toggled -> paused=$paused")
        modMessage("[Savepoint] ${if (paused) "paused" else "resumed"}")
    }

    private fun skipStep() {
        if (!enabled || !savepointEnabled || paused) return
        val armed = SavepointEngine.armedStep(activeStages(), consumed, buildRules(), myClassCached) ?: return
        consumed += armed
        holdKey = null
        waitKey = null
        SpLog.info("key", "skip ${armed.key}")
        modMessage("[Savepoint] skipped ${armed.key}")
    }

    private fun captureHere() {
        val err = Landmarks.captureHere(landmarkName)
        if (err == null) {
            modMessage("[LeapUtils] landmark '${landmarkName.trim()}' captured at your position")
            landmarkName = ""
        } else {
            modMessage("[LeapUtils] $err")
        }
    }

    private fun loadPresetAction() {
        val id = when (presetChoice) {
            PresetChoice.ADG -> "adg"
            PresetChoice.MM7G -> "mm7g"
            PresetChoice.CUSTOM -> {
                modMessage("[Savepoint] Custom is your own matrix; pick ADG or MM7G to load a preset")
                return
            }
        }
        val now = System.currentTimeMillis()
        if (now - confirmAt > 5000) {
            confirmAt = now
            modMessage("[Savepoint] press Load preset again within 5s to overwrite the matrix with the ${presetChoice.name} seed")
            return
        }
        confirmAt = 0
        val preset = SavepointPresets.load(id)
        if (preset == null) {
            modMessage("[Savepoint] preset '$id' could not be loaded")
            return
        }
        applyPreset(preset)
    }

    private fun applyPreset(preset: PresetFile) {
        var applied = 0
        var skipped = 0
        for (e in cellList) e.setting.setValue(this, cellProp, SpTgt.NONE)
        for ((key, classMap) in preset.cells ?: emptyMap()) {
            val stage = SpStage.entries.sortedByDescending { it.id.length }.firstOrNull { key == it.id || key.startsWith("${it.id}.") }
            if (stage == null) {
                skipped++
                continue
            }
            val step = if (key == stage.id) 0 else key.removePrefix("${stage.id}.").toIntOrNull() ?: 0
            val steps = if (step in 1..stage.steps) step..step else 1..stage.steps
            for ((clsName, tgtName) in classMap) {
                if (tgtName == "TODO_USER") {
                    skipped++
                    continue
                }
                val tgt = runCatching { SpTgt.valueOf(tgtName) }.getOrNull()
                if (tgt == null) {
                    skipped++
                    continue
                }
                val classes = if (clsName == "*") {
                    SavepointEngine.LEAP_CLASSES
                } else {
                    listOfNotNull(runCatching { DungeonClass.valueOf(clsName) }.getOrNull())
                }
                if (classes.isEmpty()) {
                    skipped++
                    continue
                }
                for (s in steps) {
                    for (c in classes) {
                        if (tgt != SpTgt.NEAREST && c.name == tgt.name) continue
                        setCell(stage, s, c, tgt)
                        applied++
                    }
                }
            }
        }
        val conds = (preset.conds ?: emptyMap()).mapValues { (_, v) -> SavepointPresets.toCond(v) }
        presetConds = conds
        SavepointPresets.saveConds(conds)
        SpLog.info("preset", "loaded ${preset.label ?: preset.id}: applied=$applied skipped=$skipped conds=${conds.entries.joinToString(", ") { "${it.key}=${it.value.describe()}" }}")
        modMessage("[Savepoint] loaded ${preset.label ?: preset.id}: $applied cells set, $skipped entries skipped, ${conds.size} conds")
    }

    private fun setCell(stage: SpStage, step: Int, clazz: DungeonClass, tgt: SpTgt) {
        if (clazz == DungeonClass.EMPTY) return
        if (tgt != SpTgt.NEAREST && tgt != SpTgt.NONE && tgt.name == clazz.name) return
        cellSettings["${stage.id}/$step ${clazz.name}"]?.setValue(this, cellProp, tgt)
        SpLog.info("cell", "${stage.id}/$step ${clazz.name} -> $tgt")
    }

    private fun DungeonClass.toTgt(): SpTgt = runCatching { SpTgt.valueOf(name) }.getOrDefault(SpTgt.NONE)

    private fun migrateIfNeeded() {
        if (legacyMigrated) {
            SpLog.info("migrate", "already migrated; skipping")
            return
        }
        legacyMigrated = true
        SpLog.info("migrate", "legacy: left=${maxorLeftClass}->${maxorLeftTo} right=${maxorRightClass}->${maxorRightTo} dropOff=$dropOffSpotter purple=$purpleSpotter soloCrush=$soloCrush s1=$section1Spotter s2=$section2Spotter s3=$section3Spotter core=$coreSpotter early=$earlyNecronSpotter goldorIf=$goldorIf goldorTo=$goldorTo")
        setCell(SpStage.P1_L, 1, maxorLeftClass, maxorLeftTo.toTgt())
        setCell(SpStage.P1_R, 1, maxorRightClass, maxorRightTo.toTgt())
        for (v in listOf(SpStage.P2_GY, SpStage.P2_OLDPY, SpStage.P2_NEWPY)) {
            for (c in SavepointEngine.LEAP_CLASSES) if (c != dropOffSpotter) setCell(v, 1, c, dropOffSpotter.toTgt())
            for (c in SavepointEngine.LEAP_CLASSES) if (c != purpleSpotter) setCell(v, 2, c, purpleSpotter.toTgt())
        }
        if (soloCrush) {
            for (c in SavepointEngine.LEAP_CLASSES) {
                if (c != section1Spotter) setCell(SpStage.P3_S1, 1, c, section1Spotter.toTgt())
                if (c != section2Spotter) setCell(SpStage.P3_S2, 1, c, section2Spotter.toTgt())
                if (c != section3Spotter) setCell(SpStage.P3_S3, 1, c, section3Spotter.toTgt())
                if (c != coreSpotter) setCell(SpStage.P3_S3, 2, c, coreSpotter.toTgt())
                if (c != earlyNecronSpotter) setCell(SpStage.P3_S4, 1, c, earlyNecronSpotter.toTgt())
            }
        } else {
            for (s in listOf(SpStage.P3_S1, SpStage.P3_S2, SpStage.P3_S3, SpStage.P3_S4)) {
                setCell(s, 1, goldorIf, goldorTo.toTgt())
            }
        }
        necronTargetMode = TargetMode.NEAREST
        necronLandmark = "necron_spot"
        for (c in SavepointEngine.LEAP_CLASSES) setCell(SpStage.P4, 1, c, SpTgt.NEAREST)
        modMessage("[Savepoint] migrated your old leap settings into the rule matrix")
    }

    private fun buildRules(): Map<SpCell, SpRule> {
        val rules = HashMap<SpCell, SpRule>()
        for (e in cellList) {
            val tgt = when (e.stage) {
                SpStage.P4 -> if (necronTargetMode == TargetMode.NEAREST) SpTgt.NEAREST else e.setting.getValue(this, cellProp)
                SpStage.P5 -> if (p5TargetMode == TargetMode.NEAREST) SpTgt.NEAREST else e.setting.getValue(this, cellProp)
                else -> e.setting.getValue(this, cellProp)
            }
            if (tgt == SpTgt.NONE) continue
            val cond = presetConds["${e.stage.id}/${e.step}"]
            val rule = when (tgt) {
                SpTgt.NEAREST -> {
                    val lm = if (e.stage == SpStage.P4) necronLandmark.trim() else p5Landmark.trim()
                    val r = if (e.stage == SpStage.P4) necronRadius.toDouble() else p5Radius.toDouble()
                    SpRule(tgt, lm, r, cond)
                }
                else -> SpRule(tgt, cond = cond)
            }
            rules[SpCell(e.stage, e.step, e.clazz)] = rule
        }
        return rules
    }

    private fun phase(): M7Phases = runCatching { DungeonUtils.getF7Phase() }.getOrDefault(M7Phases.Unknown)

    private fun activeStages(): List<SpStage> = when (phase()) {
        M7Phases.P1 -> if (maxor) listOf(SpStage.P1_L, SpStage.P1_R) else emptyList()
        M7Phases.P2 -> if (storm) {
            listOf(
                when (stormVariant) {
                    StormVariant.GY -> SpStage.P2_GY
                    StormVariant.OLD_PY -> SpStage.P2_OLDPY
                    StormVariant.NEW_PY -> SpStage.P2_NEWPY
                }
            )
        } else {
            emptyList()
        }
        M7Phases.P3 -> {
            if (!goldor) {
                emptyList()
            } else {
                when (GoldorTracker.section) {
                    1 -> if (goldorS1) listOf(SpStage.P3_S1) else emptyList()
                    2 -> if (goldorS2) listOf(SpStage.P3_S2) else emptyList()
                    3 -> if (goldorS3) listOf(SpStage.P3_S3) else emptyList()
                    4 -> if (goldorS4) listOf(SpStage.P3_S4) else emptyList()
                    else -> emptyList()
                }
            }
        }
        M7Phases.P4 -> if (necron) listOf(SpStage.P4) else emptyList()
        M7Phases.P5 -> if (witherKing) listOf(SpStage.P5) else emptyList()
        M7Phases.Unknown -> emptyList()
    }

    private fun floorOk(): Boolean {
        val f = runCatching { DungeonUtils.floor }.getOrNull() ?: return false
        return when (floorFilter) {
            FloorFilter.F7 -> f == Floor.F7
            FloorFilter.M7 -> f == Floor.M7
            FloorFilter.BOTH -> DungeonUtils.isFloor(7)
        }
    }

    private fun buildCtx(stages: List<SpStage>, screen: AbstractContainerScreen<*>): SpCtx {
        val p = Minecraft.getInstance().player
        val myName = runCatching { DungeonUtils.currentDungeonPlayer?.name }.getOrNull()
        val tmates = runCatching { DungeonUtils.leapTeammates }.getOrDefault(emptyList())
            .filter { myName == null || nameKey(it.name) != nameKey(myName) }
            .map { t ->
                val e = OdinAccess.entityOf(t)
                SpTmate(t.name, t.clazz, t.isDead, OdinAccess.isLeapAvailable(t, screen), e?.x, e?.y, e?.z)
            }
        return SpCtx(stages, consumed, tmates, myClassCached, p?.x, p?.y, p?.z, timings, Landmarks.all)
    }

    private fun updateTimings(plan: SpPlan, now: Long) {
        if (plan is SpPlan.Hold) {
            if (plan.key != holdKey) {
                holdKey = plan.key
                holdSince = now
            }
            timings[plan.key] = now - holdSince
        } else {
            holdKey = null
        }
        val wk = (plan as? SpPlan.Wait)?.step?.let { "wait:${it.key}" }
        if (wk != null) {
            if (wk != waitKey) {
                waitKey = wk
                waitSince = now
            }
            timings[wk] = now - waitSince
        } else {
            waitKey = null
        }
    }

    private fun executeLeap(plan: SpPlan.Leap, screen: AbstractContainerScreen<*>) {
        if (dryRun) {
            consumed += plan.step
            modMessage("[Savepoint] dry run: ${plan.label}")
            SpLog.info("leap", "DRY ${plan.label}")
            return
        }
        val dp = runCatching { DungeonUtils.leapTeammates }.getOrDefault(emptyList())
            .firstOrNull { nameKey(it.name) == nameKey(plan.target.name) }
        if (dp == null || !OdinAccess.autoLeapTo(dp, screen)) {
            warnMissingOnce("${plan.target.name} (${plan.target.clazz.name.lowercase()}) in the leap menu")
            SpLog.warn("leap", "FAILED ${plan.step.key} -> ${plan.target.name}(${plan.target.clazz}) not clickable in menu")
            return
        }
        leapedThisMenu = true
        consumed += plan.step
        val p = Minecraft.getInstance().player
        val e = OdinAccess.entityOf(dp)
        SpLog.info(
            "leap",
            "OK ${plan.step.key} -> ${plan.target.name}(${plan.target.clazz}) tgtPos=${e?.x},${e?.y},${e?.z} " +
                "myPos=${p?.x},${p?.y},${p?.z} phase=${phase()} section=${GoldorTracker.section} " +
                "menuAge=${System.currentTimeMillis() - menuOpenedAt}ms"
        )
        modMessage("[Savepoint] ${plan.label}")
    }
    // pls send me ur logs
    private fun logPlan(plan: SpPlan) {
        if (!verboseLog) return
        val key = when (plan) {
            is SpPlan.Leap -> "L:${plan.step.key}:${plan.target.name}"
            is SpPlan.Hold -> "H:${plan.key}:${plan.heldMs / 1000}"
            is SpPlan.Wait -> "W:${plan.step?.key ?: "-"}:${plan.reason}"
            is SpPlan.SkipStep -> "S:${plan.step.key}"
            SpPlan.Idle -> "I"
        }
        if (key == lastTraceKey) return
        lastTraceKey = key
        when (plan) {
            is SpPlan.Leap -> SpLog.info("plan", "leap ${plan.step.key} -> ${plan.target.name}(${plan.target.clazz}) tgtPos=${plan.target.x},${plan.target.y},${plan.target.z}")
            is SpPlan.Hold -> SpLog.info("plan", "hold ${plan.step.key} ${plan.heldMs}/${plan.needMs}ms ${plan.label}")
            is SpPlan.Wait -> SpLog.info("plan", "wait ${plan.step?.key ?: "-"} ${plan.reason}")
            is SpPlan.SkipStep -> SpLog.info("plan", "skip ${plan.step.key} ${plan.reason}")
            SpPlan.Idle -> {}
        }
    }

    private fun trace(plan: SpPlan) {
        val key = when (plan) {
            is SpPlan.Leap -> "L:${plan.step.key}:${plan.target.name}"
            is SpPlan.Hold -> "H:${plan.key}:${plan.heldMs}"
            is SpPlan.Wait -> "W:${plan.step?.key ?: "-"}:${plan.reason}"
            is SpPlan.SkipStep -> "S:${plan.step.key}"
            SpPlan.Idle -> "I"
        }
        if (key == lastTraceKey) return
        logPlan(plan)
        lastTraceKey = key
        when (plan) {
            is SpPlan.Hold -> modMessage("[Savepoint] holding ${plan.label} (${plan.heldMs}/${plan.needMs}ms)")
            is SpPlan.Wait -> modMessage("[Savepoint] waiting: ${plan.reason}")
            is SpPlan.SkipStep -> modMessage("[Savepoint] would skip ${plan.step.key}: ${plan.reason}")
            else -> {}
        }
    }

    private fun warnMissingOnce(msg: String) {
        if (warnedMissing) return
        warnedMissing = true
        modMessage("[Savepoint] waiting for $msg")
    }

    private fun statusLine(): String? {
        if (paused) return "\u00a7eSavepoint paused"
        if (!runCatching { DungeonUtils.inBoss }.getOrDefault(false)) return null
        if (!floorOk()) return null
        if (inLeapMenu) lastStatus?.let { return it }
        val stages = activeStages()
        if (stages.isEmpty()) return null
        val myClass = myClassCached ?: return null
        val rules = buildRules()
        val armed = SavepointEngine.armedStep(stages, consumed, rules, myClass) ?: return null
        val rule = rules[SpCell(armed.stage, armed.step, myClass)] ?: return null
        return "\u00a77Next: \u00a7f${rule.target.label} \u00a77(${armed.key})"
    }

    private fun shouldWaitForSchizoKeypress(screen: Screen): Boolean {
        val container = screen as? AbstractContainerScreen<*> ?: return false
        if (!enabled || !savepointEnabled || !schizoMode || leapedThisMenu || paused) return false
        if (!isLeapScreen(container)) return false
        if (!runCatching { DungeonUtils.inBoss }.getOrDefault(false)) return false
        if (!floorOk()) return false
        return SavepointEngine.armedStep(activeStages(), consumed, buildRules(), myClassCached) != null
    }

    private fun isLeapScreen(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string
        return title == "Spirit Leap" || title == "Teleport to Player" || title == "Obsidian"
        // to which ever motherfucker comes here and comments about this "Obsidian" was a chest i had renamed to see if it clicked properly
    }

    private fun nameKey(name: String): String = name.trim().lowercase()

    private fun resetMenuState() {
        if (inLeapMenu && verboseLog) SpLog.info("menu", "close leaped=$leapedThisMenu status=${lastStatus ?: "-"}")
        inLeapMenu = false
        menuOpenedAt = 0
        leapedThisMenu = false
        timings.clear()
        holdKey = null
        waitKey = null
        lastStatus = null
        lastTraceKey = ""
    }

    private fun resetFightState() {
        consumed.clear()
        timings.clear()
        holdKey = null
        waitKey = null
        lastTrackerSection = GoldorTracker.section
        lastStatus = null
    }
}
