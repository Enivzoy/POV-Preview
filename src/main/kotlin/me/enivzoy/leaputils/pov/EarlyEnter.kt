package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.ScreenCloseEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.clickSlot
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.itemUUID
import com.odtheking.odin.utils.loreString
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.toFixed
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

private fun earlyEnterLog(tag: String, msg: String) = SpLog.info(tag, msg, "maskhelper.log")

object EarlyEnter : Module(
    name = "EarlyEnter",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Early-entry helper: runs your mask swap order during death-tick sits, optionally gated on your phoenix pet. Test rig: /leaputils sgdt"
) {
    enum class MaskKind(
        val itemIds: List<String>,
        val cooldownSeconds: Int,
        val selfLine: Regex,

        val nameParts: List<String>,
    ) {
        SPIRIT(
            listOf("SPIRIT_MASK", "STARRED_SPIRIT_MASK"), 30,
            Regex("^Second Wind Activated! Your Spirit Mask saved your life!$"),
            listOf("spirit mask")
        ),
        BONZO(
            listOf("BONZO_MASK", "STARRED_BONZO_MASK"), 180,
            Regex("^Your (?:. )?Bonzo's Mask saved your life!$"),
            listOf("bonzo's mask", "bonzos mask")
        );

        val other: MaskKind get() = if (this == SPIRIT) BONZO else SPIRIT

        fun matches(item: ItemStack?): Boolean {
            if (item == null) return false
            val id = runCatching { item.itemId }.getOrNull() ?: ""
            if (id.isNotEmpty() && id in itemIds) return true
            val name = cleanItemName(item)
            return name.isNotEmpty() && nameParts.any { name.contains(it) }
        }

        companion object {
            fun match(line: String): MaskKind? = entries.firstOrNull { it.selfLine.matches(line) }
        }
    }

    private data class HeadMatch(val id: String, val name: String, val uuid: String = "") {

        fun sameInstance(item: ItemStack): Boolean =
            uuid.isNotBlank() && runCatching { item.itemUUID }.getOrNull().orEmpty() == uuid

        fun sameItem(item: ItemStack): Boolean =
            (id.isNotBlank() && runCatching { item.itemId }.getOrNull().orEmpty() == id) ||
                (name.isNotBlank() && cleanItemName(item) == name)

        fun matches(item: ItemStack?): Boolean = item != null && (sameInstance(item) || sameItem(item))
    }

    private data class SlotHit(val index: Int, val id: String, val name: String, val count: Int, val uuid: String) {
        val renderForLog: String get() = "slot=$index id='$id' name='$name' count=$count"
    }

    val eeEnabled by BooleanSetting("Enabled", true, desc = "Watch invincibility pops and run the swap flow")
    val schizoMode by BooleanSetting("Schizo Mode", false, desc = "Press any key in the screen to fire the queued action instead of it happening automatically (mirrors Savepoint)")
    val swapBack by BooleanSetting("Swap back", true, desc = "Put your original helmet back on when the sit is over (right before the screen closes)")
    val verboseLog by BooleanSetting("Verbose logging", true, desc = "Writes pops, clicks and resets to config/leaputils/savepoint.log (tag: earlyenter)")
    val screenTitles by StringSetting("Screen titles", "Stats & Equipment", 64, "titles", "Comma separated contains-match titles that count as the swap screen; * = any container screen")
    val maxActionDelay by NumberSetting("Max action delay", 500.0, 100.0..5000.0, 50.0, desc = "Upper bound of the randomized 100..N ms wait before an automated click fires - lower is snappier", unit = "ms")
    val verifyWindow by NumberSetting("Verify window", 2.0, 0.5..10.0, 0.5, desc = "Seconds an equip click gets to prove itself before a retry; also spaces the end-of-sit chain stages apart", unit = "s")
    val sgdtInterval by NumberSetting("SGDT interval", 3.0, 1.0..120.0, 0.5, desc = "Seconds between the fake death ticks of the /leaputils sgdt test rig (odin's goldor tick is 60t = 3s)", unit = "s")
    val pheonixPet by BooleanSetting("Pheonix Pet", false, desc = "Only start the order after your Phoenix Pet popped (mask pops before that are ignored). Off: the moment the stats screen is open the order starts")
    val bonzoFirst by BooleanSetting("Bonzo first", false, desc = "The order: Spirit onto your head first (default) or Bonzo first; also sets the order the sgdt rig pops in")
    val onlyInDungeons by BooleanSetting("Only in Dungeons", true, desc = "Only react to mask pops and run the swap flow while inside a dungeon")
    val onlyInGoldor by BooleanSetting("Only in Goldor", true, desc = "Only run the swap flow during the Goldor boss fight (in boss + goldor floor + sections 1..5); off = don't apply the fight-specific gate")

    private val lock = Any()

    private val maskCdTicks = HashMap<MaskKind, Int>()
    private val poppedKinds = LinkedHashSet<MaskKind>()
    private val actions = mutableListOf<EeAction>()

    private var closedChain = false
    private var lastSection = -1
    private var inScreen = false
    private var screenOpenedAt = 0L
    private var originalHelmet: HeadMatch? = null
    private var swapBackPending = false
    private var screenDumped = false
    private var rigArmed = false
    private var phoenixPopped = false
    private var phoenixCdTicks = 0
    private var bootstrapped = false
    private var sgdtPhoenixPending = false

    private var sgdtRunning = false
    private var sgdtTick = 0
    private val sgdtQueue = ArrayDeque<MaskKind>()

    private val phoenixLine = Regex("^Your Phoenix Pet saved you from certain death!$")
    private const val PHOENIX_COOLDOWN_SECONDS = 60

    private const val QUEUE_PURGE_MS = 15_000L
    private const val RETRY_MS = 250L
    private const val NOT_FOUND_GIVEUP_MS = 8_000L

    private sealed class EeAction(val queuedAt: Long) {
        var fireAt = 0L
    }
    private class Swap(val kind: MaskKind, queuedAt: Long, val bootstrap: Boolean = false) : EeAction(queuedAt) {

        var tries = 0
        var clickedUuid = ""
        var predictedLandSlot = -1
    }
    private class Close(queuedAt: Long) : EeAction(queuedAt)
    private class Restore(val wanted: HeadMatch, queuedAt: Long) : EeAction(queuedAt) {
        var tries = 0
        var clickedUuid = ""
        var loggedMissing = false
        var notFoundSince = 0L
    }

    private val statusHud by HUD(
        "EarlyEnter Status",
        desc = "Mask cooldowns and what EarlyEnter is waiting for",
        x = 491,
        y = 460,
        scale = 1f
    ) { example ->
        val line = if (example) "\u00a77Spirit \u00a7a\u2714  \u00a77Bonzo \u00a7a\u2714  \u00a78[SGDT \u00a7f3s\u00a78]" else statusLine()
        if (line.isNullOrEmpty()) return@HUD 0 to 0
        val width = textDim(line, 0, 0, Colors.WHITE).first
        text(line, 0, 0, Colors.WHITE)
        width to 9
    }

    init {
        statusHud.enabled = true
        earlyEnterLog("earlyenter", "init: schizo=$schizoMode swapBack=$swapBack delay=100..${maxActionDelay.toInt()}ms verify=${verifyWindow}s phoenix=$pheonixPet bonzoFirst=$bonzoFirst sgdtInterval=${sgdtInterval}s screenTitles='$screenTitles' onlyInDungeons=$onlyInDungeons onlyInGoldor=$onlyInGoldor")
        SchizoLeapInput.registerHandler(::shouldWaitForSchizoKeypress)

        on<MessageEvent.Chat> {
            if (!enabled || !eeEnabled) return@on
            val kind = MaskKind.match(message)
            val phoenix = kind == null && phoenixLine.matches(message)
            if (kind == null && !phoenix) return@on
            if (onlyInDungeons && !runCatching { DungeonUtils.inDungeons }.getOrDefault(false)) return@on
            if (phoenix) onPhoenixPop(synthetic = false)
            else kind?.let { onPop(it, System.currentTimeMillis(), synthetic = false) }
        }

        on<TickEvent.Server> {

            val active = enabled && eeEnabled
            val fire = synchronized(lock) {
                for (k in MaskKind.entries) maskCdTicks[k] = (maskCdTicks[k] ?: 0).let { if (it > 0) it - 1 else 0 }
                if (phoenixCdTicks > 0) phoenixCdTicks--
                if (sgdtRunning && active) {
                    sgdtTick++
                    if (sgdtTick >= sgdtIntervalTicks()) {
                        sgdtTick = 0
                        true
                    } else false
                } else false
            }
            if (fire) fireSgdtPop()
        }

        on<ScreenEvent.Open>(EventPriority.HIGHEST + 90) {
            if (!enabled || !eeEnabled) return@on
            (screen as? AbstractContainerScreen<*>)?.takeIf { isTrackedScreen(it) }?.let { openScreen(it) }
        }

        on<ScreenCloseEvent> {
            if (!enabled || !eeEnabled) return@on
            synchronized(lock) { closeScreenInternal() }
        }

        on<ScreenEvent.Render>(EventPriority.HIGHEST + 120) {
            if (!enabled || !eeEnabled) {
                synchronized(lock) { inScreen = false }
                return@on
            }
            val container = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: run {
                synchronized(lock) { inScreen = false }
                return@on
            }
            if (!isTrackedScreen(container)) {
                synchronized(lock) { inScreen = false }
                return@on
            }
            if (synchronized(lock) { !inScreen }) openScreen(container)
            if (!reactionAllowed()) return@on
            if (schizoMode && !SchizoLeapInput.consumeKeyPress(container)) return@on
            dispatchDue(container, System.currentTimeMillis())
            maybeBootstrap()
        }

        on<FloorEnterEvent> { resetRunState("floor enter") }
        on<LevelEvent.Load> { resetRunState("world load") }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!enabled || !eeEnabled) return@register
            val sec = GoldorTracker.section
            if (sec != lastSection) {
                val prev = lastSection
                lastSection = sec
                synchronized(lock) {

                    if (poppedKinds.isNotEmpty()) {
                        earlyEnterLog("earlyenter", "section ${prev}->$sec; resetting pop chain (${poppedKinds.joinToString("+")})")
                        poppedKinds.clear()
                    }
                    closedChain = false
                    actions.removeAll { it !is Restore }

                    phoenixPopped = false
                    bootstrapped = false
                }
            }
        }
    }

    override fun onDisable() {
        resetRunState("module disabled")
        super.onDisable()
    }

    private fun onPop(kind: MaskKind, at: Long, synthetic: Boolean) {
        val seconds = if (kind == MaskKind.BONZO) headCooldownSeconds() else null
        var reaction: String? = null
        var target: MaskKind? = null
        synchronized(lock) {
            maskCdTicks[kind] = (seconds ?: kind.cooldownSeconds) * 20
            if (pheonixPet && !phoenixPopped) {
                earlyEnterLog("earlyenter", "POP ${kind.name}${if (synthetic) " [SGDT]" else ""} cd=${seconds ?: kind.cooldownSeconds}s ignored - Pheonix Pet mode: the phoenix hasn't popped yet")
                return@synchronized
            }
            val firstPopOfKind = poppedKinds.add(kind)
            earlyEnterLog(
                "earlyenter",
                "POP ${kind.name}${if (synthetic) " [SGDT]" else ""} cd=${seconds ?: kind.cooldownSeconds}s popped=${poppedKinds.joinToString("+")}"
            )
            if (!firstPopOfKind) {
                earlyEnterLog("earlyenter", "${kind.name} already popped this section, ignoring repeat proc line")
                return@synchronized
            }
            if (poppedKinds.size >= 2 && !closedChain) {

                closedChain = true
                actions.removeAll { it is Swap }

                arm(Close(at), 0)
                reaction = "both"
            } else if (actions.none { it is Swap }) {
                push(Swap(kind.other, at), at)
                reaction = "swap"
                target = kind.other
            } else {
                earlyEnterLog("earlyenter", "a swap is already armed, ignoring extra pop")
            }
        }
        when (reaction) {
            "both" -> modMessage("[EarlyEnter] \u00a7a${kind.name}\u00a7f popped \u00a78(${if (synthetic) "sgdt" else "proc"})\u00a7f \u00a77-> both on cd: helmet back on, closing")
            "swap" -> modMessage("[EarlyEnter] \u00a7a${kind.name}\u00a7f popped \u00a78(${if (synthetic) "sgdt" else "proc"})\u00a7f \u00a77-> clicking ${target?.name?.lowercase()} mask")
        }
    }

    private fun onPhoenixPop(synthetic: Boolean) {
        synchronized(lock) {
            phoenixPopped = true
            phoenixCdTicks = PHOENIX_COOLDOWN_SECONDS * 20
        }
        earlyEnterLog("earlyenter", "POP PHOENIX${if (synthetic) " [SGDT]" else ""} cd=${PHOENIX_COOLDOWN_SECONDS}s${if (pheonixPet) " - swap gate open" else ""}")
        modMessage("[EarlyEnter] \u00a7aphoenix popped\u00a7f \u00a78(${if (synthetic) "sgdt" else "proc"})\u00a7f${if (pheonixPet) " \u00a77- swaps unlocked" else ""}")
    }

    private fun headMatchOrNull(): HeadMatch? {
        val head = headStack()?.takeIf { !it.isEmpty } ?: return null
        return HeadMatch(
            runCatching { head.itemId }.getOrNull() ?: "",
            cleanItemName(head),
            runCatching { head.itemUUID }.getOrNull().orEmpty()
        )
    }

    private fun push(action: EeAction, now: Long) {
        action.fireAt = if (schizoMode) 0L else now + randomActionDelay()
        if (sgdtRunning) rigArmed = true
        actions += action
    }

    private fun arm(action: EeAction, stage: Int) {
        action.fireAt = if (schizoMode) 0L else System.currentTimeMillis() + randomActionDelay() + verifyMs() * 2 * stage
        if (sgdtRunning) rigArmed = true
        actions += action
    }

    private fun randomActionDelay(): Long = (100..maxActionDelay.roundToInt().coerceAtLeast(100)).random().toLong()

    private fun verifyMs(): Long = (verifyWindow * 1000).roundToInt().coerceAtLeast(250).toLong()

    private fun openScreen(container: AbstractContainerScreen<*>) {
        val now = System.currentTimeMillis()
        val title = container.title.string
        val captureHelmet: Boolean
        var staleDropped = 0
        synchronized(lock) {
            if (inScreen) closeScreenInternal()

            val before = actions.size
            actions.removeAll { it.queuedAt < now - QUEUE_PURGE_MS && it !is Restore && it !is Swap }
            staleDropped = before - actions.size
            inScreen = true
            screenOpenedAt = now
            screenDumped = false
            captureHelmet = originalHelmet == null && !swapBackPending
        }
        if (captureHelmet) {
            val head = headMatchOrNull()
            if (head != null) {
                synchronized(lock) { originalHelmet = head }
                if (verboseLog) earlyEnterLog("earlyenter", "open '$title': original helmet = ${head.render()} (uuid='${head.uuid}')")
            }
        }
        dumpScreenItems(container)
        val (orig, pending, chainDue) = synchronized(lock) { Triple(originalHelmet, swapBackPending, closedChain) }
        if (orig != null && pending && swapBack) {
            synchronized(lock) {

                if (actions.none { it is Restore }) {

                    if (!chainDue) {
                        if (verboseLog) earlyEnterLog("earlyenter", "open '$title': swap-back pending but the end sequence hasn't started (not both masks popped), leaving it to the chain")
                    } else {
                        push(Restore(orig, now), now)
                        earlyEnterLog("earlyenter", "open '$title': queue swap-back -> ${orig.render()}")
                        modMessage("[EarlyEnter] reopening: swapping back to your original helmet")
                    }
                } else if (verboseLog) earlyEnterLog("earlyenter", "open '$title': a swap-back is already queued, leaving it")
            }
        }
        if (verboseLog) {
            val popped = synchronized(lock) { poppedKinds.joinToString("+") }
            earlyEnterLog("earlyenter", "tracked screen open title='$title' section=${GoldorTracker.section} popped=$popped staleDropped=$staleDropped")
        }
    }

    private fun closeScreenInternal() {
        if (verboseLog && inScreen) {
            val popped = synchronized(lock) { poppedKinds.joinToString("+") }
            earlyEnterLog("earlyenter", "screen closed; popped=$popped queued=${actions.size}")
        }
        if (inScreen) {
            inScreen = false
            screenOpenedAt = 0
        }
    }

    private fun dispatchDue(container: AbstractContainerScreen<*>, now: Long) {
        val action = synchronized(lock) { actions.firstOrNull { it.fireAt > 0 && it.fireAt <= now } ?: return }
        when (action) {
            is Close -> {

                if (synchronized(lock) { actions.any { it is Restore } }) {
                    synchronized(lock) { action.fireAt = now + 500L }
                    return
                }

                val orig = synchronized(lock) { if (swapBackPending) originalHelmet else null }
                if (swapBack && orig != null) {
                    val hit = orig.let { findRestoreSlot(container, it) }
                    if (hit != null) {
                        runCatching { mc.player?.clickSlot(hit.index) }
                        earlyEnterLog("earlyenter", "restore-before-close: clicked ${hit.renderForLog}")
                        modMessage("[EarlyEnter] helmet back on; closing the screen")
                        synchronized(lock) { swapBackPending = false; actions.remove(action) }

                    } else if (verboseLog) {
                        earlyEnterLog("earlyenter", "restore-before-close: original (${orig.render()}) not in the inventory region (already on the head?), closing")
                    }
                }
                modMessage("[EarlyEnter] \u00a7cboth masks popped\u00a7f - closing the screen")
                earlyEnterLog("earlyenter", "CLOSE screen ${if (orig != null) "(original helmet not restored!)" else ""}")
                synchronized(lock) { actions.remove(action) }
                mc.gui.setScreen(null)

                synchronized(lock) {
                    poppedKinds.clear()
                    closedChain = false
                }
            }
            is Restore -> {

                if (action.clickedUuid.isNotEmpty() && !invRegionHoldsUuid(container, action.clickedUuid)) {
                    earlyEnterLog("earlyenter", "restore: original helmet equipped (it left the inventory after try=${action.tries})")
                    modMessage("[EarlyEnter] \u00a7ahelmet back on\u00a7f \u00a78(try ${action.tries})")
                    synchronized(lock) {
                        swapBackPending = false
                        actions.remove(action)
                    }
                    return
                }
                if (action.tries >= 2) {
                    synchronized(lock) { actions.remove(action) }
                    earlyEnterLog("earlyenter", "restore: helmet (${action.wanted.render()}) still in your inventory after ${action.tries} click(s), giving up (server rejected the click)")
                    return
                }
                val hit = findRestoreSlot(container, action.wanted)
                if (hit == null) {

                    if (action.notFoundSince == 0L) action.notFoundSince = now
                    if (now - action.notFoundSince < NOT_FOUND_GIVEUP_MS) {
                        if (!action.loggedMissing) {
                            action.loggedMissing = true
                            earlyEnterLog("earlyenter", "restore: original helmet (${action.wanted.render()}) not in the inventory region yet - waiting for hypixel's late sync")
                        }
                        synchronized(lock) { action.fireAt = now + RETRY_MS }
                    } else {
                        earlyEnterLog("earlyenter", "restore: original helmet never appeared in the inventory region within ${NOT_FOUND_GIVEUP_MS / 1000.0}s - assuming it is on your head, skipping the click")
                        modMessage("[EarlyEnter] \u00a7e${action.wanted.name} never appeared in your inventory\u00a7f \u00a77- check your helmet")
                        synchronized(lock) {
                            swapBackPending = false
                            actions.remove(action)
                        }
                    }
                    return
                }
                runCatching { mc.player?.clickSlot(hit.index) }
                action.tries++
                action.clickedUuid = hit.uuid
                earlyEnterLog("earlyenter", "swap-back click ${hit.renderForLog} (try=${action.tries}); verifying in ${verifyMs() / 1000.0}s")
                synchronized(lock) { action.fireAt = now + verifyMs() }
            }
            is Swap -> {

                val targetCd = synchronized(lock) { maskCdTicks[action.kind] ?: 0 }
                if (targetCd > 0) {
                    earlyEnterLog("earlyenter", "swap ${action.kind.name}: target mask still on cooldown (${targetCd / 20f}s) - holding swap")
                    return
                }

                if (action.clickedUuid.isNotEmpty() && !invRegionHoldsUuid(container, action.clickedUuid)) {
                    reportLanding(container, action)
                    earlyEnterLog("earlyenter", "swap ${action.kind.name}: equipped (mask left the inventory after try=${action.tries})")
                    modMessage("[EarlyEnter] \u00a7a${action.kind.name.lowercase()} mask equipped\u00a7f \u00a78(try ${action.tries})")
                    synchronized(lock) {
                        swapBackPending = true
                        actions.remove(action)
                    }
                    return
                }
                if (action.tries >= 2) {
                    synchronized(lock) {
                        actions.remove(action)

                        if (action.bootstrap) bootstrapped = false
                    }
                    earlyEnterLog("earlyenter", "swap ${action.kind.name}: mask still in your inventory after ${action.tries} click(s), giving up (server rejected the click)")
                    modMessage("[EarlyEnter] \u00a7c${action.kind.name.lowercase()} mask didn't equip\u00a7f \u00a77after 2 clicks - swap manually, and please check the log")
                    return
                }
                val hit = findMaskSlot(container, action.kind)
                if (hit == null) {
                    if (action.tries == 0) {

                        if (verboseLog) earlyEnterLog("earlyenter", "swap ${action.kind.name}: mask not in the inventory region (already equipped or not carried), nothing to click")
                        modMessage("[EarlyEnter] \u00a7e${action.kind.name.lowercase()} mask not in your inventory\u00a7f \u00a77- nothing to click")
                        synchronized(lock) { actions.remove(action) }
                    } else {

                        dumpScreenItems(container)
                        synchronized(lock) { action.fireAt = now + RETRY_MS }
                    }
                    return
                }

                action.predictedLandSlot = predictedLandSlotFor(container, hit.index)
                val displaced = synchronized(lock) {
                    if (swapBackPending) "your ${action.kind.other.name.lowercase()} mask"
                    else originalHelmet?.let { "your ${it.render()}" } ?: "the item on your head"
                }
                runCatching { mc.player?.clickSlot(hit.index) }
                action.tries++
                action.clickedUuid = hit.uuid
                earlyEnterLog(
                    "earlyenter",
                    "swap click ${action.kind.name} ${hit.renderForLog} (try=${action.tries}); $displaced should land in inventory slot ${action.predictedLandSlot}; verifying in ${verifyMs() / 1000.0}s"
                )
                synchronized(lock) { action.fireAt = now + verifyMs() }
            }
        }
    }

    private fun maybeBootstrap() {
        val first: MaskKind
        synchronized(lock) {
            if (bootstrapped || poppedKinds.isNotEmpty() || actions.any { it is Swap }) return
            if (pheonixPet && !phoenixPopped) return
            bootstrapped = true
            first = if (bonzoFirst) MaskKind.BONZO else MaskKind.SPIRIT
        }
        val now = System.currentTimeMillis()
        push(Swap(first, now, bootstrap = true), now)
        earlyEnterLog("earlyenter", "bootstrap: queue first swap -> ${first.name} mask (order ${if (bonzoFirst) "Bonzo->Spirit" else "Spirit->Bonzo"}${if (pheonixPet) ", phoenix gate open" else ""})")
        modMessage("[EarlyEnter] starting the order: clicking the \u00a7a${first.name.lowercase()}\u00a7f mask")
    }

    private fun findMaskSlot(container: AbstractContainerScreen<*>, kind: MaskKind): SlotHit? =
        slotInInvRegion(container) { kind.matches(it) }

    private fun findRestoreSlot(container: AbstractContainerScreen<*>, wanted: HeadMatch): SlotHit? =

        (if (wanted.uuid.isNotBlank()) slotInInvRegion(container) { wanted.sameInstance(it) } else null)
            ?: slotInInvRegion(container) { wanted.sameItem(it) }

    private fun slotInInvRegion(container: AbstractContainerScreen<*>, predicate: (ItemStack) -> Boolean): SlotHit? {
        val firstInvSlot = container.menu.slots.size - 36
        return container.menu.slots.withIndex().firstOrNull { (i, s) ->
            i >= firstInvSlot && !s.item.isEmpty && predicate(s.item)
        }?.let { (i, s) ->
            SlotHit(
                i,
                runCatching { s.item.itemId }.getOrNull() ?: "",
                cleanItemName(s.item),
                s.item.count,
                runCatching { s.item.itemUUID }.getOrNull() ?: ""
            )
        }
    }

    private fun invRegionHoldsUuid(container: AbstractContainerScreen<*>, uuid: String): Boolean {
        if (uuid.isBlank()) return false
        val firstInvSlot = container.menu.slots.size - 36
        return container.menu.slots.withIndex().any { (i, s) ->
            i >= firstInvSlot && !s.item.isEmpty && runCatching { s.item.itemUUID }.getOrNull().orEmpty() == uuid
        }
    }

    private fun predictedLandSlotFor(container: AbstractContainerScreen<*>, clickedSlot: Int): Int {
        val n = container.menu.slots.size
        val firstInvSlot = n - 36
        for (i in (n - 9) until n) if (container.menu.slots[i].item.isEmpty) return i
        for (i in firstInvSlot until (n - 9)) if (container.menu.slots[i].item.isEmpty) return i
        return clickedSlot
    }

    private fun reportLanding(container: AbstractContainerScreen<*>, action: Swap) {
        val at = action.predictedLandSlot
        if (at < 0) return
        val item = container.menu.slots.getOrNull(at)?.item
        if (item == null || item.isEmpty) {
            earlyEnterLog("earlyenter", "swap ${action.kind.name}: predicted landing slot $at is still empty (hypixel syncs the displaced item late)")
            return
        }
        val isOriginal = synchronized(lock) { originalHelmet?.matches(item) == true }
        earlyEnterLog(
            "earlyenter",
            "swap ${action.kind.name}: slot $at now holds id='${runCatching { item.itemId }.getOrNull().orEmpty()}' '${cleanItemName(item)}'" +
                if (isOriginal) " - that's your original helmet, as predicted" else ""
        )
    }

    private fun isTrackedScreen(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string

        if (title.equalsOneOf("Spirit Leap", "Teleport to Player", "Obsidian", "Game Menu")) return false
        val filter = screenTitles.trim()
        if (filter.equals("*")) return true

        val effective = filter.ifEmpty { "Stats & Equipment" }
        return effective.split(',').any { it.isNotBlank() && title.contains(it.trim(), ignoreCase = true) }
    }

    private fun reactionAllowed(): Boolean {

        if (synchronized(lock) { sgdtRunning || (rigArmed && actions.isNotEmpty()) }) return true
        val inBoss = runCatching { DungeonUtils.inBoss }.getOrDefault(false)
        val onGoldorFloor = runCatching { DungeonUtils.isFloor(7) }.getOrDefault(false)

        val inGoldorSection = GoldorTracker.section in 1..5
        if (onlyInGoldor) {
            if (!inBoss) {
                earlyEnterLog("earlyenter", "reaction blocked: not in a boss fight (inBoss=$inBoss) - swap chain held")
                return false
            }
            if (!onGoldorFloor) {
                earlyEnterLog("earlyenter", "reaction blocked: boss fight is not on the goldor floor (isFloor7=$onGoldorFloor) - swap chain held")
                return false
            }
            if (!inGoldorSection) {
                earlyEnterLog("earlyenter", "reaction blocked: goldor section=${GoldorTracker.section} not in 1..5 - swap chain held")
                return false
            }
        }
        return true
    }

    private fun shouldWaitForSchizoKeypress(screen: Screen): Boolean {
        if (!enabled || !eeEnabled || !schizoMode) return false
        val container = screen as? AbstractContainerScreen<*> ?: return false
        if (!isTrackedScreen(container)) return false
        if (!reactionAllowed()) return false
        return synchronized(lock) { actions.any { it.fireAt == 0L } }
    }

    private fun headStack(): ItemStack? = mc.player?.getItemBySlot(EquipmentSlot.HEAD)

    private fun HeadMatch.render(): String = if (id.isNotBlank()) "$id ('$name')" else "'$name'"

    private fun headCooldownSeconds(): Int? = runCatching {
        val cooldownRegex = Regex("^Cooldown: (\\d+)s$")
        headStack()?.loreString
            ?.reversed()?.firstNotNullOfOrNull { cooldownRegex.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
    }.getOrNull()

    private fun dumpScreenItems(container: AbstractContainerScreen<*>) {
        if (!verboseLog || screenDumped) return
        screenDumped = true
        val items = container.menu.slots.withIndex().mapNotNull { (i, s) ->
            if (s.item.isEmpty) null
            else "$i=${runCatching { s.item.itemId }.getOrNull().orEmpty().ifEmpty { "id?" }} '${cleanItemName(s.item)}' x${s.item.count}"
        }
        earlyEnterLog("earlyenter", "screen contents (${container.menu.slots.size} slots): ${items.joinToString(" ").take(1500)}")
    }

    private fun statusLine(): String? {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        for (kind in MaskKind.entries) {
            val cd = synchronized(lock) { maskCdTicks[kind] ?: 0 }
            val state = if (cd > 0) "\u00a7c${(cd / 20f).toFixed()}" else "\u00a7a\u2714"
            if (sb.isNotEmpty()) sb.append("  ")
            sb.append("\u00a77").append(kind.name).append(' ').append(state)
        }
        if (pheonixPet) {
            val (popped, cd) = synchronized(lock) { phoenixPopped to phoenixCdTicks }
            val state = when {
                !popped -> "\u00a7ewait"
                cd > 0 -> "\u00a7c${(cd / 20f).toFixed()}"
                else -> "\u00a7a\u2714"
            }
            sb.append("  \u00a77Phoenix ").append(state)
        }
        synchronized(lock) {
            if (sgdtRunning) sb.append("  \u00a78[SGDT \u00a7f${sgdtInterval}s\u00a78]")
            when (val a = actions.firstOrNull()) {
                null -> {}
                is Swap -> sb.append("  \u00a78queue \u00a7fswap\u2192").append(a.kind.name)
                    .append(if (a.fireAt == 0L) "\u00a78 (keypress)" else " \u00a7f${(((a.fireAt - now).coerceAtLeast(0)) / 1000.0).toFixed()}")
                is Restore -> sb.append("  \u00a78queue \u00a7fswap-back \u00a77").append(a.wanted.render().lowercase())
                is Close -> sb.append("  \u00a78queue \u00a7fclose")
            }
        }
        return sb.toString()
    }

    fun toggleTestTimer() {
        val nowRunning: Boolean
        synchronized(lock) {
            nowRunning = !sgdtRunning
            sgdtRunning = nowRunning
            sgdtTick = 0
            sgdtQueue.clear()
            if (nowRunning) {

                poppedKinds.clear()
                closedChain = false
                maskCdTicks.clear()
                actions.removeAll { it !is Restore }
                swapBackPending = false
                originalHelmet = null
                screenDumped = false
                rigArmed = false
                bootstrapped = false
                phoenixPopped = false
                phoenixCdTicks = 0

                sgdtPhoenixPending = true
                sgdtQueue += if (bonzoFirst) listOf(MaskKind.BONZO, MaskKind.SPIRIT) else listOf(MaskKind.SPIRIT, MaskKind.BONZO)
            }
        }
        if (nowRunning) {
            val order = if (bonzoFirst) "Bonzo -> Spirit" else "Spirit -> Bonzo"
            earlyEnterLog("earlyenter", "SGDT started: fake death tick every ${sgdtInterval}s, order Phoenix -> $order")
            modMessage("[EarlyEnter] \u00a7bSGDT\u00a7f death-tick timer started - one fake pop every ${sgdtInterval}s (Phoenix -> $order)")
        } else {
            earlyEnterLog("earlyenter", "SGDT stopped")
            modMessage("[EarlyEnter] \u00a7bSGDT\u00a7f timer stopped")
        }
    }

    private fun sgdtIntervalTicks(): Int = (sgdtInterval * 20).roundToInt().coerceAtLeast(20)

    private fun fireSgdtPop() {
        val phoenix = synchronized(lock) {
            if (sgdtPhoenixPending) {
                sgdtPhoenixPending = false
                true
            } else false
        }
        if (phoenix) {
            onPhoenixPop(synthetic = true)
            return
        }
        val kind = synchronized(lock) { sgdtQueue.removeFirstOrNull() }
        if (kind == null) {
            synchronized(lock) {
                sgdtRunning = false
                sgdtTick = 0
            }
            earlyEnterLog("earlyenter", "SGDT queue drained, timer stopped")
            modMessage("[EarlyEnter] \u00a7bSGDT\u00a7f did all the fake pops, timer stopped")
            return
        }
        onPop(kind, System.currentTimeMillis(), synthetic = true)
    }

    fun resetForCommand() {
        resetRunState("manual reset (/leaputils eereset)")
        modMessage("[EarlyEnter] \u00a7btimers reset\u00a7f - cooldowns, pops and queues cleared")
    }

    private fun resetRunState(reason: String) {
        synchronized(lock) {
            if (verboseLog) earlyEnterLog(
                "earlyenter",
                "reset ($reason): popped=${poppedKinds.joinToString("+")} queued=${actions.size} swapBackPending=$swapBackPending original=${originalHelmet?.render() ?: "none"}"
            )
            inScreen = false
            poppedKinds.clear()
            closedChain = false
            maskCdTicks.clear()
            actions.clear()
            sgdtRunning = false
            sgdtTick = 0
            sgdtQueue.clear()
            originalHelmet = null
            swapBackPending = false
            screenDumped = false
            rigArmed = false
            phoenixPopped = false
            phoenixCdTicks = 0
            bootstrapped = false
            sgdtPhoenixPending = false
        }
        lastSection = -1
    }
}

private val colorCodeRegex = Regex("\u00a7.")

private fun cleanItemName(item: ItemStack?): String =
    item?.hoverName?.string
        ?.replace('\u2019', '\'')
        ?.replace(colorCodeRegex, "")
        ?.lowercase()
        ?.trim()
        ?: ""
