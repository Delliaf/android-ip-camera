package com.github.digitallyrefined.androidipcamera.helpers

/**
 * Bounded and reproducible adaptive LG Camera0 comparison matrix.
 * ALL tests use the exact same requested 4.5x optical-field crop; it is
 * digital/ISP or CPU ROI, never an optical zoom lens. Continuous ISO/exposure
 * and diopter ranges cannot be exhaustively enumerated.
 */
internal data class LgBenchmarkCase(
    val template: Int,
    val pipeline: String,
    val nr: Int? = null,
    val edge: Int? = null,
    val aberration: Int? = null,
    val awb: Int? = null,
    val iso: Int? = null,
    val shutterUs: Long? = null,
    val focusD: Float? = null
) {
    val id: String get() {
        val pieces = listOf(
            "t$template", pipeline,
            "nr${nr ?: "auto"}", "e${edge ?: "auto"}", "ab${aberration ?: "auto"}",
            "wb${awb ?: "auto"}", "i${iso ?: "auto"}",
            "s${shutterUs ?: "auto"}", "f${focusD ?: "auto"}")
        return pieces.joinToString("_").replace('.', 'p')
    }
}

internal object LgBenchmarkPlan {
    const val ZOOM = 4.5
    const val MAX_QUICK_CASES = 24
    const val MAX_DEEP_CASES = 90

    /** Only offered ENUM values are sent, not invented vendor tags. */
    fun create(
        deep: Boolean, noiseModes: IntArray, edgeModes: IntArray,
        aberrationModes: IntArray, awbModes: IntArray,
        minIso: Int, maxIso: Int, minFocusD: Float, maxFocusD: Float
    ): List<LgBenchmarkCase> {
        val cases = linkedSetOf<LgBenchmarkCase>()
        val defaultNr = noiseModes.find { it == 1 } ?: noiseModes.firstOrNull()
        val defaultEdge = edgeModes.find { it == 1 } ?: edgeModes.firstOrNull()
        val highNr = noiseModes.find { it == 2 } ?: defaultNr
        val highEdge = edgeModes.find { it == 2 } ?: defaultEdge
        val offNr = noiseModes.find { it == 0 } ?: defaultNr
        val offEdge = edgeModes.find { it == 0 } ?: defaultEdge
        // All meaningful repeating-request templates and both crop pipelines.
        for (template in listOf(1, 3, 5)) {
            for (pipeline in listOf("isp", "cpu")) {
                cases += LgBenchmarkCase(template, pipeline)
                if (defaultNr != null || defaultEdge != null)
                    cases += LgBenchmarkCase(template, pipeline, defaultNr, defaultEdge)
                if (highNr != null || highEdge != null)
                    cases += LgBenchmarkCase(template, pipeline, highNr, highEdge)
                if (offNr != null || offEdge != null)
                    cases += LgBenchmarkCase(template, pipeline, offNr, offEdge)
            }
        }
        if (deep) {
            // Sweep each individually ADVERTISED discrete processing mode.
            for (nr in noiseModes.distinct()) cases += LgBenchmarkCase(5, "isp", nr = nr)
            for (edge in edgeModes.distinct()) cases += LgBenchmarkCase(5, "isp", edge = edge)
            for (ab in aberrationModes.distinct()) cases += LgBenchmarkCase(5, "isp", aberration = ab)
            for (awb in awbModes.distinct()) cases += LgBenchmarkCase(5, "isp", awb = awb)
            // Bounded continuous-value samples; always in supported manual sensor range.
            val isos = listOf(minIso, 100, 200, 400, 800, maxIso)
                .filter { it in minIso..maxIso }.distinct()
            val exposures = listOf(4_000L, 8_333L, 16_667L, 33_333L)
            for (iso in isos) for (shutter in exposures) {
                cases += LgBenchmarkCase(5, "isp", iso = iso, shutterUs = shutter)
            }
            val focus = listOf(minFocusD, .25f, .5f, 1f, 2f, 4f, 8f, maxFocusD)
                .filter { it in minFocusD..maxFocusD }.distinct()
            for (d in focus) cases += LgBenchmarkCase(5, "isp", focusD = d)
        }
        return cases.take(if (deep) MAX_DEEP_CASES else MAX_QUICK_CASES)
    }
}
