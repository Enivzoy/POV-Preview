package me.enivzoy.leaputils.pov

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.EventPriority
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
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
    val delay by NumberSetting("Delay", 250, 0..1000, 10, desc = "")

    val maxor by BooleanSetting("Maxor", true, desc = "")
    val maxorLeftClass by SelectorSetting("Leap if left", DungeonClass.BERSERK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val maxorLeftTo by SelectorSetting("To left", DungeonClass.TANK, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val maxorRightClass by SelectorSetting("Leap if right", DungeonClass.ARCHER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val maxorRightTo by SelectorSetting("To right", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    val storm by BooleanSetting("Storm", true, desc = "")
    val stormIf by SelectorSetting("Leap if", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val stormTo by SelectorSetting("To", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    val goldor by BooleanSetting("Goldor", true, desc = "")
    val goldorIf by SelectorSetting("Leap if", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val goldorTo by SelectorSetting("To", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    val necron by BooleanSetting("Necron", true, desc = "")
    val necronIf by SelectorSetting("Leap if", DungeonClass.HEALER, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))
    val necronTo by SelectorSetting("To", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    val terminals by BooleanSetting("Terminals/Core", true, desc = "")
    val coreSpotter by SelectorSetting("Core Spotter", DungeonClass.MAGE, desc = "", listOf(DungeonClass.BERSERK, DungeonClass.ARCHER, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER))

    private var lastLeap = 0L
    private var leapedThisPhase = false
    private var lastPhaseKey = ""

    private fun getMyClass(): DungeonClass? {
        val p = OdinAccess.currentDungeonPlayer ?: return null
        return if (p.clazz != DungeonClass.EMPTY) p.clazz else null
    }

    private fun find(clazz: DungeonClass): DungeonPlayer? {
        return OdinAccess.leapTeammates.firstOrNull { it.clazz == clazz && !it.isDead }
    }

    private fun leapTo(p: DungeonPlayer) {
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        OdinAccess.autoLeapTo(p, screen)
        println("[LeapUtils Savepoint] leapt to " + p.name + " (" + p.clazz + ")")
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
            println("[LeapUtils Savepoint] phase raw=$bp -> $b")
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
            if (!enabled || !savepointEnabled) return@on
            try {
                if (LeapMenu.enabled == false) {
                    // Don't require LeapMenu module to be enabled
                }
            } catch (e: Exception) {
            }
            if (!OdinAccess.inBoss) return@on
            if (!isF7()) return@on

            val mc = Minecraft.getInstance()
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@on
            val title = screen.title.string
            if (title != "Spirit Leap" && title != "Teleport to Player" && title != "Obsidian") return@on

            val now = System.currentTimeMillis()
            if (now - lastLeap < delay) return@on

            val phase = getPhase()
            val key = (OdinAccess.floorName ?: "") + ":" + phase
            if (key != lastPhaseKey) {
                lastPhaseKey = key
                leapedThisPhase = false
                println("[LeapUtils Savepoint] Phase changed: $phase (raw:${OdinAccess.bossPhase}) floor=${OdinAccess.floorName}")
            }
            if (leapedThisPhase) return@on

            val myClass = getMyClass() ?: run { println("[LeapUtils Savepoint] no myClass"); return@on }
            println("[LeapUtils Savepoint] in boss $phase myClass=$myClass leapTeammates=${OdinAccess.leapTeammates.size}")

            if (maxor && phase.contains("maxor")) {
                if (myClass == maxorLeftClass) {
                    val t = find(maxorLeftTo) ?: run { println("[LeapUtils Savepoint] no target for left crystal"); return@on }
                    leapTo(t)
                    leapedThisPhase = true
                    lastLeap = now
                    return@on
                }
                if (myClass == maxorRightClass) {
                    val t = find(maxorRightTo) ?: run { println("[LeapUtils Savepoint] no target for right crystal"); return@on }
                    leapTo(t)
                    leapedThisPhase = true
                    lastLeap = now
                    return@on
                }
                return@on
            }
            if (storm && phase.contains("storm")) {
                if (myClass == stormIf) {
                    val t = find(stormTo) ?: return@on
                    leapTo(t)
                    leapedThisPhase = true
                    lastLeap = now
                }
                return@on
            }
            if (goldor && (phase.contains("goldor") || phase.contains("p3"))) {
                if (myClass == goldorIf) {
                    val t = find(goldorTo) ?: run { println("[LeapUtils Savepoint] no target for goldor"); return@on }
                    leapTo(t)
                    leapedThisPhase = true
                    lastLeap = now
                    return@on
                }
                if (terminals && phase.contains("p3")) {
                    if (myClass == coreSpotter) return@on
                    val spotter = find(coreSpotter) ?: run { println("[LeapUtils Savepoint] no core spotter found"); return@on }
                    leapTo(spotter)
                    leapedThisPhase = true
                    lastLeap = now
                    return@on
                }
                return@on
            }
            if (necron && phase.contains("necron")) {
                if (myClass == necronIf) {
                    val t = find(necronTo) ?: run { println("[LeapUtils Savepoint] no target for necron"); return@on }
                    leapTo(t)
                    leapedThisPhase = true
                    lastLeap = now
                    return@on
                }
                return@on
            }
            if (terminals && (phase.contains("term") || phase.contains("device"))) {
                if (myClass == coreSpotter) return@on
                val spotter = find(coreSpotter) ?: run { println("[LeapUtils Savepoint] no core spotter found"); return@on }
                leapTo(spotter)
                leapedThisPhase = true
                lastLeap = now
                return@on
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!OdinAccess.inBoss) {
                leapedThisPhase = false
                lastPhaseKey = ""
            }
        }
    }
}
