package com.github.digitallyrefined.androidipcamera.helpers

/**
 * LG Camera0 reports [15,15], [20,20], [2,24], [24,24], [2,30], [30,30]
 * (among others). Picking the FIRST containing 15 locks exposure to 1/15s.
 * CameraX instead chooses the widest [2,30] and can expose longer at night.
 * Pure logic for JVM tests; Camera2 Range<Int> conversion is in the backend.
 */
internal object LgG6AeFps {
    fun select(ranges: List<Pair<Int, Int>>, target: Int, adaptive: Boolean): Pair<Int, Int>? {
        val available = ranges.filter { it.first > 0 && it.first <= target && it.second >= target }
        val comparator = compareBy<Pair<Int, Int>>({ it.second - it.first }, { it.second })
        return if (adaptive) available.maxWithOrNull(comparator)
               else available.minWithOrNull(comparator)
    }
}
