package com.transcribe.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands text to the Google Translate app instead of calling a translation API.
 *
 * The Translate app can't be told which language to use through these intents; it uses the
 * pair last picked inside it. The target language we know about is only used for the
 * browser fallback.
 */
object TranslateLauncher {
    private const val TRANSLATE_PACKAGE = "com.google.android.apps.translate"

    /** Returns false when nothing could be opened. */
    fun open(context: Context, text: String, targetLanguage: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Same entry point the system text-selection menu uses for its "Translate" action.
        val processText = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            setPackage(TRANSLATE_PACKAGE)
        }
        if (start(context, processText)) return true

        // Older versions of the app only take a plain share.
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, trimmed)
            setPackage(TRANSLATE_PACKAGE)
        }
        if (start(context, send)) return true

        // Not installed: use the website.
        val url = "https://translate.google.com/?sl=auto&tl=${Uri.encode(targetLanguage)}" +
            "&text=${Uri.encode(trimmed)}&op=translate"
        return start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    // startActivity() works for an explicit package even without a <queries> entry, unlike
    // resolveActivity(), which returns null for packages hidden on Android 11+.
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
