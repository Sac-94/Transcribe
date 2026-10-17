package com.transcribe.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.transcribe.app.data.Settings

class TranscribeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Both the full app and the share window must agree on light/dark, even when the
        // share window is the first thing to start the process.
        applyNightMode(Settings(this))
    }

    companion object {
        /** Follows the system theme until the user flips the switch in the app. */
        fun applyNightMode(settings: Settings) {
            val mode = when (settings.darkMode) {
                true -> AppCompatDelegate.MODE_NIGHT_YES
                false -> AppCompatDelegate.MODE_NIGHT_NO
                null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
