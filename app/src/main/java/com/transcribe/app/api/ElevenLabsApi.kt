package com.transcribe.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ElevenLabsApi {
    private const val ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
    private const val MODEL_ID = "scribe_v1"

    /** An error answer from ElevenLabs; [statusCode] lets callers tell a bad key (401) apart. */
    class ApiException(val statusCode: Int, message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Streams [audio] to ElevenLabs and returns the transcript.
     * [languageCode] null means auto-detect, which is what voice notes want.
     */
    suspend fun transcribe(
        audio: File,
        fileName: String,
        mimeType: String?,
        apiKey: String,
        languageCode: String? = null
    ): String = withContext(Dispatchers.IO) {
        val mediaType = audioMediaType(fileName, mimeType).toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, audio.asRequestBody(mediaType))
            .addFormDataPart("model_id", MODEL_ID)
            // Voice notes read better without "(laughter)"-style tags mixed into the text.
            .addFormDataPart("tag_audio_events", "false")
        if (!languageCode.isNullOrBlank()) {
            body.addFormDataPart("language_code", languageCode)
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("xi-api-key", apiKey)
            .post(body.build())
            .build()

        client.newCall(request).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, errorMessage(response.code, raw))
            }
            parseText(raw)
        }
    }

    private fun audioMediaType(fileName: String, mimeType: String?): String {
        val byExtension = when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus", "oga" -> "audio/ogg"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            else -> null
        }
        if (byExtension != null) return byExtension

        // WhatsApp reports "audio/ogg; codecs=opus"; keep only the type itself.
        val declared = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return if (declared != null && declared.startsWith("audio/")) declared else "audio/mpeg"
    }

    private fun errorMessage(code: Int, raw: String): String {
        val detail: Any? = try {
            JSONObject(raw).opt("detail")
        } catch (e: Exception) {
            null
        }
        val message = when (detail) {
            is JSONObject -> detail.optString("message").ifBlank { detail.optString("status") }
            is JSONArray -> detail.optJSONObject(0)?.optString("msg").orEmpty()
            is String -> detail
            else -> ""
        }
        return when {
            message.isNotBlank() -> "ElevenLabs: $message"
            code == 401 -> "ElevenLabs rejected the API key"
            else -> "ElevenLabs error ($code)"
        }
    }

    private fun parseText(raw: String): String {
        val json = JSONObject(raw.ifBlank { "{}" })
        val text = json.optString("text", "")
        if (text.isNotBlank()) return text

        // Fallback: concatenate transcript segments if present.
        val segments = json.optJSONArray("segments") ?: json.optJSONArray("transcripts")
        if (segments != null) {
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONObject(i)
                val t = segment?.optString("text", "") ?: segments.optString(i, "")
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t.trim())
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        return ""
    }
}
