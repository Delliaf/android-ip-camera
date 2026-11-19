package com.github.digitallyrefined.androidipcamera.helpers

/**
 * Camera2 standard noise reduction and edge enhancement modes.
 *
 * Values are defined by CaptureRequest.NOISE_REDUCTION_MODE_* (0..4) and
 * CaptureRequest.EDGE_MODE_* (0..3) on Android 8. Separating independent
 * overrides is essential: PHOTO ZSL template defaults to NR=4, EDGE=3
 * on the LG-H872, and the previous combined CameraX-only preset could
 * not control Direct Camera2. "auto" deliberately leaves OEM template
 * defaults and the previous CameraX FAST/HQ preset intact.
 *
 * Never send a mode not advertised by the camera's characteristic array.
 * It is NOT safe to use LG vendor temporal_denoise tags in place of these.
 */
internal object LgG6IspModes {
    const val AUTO = "auto"
    val noiseChoices = mapOf("off" to 0, "fast" to 1, "hq" to 2,
        "minimal" to 3, "zsl" to 4)
    val edgeChoices = mapOf("off" to 0, "fast" to 1, "hq" to 2, "zsl" to 3)

    fun validNoise(value: String) = value == AUTO || noiseChoices.containsKey(value)
    fun validEdge(value: String) = value == AUTO || edgeChoices.containsKey(value)

    fun noise(value: String, advertised: IntArray?): Int? =
        noiseChoices[value]?.takeIf { advertised?.contains(it) == true }

    fun edge(value: String, advertised: IntArray?): Int? =
        edgeChoices[value]?.takeIf { advertised?.contains(it) == true }
}
