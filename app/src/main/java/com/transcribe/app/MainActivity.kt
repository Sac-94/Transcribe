package com.transcribe.app

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
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
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var isApiEditing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("transcribe_prefs", MODE_PRIVATE)
        loadTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadApiKeys()
        setupUI()
        setupClickListeners()
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
            processAudioFromCurrentUri()
        }

        binding.clearButton.setOnClickListener {
            binding.resultText.text = ""
            binding.translatedText.text = ""
            binding.fileName.text = "No file selected"
            currentUri = null
        }

        binding.apiLockButton.setOnClickListener {
            if (isApiEditing) {
                saveApiKeys()
            } else {
                binding.elevenLabsInput.visibility = android.view.View.VISIBLE
                binding.googleApiInput.visibility = android.view.View.VISIBLE
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
    }

    private fun showApiKeyInput() {
        val saved = sharedPreferences.getString("elevenLabsKey", "")?.isNotEmpty() == true
        isApiEditing = false
        binding.elevenLabsInput.visibility = if (saved) android.view.View.GONE else android.view.View.VISIBLE
        binding.googleApiInput.visibility = if (saved) android.view.View.GONE else android.view.View.VISIBLE
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

        binding.elevenLabsInput.visibility = android.view.View.GONE
        binding.googleApiInput.visibility = android.view.View.GONE
        binding.apiLockButton.setImageResource(R.drawable.ic_lock)
        binding.apiLockButton.contentDescription = "API keys saved. Tap to edit."
        isApiEditing = false
        Toast.makeText(this, "API keys saved", Toast.LENGTH_SHORT).show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select Audio File"), FILE_PICK_REQUEST)
    }

    private fun processAudioFromCurrentUri() {
        val elevenKey = binding.elevenLabsKey.text.toString().trim()
        val googleKey = binding.googleApiKey.text.toString().trim()

        if (elevenKey.isEmpty()) {
            binding.resultText.text = "Please enter ElevenLabs API key"
            return
        }

        if (currentUri == null) {
            binding.resultText.text = "Error: No audio file selected"
            return
        }

        lifecycleScope.launch {
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.resultText.text = "Transcribing..."
            binding.translatedText.text = ""

            try {
                val audioBytes = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(currentUri as Uri)
                    stream?.use { input ->
                        val baos = ByteArrayOutputStream()
                        input.copyTo(baos)
                        baos.toByteArray()
                    }
                }

                if (audioBytes == null) {
                    withContext(Dispatchers.Main) {
                        binding.resultText.text = "Error: Could not read audio file"
                    }
                    return@launch
                }

                val transcription = withContext(Dispatchers.IO) {
                    ElevenLabsApi.transcribe(audioBytes, getFileName(currentUri as Uri), elevenKey)
                }

                withContext(Dispatchers.Main) {
                    binding.resultText.text = "Original: $transcription"
                }

                val translated = if (googleKey.isNotEmpty()) {
                    try {
                        GoogleTranslateApi.translate(transcription, googleKey)
                    } catch (e: Exception) {
                        "Translation failed: ${e.message}"
                    }
                } else {
                    "(Translation disabled - no API key)"
                }

                withContext(Dispatchers.Main) {
                    binding.translatedText.text = "English: $translated"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.resultText.text = "Error: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun handleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) {
                currentUri = uri
                binding.fileName.text = getFileName(uri)
                processAudioFromCurrentUri()
            }
        }
        intent?.action = null
    }

    private var currentUri: Uri? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                currentUri = uri
                binding.fileName.text = getFileName(uri)
                processAudioFromCurrentUri()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unknown audio"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name
    }

    companion object {
        private const val FILE_PICK_REQUEST = 1001
    }
}