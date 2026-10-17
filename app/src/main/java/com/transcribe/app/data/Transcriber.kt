package com.transcribe.app.data

import android.content.Context
import android.net.Uri
import com.transcribe.app.R
import com.transcribe.app.api.ElevenLabsApi
import com.transcribe.app.api.GoogleTranslateApi
import com.transcribe.app.util.AudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** A failure the UI can explain to the person. */
class TranscribeException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    enum class Kind { MISSING_KEY, BAD_KEY, NO_AUDIO, NO_SPEECH, NETWORK, UNREADABLE, API, UNKNOWN }

    /** True when the fix is to enter or correct the ElevenLabs key. */
    val needsKey: Boolean
        get() = kind == Kind.MISSING_KEY || kind == Kind.BAD_KEY
}

/** Transcribes one shared audio file (and translates it through the API when that is turned on). */
class Transcriber(context: Context, private val settings: Settings) {
    internal val appContext: Context = context.applicationContext

    class Outcome(
        val fileName: String,
        val transcript: String,
        /** Set when the Cloud Translation API was used and succeeded. */
        val translation: String?,
        /** Set when the Cloud Translation API was used and failed; the transcript is still good. */
        val translationError: String?
    )

    suspend fun transcribe(uri: Uri): Outcome {
        val key = settings.elevenLabsKey.trim()
        if (key.isEmpty()) throw TranscribeException(TranscribeException.Kind.MISSING_KEY)

        val audio = try {
            AudioCache.copy(appContext, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranscribeException(TranscribeException.Kind.UNREADABLE, e.message, e)
        }

        try {
            val transcript = try {
                ElevenLabsApi.transcribe(audio.file, audio.uploadName, audio.mimeType, key).trim()
            } catch (e: ElevenLabsApi.ApiException) {
                val kind = if (e.statusCode == 401) {
                    TranscribeException.Kind.BAD_KEY
                } else {
                    TranscribeException.Kind.API
                }
                throw TranscribeException(kind, e.message, e)
            } catch (e: IOException) {
                throw TranscribeException(TranscribeException.Kind.NETWORK, e.message, e)
            } catch (e: JSONException) {
                throw TranscribeException(
                    TranscribeException.Kind.API,
                    "Unexpected answer from ElevenLabs",
                    e
                )
            }
            if (transcript.isEmpty()) throw TranscribeException(TranscribeException.Kind.NO_SPEECH)

            var translation: String? = null
            var translationError: String? = null
            val googleKey = settings.googleApiKey.trim()
            if (!settings.useTranslateApp && googleKey.isNotEmpty()) {
                try {
                    translation = GoogleTranslateApi.translate(transcript, googleKey, settings.targetLanguage)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    translationError = e.message ?: "unknown error"
                }
            }

            settings.addHistory(
                Settings.HistoryEntry(
                    file = audio.displayName,
                    transcription = transcript,
                    translation = translation.orEmpty(),
                    timestamp = System.currentTimeMillis()
                )
            )
            return Outcome(audio.displayName, transcript, translation, translationError)
        } finally {
            audio.file.delete()
        }
    }
}

/** The combined result of one or more shared files. */
class Batch(
    val transcript: String,
    val translation: String?,
    val translationError: String?
)

/**
 * Transcribes every file in order, reporting progress as it goes.
 * With several files, the ones that worked are kept when another fails; a wrong or missing key
 * fails them all, so that error is thrown straight away.
 */
suspend fun Transcriber.transcribeAll(
    uris: List<Uri>,
    onProgress: (current: Int, total: Int) -> Unit
): Batch {
    val total = uris.size
    val transcripts = ArrayList<String>()
    val translations = ArrayList<String>()
    var translationError: String? = null

    for ((index, uri) in uris.withIndex()) {
        onProgress(index + 1, total)
        try {
            val outcome = transcribe(uri)
            transcripts += labelledResult(outcome.fileName, outcome.transcript, total)
            outcome.translation?.let { translations += labelledResult(outcome.fileName, it, total) }
            if (outcome.translationError != null) translationError = outcome.translationError
        } catch (e: TranscribeException) {
            if (total == 1 || e.needsKey) throw e
            val name = withContext(Dispatchers.IO) { AudioCache.displayName(appContext, uri) }
            transcripts += appContext.getString(R.string.file_failed, name)
        }
    }
    return Batch(
        transcript = transcripts.joinToString("\n\n"),
        translation = translations.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        translationError = translationError
    )
}

/** With several files at once each result gets its file name above it; a single one stays bare. */
fun labelledResult(fileName: String, text: String, totalFiles: Int): String =
    if (totalFiles > 1) "[$fileName]\n$text" else text
