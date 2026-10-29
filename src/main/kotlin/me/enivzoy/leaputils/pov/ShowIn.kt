package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases

object ShowIn {
    enum class Place { BLOOD_RUSH, BOSS, GOLDOR }

    enum class Option(val place: Place?) {
        EVERYWHERE(null), ONLY_IN_BOSS(Place.BOSS), ONLY_IN_GOLDOR(Place.GOLDOR), ONLY_IN_BLOOD_RUSH(Place.BLOOD_RUSH)
    }

    private val FORMATTING = Regex("§.")

    private var bloodOpened = false

    fun onChat(raw: String) {
        if (raw.replace(FORMATTING, "") == "The BLOOD DOOR has been opened!") bloodOpened = true
    }

    fun reset() { bloodOpened = false }

    fun isIn(place: Place): Boolean = when (place) {
        Place.BOSS -> DungeonUtils.inBoss
        Place.GOLDOR -> DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3
        Place.BLOOD_RUSH -> DungeonUtils.inDungeons && !DungeonUtils.inBoss && !bloodOpened
    }

    fun allows(places: Collection<Place>): Boolean = places.isEmpty() || places.any(::isIn)

    fun allows(option: Int): Boolean = allows(listOfNotNull(Option.entries.getOrNull(option)?.place))
}
