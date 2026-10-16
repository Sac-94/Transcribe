package com.transcribe.app.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GoogleTranslateApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun translate(text: String, apiKey: String, targetLang: String = "en"): String {
        if (text.isBlank()) return ""

        val jsonBody = JSONObject().apply {
            put("q", JSONArray().put(text))
            put("target", targetLang)
            put("format", "text")
        }

        val requestBody = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://translation.googleapis.com/language/translate/v2?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                if (response.code == 403) throw Exception("Invalid Google API key")
                if (response.code == 400) throw Exception("Bad translation request: ${body ?: ""}")
                throw Exception("Translation API error (${response.code}): ${body ?: ""}")
            }
            val json = JSONObject(body ?: "{}")
            val translations = json
                .optJSONObject("data")
                ?.optJSONArray("translations")

            return if (translations != null && translations.length() > 0) {
                translations.getJSONObject(0).optString("translatedText", "")
            } else {
                throw Exception("No translation result")
            }
        }
    }
}
