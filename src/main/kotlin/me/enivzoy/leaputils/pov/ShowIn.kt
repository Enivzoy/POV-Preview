package me.enivzoy.leaputils.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases

object ShowIn {
    enum class Place { BLOOD_RUSH, GOLDOR }

    private val FORMATTING = Regex("§.")

    private var bloodOpened = false

    fun onChat(raw: String) {
        if (raw.replace(FORMATTING, "") == "The BLOOD DOOR has been opened!") bloodOpened = true
    }

    fun reset() { bloodOpened = false }

    fun isIn(place: Place): Boolean = when (place) {
        Place.GOLDOR -> DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3
        Place.BLOOD_RUSH -> DungeonUtils.inDungeons && !DungeonUtils.inBoss && !bloodOpened
    }

    fun allows(places: Collection<Place>): Boolean = places.isEmpty() || places.any(::isIn)
}
