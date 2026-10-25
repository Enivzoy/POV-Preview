package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object Savepoint : Module(
    name = "Savepoint",
    category = Category.custom("Leap Utils", 860, 10),
    description = "Auto leap to correct people for F7/M7 phases"
) {
    val savepointEnabled by BooleanSetting("Enabled", true, desc = "")
    val delay by NumberSetting("Delay", 250, 0..1000, 10, desc = "Delay after opening the leap menu")

    val maxor by BooleanSetting("Maxor", true, desc = "")
    val maxorLeftClass by SelectorSetting("Leap if left", DungeonClass.BERSERK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val maxorLeftTo by SelectorSetting("To left", DungeonClass.TANK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val maxorRightClass by SelectorSetting("Leap if right", DungeonClass.ARCHER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val maxorRightTo by SelectorSetting("To right", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    val storm by BooleanSetting("Storm", true, desc = "Run the Drop off and Purple Spotter leap sequence after Maxor")
    val dropOffSpotter by SelectorSetting("Drop off spotter", DungeonClass.BERSERK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val purpleSpotter by SelectorSetting("Purple Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    val goldor by BooleanSetting("Goldor", true, desc = "")
    val goldorIf by SelectorSetting("Goldor Leap If Class", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val goldorTo by SelectorSetting("Goldor Waypoint", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    val necron by BooleanSetting("Necron", true, desc = "")

    val soloCrush by BooleanSetting("SoloCrush", true, desc = "Auto-leap to the spotter for the current Goldor section")
    val section1Spotter by SelectorSetting("Section 1 Spotter", DungeonClass.BERSERK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val section2Spotter by SelectorSetting("Section 2 Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val section3Spotter by SelectorSetting("Section 3 Spotter", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val coreSpotter by SelectorSetting("Core Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val earlyNecronSpotter by SelectorSetting("Early Necron Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    private var menuOpenedAt = 0L
    private var inLeapMenu = false
    private var leapedThisMenu = false
    private var maxorLeapedThisPhase = false
    private var sequenceStep = 0
    private var highestGoldorSection = 0
    private var lastPhaseKey = ""
    private var loggedPhaseInfo = false
    private var loggedMissing = false

    override fun onDisable() {
        inLeapMenu = false
        menuOpenedAt = 0
        leapedThisMenu = false
        maxorLeapedThisPhase = false
        sequenceStep = 0
        highestGoldorSection = 0
        lastPhaseKey = ""
        super.onDisable()
    }

    private fun getMyClass(): DungeonClass? {
        val p = OdinAccess.currentDungeonPlayer ?: return null
        return if (p.clazz != DungeonClass.EMPTY) p.clazz else null
    }

    private fun find(clazz: DungeonClass): DungeonPlayer? {
        return OdinAccess.leapTeammates.firstOrNull { it.clazz == clazz && !it.isDead }
    }

    private fun isLeapScreen(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title.string
        return title == "Spirit Leap" || title == "Teleport to Player" || title == "Obsidian"
    }

    private fun warnMissing(what: String) {
        if (loggedMissing) return
        loggedMissing = true
        println("[LeapUtils Savepoint] waiting for $what")
    }

    private fun leapTo(p: DungeonPlayer, label: String, screen: AbstractContainerScreen<*>): Boolean {
        if (!OdinAccess.autoLeapTo(p, screen)) {
            warnMissing("${p.name} ($label) in the leap menu")
            return false
        }
        leapedThisMenu = true
        println("[LeapUtils Savepoint] $label: leapt to ${p.name} (${p.clazz})")
        return true
    }

    private fun handleMaxor(myClass: DungeonClass, screen: AbstractContainerScreen<*>) {
        if (maxorLeapedThisPhase) return
        val to = when (myClass) {
            maxorLeftClass -> maxorLeftTo
            maxorRightClass -> maxorRightTo
            else -> return
        }
        val target = find(to) ?: run { warnMissing("Maxor target ($to)"); return }
        if (leapTo(target, "Maxor", screen)) maxorLeapedThisPhase = true
    }

    private fun handleStorm(screen: AbstractContainerScreen<*>) {
        val to = if (sequenceStep == 0) dropOffSpotter else purpleSpotter
        val label = if (sequenceStep == 0) "Drop off Spotter" else "Purple Spotter"
        val target = find(to) ?: run { warnMissing("$label target ($to)"); return }
        if (leapTo(target, label, screen) && sequenceStep == 0) sequenceStep = 1
    }

    private fun handleGoldor(myClass: DungeonClass?, screen: AbstractContainerScreen<*>) {
        if (soloCrush) {
            val section = getGoldorSection()
            val (to, label) = when (section) {
                1 -> section1Spotter to "Section 1 Spotter"
                2 -> section2Spotter to "Section 2 Spotter"
                3 -> if (sequenceStep == 0) section3Spotter to "Section 3 Spotter" else coreSpotter to "Core Spotter"
                4 -> earlyNecronSpotter to "Early Necron Spotter"
                else -> {
                    warnMissing("Goldor section number (section=$section)")
                    return
                }
            }
            val spotter = find(to) ?: run { warnMissing("$label target ($to)"); return }
            if (leapTo(spotter, label, screen) && section == 3 && sequenceStep == 0) sequenceStep = 1
            return
        }

        if (goldor && myClass == goldorIf) {
            val target = find(goldorTo) ?: run { warnMissing("Goldor target ($goldorTo)"); return }
            leapTo(target, "Goldor", screen)
            return
        }
    }

    private fun handleNecron(screen: AbstractContainerScreen<*>) {
        val target = OdinAccess.findClosestTeammateTo(54.0, 65.0, 76.0, screen)
            ?: run { warnMissing("a leapable teammate near the Necron spot (54, 65, 76)"); return }
        leapTo(target, "Necron", screen)
    }

    private fun getGoldorSectionKey(phase: String): String {
        if (!phase.contains("goldor") && !phase.contains("p3")) return ""
        return ":section=${getGoldorSection()}"
    }

    private fun getGoldorSection(): Int? {
        OdinAccess.terminalSection?.let { observed ->
            if (observed in 1..4) highestGoldorSection = maxOf(highestGoldorSection, observed)
        }
        return highestGoldorSection.takeIf { it > 0 }
    }

    private fun getPhase(): String {
        val bp = OdinAccess.bossPhase
        if (bp != null && bp.isNotEmpty() && bp != "null") {
            val b = bp.replace("M7Phases.", "").replace("F7Phases.", "").lowercase()
            if (b.contains("p1") || b.contains("maxor")) return "maxor"
            if (b.contains("p2") || b.contains("storm")) return "storm"
            if (b.contains("p3") || b.contains("goldor")) return "goldor"
            if (b.contains("p4") || b.contains("necron")) return "necron"
            if (b.contains("term") || b.contains("device")) return "terminals"
            return b
        }
        val room = OdinAccess.currentRoomData?.let { OdinAccess.getRoomName(it) } ?: ""
        return room.lowercase()
    }

    private fun isF7(): Boolean {
        val f = OdinAccess.floorName ?: return false
        return f == "F7" || f == "M7"
    }

    init {
        on<ScreenEvent.Render>(EventPriority.HIGHEST + 150) {
            if (!enabled || !savepointEnabled) {
                inLeapMenu = false
                leapedThisMenu = false
                return@on
            }

            val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: run {
                inLeapMenu = false
                leapedThisMenu = false
                return@on
            }
            if (!isLeapScreen(screen)) {
                inLeapMenu = false
                leapedThisMenu = false
                return@on
            }
            if (!inLeapMenu) {
                inLeapMenu = true
                menuOpenedAt = System.currentTimeMillis()
                leapedThisMenu = false
            }

            if (!OdinAccess.inBoss) return@on
            if (!isF7()) return@on
            if (leapedThisMenu) return@on

            val now = System.currentTimeMillis()
            val phase = getPhase()
            val key = (OdinAccess.floorName ?: "") + ":" + phase + getGoldorSectionKey(phase)
            if (key != lastPhaseKey) {
                lastPhaseKey = key
                sequenceStep = 0
                maxorLeapedThisPhase = false
                loggedPhaseInfo = false
                loggedMissing = false
                menuOpenedAt = now
                println("[LeapUtils Savepoint] Phase changed: $phase (raw:${OdinAccess.bossPhase}) floor=${OdinAccess.floorName}")
            }
            if (now - menuOpenedAt < delay) return@on

            val myClass = getMyClass()
            if (!loggedPhaseInfo) {
                loggedPhaseInfo = true
                println("[LeapUtils Savepoint] phase=$phase myClass=${myClass ?: "unknown"} teammates=${OdinAccess.leapTeammates.size}")
            }

            when {
                maxor && phase.contains("maxor") -> {
                    val clazz = myClass ?: return@on
                    handleMaxor(clazz, screen)
                }
                storm && phase.contains("storm") -> handleStorm(screen)
                (phase.contains("goldor") || phase.contains("p3")) -> handleGoldor(myClass, screen)
                necron && phase.contains("necron") -> handleNecron(screen)
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
            if (screen == null || !isLeapScreen(screen)) {
                inLeapMenu = false
            }
            if (!OdinAccess.inBoss) {
                leapedThisMenu = false
                maxorLeapedThisPhase = false
                sequenceStep = 0
                highestGoldorSection = 0
                lastPhaseKey = ""
                loggedPhaseInfo = false
                loggedMissing = false
            }
        }
    }
}
