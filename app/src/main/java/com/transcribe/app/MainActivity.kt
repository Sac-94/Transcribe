package com.transcribe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.transcribe.app.api.ElevenLabsApi
import com.transcribe.app.api.GoogleTranslateApi
import com.transcribe.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var isApiEditing = false
    private val currentUris = mutableListOf<Uri>()
    private var isProcessing = false

    private val targetLanguages = listOf(
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

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                currentUris.clear()
                currentUris.addAll(uris)
                binding.fileName.text = uris.joinToString(", ") { getFileName(it) }
                processAll()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("transcribe_prefs", MODE_PRIVATE)
        loadTheme()

        // When launched by sharing an audio file, present as a small floating window
        // on top of the app the user was using instead of taking over full screen.
        val isSharedLaunch = intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE
        if (isSharedLaunch) {
            setTheme(R.style.Theme_Transcribe_Floating)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadApiKeys()
        setupUI()
        setupClickListeners()
        setupLanguageSpinner()
        handleSharedIntent()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent()
    }

    private fun loadTheme() {
        val isDark = sharedPreferences.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun setupUI() {
        val isDark = sharedPreferences.getBoolean("dark_mode", false)
        binding.toggleTheme.isChecked = isDark

        binding.toggleTheme.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("dark_mode", isChecked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        syncLockIcon()
        showApiKeyInput()
    }

    private fun loadApiKeys() {
        binding.elevenLabsKey.setText(sharedPreferences.getString("elevenLabsKey", ""))
        binding.googleApiKey.setText(sharedPreferences.getString("googleApiKey", ""))
    }

    private fun syncLockIcon() {
        val saved = sharedPreferences.getString("elevenLabsKey", "")?.isNotEmpty() == true
        binding.apiLockButton.setImageResource(
            if (saved) R.drawable.ic_lock
            else R.drawable.ic_lock_open
        )
        binding.apiLockButton.contentDescription =
            if (saved) "API keys saved. Tap to edit."
            else "Save API keys"
    }

    private fun setupClickListeners() {
        binding.processButton.setOnClickListener {
            processAll()
        }

        binding.clearButton.setOnClickListener {
            binding.resultText.text = ""
            binding.translatedText.text = ""
            binding.fileName.text = "No file selected"
            currentUris.clear()
        }

        binding.apiLockButton.setOnClickListener {
            if (isApiEditing) {
                saveApiKeys()
            } else {
                binding.elevenLabsInput.visibility = View.VISIBLE
                binding.googleApiInput.visibility = View.VISIBLE
                binding.elevenLabsKey.setText(sharedPreferences.getString("elevenLabsKey", ""))
                binding.googleApiKey.setText(sharedPreferences.getString("googleApiKey", ""))
                binding.apiLockButton.setImageResource(R.drawable.ic_lock_open)
                binding.apiLockButton.contentDescription = "Save API keys"
                isApiEditing = true
                Toast.makeText(this, "Editing API keys", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fileFAB.setOnClickListener {
            openFilePicker()
        }

        binding.copyTranscriptionButton.setOnClickListener {
            copyToClipboard("Transcription", binding.resultText.text.toString())
        }

        binding.copyTranslationButton.setOnClickListener {
            copyToClipboard("Translation", binding.translatedText.text.toString())
        }

        binding.shareButton.setOnClickListener {
            shareResult()
        }

        binding.historyButton.setOnClickListener {
            showHistory()
        }

        binding.translateAppButton.setOnClickListener {
            openInGoogleTranslate(binding.resultText.text.toString())
        }
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            targetLanguages.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.targetLanguageSpinner.adapter = adapter
        binding.targetLanguageSpinner.setSelection(0)
    }

    private fun showApiKeyInput() {
        val saved = sharedPreferences.getString("elevenLabsKey", "")?.isNotEmpty() == true
        isApiEditing = false
        binding.elevenLabsInput.visibility = if (saved) View.GONE else View.VISIBLE
        binding.googleApiInput.visibility = if (saved) View.GONE else View.VISIBLE
    }

    private fun saveApiKeys() {
        val elevenKey = binding.elevenLabsKey.text.toString().trim()
        val googleKey = binding.googleApiKey.text.toString().trim()

        if (elevenKey.isEmpty()) {
            Toast.makeText(this, "ElevenLabs API key required", Toast.LENGTH_SHORT).show()
            return
        }

        sharedPreferences.edit()
            .putString("elevenLabsKey", elevenKey)
            .putString("googleApiKey", googleKey)
            .apply()

        binding.elevenLabsInput.visibility = View.GONE
        binding.googleApiInput.visibility = View.GONE
        binding.apiLockButton.setImageResource(R.drawable.ic_lock)
        binding.apiLockButton.contentDescription = "API keys saved. Tap to edit."
        isApiEditing = false
        syncLockIcon()
        Toast.makeText(this, "API keys saved", Toast.LENGTH_SHORT).show()
    }

    private fun openFilePicker() {
        filePickerLauncher.launch("audio/*")
    }

    private fun getElevenKey(): String {
        val input = binding.elevenLabsKey.text.toString().trim()
        if (input.isNotEmpty()) return input
        return sharedPreferences.getString("elevenLabsKey", "") ?: ""
    }

    private fun getGoogleKey(): String {
        val input = binding.googleApiKey.text.toString().trim()
        if (input.isNotEmpty()) return input
        return sharedPreferences.getString("googleApiKey", "") ?: ""
    }

    private fun processAll() {
        if (isProcessing) return
        val elevenKey = getElevenKey()
        if (elevenKey.isEmpty()) {
            binding.resultText.text = "Please enter ElevenLabs API key"
            return
        }
        if (currentUris.isEmpty()) {
            binding.resultText.text = "Error: No audio file selected"
            return
        }

        isProcessing = true
        updateProcessingUi(true)
        binding.resultText.text = "Transcribing..."
        binding.translatedText.text = ""

        lifecycleScope.launch {
            val sbTrans = StringBuilder()
            val sbTransl = StringBuilder()
            try {
                for ((index, uri) in currentUris.withIndex()) {
                    withContext(Dispatchers.Main) {
                        binding.resultText.text =
                            "Transcribing ${index + 1}/${currentUris.size}: ${getFileName(uri)}"
                    }
                    val audioBytes = withContext(Dispatchers.IO) { readBytes(uri) }
                    if (audioBytes == null) {
                        val msg = "[${getFileName(uri)}] Could not read audio file"
                        sbTrans.append(msg).append("\n")
                        sbTransl.append(msg).append("\n")
                        continue
                    }
                    val transcription = withContext(Dispatchers.IO) {
                        ElevenLabsApi.transcribe(audioBytes, getFileName(uri), elevenKey, null)
                    }
                    val targetLang =
                        targetLanguages.getOrElse(binding.targetLanguageSpinner.selectedItemPosition) { targetLanguages[0] }.second
                    val useApp = binding.useTranslateAppSwitch.isChecked
                    val translated = if (useApp) {
                        "(Use 'GTranslate' button to translate in Google Translate app)"
                    } else {
                        val googleKey = getGoogleKey()
                        if (googleKey.isNotEmpty()) {
                            try {
                                GoogleTranslateApi.translate(transcription, googleKey, targetLang)
                            } catch (e: Exception) {
                                "Translation failed: ${e.message}"
                            }
                        } else {
                            "(Translation disabled - no API key)"
                        }
                    }

                    if (currentUris.size > 1) {
                        sbTrans.append("[${getFileName(uri)}] ").append(transcription).append("\n")
                        sbTransl.append("[${getFileName(uri)}] ").append(translated).append("\n")
                    } else {
                        sbTrans.append(transcription)
                        sbTransl.append(translated)
                    }
                    saveHistory(getFileName(uri), transcription, translated)
                }
                withContext(Dispatchers.Main) {
                    binding.resultText.text = "Original: ${sbTrans.toString().trim()}"
                    binding.translatedText.text = "Translated: ${sbTransl.toString().trim()}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.resultText.text = "Error: ${e.message}"
                }
            } finally {
                isProcessing = false
                updateProcessingUi(false)
            }
        }
    }

    private fun updateProcessingUi(processing: Boolean) {
        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
        binding.processButton.isEnabled = !processing
        binding.processButton.text = if (processing) "Processing..." else "Process Audio"
    }

    private fun readBytes(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val baos = ByteArrayOutputStream()
                input.copyTo(baos)
                baos.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun handleSharedIntent() {
        val intent = intent ?: return
        val action = intent.action
        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
        } else if (action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("audio/") == true) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) {
            currentUris.clear()
            currentUris.addAll(uris)
            binding.fileName.text = uris.joinToString(", ") { getFileName(it) }
            intent.action = null
            processAll()
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unknown audio"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx) ?: name
            }
        }
        return name
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareResult() {
        val text = buildString {
            append(binding.resultText.text)
            append("\n")
            append(binding.translatedText.text)
        }.trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share transcription"))
    }

    private fun saveHistory(fileName: String, transcription: String, translation: String) {
        try {
            val history = loadHistory()
            val entry = JSONObject().apply {
                put("file", fileName)
                put("transcription", transcription)
                put("translation", translation)
                put("timestamp", System.currentTimeMillis())
            }
            history.put(entry)
            // keep last 20
            while (history.length() > 20) history.remove(0)
            sharedPreferences.edit().putString("history", history.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadHistory(): JSONArray {
        val raw = sharedPreferences.getString("history", "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun openInGoogleTranslate(text: String) {
        val trimmed = text.removePrefix("Original:").trim()
        if (trimmed.isBlank()) {
            Toast.makeText(this, "Nothing to translate", Toast.LENGTH_SHORT).show()
            return
        }
        val targetLang = targetLanguages.getOrElse(binding.targetLanguageSpinner.selectedItemPosition) { targetLanguages[0] }.second
        val appIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, trimmed)
            setPackage("com.google.android.apps.translate")
        }
        if (appIntent.resolveActivity(packageManager) != null) {
            startActivity(appIntent)
        } else {
            val url = "https://translate.google.com/?sl=auto&tl=$targetLang&text=${Uri.encode(trimmed)}&op=translate"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun showHistory() {
        val history = loadHistory()
        if (history.length() == 0) {
            Toast.makeText(this, "No history yet", Toast.LENGTH_SHORT).show()
            return
        }
        val items = mutableListOf<String>()
        for (i in history.length() - 1 downTo 0) {
            val o = history.getJSONObject(i)
            val ts = o.optLong("timestamp", 0L)
            val date = if (ts > 0) SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts)) else ""
            items.add("${o.optString("file")}  ($date)")
        }
        AlertDialog.Builder(this)
            .setTitle("History")
            .setItems(items.toTypedArray()) { _, which ->
                val idx = history.length() - 1 - which
                val o = history.getJSONObject(idx)
                binding.resultText.text = "Original: ${o.optString("transcription")}"
                binding.translatedText.text = "Translated: ${o.optString("translation")}"
            }
            .setNegativeButton("Clear history") { _, _ ->
                sharedPreferences.edit().remove("history").apply()
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Close", null)
            .show()
    }
}
