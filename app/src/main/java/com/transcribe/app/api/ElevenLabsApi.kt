package com.transcribe.app.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ElevenLabsApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun transcribe(
        audioBytes: ByteArray,
        fileName: String,
        apiKey: String,
        languageCode: String? = null
    ): String {
        val mimeType = when {
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            fileName.endsWith(".ogg", ignoreCase = true) || fileName.endsWith(".opus", ignoreCase = true) -> "audio/ogg"
            fileName.endsWith(".m4a", ignoreCase = true) || fileName.endsWith(".aac", ignoreCase = true) -> "audio/mp4"
            fileName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
            fileName.endsWith(".webm", ignoreCase = true) -> "audio/webm"
            else -> "audio/mpeg"
        }

        val audioBody = audioBytes.toRequestBody(mimeType.toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", fileName, audioBody)

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(filePart)
            .addFormDataPart("model_id", "scribe_v1")

        if (!languageCode.isNullOrBlank()) {
            builder.addFormDataPart("language_code", languageCode)
        }

        val requestBody = builder.build()

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw Exception("ElevenLabs API error (${response.code}): ${body ?: ""}")
            }
            val json = JSONObject(body ?: "{}")
            val text = json.optString("text", "")
            if (text.isNotBlank()) return text
            // Fallback: concatenate transcript segments if present
            val segments = json.optJSONArray("segments") ?: json.optJSONArray("transcripts")
            if (segments != null) {
                val sb = StringBuilder()
                for (i in 0 until segments.length()) {
                    val seg = segments.optJSONObject(i)
                    val t = seg?.optString("text", "") ?: segments.optString(i, "")
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(t.trim())
                    }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            return ""
        }
    }
}
