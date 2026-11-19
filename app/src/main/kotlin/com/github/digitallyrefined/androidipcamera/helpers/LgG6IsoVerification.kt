package com.github.digitallyrefined.androidipcamera.helpers

/** Manual ISO > advertised range needs actual CaptureResult validation.
 * A partly applied ISO (e.g. requested 3200, actual 2700) is still useful
 * and must NOT be treated the same as a HAL locked to 800.
 */
internal object LgG6IsoVerification {
    fun extendedRequestIgnored(requested: Int, actual: Int, advertisedMax: Int): Boolean {
        if (requested <= advertisedMax) return false
        val tolerance = maxOf(16, advertisedMax / 20)
        return actual <= advertisedMax + tolerance
    }
}
