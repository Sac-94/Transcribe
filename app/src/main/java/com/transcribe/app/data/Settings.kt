package com.transcribe.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers: API keys, preferences and the recent-transcription history. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var elevenLabsKey: String
        get() = prefs.getString(KEY_ELEVEN_LABS, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_ELEVEN_LABS, value) }

    var googleApiKey: String
        get() = prefs.getString(KEY_GOOGLE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_GOOGLE, value) }

    /** `null` means the user never chose, so the app follows the system theme. */
    var darkMode: Boolean?
        get() = if (prefs.contains(KEY_DARK_MODE)) prefs.getBoolean(KEY_DARK_MODE, false) else null
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_DARK_MODE)
            } else {
                putBoolean(KEY_DARK_MODE, value)
            }
        }

    /**
     * Hand the transcript to the Google Translate app instead of calling the Cloud Translation
     * API. Until the user decides, this is on unless a Google API key has been saved.
     */
    var useTranslateApp: Boolean
        get() = if (prefs.contains(KEY_USE_TRANSLATE_APP)) {
            prefs.getBoolean(KEY_USE_TRANSLATE_APP, true)
        } else {
            googleApiKey.isBlank()
        }
        set(value) = prefs.edit { putBoolean(KEY_USE_TRANSLATE_APP, value) }

    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET_LANGUAGE, "en").orEmpty().ifBlank { "en" }
        set(value) = prefs.edit { putString(KEY_TARGET_LANGUAGE, value) }

    data class HistoryEntry(
        val file: String,
        val transcription: String,
        val translation: String,
        val timestamp: Long
    )

    /** Oldest first. */
    fun history(): List<HistoryEntry> {
        val array = readHistory()
        val entries = ArrayList<HistoryEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            entries.add(
                HistoryEntry(
                    file = o.optString("file"),
                    transcription = o.optString("transcription"),
                    translation = o.optString("translation"),
                    timestamp = o.optLong("timestamp", 0L)
                )
            )
        }
        return entries
    }

    fun addHistory(entry: HistoryEntry) {
        try {
            val array = readHistory()
            array.put(
                JSONObject().apply {
                    put("file", entry.file)
                    put("transcription", entry.transcription)
                    put("translation", entry.translation)
                    put("timestamp", entry.timestamp)
                }
            )
            while (array.length() > MAX_HISTORY) array.remove(0)
            prefs.edit { putString(KEY_HISTORY, array.toString()) }
        } catch (ignored: Exception) {
            // History is a convenience; never fail a transcription because of it.
        }
    }

    fun clearHistory() {
        prefs.edit { remove(KEY_HISTORY) }
    }

    private fun readHistory(): JSONArray = try {
        JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
    } catch (ignored: Exception) {
        JSONArray()
    }

    private companion object {
        const val PREFS_NAME = "transcribe_prefs"
        const val KEY_ELEVEN_LABS = "elevenLabsKey"
        const val KEY_GOOGLE = "googleApiKey"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_USE_TRANSLATE_APP = "use_translate_app"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_HISTORY = "history"
        const val MAX_HISTORY = 20
    }
}
