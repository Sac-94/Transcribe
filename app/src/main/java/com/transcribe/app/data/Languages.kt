package com.transcribe.app.data

/** Target languages offered for translation (name to ISO code). */
object Languages {
    val targets: List<Pair<String, String>> = listOf(
        "English" to "en",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Hindi" to "hi",
        "Arabic" to "ar",
        "Chinese" to "zh",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Portuguese" to "pt",
        "Russian" to "ru",
        "Italian" to "it",
        "Dutch" to "nl",
        "Turkish" to "tr"
    )

    fun names(): List<String> = targets.map { it.first }

    fun indexOfCode(code: String?): Int {
        val index = targets.indexOfFirst { it.second == code }
        return if (index >= 0) index else 0
    }

    fun codeAt(index: Int): String = targets.getOrElse(index) { targets[0] }.second

    fun nameAt(index: Int): String = targets.getOrElse(index) { targets[0] }.first
}
