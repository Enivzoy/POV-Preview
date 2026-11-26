package me.enivzoy.leaputils.pov

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import me.enivzoy.leaputils.pov.fastleap.FastLeapContext
import me.enivzoy.leaputils.pov.fastleap.FastLeapDefaults
import me.enivzoy.leaputils.pov.fastleap.FastLeapEvaluator
import me.enivzoy.leaputils.pov.fastleap.FastLeapResult
import me.enivzoy.leaputils.pov.fastleap.FastLeapRule
import me.enivzoy.leaputils.pov.fastleap.FastLeapState
import me.enivzoy.leaputils.pov.fastleap.FastLeapTriggerSource
import me.enivzoy.leaputils.pov.fastleap.ContainerCounter
import me.enivzoy.leaputils.pov.fastleap.LeapOrderCache
import me.enivzoy.leaputils.pov.fastleap.PositionCondition
import me.enivzoy.leaputils.pov.fastleap.PrefireState
import me.enivzoy.leaputils.pov.fastleap.RegionSpec
import me.enivzoy.leaputils.pov.fastleap.TargetSelectorType
import me.enivzoy.leaputils.pov.fastleap.TargetSpec
import me.enivzoy.leaputils.pov.fastleap.WarningThrottle
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FastLeap : Module(
    name = "Fast Leap",
    category = Category.custom("Leap Utils", 860, 220),
    description = "Leap on a left click with ordered, position aware rules"
) {
    enum class LeapMode { CLASS, NAME }
    enum class FallbackMode { NOTHING, NEAREST_TO, LAST_LEAPED }
    enum class FeedbackMode { ERRORS, VERBOSE, OFF }
    enum class LocationFilter { BOSS, CLEAR, BOTH }
    enum class FloorFilter { F7, M7, BOTH }

    private var rules: List<FastLeapRule> = FastLeapDefaults.pack()
    private var disabledIds = mutableSetOf<String>()
    private var firedThisScreen: Screen? = null
    private val warningThrottle = WarningThrottle()
    private var tickCounter = 0L
    private var levelIdentity: Any? = null
    private var lastLeapedName: String? = null
    private var hudLine = ""
    private var lastSoloWarnAt = 0L
    private var menuOpenedMs = 0L
    private var runIndex = 0
    private val leapOrderCache = LeapOrderCache()
    private var observedScreen: Screen? = null
    private var lastEmptyProbe = Int.MIN_VALUE
    private var prefireState: PrefireState? = null
    private var containerCounter = ContainerCounter()
    private val cooldownUntil = HashMap<String, Long>()
    private var dispatchTimes = ArrayDeque<Long>()

    private val closer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "leaputils-pov-fastleap-close").apply { isDaemon = true }
    }

    val fastLeapEnabled by BooleanSetting("Enabled", true, desc = "Master switch for Fast Leap")
    val floorFilter by SelectorSetting("Floors", FloorFilter.BOTH, desc = "Run on F7, M7 or both", listOf(FloorFilter.F7, FloorFilter.M7, FloorFilter.BOTH))
    val leapMode by SelectorSetting("Leap mode", LeapMode.CLASS, desc = "Pick the target by class or by name", listOf(LeapMode.CLASS, LeapMode.NAME))
    val ignorePhaseGates by BooleanSetting("Ignore phase gates", false, desc = "Evaluate every rule regardless of its phase or section gate")
    val locationFilter by SelectorSetting("Locations", LocationFilter.BOTH, desc = "Run in the boss fight, in clear, or both", listOf(LocationFilter.BOSS, LocationFilter.CLEAR, LocationFilter.BOTH))
    val respectCooldown by BooleanSetting("Respect cooldown", true, desc = "Skip a leap item that still has cooldown left")
    val autoSwap by BooleanSetting("Auto swap", true, desc = "Select a ready leap item from hotbar slots 0-7 when needed")
    val swapBack by BooleanSetting("Swap back", false, desc = "Return to the previous hotbar slot after the leap click is dispatched")
    val doorOpenerLeap by BooleanSetting("Door opener leap", false, desc = "Outside the boss, run the door opener rule when the opener is known")
    val disableAfterBloodOpen by BooleanSetting("Disable after Blood open", true, desc = "Stop door opener leaps once Blood is open")
    val fallbackMode by SelectorSetting("Fallback", FallbackMode.NOTHING, desc = "What to do when no rule matched", listOf(FallbackMode.NOTHING, FallbackMode.NEAREST_TO, FallbackMode.LAST_LEAPED))
    val fallbackLandmark by StringSetting("Fallback landmark", "", 32, "landmark", "Nearest to landmark, used by the Fallback setting")

    // guards, every default keeps the pre-setting behaviour
    val pauseOnGui by BooleanSetting("Pause when GUI open", true, desc = "Never leap while an unrelated screen is open")
    val pauseOnDead by BooleanSetting("Pause when dead", true, desc = "Never evaluate while you are dead or dying")
    val pauseOnSolo by BooleanSetting("Pause when solo", true, desc = "No leap with fewer than two party teammates, one throttled message")
    val ignoreSelf by BooleanSetting("Ignore self", true, desc = "Never offer yourself as a leap target")
    val caseInsensitive by BooleanSetting("Case-insensitive names", true, desc = "Match teammate, menu and rule names without case")
    val skipDead by BooleanSetting("Skip dead", true, desc = "Never offer a dead teammate as a target")
    val skipNotInMenu by BooleanSetting("Skip not in menu", true, desc = "Only dispatch to a teammate Odin can see in the open leap menu")
    val skipLastLeaped by BooleanSetting("Skip last leaped", false, desc = "Do not leap to the same teammate twice in a row")
    val blacklist by StringSetting("Blacklist", "", 200, "name, name", "Comma-separated names that are never leap targets")
    val whitelist by StringSetting("Whitelist", "", 200, "name, name", "When not empty only these names are leap targets")
    val collectAll by BooleanSetting("Collect all and pick best", false, desc = "Evaluate every matching rule and take the lowest measured distance")
    val defaultRadius by NumberSetting("Default radius", 6.0, 1.0..64.0, 0.5, desc = "Radius for rules without their own radius or mode")
    val defaultHorizontal by BooleanSetting("Horizontal only", false, desc = "Default distance check to XZ only; per-condition overrides still win")

    val clickTrigger by BooleanSetting("Left click", true, desc = "Leap when left clicking while holding a spirit leap item")
    val onlyHoldingLeap by BooleanSetting("Only while holding leap", true, desc = "The click trigger only fires with a spirit leap item in hand")
    val requireSneaking by BooleanSetting("Require sneak", false, desc = "Only trigger while you are sneaking")
    val cancelOnNoMatch by BooleanSetting("Cancel on no-match", false, desc = "Swallow the click even when no rule matched")
    val debounceMs by NumberSetting("Trigger debounce ms", 250, 0..2000, 10, desc = "Duplicate trigger guard; it never delays the first accepted request")
    val maxLeapsPerSecond by NumberSetting("Max leaps per second", 2, 1..10, 1, desc = "Safety cap on dispatched leaps")
    val openTrigger by BooleanSetting("On menu open", false, desc = "Evaluate once when a spirit leap menu opens")
    val openDelayMs by NumberSetting("Open delay ms", 0, 0..150, 5, desc = "Lower bound wait before reading the menu; slot readiness is still checked")

    val closeAfterDispatch by BooleanSetting("Close GUI After Teammate Click Dispatch", true, desc = "Close the menu as soon as the teammate click is dispatched, never waiting for the leap to land")
    val closeDelayMs by NumberSetting("GUI Close Delay", 1, 0..100, 1, desc = "Delay from the teammate click dispatch to the local close, in milliseconds")

    val targetHud by HUD("Fast Leap Target", desc = "Last matched Fast Leap target", x = 491, y = 493, scale = 1f) { example ->
        val line = if (example) "\u00a77Leap: \u00a7fHealer \u00a77(rule p1)" else hudLine
        if (line.isEmpty()) return@HUD 0 to 0
        val width = textDim(line, 0, 0, Colors.WHITE).first
        text(line, 0, 0, Colors.WHITE)
        width to 9
    }
    val soundFeedback by BooleanSetting("Sound", false, desc = "Play a short click when the teammate click is dispatched")

    val geckoKeybind by KeybindSetting("Keybind", -1, desc = "Run the same evaluation from a key").onPress { attempt(FastLeapTriggerSource.KEYBIND) }
    val goNow by ActionSetting("Leap now", "Evaluate the rule list once and leap if something matches") { attempt(FastLeapTriggerSource.COMMAND) }
    val reloadRules by ActionSetting("Reload rules", "Re-read config/leaputils/fastleap.json over the built in pack") { reload() }
    val listRules by ActionSetting("List rules", "Print the ordered rule list to chat") { printRules() }

    val feedback by SelectorSetting("Feedback", FeedbackMode.ERRORS, desc = "How much Fast Leap says in chat", listOf(FeedbackMode.ERRORS, FeedbackMode.VERBOSE, FeedbackMode.OFF))
    val dryRun by BooleanSetting("Dry run", false, desc = "Log the decision without clicking")
    val profiling by BooleanSetting("Profile timings", false, desc = "Write t0 to t6 timings per request to savepoint.log")

    // experimental, every one off: nothing here changes behaviour while off
    val saveLeapOrdering by BooleanSetting(
        "Save leap ordering log [experimental]",
        false,
        desc = "Write the observed Spirit Leap slot order to leaporder.log, local only",
    )
    val leapOrderingDiagnostics by BooleanSetting(
        "Leap ordering diagnostics [experimental]",
        false,
        desc = "Log observed uuid, class and slot per menu to savepoint.log",
    )
    val useContainerPredictor by BooleanSetting(
        "Use local container id predictor [experimental]",
        false,
        desc = "Shadow only: track the next container id for diagnostics, never sends a packet",
    )
    val experimentalPrefire by BooleanSetting(
        "Experimental leap prefire",
        false,
        desc = "Inert: the packet prefire path is not implemented, this only gates the state machine",
    )
    val prefireDelayTicks by NumberSetting(
        "Prefire delay (client ticks)",
        1,
        1..10,
        1,
        desc = "Ticks between the item use and the speculative click, when prefire exists",
    )
    val requireVerifiedSlotOrder by BooleanSetting(
        "Require verified slot order [experimental]",
        true,
        desc = "Hard gate: never prefire without a confirmed uuid to slot mapping",
    )
    val prefireFallbackToMenu by BooleanSetting(
        "Prefire fallback to normal menu [experimental]",
        true,
        desc = "On any mismatch use the normal menu path instead of a guessed click",
    )
    val prefireDiagnostics by BooleanSetting(
        "Prefire diagnostics [experimental]",
        false,
        desc = "Log prefire state transitions and latencies to savepoint.log",
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils")

    init {
        targetHud.enabled = false
        on<ScreenEvent.Open> { onScreenOpen(screen) }
        ClientTickEvents.END_CLIENT_TICK.register { onTick() }
    }

    override fun onEnable() {
        super.onEnable()
        reload()
    }

    override fun onDisable() {
        FastLeapState.reset()
        firedThisScreen = null
        cooldownUntil.clear()
        dispatchTimes = ArrayDeque()
        warningThrottle.clear()
        lastLeapedName = null
        hudLine = ""
        runIndex = 0
        leapOrderCache.invalidate()
        containerCounter.reset()
        prefireState = null
        super.onDisable()
    }

    fun reload() {
        Landmarks.load()
        val loaded = runCatching {
            val f = dir.resolve("fastleap.json")
            if (!Files.exists(f)) return@runCatching null
            gson.fromJson(Files.readString(f), FastLeapConfig::class.java)
        }.getOrNull()
        rules = loaded?.toRules()?.takeIf { it.isNotEmpty() } ?: FastLeapDefaults.pack()
        disabledIds = loaded?.disabled?.toMutableSet() ?: mutableSetOf()
        SpLog.info("fastleap", "rules=${rules.size} disabled=$disabledIds")
    }

    private fun enabledRules(): List<FastLeapRule> =
        rules.map { if (it.id in disabledIds) it.copy(enabled = false) else it }

    fun printRules() {
        val text = enabledRules().joinToString("\n") { "${if (it.enabled) "on " else "off"} ${it.id} (${it.priority}) ${it.name}" }
        modMessage("[FastLeap] ${rules.size} rules:\n$text")
    }

    fun toggleRule(id: String): String? {
        if (rules.none { it.id == id }) return "no rule '$id'"
        if (id in disabledIds) disabledIds.remove(id) else disabledIds.add(id)
        SpLog.info("fastleap", "toggle $id -> ${if (id in disabledIds) "off" else "on"}")
        return null
    }

    fun testRule(id: String?) {
        val ctx = buildContext(FastLeapTriggerSource.COMMAND) ?: run {
            modMessage("[FastLeap] not in a dungeon right now")
            return
        }
        when (val res = FastLeapEvaluator.evaluate(enabledRules(), ctx, id)) {
            is FastLeapResult.Plan -> modMessage(
                "[FastLeap] distance=${res.measuredDistance?.let { "%.1f".format(it) } ?: "-"}; " +
                    "allowed=${ctx.defaultErrorMin}..${ctx.defaultErrorMax}; axes=${res.axes}; " +
                    "target=${res.teammate.name} (${res.teammate.clazz.name.lowercase()}); rule=${res.rule.id}; source=${res.positionSource}"
            )
            is FastLeapResult.NoMatch -> modMessage("[FastLeap] no match: ${res.failures.joinToString("; ") { "${it.ruleId}: ${it.reason}" }}")
        }
    }

    fun goNowValue() {
        attempt(FastLeapTriggerSource.COMMAND)
    }

    fun goRule(id: String) {
        attempt(FastLeapTriggerSource.COMMAND, id)
    }

    // true when the click was consumed by fastleap
    fun onLeftClick(skyblockId: String?): Boolean {
        if (!enabled || !fastLeapEnabled || !clickTrigger) return false
        if (onlyHoldingLeap && (skyblockId == null || skyblockId !in FastLeapDefaults.leapItemIds)) return false
        if (requireSneaking && mc.player?.isShiftKeyDown != true) return false
        return when (attempt(FastLeapTriggerSource.CLICK)) {
            Attempt.Matched -> true
            Attempt.NoMatch -> cancelOnNoMatch
            Attempt.Blocked -> false
        }
    }

    private enum class Attempt { Matched, NoMatch, Blocked }

    private fun onScreenOpen(screen: Screen) {
        val container = screen as? AbstractContainerScreen<*> ?: return
        if (!isLeapScreen(container)) return
        if (leapOrderingDiagnostics || saveLeapOrdering) recordLeapOrder(container)
        if (useContainerPredictor) {
            containerCounter.observe(container.menu.containerId)
            SpLog.info("fastleap", "container=${container.menu.containerId} next=${containerCounter.predict() ?: "-"}")
        }
        prefireState?.let { st ->
            val res = st.onMenuOpen(container.menu.containerId, FastLeapState.generation, System.nanoTime())
            if (prefireDiagnostics) SpLog.info("prefire", "menu open reconcile=$res phase=${st.phase} failure=${st.failure ?: "-"}")
        }
        if (firedThisScreen === screen) return
        firedThisScreen = screen

        val pending = FastLeapState.request
        if (pending != null && !FastLeapState.isStale(pending.generation)) {
            pending.timing.t4 = System.nanoTime()
            // the real menu arrived, that is the observed point the prefire line reports
            if (pending.timing.l2 == null) pending.timing.l2 = pending.timing.t4
            menuOpenedMs = System.currentTimeMillis()
            tryExecute(container, pending.id)
            return
        }
        if (!enabled || !fastLeapEnabled || !openTrigger) return

        val t4 = System.nanoTime()
        val ctx = buildContext(FastLeapTriggerSource.MENU_OPEN) ?: return
        when (val res = FastLeapEvaluator.evaluate(enabledRules(), ctx)) {
            is FastLeapResult.Plan -> {
                val req = FastLeapState.createRequest(
                    ruleId = res.rule.id,
                    targetName = res.teammate.name,
                    itemId = "",
                    swapped = false,
                    source = FastLeapTriggerSource.MENU_OPEN,
                    nowMs = System.currentTimeMillis(),
                    nowTick = tickCounter,
                    measuredDistance = res.measuredDistance,
                    axes = res.axes,
                    t0 = t4,
                )
                req.timing.t1 = t4
                req.timing.t2 = t4
                req.timing.t4 = t4
                menuOpenedMs = System.currentTimeMillis()
                FastLeapState.markHandled(System.currentTimeMillis())
                tryExecute(container, req.id)
            }
            is FastLeapResult.NoMatch -> reportNoMatch(res)
        }
    }

    private fun onTick() {
        tickCounter++
        val level = mc.level
        if (level !== levelIdentity) {
            levelIdentity = level
            cooldownUntil.clear()
            dispatchTimes = ArrayDeque()
            FastLeapState.bumpGeneration()
            firedThisScreen = null
            // a new world is a new dungeon run: no slot or counter from the old one may survive
            runIndex++
            leapOrderCache.invalidate()
            containerCounter.reset()
            prefireState?.onWorldChange()
            observedScreen = null
            lastEmptyProbe = Int.MIN_VALUE
        }
        val container = runCatching { mc.gui.screen() }.getOrNull() as? AbstractContainerScreen<*>
        if (container == null || !isLeapScreen(container)) {
            FastLeapState.request?.let { req ->
                if (FastLeapState.expired(tickCounter)) finish(req, "timeout without a leap menu")
            }
            runScheduledClose()
            return
        }
        // contents can arrive ticks after the screen, so the tick also probes the real menu
        if ((leapOrderingDiagnostics || saveLeapOrdering) && observedScreen !== container) recordLeapOrder(container)
        val req = FastLeapState.request
        if (req != null && !FastLeapState.isStale(req.generation)) {
            if (req.timing.t4 == null) {
                req.timing.t4 = System.nanoTime()
                req.timing.l2 = req.timing.l2 ?: req.timing.t4
                menuOpenedMs = System.currentTimeMillis()
            }
            tryExecute(container, req.id)
        }
        runScheduledClose()
    }

    private fun attempt(source: FastLeapTriggerSource, onlyRuleId: String? = null): Attempt {
        val t0 = System.nanoTime()
        val nowMs = System.currentTimeMillis()
        if (FastLeapState.debounced(nowMs, debounceMs.toLong())) return Attempt.Blocked
        if (FastLeapState.request != null) return Attempt.Blocked

        val screen = runCatching { mc.gui.screen() }.getOrNull() as? AbstractContainerScreen<*>
        if (screen != null && !isLeapScreen(screen)) return Attempt.Blocked

        val ctx = buildContext(source) ?: return Attempt.Blocked
        val t1 = System.nanoTime()
        if (experimentalPrefire) tryPrefire(ctx)
        val applyFallback = fallbackMode != FallbackMode.NOTHING && onlyRuleId == null
        val res = FastLeapEvaluator.evaluate(enabledRules(), ctx, onlyRuleId, applyFallback)
        val t2 = System.nanoTime()
        return when (res) {
            is FastLeapResult.Plan ->
                if (screen != null) useOpenMenu(res, screen, source, nowMs, t0, t1, t2)
                else openMenuAndLeap(res, source, nowMs, t0, t1, t2)
            is FastLeapResult.NoMatch -> {
                reportNoMatch(res)
                Attempt.NoMatch
            }
        }
    }

    private fun useOpenMenu(
        res: FastLeapResult.Plan,
        screen: AbstractContainerScreen<*>,
        source: FastLeapTriggerSource,
        nowMs: Long,
        t0: Long,
        t1: Long,
        t2: Long,
    ): Attempt {
        val req = FastLeapState.createRequest(
            ruleId = res.rule.id,
            targetName = res.teammate.name,
            itemId = "",
            swapped = false,
            source = source,
            nowMs = nowMs,
            nowTick = tickCounter,
            measuredDistance = res.measuredDistance,
            axes = res.axes,
            t0 = t0,
        )
        req.timing.t1 = t1
        req.timing.t2 = t2
        req.timing.t4 = System.nanoTime()
        // menu was already open, so the observation point is this screen read
        req.timing.l2 = req.timing.t4
        menuOpenedMs = nowMs
        FastLeapState.markHandled(nowMs)
        dispatch(screen, req.id)
        return Attempt.Matched
    }

    private fun openMenuAndLeap(
        res: FastLeapResult.Plan,
        source: FastLeapTriggerSource,
        nowMs: Long,
        t0: Long,
        t1: Long,
        t2: Long,
    ): Attempt {
        val player = mc.player ?: return Attempt.Blocked
        val inv = player.inventory
        val heldSlot = inv.selectedSlot
        val heldId = runCatching { inv.getItem(heldSlot).itemId }.getOrNull()
        val hotbar = (0..7).mapNotNull { slot ->
            val id = runCatching { inv.getItem(slot).itemId }.getOrNull() ?: return@mapNotNull null
            FastLeapState.HeldItem(slot, id)
        }
        val choice = FastLeapState.chooseItem(
            held = heldId?.let { FastLeapState.HeldItem(heldSlot, it) },
            hotbar = hotbar,
            cooldownOf = { remainingCooldown(it) },
            leapIds = FastLeapDefaults.leapItemIds,
            respectCooldown = respectCooldown,
            autoSwap = autoSwap,
        ) ?: run {
            report("no ready spirit leap item in hotbar slots 0-7")
            return Attempt.Blocked
        }

        if (dryRun) {
            modMessage("[FastLeap] dry run: ${res.rule.id} -> ${res.teammate.name} (${res.teammate.clazz.name.lowercase()})")
            return Attempt.Matched
        }

        val req = FastLeapState.createRequest(
            ruleId = res.rule.id,
            targetName = res.teammate.name,
            itemId = choice.id,
            swapped = choice.swap,
            source = source,
            nowMs = nowMs,
            nowTick = tickCounter,
            measuredDistance = res.measuredDistance,
            axes = res.axes,
            t0 = t0,
            restoreSlot = if (choice.swap) heldSlot else null,
        )
        req.timing.t1 = t1
        req.timing.t2 = t2
        FastLeapState.markHandled(nowMs)

        if (choice.swap) {
            inv.selectedSlot = choice.slot
            mc.connection?.send(ServerboundSetCarriedItemPacket(choice.slot))
        }
        if (profiling) SpLog.info("fastleap", "request=${req.id} rule=${req.ruleId} target=${req.targetName} item=${req.itemId} swapped=${req.swapped}")
        // normal client interaction, the server opens the real menu
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        req.timing.t3 = System.nanoTime()
        return Attempt.Matched
    }

    private fun dispatch(screen: AbstractContainerScreen<*>, requestId: Long): Boolean {
        val req = FastLeapState.request ?: return false
        if (req.id != requestId) return false
        val dp = resolveTarget(req.targetName) ?: run {
            finish(req, "target ${req.targetName} is gone")
            return false
        }
        if (FastLeapState.isDispatched()) return false
        if (!FastLeapState.canExecute(dp.name, req.generation, true)) return false
        // the menu is only source of truth once it is open, not-in-menu is the evaluator's gate
        if (!OdinAccess.isLeapAvailable(dp, screen)) return false
        if (!withinLeapBudget(System.currentTimeMillis())) {
            report("max leaps per second reached")
            return false
        }

        req.timing.t5 = System.nanoTime()
        if (!OdinAccess.autoLeapTo(dp, screen)) {
            SpLog.warn("fastleap", "autoLeapTo refused ${req.targetName}; menu left open")
            finish(req, "dispatch refused")
            return false
        }
        // the teammate click was issued, timings and close start from this exact point
        val dispatchedNs = System.nanoTime()
        req.timing.t6 = dispatchedNs
        FastLeapState.markDispatched(dispatchedNs)
        // mirror the prefire marks so one log line can compare both paths
        req.timing.l0 = req.timing.t3 ?: dispatchedNs
        req.timing.l1 = dispatchedNs
        dispatchTimes.addLast(System.currentTimeMillis())
        markCooldown(req.itemId)
        lastLeapedName = req.targetName
        if (swapBack) req.restoreSlot?.let { slot -> restoreHotbarSlot(slot) }
        if (profiling) SpLog.info("fastleap", "dispatch request=${req.id} target=${req.targetName}")
        if (soundFeedback) playClick()
        updateHud(req)
        if (closeAfterDispatch) {
            FastLeapState.scheduleClose(dispatchedNs, closeDelayMs.toLong(), screen)
            scheduleClose(screen, req.generation)
        } else {
            if (feedback == FeedbackMode.VERBOSE) modMessage("[FastLeap] ${req.ruleId} -> ${req.targetName}")
            reportSummary(req)
            FastLeapState.clear()
        }
        return true
    }

    private fun tryExecute(screen: AbstractContainerScreen<*>, requestId: Long) {
        val req = FastLeapState.request ?: return
        if (req.id != requestId || FastLeapState.isDispatched()) return
        val delay = openDelayMs.toLong()
        if (delay > 0 && System.currentTimeMillis() - menuOpenedMs < delay) return
        val dp = resolveTarget(req.targetName) ?: return
        // only click once odin sees that teammate head in the real menu
        if (!OdinAccess.isLeapAvailable(dp, screen)) return
        dispatch(screen, requestId)
    }

    // the timer measures the delay off thread, the screen mutation is enqueued on the client thread
    private fun scheduleClose(screen: AbstractContainerScreen<*>, generation: Long) {
        closer.schedule({
            runCatching { mc.execute { closeIfCurrent(screen, generation) } }
        }, closeDelayMs.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun closeIfCurrent(screen: AbstractContainerScreen<*>, generation: Long) {
        if (FastLeapState.isStale(generation)) return
        val token = FastLeapState.closeToken ?: return
        if (token.generation != generation) return
        val current = runCatching { mc.gui.screen() }.getOrNull()
        if (current !== screen) {
            FastLeapState.consumeClose()
            FastLeapState.clear()
            return
        }
        mc.gui.setScreen(null)
        FastLeapState.consumeClose(System.nanoTime())
        FastLeapState.request?.let { reportSummary(it) }
        if (FastLeapState.closeToken == null) FastLeapState.clear()
    }

    private fun runScheduledClose() {
        val token = FastLeapState.closeDue(System.nanoTime()) ?: return
        if (token.generation != FastLeapState.generation) {
            FastLeapState.consumeClose()
            return
        }
        val current = runCatching { mc.gui.screen() }.getOrNull()
        // only ever close the originating screen instance
        if (current === token.screen) mc.gui.setScreen(null)
        FastLeapState.consumeClose(System.nanoTime())
        FastLeapState.request?.let { reportSummary(it) }
        if (FastLeapState.closeToken == null) FastLeapState.clear()
    }

    private fun reportSummary(req: FastLeapState.Request) {
        if (!profiling) return
        SpLog.info("fastleap", FastLeapState.summary(req))
    }

    private fun updateHud(req: FastLeapState.Request) {
        hudLine = "\u00a77Leap: \u00a7f${req.targetName} \u00a77(rule ${req.ruleId})"
    }

    private fun playClick() {
        runCatching {
            val level = mc.level ?: return
            level.playLocalSound(
                mc.player?.x ?: 0.0,
                mc.player?.y ?: 0.0,
                mc.player?.z ?: 0.0,
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(),
                net.minecraft.sounds.SoundSource.PLAYERS,
                0.5f,
                1.6f,
                false,
            )
        }
    }

    private fun finish(req: FastLeapState.Request, reason: String) {
        req.timing.failure = reason
        if (profiling) SpLog.info("fastleap", FastLeapState.summary(req))
        FastLeapState.clear(reason)
    }

    // debounce is only a duplicate guard, the first accepted trigger is never delayed
    // prefire is inert on purpose: the click packet needs a state id that 26.3 never exposes
    // before the menu contents arrive, so the gate aborts and the normal path runs instead
    private fun tryPrefire(ctx: FastLeapContext) {
        // prefire is only ever allowed with the normal menu path kept as its fallback
        if (!prefireFallbackToMenu) {
            if (prefireDiagnostics) SpLog.info("prefire", "skipped: fallback to the normal menu is disabled")
            return
        }
        val st = prefireState ?: PrefireState(prefireDelayTicks).also { prefireState = it }
        if (st.inFlight) {
            if (prefireDiagnostics) SpLog.info("prefire", "skipped: request ${st.prepared?.requestId} still in flight")
            return
        }
        st.reset()
        val res = FastLeapEvaluator.evaluate(enabledRules(), ctx)
        val plan = res as? FastLeapResult.Plan ?: return
        val uuid = runCatching { OdinAccess.entityOf(resolveTarget(plan.teammate.name) ?: return)?.uuid?.toString() }.getOrNull()
        val slot = uuid?.let { leapOrderCache.slotFor(it) }
        // the slot gate cannot be weakened by a setting: an unvalidated order never predicts
        val orderingVerified = leapOrderCache.validated() && (requireVerifiedSlotOrder || slot != null)
        // no verified state id exists client side, so prepare always aborts here
        val prepared = st.prepare(
            generation = FastLeapState.generation,
            targetUuid = uuid,
            containerId = containerCounter.predict(),
            stateId = null,
            slot = slot,
            orderingVerified = orderingVerified,
            nowTick = tickCounter,
        )
        if (prefireDiagnostics || profiling) {
            val why = prepared?.let { "armed container=${it.containerId} slot=${it.slot}" } ?: "aborted: ${st.failure}"
            SpLog.info("prefire", "$why (falling back to the normal menu path)")
            // same shape as the normal path so the two lines can be compared side by side
            SpLog.info("prefire", st.latency.summary())
        }
        // a prepared request would only arm the state machine, never send a packet
        if (prepared == null) st.reset()
    }

    private fun withinLeapBudget(nowMs: Long): Boolean {
        val cap = maxLeapsPerSecond.coerceAtLeast(1)
        while (dispatchTimes.isNotEmpty() && nowMs - dispatchTimes.first() > 1000L) dispatchTimes.removeFirst()
        return dispatchTimes.size < cap
    }

    // swap back is optional and best effort: the click already went out, so a failure here is harmless
    private fun restoreHotbarSlot(slot: Int) {
        if (slot !in 0..8) return
        runCatching {
            mc.player?.inventory?.selectedSlot = slot
            mc.connection?.send(ServerboundSetCarriedItemPacket(slot))
        }
    }

    private fun resolveTarget(name: String): DungeonPlayer? {
        val key = FastLeapEvaluator.nameKey(name)
        return runCatching {
            DungeonUtils.leapTeammates.firstOrNull { FastLeapEvaluator.nameKey(it.name) == key && !it.isDead }
        }.getOrNull()
    }

    private fun remainingCooldown(id: String): Long {
        val until = cooldownUntil[id] ?: return 0L
        return (until - tickCounter).coerceAtLeast(0L)
    }

    private fun markCooldown(id: String) {
        val base = FastLeapDefaults.cooldownTicks[id] ?: return
        val ticks = Math.ceil(base * mageCooldownMultiplier()).toLong()
        cooldownUntil[id] = tickCounter + ticks
    }

    // mage halves leap cooldown, read the class from odin and fall back to no scaling
    private fun mageCooldownMultiplier(): Double = runCatching {
        if (DungeonUtils.currentDungeonPlayer.clazz == DungeonClass.MAGE) 0.5 else 1.0
    }.getOrDefault(1.0)

    private fun buildContext(source: FastLeapTriggerSource): FastLeapContext? {
        val player = mc.player ?: return null
        if (pauseOnDead && player.isDeadOrDying) return null
        if (!runCatching { DungeonUtils.inDungeons }.getOrDefault(false)) return null
        val inBoss = runCatching { DungeonUtils.inBoss }.getOrDefault(false)
        if (locationFilter == LocationFilter.BOSS && !inBoss) return null
        if (locationFilter == LocationFilter.CLEAR && inBoss) return null
        if (!floorOk()) return null

        Landmarks.load()
        val screen = runCatching { mc.gui.screen() }.getOrNull() as? AbstractContainerScreen<*>
        val leapScreen = screen?.takeIf { isLeapScreen(it) }
        if (pauseOnGui && screen != null && leapScreen == null) return null
        val myName = runCatching { DungeonUtils.currentDungeonPlayer.name }.getOrNull()
        val roster = runCatching { DungeonUtils.leapTeammates }.getOrDefault(emptyList())
        val teammates = roster
            .filter { myName == null || FastLeapEvaluator.nameKey(it.name) != FastLeapEvaluator.nameKey(myName) }
            .map { dp ->
                val e = runCatching { OdinAccess.entityOf(dp) }.getOrNull()
                val inMenu = leapScreen != null && OdinAccess.isLeapAvailable(dp, leapScreen)
                SpTmate(dp.name, dp.clazz, dp.isDead, inMenu, e?.x, e?.y, e?.z)
            }
        if (pauseOnSolo && teammates.size < 2) {
            warnSolo(teammates.size)
            return null
        }
        val myClass = runCatching { DungeonUtils.currentDungeonPlayer.clazz }.getOrNull()
            ?.takeIf { it != DungeonClass.EMPTY }
        val chatPhase = runCatching {
            when (DungeonUtils.getF7Phase()) {
                M7Phases.P1 -> 1
                M7Phases.P2 -> 2
                M7Phases.P3 -> 3
                M7Phases.P4 -> 4
                M7Phases.P5 -> 5
                else -> 0
            }
        }.getOrDefault(0)
        return FastLeapContext(
            trigger = source,
            teammates = teammates,
            myClass = myClass,
            myName = myName,
            myX = player.x,
            myY = player.y,
            myZ = player.z,
            landmarks = Landmarks.all.toMap(),
            inBoss = inBoss,
            phase = chatPhase,
            section = GoldorTracker.section,
            doorOpener = if (!inBoss && doorOpenerLeap && !(disableAfterBloodOpen && runCatching { DungeonUtils.bloodDone }.getOrDefault(false))) OdinAccess.findClosestToWitherDoor()?.name else null,
            ignorePhaseGates = ignorePhaseGates,
            preferredClass = if (leapMode == LeapMode.CLASS) myClass else null,
            preferredName = if (leapMode == LeapMode.NAME) myName else null,
            fallbackNearestLandmark = if (fallbackMode == FallbackMode.NEAREST_TO) fallbackLandmark else null,
            defaultErrorMin = Savepoint.errorMinDistance,
            defaultErrorMax = Savepoint.errorMaxDistance,
            defaultRadius = defaultRadius,
            defaultHorizontal = defaultHorizontal,
            ignoreSelf = ignoreSelf,
            caseInsensitive = caseInsensitive,
            skipDead = skipDead,
            skipNotInMenu = skipNotInMenu,
            blacklist = parseNames(blacklist),
            whitelist = parseNames(whitelist),
            skipLastLeaped = skipLastLeaped,
            lastLeapedName = lastLeapedName,
            collectAll = collectAll,
            fallbackLastLeaped = fallbackMode == FallbackMode.LAST_LEAPED,
            menuOpen = leapScreen != null,
        )
    }

    private fun floorOk(): Boolean = runCatching {
        when (floorFilter) {
            FloorFilter.F7 -> DungeonUtils.floor == Floor.F7
            FloorFilter.M7 -> DungeonUtils.floor == Floor.M7
            FloorFilter.BOTH -> DungeonUtils.isFloor(7)
        }
    }.getOrDefault(false)

    private fun parseNames(raw: String): Set<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // learn the real menu order once per run, from the container itself, not from a name guess
    // the run id belongs to the dungeon run, so every menu in it keeps the same record
    private fun recordLeapOrder(screen: AbstractContainerScreen<*>) {
        // the same screen is observed once; contents arriving later only fill the first attempt
        if (observedScreen === screen) return
        val roster = runCatching { DungeonUtils.leapTeammates }.getOrDefault(emptyList())
        val uuids = roster.mapNotNull { dp -> runCatching { OdinAccess.entityOf(dp)?.uuid?.toString() }.getOrNull() }
        // no roster means no identity to anchor an observation to, so nothing is recorded
        if (uuids.isEmpty()) return
        leapOrderCache.beginRun(runIndex.coerceAtLeast(1), uuids)
        val classOf = { name: String ->
            roster.firstOrNull { it.name.equals(name, ignoreCase = true) }?.clazz?.name
        }
        val entries = LeapOrderProbe.scan(screen, classOf)
        if (entries.isEmpty()) {
            // contents can land a tick after the screen, so this stays retryable and quiet
            if (leapOrderingDiagnostics && lastEmptyProbe != screen.menu.containerId) {
                lastEmptyProbe = screen.menu.containerId
                SpLog.info("leaporder", "menu ${screen.menu.containerId}: no profile heads yet")
            }
            return
        }
        observedScreen = screen
        if (leapOrderingDiagnostics) {
            SpLog.info("leaporder", "menu ${screen.menu.containerId}: " + entries.joinToString(", ") { "${it.uuid}@${it.slot}/${it.clazz}" })
        }
        if (!saveLeapOrdering) {
            leapOrderCache.observe(entries)
            return
        }
        LeapOrderProbe.write(leapOrderCache, entries)
    }

    // one message per solo stretch, not one per click
    private fun warnSolo(count: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSoloWarnAt < SOLO_WARN_MS) return
        lastSoloWarnAt = now
        report("paused: solo or only $count teammate")
    }

    private fun reportNoMatch(res: FastLeapResult.NoMatch) {
        if (feedback != FeedbackMode.VERBOSE) return
        val msg = res.failures.take(3).joinToString("; ") { "${it.ruleId}: ${it.reason}" }
        report("no rule matched ($msg)")
    }

    private fun report(msg: String) {
        if (feedback == FeedbackMode.OFF) return
        if (!warningThrottle.allow(msg, System.currentTimeMillis())) return
        modMessage("[FastLeap] $msg")
    }

    private fun isLeapScreen(screen: AbstractContainerScreen<*>): Boolean {
        val title = runCatching { screen.title.string }.getOrNull() ?: return false
        return title == "Spirit Leap" || title == "Teleport to Player" || title == "Obsidian"
    }

    fun rules(): List<FastLeapRule> = rules

    fun regions(): List<Pair<String, RegionSpec>> = FastLeapDefaults.regions

    private const val SOLO_WARN_MS = 5_000L

    private class FastLeapConfig(
        val disabled: List<String>? = null,
        val rules: List<FastLeapRuleJson>? = null,
    ) {
        fun toRules(): List<FastLeapRule>? = rules?.mapNotNull { it.toRule() }
    }

    private class FastLeapRuleJson(
        val id: String? = null,
        val name: String? = null,
        val enabled: Boolean? = null,
        val priority: Int? = null,
        val phase: Int? = null,
        val phaseAt: Int? = null,
        val phaseAnyOf: List<Int>? = null,
        val stage: Int? = null,
        val section: Int? = null,
        val inBoss: Boolean? = null,
        val triggers: List<String>? = null,
        val targetType: String? = null,
        val targetClass: String? = null,
        val targetName: String? = null,
        val landmark: String? = null,
        val radius: Double? = null,
        val selfRegion: String? = null,
        val excludeRegions: List<String>? = null,
        val conditions: List<CondJson>? = null,
    ) {
        fun toRule(): FastLeapRule? {
            val ruleId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val type = targetType?.let { runCatching { TargetSelectorType.valueOf(it.uppercase()) }.getOrNull() }
                ?: TargetSelectorType.FIRST_IN_MENU
            return FastLeapRule(
                id = ruleId,
                name = name ?: ruleId,
                enabled = enabled ?: true,
                priority = priority ?: 100,
                allowedTriggers = triggers?.mapNotNull { t -> runCatching { FastLeapTriggerSource.valueOf(t.uppercase()) }.getOrNull() }?.toSet()
                    ?: FastLeapTriggerSource.entries.toSet(),
                inBoss = inBoss,
                phase = phase,
                phaseAnyOf = phaseAnyOf?.toSet() ?: emptySet(),
                phaseAt = phaseAt,
                stage = stage,
                section = section,
                target = TargetSpec(
                    type = type,
                    targetClass = targetClass?.let { runCatching { DungeonClass.valueOf(it.uppercase()) }.getOrNull() },
                    targetName = targetName,
                    landmarkName = landmark,
                    radius = radius,
                ),
                selfRegion = selfRegion?.let { resolveRegion(it) },
                excludeRegions = excludeRegions?.mapNotNull { resolveRegion(it) } ?: emptyList(),
                conditions = conditions?.map { it.toCond() } ?: emptyList(),
            )
        }

        private fun resolveRegion(id: String): RegionSpec? =
            FastLeapDefaults.region(id) ?: id.trim()
                .takeIf { it.startsWith("{") }
                ?.let { runCatching { gson.fromJson(it, RegionSpec::class.java) }.getOrNull() }
    }

    private class CondJson(
        val subject: String? = null,
        val landmark: String? = null,
        val x: Double? = null,
        val y: Double? = null,
        val z: Double? = null,
        val horizontal: Boolean? = null,
        val errorMinDistance: Double? = null,
        val errorMaxDistance: Double? = null,
        val mode: String? = null,
        val radius: Double? = null,
    ) {
        fun toCond(): PositionCondition = PositionCondition(
            subject = subject?.let { runCatching { SpSubject.valueOf(it.uppercase()) }.getOrNull() } ?: SpSubject.TARGET,
            landmarkName = landmark,
            customX = x,
            customY = y,
            customZ = z,
            horizontal = horizontal,
            errorMinDistance = errorMinDistance,
            errorMaxDistance = errorMaxDistance,
            mode = mode?.let { runCatching { SpMode.valueOf(it.uppercase()) }.getOrNull() },
            radius = radius,
        )
    }
}
