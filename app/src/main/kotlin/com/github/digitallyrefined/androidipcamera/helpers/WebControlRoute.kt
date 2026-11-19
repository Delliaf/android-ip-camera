package com.github.digitallyrefined.androidipcamera.helpers

/** Pure HTTP routing rules; prevents the dashboard GET from swallowing lens controls. */
internal object WebControlRoute {
    const val EXPLICIT_PATH = "/control/set"
    private val displayOnlyKeys = setOf("v", "ts", "_", "cacheBust")

    /** Legacy UI sends GET /?focus_distance=..&ts=...; /?v=8 must show HTML. */
    fun isLegacyControl(path: String, query: Map<String, String>): Boolean {
        if (path != "/" && path.isNotEmpty()) return false
        return query.keys.any { it !in displayOnlyKeys }
    }

    fun filterLegacyControls(query: Map<String, String>): Map<String, String> =
        query.filterKeys { it !in displayOnlyKeys }

    /** No request-splitting, injection or unbounded keys accepted. */
    fun validKey(key: String): Boolean =
        key.length in 1..64 && key[0].isLetter() &&
            key.all { it.isLetterOrDigit() || it == '_' } && key.all { it.code < 128 }

    fun validValue(value: String): Boolean = value.length <= 512 &&
        value.none { it == '\r' || it == '\n' || it.code == 0 }
}
