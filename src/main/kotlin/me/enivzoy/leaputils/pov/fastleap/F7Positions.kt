package me.enivzoy.leaputils.pov.fastleap

// quoi's numeric phase/stage detection, kept pure so it can be tested without minecraft
object F7Positions {

    // quoi Floor7.getPhaseAt: y bands, >210 p1, >155 p2, >100 p3, >45 p4, else p5
    fun phaseAt(y: Double?): Int = when {
        y == null -> 0
        y > 210.0 -> 1
        y > 155.0 -> 2
        y > 100.0 -> 3
        y > 45.0 -> 4
        else -> 5
    }

    // quoi Floor7.getStageAt: only meaningful in the position phase p3
    fun stageAt(x: Double?, z: Double?, phaseAt: Int): Int {
        if (x == null || z == null || phaseAt != 3) return 0
        return when {
            x in 89.0..113.0 && z in 30.0..122.0 -> 1
            x in 19.0..111.0 && z in 121.0..145.0 -> 2
            x in -6.0..19.0 && z in 51.0..143.0 -> 3
            x in -2.0..90.0 && z in 27.0..51.0 -> 4
            x in 41.0..68.0 && z in 59.0..117.0 -> 5
            else -> 0
        }
    }
}
