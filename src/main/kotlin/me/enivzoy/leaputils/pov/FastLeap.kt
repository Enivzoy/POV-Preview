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
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import me.enivzoy.leaputils.pov.fastleap.FastLeapContext
import me.enivzoy.leaputils.pov.fastleap.FastLeapDefaults
import me.enivzoy.leaputils.pov.fastleap.FastLeapEvaluator
import me.enivzoy.leaputils.pov.fastleap.FastLeapResult
import me.enivzoy.leaputils.pov.fastleap.FastLeapRule
import me.enivzoy.leaputils.pov.fastleap.FastLeapState
import me.enivzoy.leaputils.pov.fastleap.FastLeapTriggerSource
import me.enivzoy.leaputils.pov.fastleap.PositionCondition
import me.enivzoy.leaputils.pov.fastleap.RegionSpec
import me.enivzoy.leaputils.pov.fastleap.TargetSelectorType
import me.enivzoy.leaputils.pov.fastleap.TargetSpec
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
    enum class FallbackMode { NOTHING, NEAREST_TO }
    enum class FeedbackMode { ERRORS, VERBOSE, OFF }
    enum class LocationFilter { BOSS, CLEAR, BOTH }

    private var rules: List<FastLeapRule> = FastLeapDefaults.pack()
    private var disabledIds = mutableSetOf<String>()
    private var firedThisScreen: Screen? = null
    private var lastWarnKey = ""
    private var lastWarnAt = 0L
    private var tickCounter = 0L
    private var levelIdentity: Any? = null
    private var menuOpenedMs = 0L
    private val cooldownUntil = HashMap<String, Long>()
    private var dispatchTimes = ArrayDeque<Long>()

    private val closer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "leaputils-pov-fastleap-close").apply { isDaemon = true }
    }

    val fastLeapEnabled by BooleanSetting("Enabled", true, desc = "Master switch for Fast Leap")
    val leapMode by SelectorSetting("Leap mode", LeapMode.CLASS, desc = "Pick the target by class or by name", listOf(LeapMode.CLASS, LeapMode.NAME))
    val ignorePhaseGates by BooleanSetting("Ignore phase gates", false, desc = "Evaluate every rule regardless of its phase or section gate")
    val locationFilter by SelectorSetting("Locations", LocationFilter.BOTH, desc = "Run in the boss fight, in clear, or both", listOf(LocationFilter.BOSS, LocationFilter.CLEAR, LocationFilter.BOTH))
    val respectCooldown by BooleanSetting("Respect cooldown", true, desc = "Skip a leap item that still has cooldown left")
    val autoSwap by BooleanSetting("Auto swap", true, desc = "Select a ready leap item from hotbar slots 0-7 when needed")
    val swapBack by BooleanSetting("Swap back", false, desc = "Return to the previous hotbar slot after the leap click is dispatched")
    val doorOpenerLeap by BooleanSetting("Door opener leap", false, desc = "Outside the boss, run the door opener rule when the opener is known")
    val disableAfterBloodOpen by BooleanSetting("Disable after Blood open", true, desc = "Stop door opener leaps once Blood is open")
    val fallbackMode by SelectorSetting("Fallback", FallbackMode.NOTHING, desc = "What to do when no rule matched", listOf(FallbackMode.NOTHING, FallbackMode.NEAREST_TO))
    val fallbackLandmark by StringSetting("Fallback landmark", "", 32, "landmark", "Nearest to landmark, used by the Fallback setting")

    val clickTrigger by BooleanSetting("Left click", true, desc = "Leap when left clicking while holding a spirit leap item")
    val requireSneaking by BooleanSetting("Require sneak", false, desc = "Only trigger while you are sneaking")
    val cancelOnNoMatch by BooleanSetting("Cancel on no-match", false, desc = "Swallow the click even when no rule matched")
    val debounceMs by NumberSetting("Trigger debounce ms", 250, 0..2000, 10, desc = "Duplicate trigger guard; it never delays the first accepted request")
    val maxLeapsPerSecond by NumberSetting("Max leaps per second", 2, 1..10, 1, desc = "Safety cap on dispatched leaps")
    val openTrigger by BooleanSetting("On menu open", false, desc = "Evaluate once when a spirit leap menu opens")
    val openDelayMs by NumberSetting("Open delay ms", 0, 0..150, 5, desc = "Lower bound wait before reading the menu; slot readiness is still checked")

    val closeAfterDispatch by BooleanSetting("Close GUI After Teammate Click Dispatch", true, desc = "Close the menu as soon as the teammate click is dispatched, never waiting for the leap to land")
    val closeDelayMs by NumberSetting("GUI Close Delay", 1, 0..100, 1, desc = "Delay from the teammate click dispatch to the local close, in milliseconds")

    val geckoKeybind by KeybindSetting("Keybind", -1, desc = "Run the same evaluation from a key").onPress { attempt(FastLeapTriggerSource.KEYBIND) }
    val goNow by ActionSetting("Leap now", "Evaluate the rule list once and leap if something matches") { attempt(FastLeapTriggerSource.COMMAND) }
    val reloadRules by ActionSetting("Reload rules", "Re-read config/leaputils/fastleap.json over the built in pack") { reload() }
    val listRules by ActionSetting("List rules", "Print the ordered rule list to chat") { printRules() }

    val feedback by SelectorSetting("Feedback", FeedbackMode.ERRORS, desc = "How much Fast Leap says in chat", listOf(FeedbackMode.ERRORS, FeedbackMode.VERBOSE, FeedbackMode.OFF))
    val dryRun by BooleanSetting("Dry run", false, desc = "Log the decision without clicking")
    val profiling by BooleanSetting("Profile timings", false, desc = "Write t0 to t6 timings per request to savepoint.log")

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: Path = FabricLoader.getInstance().configDir.resolve("leaputils")

    init {
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
        if (skyblockId == null || skyblockId !in FastLeapDefaults.leapItemIds) return false
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
        if (firedThisScreen === screen) return
        firedThisScreen = screen

        val pending = FastLeapState.request
        if (pending != null && !FastLeapState.isStale(pending.generation)) {
            pending.timing.t4 = System.nanoTime()
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
        }
        val container = runCatching { mc.gui.screen() }.getOrNull() as? AbstractContainerScreen<*>
        if (container == null || !isLeapScreen(container)) {
            FastLeapState.request?.let { req ->
                if (FastLeapState.expired(tickCounter)) finish(req, "timeout without a leap menu")
            }
            runScheduledClose()
            return
        }
        val req = FastLeapState.request
        if (req != null && !FastLeapState.isStale(req.generation)) {
            if (req.timing.t4 == null) {
                req.timing.t4 = System.nanoTime()
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
        val applyFallback = fallbackMode == FallbackMode.NEAREST_TO && onlyRuleId == null
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
        dispatchTimes.addLast(System.currentTimeMillis())
        markCooldown(req.itemId)
        if (swapBack) req.restoreSlot?.let { slot -> restoreHotbarSlot(slot) }
        if (profiling) SpLog.info("fastleap", "dispatch request=${req.id} target=${req.targetName}")
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

    private fun finish(req: FastLeapState.Request, reason: String) {
        req.timing.failure = reason
        if (profiling) SpLog.info("fastleap", FastLeapState.summary(req))
        FastLeapState.clear(reason)
    }

    // debounce is only a duplicate guard, the first accepted trigger is never delayed
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
        if (player.isDeadOrDying) return null
        if (!runCatching { DungeonUtils.inDungeons }.getOrDefault(false)) return null
        val inBoss = runCatching { DungeonUtils.inBoss }.getOrDefault(false)
        if (locationFilter == LocationFilter.BOSS && !inBoss) return null
        if (locationFilter == LocationFilter.CLEAR && inBoss) return null
        if (!runCatching { DungeonUtils.isFloor(7) }.getOrDefault(false)) return null

        Landmarks.load()
        val screen = runCatching { mc.gui.screen() }.getOrNull() as? AbstractContainerScreen<*>
        val leapScreen = screen?.takeIf { isLeapScreen(it) }
        val myName = runCatching { DungeonUtils.currentDungeonPlayer.name }.getOrNull()
        val teammates = runCatching { DungeonUtils.leapTeammates }.getOrDefault(emptyList())
            .filter { myName == null || FastLeapEvaluator.nameKey(it.name) != FastLeapEvaluator.nameKey(myName) }
            .map { dp ->
                val e = runCatching { OdinAccess.entityOf(dp) }.getOrNull()
                val inMenu = leapScreen != null && OdinAccess.isLeapAvailable(dp, leapScreen)
                SpTmate(dp.name, dp.clazz, dp.isDead, inMenu, e?.x, e?.y, e?.z)
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
        )
    }

    private fun reportNoMatch(res: FastLeapResult.NoMatch) {
        if (feedback != FeedbackMode.VERBOSE) return
        val msg = res.failures.take(3).joinToString("; ") { "${it.ruleId}: ${it.reason}" }
        report("no rule matched ($msg)")
    }

    private fun report(msg: String) {
        if (feedback == FeedbackMode.OFF) return
        val now = System.currentTimeMillis()
        if (msg == lastWarnKey && now - lastWarnAt < WARN_THROTTLE_MS) return
        lastWarnKey = msg
        lastWarnAt = now
        modMessage("[FastLeap] $msg")
    }

    private fun isLeapScreen(screen: AbstractContainerScreen<*>): Boolean {
        val title = runCatching { screen.title.string }.getOrNull() ?: return false
        return title == "Spirit Leap" || title == "Teleport to Player" || title == "Obsidian"
    }

    fun rules(): List<FastLeapRule> = rules

    fun regions(): List<Pair<String, RegionSpec>> = FastLeapDefaults.regions

    private const val WARN_THROTTLE_MS = 5_000L

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
                    radius = radius ?: 8.0,
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
            horizontal = horizontal ?: false,
            errorMinDistance = errorMinDistance,
            errorMaxDistance = errorMaxDistance,
            mode = mode?.let { runCatching { SpMode.valueOf(it.uppercase()) }.getOrNull() },
            radius = radius,
        )
    }
}
