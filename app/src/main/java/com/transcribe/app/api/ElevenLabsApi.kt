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
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun transcribe(audioBytes: ByteArray, fileName: String, apiKey: String): String {
        val mimeType = when {
            fileName.endsWith(".mp3") -> "audio/mpeg"
            fileName.endsWith(".wav") -> "audio/wav"
            fileName.endsWith(".ogg") || fileName.endsWith(".opus") -> "audio/ogg"
            fileName.endsWith(".m4a") || fileName.endsWith(".aac") -> "audio/mp4"
            fileName.endsWith(".flac") -> "audio/flac"
            else -> "audio/mpeg"
        }

        val audioBody = audioBytes.toRequestBody(mimeType.toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", fileName, audioBody)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(filePart)
            .addFormDataPart("model_id", "scribe_v1")
            .build()

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()

        if (!response.isSuccessful) {
            throw Exception("ElevenLabs API error: $body")
        }

        val json = JSONObject(body ?: "")
        return json.optString("text", "")
    }
}
