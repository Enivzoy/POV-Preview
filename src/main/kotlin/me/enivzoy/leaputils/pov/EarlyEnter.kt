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

// early-enter survival helper: you sit in the goldor death-tick pocket with your gear screen (/stats) held up
// the whole time, and this does the helmet chores for you:
//   - the first mask of your order goes onto your head when the screen opens - with "Pheonix Pet" on only
//     after the pet popped (mask pops before that are ignored), without it the moment the screen is open
//   - an invincibility pops (spirit/bonzo proc'd a save) -> it clicks the other mask onto your head for you
//   - the second pop (both on cooldown) -> put your original helmet back on and close
// the real input is your own proc chat lines - detection is vendored from odin's InvincibilityTimer (its type
// enum is private, see the same note on engineerclient's MaskTracker): same regexes, same cooldowns, bonzo's
// scaling cooldown read off the worn head's lore. real pops need nothing extra - the chat handler feeds the
// same onPop/onPhoenixPop everything else runs on. /leaputils sgdt is a dry-test rig that replays those lines
// on a timer (default 3s = the goldor tick odin's TickTimers shows, 60t) so the flow can be watched outside a
// run; it stops on floor enter / world load so a forgotten rig can't fire fake pops in a real one.
// /leaputils eereset clears the timers, pops and queues without toggling the module.
object EarlyEnter : Module(
    name = "EarlyEnter",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Early-entry helper: runs your mask swap order during death-tick sits, optionally gated on your phoenix pet. Test rig: /leaputils sgdt"
) {
    enum class MaskKind(
        val itemIds: List<String>,
        val cooldownSeconds: Int,
        val selfLine: Regex,
        /** display-name fragments, matched after cleaning colour codes and reforge prefixes off ("Necrotic Spirit Mask") */
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

        /** skyblock item id first, then the cleaned display name so reforged / fragged / starred variants are found */
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

    /** head item captured before the first module swap; matched by skyblock uuid, then id, then cleaned display name */
    private data class HeadMatch(val id: String, val name: String, val uuid: String = "") {
        /** the exact instance (same skyblock uuid) - survives the item moving between slots */
        fun sameInstance(item: ItemStack): Boolean =
            uuid.isNotBlank() && runCatching { item.itemUUID }.getOrNull().orEmpty() == uuid

        /** same kind of item (skyblock id or cleaned display name) - also matches a different instance */
        fun sameItem(item: ItemStack): Boolean =
            (id.isNotBlank() && runCatching { item.itemId }.getOrNull().orEmpty() == id) ||
                (name.isNotBlank() && cleanItemName(item) == name)

        fun matches(item: ItemStack?): Boolean = item != null && (sameInstance(item) || sameItem(item))
    }

    /** what a slot search landed on, logged verbatim so server-rejected clicks are visible in the log */
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

    private val lock = Any()

    private val maskCdTicks = HashMap<MaskKind, Int>()   // remaining cooldown in ticks, like odin's currentCooldown
    private val poppedKinds = LinkedHashSet<MaskKind>()  // masks that popped since this goldor section started
    private val actions = mutableListOf<EeAction>()      // queued reactions waiting for their delay / keypress

    private var closedChain = false     // both masks popped this section and the end sequence is queued
    private var lastSection = -1        // goldor section mirror; pop state resets every section change
    private var inScreen = false        // a tracked swap screen is open right now
    private var screenOpenedAt = 0L
    private var originalHelmet: HeadMatch? = null   // head item before the first swap of the run
    private var swapBackPending = false // a module swap is waiting to be reverted at the end of the sit
    private var screenDumped = false    // one diagnostics listing per screen open
    private var rigArmed = false        // the running action chain was created by the sgdt rig (keeps its gate open until it drains)
    private var phoenixPopped = false   // "Pheonix Pet" gate: the pet's save line was seen this sit
    private var phoenixCdTicks = 0      // phoenix pet cooldown for the HUD (60s, odin's InvincibilityTimer)
    private var bootstrapped = false    // the first swap of the order was already initiated this sit
    private var sgdtPhoenixPending = false // the rig's next fire is the fake phoenix pop that opens the gate

    private var sgdtRunning = false     // /leaputils sgdt fake death-tick timer
    private var sgdtTick = 0
    private val sgdtQueue = ArrayDeque<MaskKind>()

    // vendored from odin's InvincibilityTimer (PHOENIX entry): the pet's save line and its 60s cooldown
    private val phoenixLine = Regex("^Your Phoenix Pet saved you from certain death!$")
    private const val PHOENIX_COOLDOWN_SECONDS = 60

    private const val QUEUE_PURGE_MS = 15_000L  // queued swap older than this is from a long-over sit
    private const val RETRY_MS = 250L           // retry pacing when the item isn't findable in the screen yet
    private const val NOT_FOUND_GIVEUP_MS = 8_000L // hypixel syncs a displaced item into your inventory late (~3.4s seen)

    private sealed class EeAction(val queuedAt: Long) {
        var fireAt = 0L // 0 = armed and waiting for a schizo keypress instead of the delay
    }
    private class Swap(val kind: MaskKind, queuedAt: Long, val bootstrap: Boolean = false) : EeAction(queuedAt) {
        // only the render thread touches these, so plain fields are fine
        var tries = 0        // equip clicks sent; each gets a verify window, one retry, then give up
        var clickedUuid = "" // skyblock uuid of the clicked stack; it leaves the player-inv region once equipped
        var predictedLandSlot = -1 // where the item this click displaces off the head should land
    }
    private class Close(queuedAt: Long) : EeAction(queuedAt)
    private class Restore(val wanted: HeadMatch, queuedAt: Long) : EeAction(queuedAt) {
        var tries = 0
        var clickedUuid = ""
        var loggedMissing = false
        var notFoundSince = 0L // first moment the helmet was missing from the inventory region (it syncs late)
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
        SpLog.info("earlyenter", "init: schizo=$schizoMode swapBack=$swapBack delay=100..${maxActionDelay.toInt()}ms verify=${verifyWindow}s phoenix=$pheonixPet bonzoFirst=$bonzoFirst sgdtInterval=${sgdtInterval}s screenTitles='$screenTitles'")
        SchizoLeapInput.registerHandler(::shouldWaitForSchizoKeypress)

        // your own proc lines are only sent to you, so your own state here is exact (see odin's InvincibilityTimer).
        // this is the real hookup: live hypixel proc lines feed the same onPop/onPhoenixPop the sgdt rig replays
        on<MessageEvent.Chat> {
            if (!enabled || !eeEnabled) return@on
            val kind = MaskKind.match(message)
            val phoenix = kind == null && phoenixLine.matches(message)
            if (kind == null && !phoenix) return@on
            if (!runCatching { DungeonUtils.inDungeons }.getOrDefault(false)) return@on
            if (phoenix) onPhoenixPop(synthetic = false)
            else kind?.let { onPop(it, System.currentTimeMillis(), synthetic = false) }
        }

        on<TickEvent.Server> {
            // cooldowns keep ticking while the module is off so the HUD never shows frozen numbers, but the
            // rig must not: an off module stays completely silent, fake pops included
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
                    // a new section means the death-tick pocket moved on; a fresh sit gets fresh pops
                    if (poppedKinds.isNotEmpty()) {
                        SpLog.info("earlyenter", "section ${prev}->$sec; resetting pop chain (${poppedKinds.joinToString("+")})")
                        poppedKinds.clear()
                    }
                    closedChain = false
                    actions.removeAll { it !is Restore }
                    // a fresh section is a fresh sit: the phoenix gate and the first-swap bootstrap re-arm
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

    // ============================= pop handling =============================

    private fun onPop(kind: MaskKind, at: Long, synthetic: Boolean) {
        val seconds = if (kind == MaskKind.BONZO) headCooldownSeconds() else null
        var reaction: String? = null
        var target: MaskKind? = null
        synchronized(lock) {
            maskCdTicks[kind] = (seconds ?: kind.cooldownSeconds) * 20
            if (pheonixPet && !phoenixPopped) {
                SpLog.info("earlyenter", "POP ${kind.name}${if (synthetic) " [SGDT]" else ""} cd=${seconds ?: kind.cooldownSeconds}s ignored - Pheonix Pet mode: the phoenix hasn't popped yet")
                return@synchronized
            }
            val firstPopOfKind = poppedKinds.add(kind)
            SpLog.info(
                "earlyenter",
                "POP ${kind.name}${if (synthetic) " [SGDT]" else ""} cd=${seconds ?: kind.cooldownSeconds}s popped=${poppedKinds.joinToString("+")}"
            )
            if (!firstPopOfKind) {
                SpLog.info("earlyenter", "${kind.name} already popped this section, ignoring repeat proc line")
                return@synchronized
            }
            if (poppedKinds.size >= 2 && !closedChain) {
                // both masks are on cooldown: the sit's saves are spent - original helmet back on, then close.
                // no mask re-wear here: the other mask was JUST worn and popped, re-equipping it does nothing
                closedChain = true
                actions.removeAll { it is Swap } // a still-queued pop#1 swap would only put a fresh-cooldown mask on
                originalHelmet?.let { arm(Restore(it, at), 0) }
                arm(Close(at), 1)
                reaction = "both"
            } else if (actions.none { it is Swap }) {
                push(Swap(kind.other, at), at)
                reaction = "swap"
                target = kind.other
            } else {
                SpLog.info("earlyenter", "a swap is already armed, ignoring extra pop")
            }
        }
        when (reaction) {
            "both" -> modMessage("[EarlyEnter] \u00a7a${kind.name}\u00a7f popped \u00a78(${if (synthetic) "sgdt" else "proc"})\u00a7f \u00a77-> both on cd: helmet back on, closing")
            "swap" -> modMessage("[EarlyEnter] \u00a7a${kind.name}\u00a7f popped \u00a78(${if (synthetic) "sgdt" else "proc"})\u00a7f \u00a77-> clicking ${target?.name?.lowercase()} mask")
        }
    }

    /** the phoenix pet's save line - with "Pheonix Pet" on this is what unlocks swapping for the rest of the sit */
    private fun onPhoenixPop(synthetic: Boolean) {
        synchronized(lock) {
            phoenixPopped = true
            phoenixCdTicks = PHOENIX_COOLDOWN_SECONDS * 20
        }
        SpLog.info("earlyenter", "POP PHOENIX${if (synthetic) " [SGDT]" else ""} cd=${PHOENIX_COOLDOWN_SECONDS}s${if (pheonixPet) " - swap gate open" else ""}")
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

    /** arms an ordered chain stage: each stage waits out the previous click + verify window */
    private fun arm(action: EeAction, stage: Int) {
        action.fireAt = if (schizoMode) 0L else System.currentTimeMillis() + randomActionDelay() + verifyMs() * 2 * stage
        if (sgdtRunning) rigArmed = true
        actions += action
    }

    /** randomized human-ish wait before an automated click, upper-bounded by the Max action delay setting */
    private fun randomActionDelay(): Long = (100..maxActionDelay.roundToInt().coerceAtLeast(100)).random().toLong()

    /** how long an equip click gets to prove itself (the item leaving the inventory) before a retry */
    private fun verifyMs(): Long = (verifyWindow * 1000).roundToInt().coerceAtLeast(250).toLong()

    // ============================== screens ==============================

    private fun openScreen(container: AbstractContainerScreen<*>) {
        val now = System.currentTimeMillis()
        val title = container.title.string
        val captureHelmet: Boolean
        var staleDropped = 0
        synchronized(lock) {
            if (inScreen) closeScreenInternal() // switching straight from one tracked screen to another
            // a queued swap older than the purge window is from a sit that is long over; dump it
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
                if (verboseLog) SpLog.info("earlyenter", "open '$title': original helmet = ${head.render()} (uuid='${head.uuid}')")
            }
        }
        dumpScreenItems(container)
        val (orig, pending, chainDue) = synchronized(lock) { Triple(originalHelmet, swapBackPending, closedChain) }
        if (orig != null && pending && swapBack) {
            synchronized(lock) {
                // the restore action itself works out whether the helmet is still in the inventory (click it)
                // or already on the head (the client's head data lags the server, so it is not consulted here)
                if (actions.none { it is Restore }) {
                    // only when the end sequence is due (both masks popped): the server re-sends the screen
                    // after EVERY equip click, and a restore here would cut the mask chain short - the golden
                    // would go back on between the two masks and the chain would end with a mask on your head
                    if (!chainDue) {
                        if (verboseLog) SpLog.info("earlyenter", "open '$title': swap-back pending but the end sequence hasn't started (not both masks popped), leaving it to the chain")
                    } else {
                        push(Restore(orig, now), now)
                        SpLog.info("earlyenter", "open '$title': queue swap-back -> ${orig.render()}")
                        modMessage("[EarlyEnter] reopening: swapping back to your original helmet")
                    }
                } else if (verboseLog) SpLog.info("earlyenter", "open '$title': a swap-back is already queued, leaving it")
            }
        }
        if (verboseLog) {
            val popped = synchronized(lock) { poppedKinds.joinToString("+") }
            SpLog.info("earlyenter", "tracked screen open title='$title' section=${GoldorTracker.section} popped=$popped staleDropped=$staleDropped")
        }
    }

    private fun closeScreenInternal() {
        if (verboseLog && inScreen) {
            val popped = synchronized(lock) { poppedKinds.joinToString("+") }
            SpLog.info("earlyenter", "screen closed; popped=$popped queued=${actions.size}")
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
                // let a still-running restore finish first - it may be waiting out hypixel's late inventory sync
                if (synchronized(lock) { actions.any { it is Restore } }) {
                    synchronized(lock) { action.fireAt = now + 500L }
                    return
                }
                // if the staged restore never fired its click, still put the helmet back before the screen goes down
                val orig = synchronized(lock) { if (swapBackPending) originalHelmet else null }
                if (swapBack && orig != null) {
                    val hit = orig.let { findRestoreSlot(container, it) }
                    if (hit != null) {
                        runCatching { mc.player?.clickSlot(hit.index) }
                        SpLog.info("earlyenter", "restore-before-close: clicked ${hit.renderForLog}")
                        modMessage("[EarlyEnter] helmet back on; closing the screen")
                        synchronized(lock) {
                            swapBackPending = false
                            actions.remove(action)
                            val again = Close(System.currentTimeMillis())
                            again.fireAt = System.currentTimeMillis() + verifyMs()
                            actions += again
                        }
                        return
                    } else if (verboseLog) {
                        SpLog.info("earlyenter", "restore-before-close: original (${orig.render()}) not in the inventory region (already on the head?), closing")
                    }
                }
                modMessage("[EarlyEnter] \u00a7cboth masks popped\u00a7f - closing the screen")
                SpLog.info("earlyenter", "CLOSE screen ${if (orig != null) "(original helmet not restored!)" else ""}")
                synchronized(lock) { actions.remove(action) }
                mc.gui.setScreen(null)
            }
            is Restore -> {
                // one click replaces whatever is on your head; the helmet leaving the inventory is the fast
                // confirm - the client's worn-helmet data lags or freezes behind the server on hypixel, so
                // the inventory region is the only truth used here
                if (action.clickedUuid.isNotEmpty() && !invRegionHoldsUuid(container, action.clickedUuid)) {
                    SpLog.info("earlyenter", "restore: original helmet equipped (it left the inventory after try=${action.tries})")
                    modMessage("[EarlyEnter] \u00a7ahelmet back on\u00a7f \u00a78(try ${action.tries})")
                    synchronized(lock) {
                        swapBackPending = false
                        actions.remove(action)
                    }
                    return
                }
                if (action.tries >= 2) {
                    synchronized(lock) { actions.remove(action) }
                    SpLog.info("earlyenter", "restore: helmet (${action.wanted.render()}) still in your inventory after ${action.tries} click(s), giving up (server rejected the click)")
                    return
                }
                val hit = findRestoreSlot(container, action.wanted)
                if (hit == null) {
                    // a module swap DID happen (that's why this is queued), so the helmet was displaced and
                    // exists somewhere - hypixel just syncs the displaced item into the inventory late
                    // (~3.4s in tests), so wait for it instead of concluding anything off the stale head
                    if (action.notFoundSince == 0L) action.notFoundSince = now
                    if (now - action.notFoundSince < NOT_FOUND_GIVEUP_MS) {
                        if (!action.loggedMissing) {
                            action.loggedMissing = true
                            SpLog.info("earlyenter", "restore: original helmet (${action.wanted.render()}) not in the inventory region yet - waiting for hypixel's late sync")
                        }
                        synchronized(lock) { action.fireAt = now + RETRY_MS }
                    } else {
                        SpLog.info("earlyenter", "restore: original helmet never appeared in the inventory region within ${NOT_FOUND_GIVEUP_MS / 1000.0}s - assuming it is on your head, skipping the click")
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
                SpLog.info("earlyenter", "swap-back click ${hit.renderForLog} (try=${action.tries}); verifying in ${verifyMs() / 1000.0}s")
                synchronized(lock) { action.fireAt = now + verifyMs() }
            }
            is Swap -> {
                // one click replaces whatever is on your head - success is the mask leaving your inventory
                // (the client's worn-helmet data lags behind the server on hypixel, so the inventory is the truth)
                if (action.clickedUuid.isNotEmpty() && !invRegionHoldsUuid(container, action.clickedUuid)) {
                    reportLanding(container, action)
                    SpLog.info("earlyenter", "swap ${action.kind.name}: equipped (mask left the inventory after try=${action.tries})")
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
                        // a rejected bootstrap shouldn't strand the sit: let the next screen open try again
                        if (action.bootstrap) bootstrapped = false
                    }
                    SpLog.info("earlyenter", "swap ${action.kind.name}: mask still in your inventory after ${action.tries} click(s), giving up (server rejected the click)")
                    modMessage("[EarlyEnter] \u00a7c${action.kind.name.lowercase()} mask didn't equip\u00a7f \u00a77after 2 clicks - swap manually, and please check the log")
                    return
                }
                val hit = findMaskSlot(container, action.kind)
                if (hit == null) {
                    if (action.tries == 0) {
                        // the mask is not in the inventory region: it is already on the head, or not carried
                        if (verboseLog) SpLog.info("earlyenter", "swap ${action.kind.name}: mask not in the inventory region (already equipped or not carried), nothing to click")
                        modMessage("[EarlyEnter] \u00a7e${action.kind.name.lowercase()} mask not in your inventory\u00a7f \u00a77- nothing to click")
                        synchronized(lock) { actions.remove(action) }
                    } else {
                        // clicked before but the uuid is still seen: mid-sync, wait a beat
                        dumpScreenItems(container)
                        synchronized(lock) { action.fireAt = now + RETRY_MS }
                    }
                    return
                }
                // predict where the item this click displaces off the head will land, so the swap-back knows
                // exactly which slot to look at - inventories without a personal deleter fill with dungeon
                // junk, so the landing slot differs per player and has to be worked out, not assumed
                action.predictedLandSlot = predictedLandSlotFor(container, hit.index)
                val displaced = synchronized(lock) {
                    if (swapBackPending) "your ${action.kind.other.name.lowercase()} mask"
                    else originalHelmet?.let { "your ${it.render()}" } ?: "the item on your head"
                }
                runCatching { mc.player?.clickSlot(hit.index) }
                action.tries++
                action.clickedUuid = hit.uuid
                SpLog.info(
                    "earlyenter",
                    "swap click ${action.kind.name} ${hit.renderForLog} (try=${action.tries}); $displaced should land in inventory slot ${action.predictedLandSlot}; verifying in ${verifyMs() / 1000.0}s"
                )
                synchronized(lock) { action.fireAt = now + verifyMs() }
            }
        }
    }

    /**
     * The first swap of the order has no mask pop before it (nothing wore a mask yet), so the screen itself
     * triggers it: with "Pheonix Pet" on only once the pet has popped, without it the moment the screen is
     * open - both after the action delay. Every later swap stays pop-driven.
     */
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
        SpLog.info("earlyenter", "bootstrap: queue first swap -> ${first.name} mask (order ${if (bonzoFirst) "Bonzo->Spirit" else "Spirit->Bonzo"}${if (pheonixPet) ", phoenix gate open" else ""})")
        modMessage("[EarlyEnter] starting the order: clicking the \u00a7a${first.name.lowercase()}\u00a7f mask")
    }

    /**
     * The slot you'd click manually: ONE click on the mask item replaces whatever is on your head (no
     * unequip step needed). Searched ONLY in the player-inventory region of the menu - every skyblock
     * chest menu is container slots followed by your 36 inventory slots, and the top grid only MIRRORS
     * your worn gear: the server rejects clicks there by closing and re-sending the screen. Matched by
     * skyblock item id first, then by cleaned display name, so reforged / fragged / starred variants
     * ("Necrotic Spirit Mask", "Sunny Bonzo's Mask") are found too.
     */
    private fun findMaskSlot(container: AbstractContainerScreen<*>, kind: MaskKind): SlotHit? =
        slotInInvRegion(container) { kind.matches(it) }

    private fun findRestoreSlot(container: AbstractContainerScreen<*>, wanted: HeadMatch): SlotHit? =
        // the exact displaced instance first (skyblock uuid), so a spare copy with the same name in a
        // cluttered inventory is never clicked by mistake; then id, then cleaned display name
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

    /** after an equip click the server moves the item onto your head; the inventory region it left is the fast confirm */
    private fun invRegionHoldsUuid(container: AbstractContainerScreen<*>, uuid: String): Boolean {
        if (uuid.isBlank()) return false
        val firstInvSlot = container.menu.slots.size - 36
        return container.menu.slots.withIndex().any { (i, s) ->
            i >= firstInvSlot && !s.item.isEmpty && runCatching { s.item.itemUUID }.getOrNull().orEmpty() == uuid
        }
    }

    /**
     * Where the server puts the item your equip click displaces off your head: vanilla inventory-add
     * order - first free hotbar slot (the last 9 menu slots), then first free main-inventory slot. Your
     * 19:11 test displaced the golden head into slot 54 exactly like that (your hotbar was full). If the
     * whole inventory region is full, the clicked mask's own slot is what frees up first, so it lands there.
     */
    private fun predictedLandSlotFor(container: AbstractContainerScreen<*>, clickedSlot: Int): Int {
        val n = container.menu.slots.size
        val firstInvSlot = n - 36
        for (i in (n - 9) until n) if (container.menu.slots[i].item.isEmpty) return i
        for (i in firstInvSlot until (n - 9)) if (container.menu.slots[i].item.isEmpty) return i
        return clickedSlot
    }

    /** confirm the landing prediction: what sits in the predicted slot once the equip synced */
    private fun reportLanding(container: AbstractContainerScreen<*>, action: Swap) {
        val at = action.predictedLandSlot
        if (at < 0) return
        val item = container.menu.slots.getOrNull(at)?.item
        if (item == null || item.isEmpty) {
            SpLog.info("earlyenter", "swap ${action.kind.name}: predicted landing slot $at is still empty (hypixel syncs the displaced item late)")
            return
        }
        val isOriginal = synchronized(lock) { originalHelmet?.matches(item) == true }
        SpLog.info(
            "earlyenter",
            "swap ${action.kind.name}: slot $at now holds id='${runCatching { item.itemId }.getOrNull().orEmpty()}' '${cleanItemName(item)}'" +
                if (isOriginal) " - that's your original helmet, as predicted" else ""
        )
    }

    // ============================== gating ==============================

    private fun isTrackedScreen(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string
        // never touch the savepoint/leap playgrounds; 'Game Menu' renders as a container screen too and must
        // not poison the original-helmet capture or run screen logic (see savepoint.log 15:35:53)
        if (title.equalsOneOf("Spirit Leap", "Teleport to Player", "Obsidian", "Game Menu")) return false
        val filter = screenTitles.trim()
        if (filter.equals("*")) return true
        // blank / untouched config falls back to the /stats screen itself instead of any container screen
        val effective = filter.ifEmpty { "Stats & Equipment" }
        return effective.split(',').any { it.isNotBlank() && title.contains(it.trim(), ignoreCase = true) }
    }

    private fun reactionAllowed(): Boolean {
        // the sgdt rig must work anywhere e.g. while dry-testing in the village with the screen open; the
        // bypass stays on until its action chain (helmet restore + close tail) has fully drained
        if (synchronized(lock) { sgdtRunning || (rigArmed && actions.isNotEmpty()) }) return true
        if (!runCatching { DungeonUtils.inBoss }.getOrDefault(false)) return false
        if (!runCatching { DungeonUtils.isFloor(7) }.getOrDefault(false)) return false
        // dynamic: any goldor sit counts (sections 1-4 + core recore sits)
        return GoldorTracker.section in 1..5
    }

    private fun shouldWaitForSchizoKeypress(screen: Screen): Boolean {
        if (!enabled || !eeEnabled || !schizoMode) return false
        val container = screen as? AbstractContainerScreen<*> ?: return false
        if (!isTrackedScreen(container)) return false
        if (!reactionAllowed()) return false
        return synchronized(lock) { actions.any { it.fireAt == 0L } }
    }

    // ============================== helpers ==============================

    private fun headStack(): ItemStack? = mc.player?.getItemBySlot(EquipmentSlot.HEAD)

    private fun HeadMatch.render(): String = if (id.isNotBlank()) "$id ('$name')" else "'$name'"

    // bonzo's cooldown scales, so odin reads the exact seconds off the head's lore when it procs
    private fun headCooldownSeconds(): Int? = runCatching {
        val cooldownRegex = Regex("^Cooldown: (\\d+)s$")
        headStack()?.loreString
            ?.reversed()?.firstNotNullOfOrNull { cooldownRegex.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
    }.getOrNull()

    /** written once per screen open, and again whenever a slot search fails, so mismatches are debuggable */
    private fun dumpScreenItems(container: AbstractContainerScreen<*>) {
        if (!verboseLog || screenDumped) return
        screenDumped = true
        val items = container.menu.slots.withIndex().mapNotNull { (i, s) ->
            if (s.item.isEmpty) null
            else "$i=${runCatching { s.item.itemId }.getOrNull().orEmpty().ifEmpty { "id?" }} '${cleanItemName(s.item)}' x${s.item.count}"
        }
        SpLog.info("earlyenter", "screen contents (${container.menu.slots.size} slots): ${items.joinToString(" ").take(1500)}")
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

    // ============================== sgdt test rig ==============================

    /**
     * /leaputils sgdt is a dry-test rig, not the module's input: a timer at the goldor tick cadence odin's
     * TickTimers shows (60t = 3s by default) replays the real proc sequence - the phoenix pet first (opens
     * the "Pheonix Pet" gate), then the masks in the configured order - exactly as the live chat lines would.
     * Real pops were always wired: the chat handler feeds the same onPop/onPhoenixPop with synthetic=false.
     */
    fun toggleTestTimer() {
        val nowRunning: Boolean
        synchronized(lock) {
            nowRunning = !sgdtRunning
            sgdtRunning = nowRunning
            sgdtTick = 0
            sgdtQueue.clear()
            if (nowRunning) {
                // a fresh rig is a fresh sit: pops, cooldowns and stale queues go so reruns always fire
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
                // the rig replays the real sequence: the phoenix pop opens the gate, then the masks pop in
                // the configured order (the first one is what the bootstrap puts on your head)
                sgdtPhoenixPending = true
                sgdtQueue += if (bonzoFirst) listOf(MaskKind.BONZO, MaskKind.SPIRIT) else listOf(MaskKind.SPIRIT, MaskKind.BONZO)
            }
        }
        if (nowRunning) {
            val order = if (bonzoFirst) "Bonzo -> Spirit" else "Spirit -> Bonzo"
            SpLog.info("earlyenter", "SGDT started: fake death tick every ${sgdtInterval}s, order Phoenix -> $order")
            modMessage("[EarlyEnter] \u00a7bSGDT\u00a7f death-tick timer started - one fake pop every ${sgdtInterval}s (Phoenix -> $order)")
        } else {
            SpLog.info("earlyenter", "SGDT stopped")
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
            SpLog.info("earlyenter", "SGDT queue drained, timer stopped")
            modMessage("[EarlyEnter] \u00a7bSGDT\u00a7f did all the fake pops, timer stopped")
            return
        }
        onPop(kind, System.currentTimeMillis(), synthetic = true)
    }

    // ============================== resets ==============================

    /** /leaputils eereset - clear the cooldown timers, pops and queues without toggling the module */
    fun resetForCommand() {
        resetRunState("manual reset (/leaputils eereset)")
        modMessage("[EarlyEnter] \u00a7btimers reset\u00a7f - cooldowns, pops and queues cleared")
    }

    private fun resetRunState(reason: String) {
        synchronized(lock) {
            if (verboseLog) SpLog.info(
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

// reforge prefixes, colour codes and recomb stars live in the display name; match against fragments instead
private val colorCodeRegex = Regex("\u00a7.")

/**
 * "Necrotic Spirit Mask" (colour codes + reforge in front, stars behind) -> "necrotic spirit mask",
 * so a `contains("spirit mask")` check still hits on any reforged / fragged / starred variant.
 */
private fun cleanItemName(item: ItemStack?): String =
    item?.hoverName?.string
        ?.replace('\u2019', '\'')  // some fonts swap the apostrophe
        ?.replace(colorCodeRegex, "")
        ?.lowercase()
        ?.trim()
        ?: ""
