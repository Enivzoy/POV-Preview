package me.enivzoy.leaputils.pov

import com.odtheking.odin.events.FloorEnterEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on

object GoldorTracker {

    var section = 0
        private set
    var terminalsDone = 0
        private set
    var leversDone = 0
        private set
    var deviceDone = 0
        private set
    var gateOpen = false
        private set
    var lastObjectiveBy: String? = null
        private set
    var lastObjectiveKind: String? = null
        private set

    private val startRegex = Regex("""^\[BOSS\] Goldor: Who dares trespass into my domain\?$""")
    private val objectiveRegex = Regex("""^(.{1,16}) (activated|completed) a (terminal|lever|device)! \((\d+)/(\d+)\)$""")
    private val gateRegex = Regex("""^The gate has been destroyed!$""")
    private val coreRegex = Regex("""^The Core entrance is opening!$""")

    private var lastObjectiveComplete = false

    fun reset(reason: String) {
        SpLog.info("goldor", "reset ($reason) was section=$section t=$terminalsDone l=$leversDone d=$deviceDone gate=$gateOpen")
        section = 0
        terminalsDone = 0
        leversDone = 0
        deviceDone = 0
        gateOpen = false
        lastObjectiveBy = null
        lastObjectiveKind = null
        lastObjectiveComplete = false
    }

    fun handle(raw: String): Boolean {
        val msg = stripFormatting(raw).trim()
        when {
            startRegex.matches(msg) -> {
                reset("goldor start")
                section = 1
                SpLog.info("goldor", "boss start -> section=1")
                return true
            }
            coreRegex.matches(msg) -> {
                if (section in 1..4) {
                    SpLog.info("goldor", "core entrance -> section $section -> 5")
                    section = 5
                }
                return true
            }
            gateRegex.matches(msg) -> {
                gateOpen = true
                SpLog.info("goldor", "gate destroyed (section=$section objComplete=$lastObjectiveComplete by=$lastObjectiveBy)")
                maybeAdvance()
                return true
            }
            else -> {
                val m = objectiveRegex.find(msg) ?: return false
                val who = m.groupValues[1].trim()
                val kind = m.groupValues[3]
                val done = m.groupValues[4].toIntOrNull() ?: return false
                val total = m.groupValues[5].toIntOrNull() ?: return false
                when (kind) {
                    "terminal" -> terminalsDone = done
                    "lever" -> leversDone = done
                    "device" -> deviceDone = done
                }
                lastObjectiveBy = who
                lastObjectiveKind = kind
                lastObjectiveComplete = done >= total
                SpLog.info("goldor", "$kind $done/$total by '$who' complete=$lastObjectiveComplete (t=$terminalsDone l=$leversDone d=$deviceDone)")
                maybeAdvance()
                return true
            }
        }
    }

    private fun maybeAdvance() {
        if (section in 1..4 && lastObjectiveComplete && gateOpen) {
            SpLog.info("goldor", "section $section -> ${section + 1}")
            section += 1
            gateOpen = false
            lastObjectiveComplete = false
            terminalsDone = 0
            leversDone = 0
            deviceDone = 0
        }
    }

    private fun stripFormatting(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\u00a7') i += 2 else sb.append(text[i++])
        }
        return sb.toString()
    }

    init {
        on<MessageEvent.Chat> {
            handle(message)
        }
        on<LevelEvent.Load> {
            reset("world load")
        }
        on<FloorEnterEvent> {
            reset("floor enter")
        }
    }
}
