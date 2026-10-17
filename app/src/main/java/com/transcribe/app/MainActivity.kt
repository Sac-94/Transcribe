package com.transcribe.app

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.transcribe.app.data.Languages
import com.transcribe.app.data.Settings
import com.transcribe.app.data.TranscribeException
import com.transcribe.app.data.Transcriber
import com.transcribe.app.data.transcribeAll
import com.transcribe.app.databinding.ActivityMainBinding
import com.transcribe.app.ui.userMessage
import com.transcribe.app.util.AudioCache
import com.transcribe.app.util.TextActions
import com.transcribe.app.util.TranslateLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full app: settings (API keys, target language, theme), picking audio files by hand and the
 * history. Audio shared from a chat app goes to [ShareActivity] instead.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var transcriber: Transcriber

    private val currentUris = mutableListOf<Uri>()
    private var processingJob: Job? = null
    private var isEditingKeys = false
    private var lastTranscript = ""
    private var lastTranslation = ""

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                currentUris.clear()
                currentUris.addAll(uris)
                lifecycleScope.launch {
                    // The provider query can be slow, so keep it off the main thread.
                    val names = withContext(Dispatchers.IO) {
                        uris.joinToString(", ") { AudioCache.displayName(this@MainActivity, it) }
                    }
                    binding.fileName.text = names
                    processAll()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        transcriber = Transcriber(this, settings)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        setupSettingsCard()
        setupClickListeners()
        binding.fileName.setText(R.string.no_file_selected)

        showKeyFieldsIfRequested(intent)
    }

    /** The share window reopens an already-running app, which delivers the request here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showKeyFieldsIfRequested(intent)
    }

    /** Sent by the share window when the ElevenLabs key is missing or wrong. */
    private fun showKeyFieldsIfRequested(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_EDIT_KEYS, false)) {
            isEditingKeys = true
            refreshKeyFields()
        }
    }

    /** Android 15 draws apps edge to edge, so keep the content clear of the system bars. */
    private fun applyWindowInsets() {
        binding.root.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val types = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
            val bars = insets.getInsets(types)
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun setupSettingsCard() {
        binding.elevenLabsKey.setText(settings.elevenLabsKey)
        binding.googleApiKey.setText(settings.googleApiKey)

        // Until the person chooses, follow the phone's own light/dark setting.
        val systemIsDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        binding.toggleTheme.isChecked = settings.darkMode ?: systemIsDark
        binding.toggleTheme.setOnCheckedChangeListener { _, isChecked ->
            settings.darkMode = isChecked
            TranscribeApp.applyNightMode(settings)
        }

        binding.useTranslateAppSwitch.isChecked = settings.useTranslateApp
        binding.useTranslateAppSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.useTranslateApp = isChecked
            refreshKeyFields()
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Languages.names())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.targetLanguageSpinner.adapter = adapter
        binding.targetLanguageSpinner.setSelection(Languages.indexOfCode(settings.targetLanguage))
        binding.targetLanguageSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    settings.targetLanguage = Languages.codeAt(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        refreshKeyFields()
    }

    private fun setupClickListeners() {
        binding.processButton.setOnClickListener { processAll() }

        binding.clearButton.setOnClickListener {
            processingJob?.cancel()
            currentUris.clear()
            showResult("", null)
            binding.fileName.setText(R.string.no_file_selected)
        }

        binding.apiLockButton.setOnClickListener {
            if (isEditingKeys || settings.elevenLabsKey.isEmpty()) {
                saveApiKeys()
            } else {
                isEditingKeys = true
                refreshKeyFields()
                Toast.makeText(this, R.string.editing_api_keys, Toast.LENGTH_SHORT).show()
            }
        }

        binding.fileFAB.setOnClickListener { filePickerLauncher.launch("audio/*") }

        binding.copyTranscriptionButton.setOnClickListener {
            TextActions.copy(this, "Transcription", lastTranscript)
        }
        binding.copyTranslationButton.setOnClickListener {
            TextActions.copy(this, "Translation", lastTranslation)
        }
        binding.shareButton.setOnClickListener {
            TextActions.share(
                this,
                listOf(lastTranscript, lastTranslation).filter { it.isNotBlank() }.joinToString("\n\n")
            )
        }
        binding.historyButton.setOnClickListener { showHistory() }
        binding.translateAppButton.setOnClickListener { openInGoogleTranslate() }
    }

    /** Shows the key fields while no key is saved or while the person is editing them. */
    private fun refreshKeyFields() {
        val hasSavedKey = settings.elevenLabsKey.isNotEmpty()
        val showFields = isEditingKeys || !hasSavedKey
        binding.elevenLabsInput.isVisible = showFields
        // The Cloud Translation key is only needed when the Translate app isn't used.
        binding.googleApiInput.isVisible = showFields && !binding.useTranslateAppSwitch.isChecked

        val locked = hasSavedKey && !isEditingKeys
        binding.apiLockButton.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        binding.apiLockButton.contentDescription =
            getString(if (locked) R.string.keys_saved_tap_to_edit else R.string.save_api_keys)
    }

    private fun saveApiKeys() {
        val elevenKey = binding.elevenLabsKey.text.toString().trim()
        if (elevenKey.isEmpty()) {
            Toast.makeText(this, R.string.key_required, Toast.LENGTH_SHORT).show()
            return
        }
        settings.elevenLabsKey = elevenKey
        settings.googleApiKey = binding.googleApiKey.text.toString().trim()
        isEditingKeys = false
        refreshKeyFields()
        Toast.makeText(this, R.string.api_keys_saved, Toast.LENGTH_SHORT).show()
    }

    /** Pressing Process with keys typed but not saved yet should just work. */
    private fun persistTypedKeys() {
        if (!binding.elevenLabsInput.isVisible) return
        val elevenKey = binding.elevenLabsKey.text.toString().trim()
        if (elevenKey.isEmpty()) return
        settings.elevenLabsKey = elevenKey
        if (binding.googleApiInput.isVisible) {
            settings.googleApiKey = binding.googleApiKey.text.toString().trim()
        }
        isEditingKeys = false
        refreshKeyFields()
    }

    private fun processAll() {
        if (processingJob?.isActive == true) return
        persistTypedKeys()
        if (currentUris.isEmpty()) {
            binding.resultText.setText(R.string.error_no_file_selected)
            return
        }

        val uris = currentUris.toList()
        setProcessing(true)
        showResult("", null)

        processingJob = lifecycleScope.launch {
            try {
                val batch = transcriber.transcribeAll(uris) { current, total ->
                    binding.resultText.text = if (total > 1) {
                        getString(R.string.status_transcribing_n, current, total)
                    } else {
                        getString(R.string.status_transcribing)
                    }
                }
                showResult(batch.transcript, batch.translation, batch.translationError)
            } catch (e: TranscribeException) {
                binding.resultText.text = e.userMessage(this@MainActivity)
                if (e.needsKey) {
                    isEditingKeys = true
                    refreshKeyFields()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.resultText.text = e.message ?: getString(R.string.error_generic)
            } finally {
                setProcessing(false)
            }
        }
    }

    private fun setProcessing(processing: Boolean) {
        binding.progressBar.isVisible = processing
        binding.processButton.isEnabled = !processing
        binding.processButton.setText(if (processing) R.string.processing else R.string.process_audio)
    }

    private fun showResult(transcript: String, translation: String?, translationError: String? = null) {
        lastTranscript = transcript
        lastTranslation = translation.orEmpty()
        binding.resultText.text = transcript
        binding.translatedText.text = when {
            translation != null -> translation
            translationError != null -> getString(R.string.translation_failed, translationError)
            transcript.isEmpty() -> ""
            settings.useTranslateApp -> getString(R.string.translate_with_app_hint)
            else -> getString(R.string.translate_no_key_hint)
        }
    }

    private fun openInGoogleTranslate() {
        if (lastTranscript.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        if (!TranslateLauncher.open(this, lastTranscript, settings.targetLanguage)) {
            Toast.makeText(this, R.string.translate_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHistory() {
        val history = settings.history().asReversed() // newest first
        if (history.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val format = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val items = history.map { entry ->
            val date = if (entry.timestamp > 0) format.format(Date(entry.timestamp)) else ""
            "${entry.file}  ($date)"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_title)
            .setItems(items.toTypedArray()) { _, which ->
                val entry = history[which]
                showResult(entry.transcription, entry.translation.ifEmpty { null })
            }
            .setNegativeButton(R.string.history_clear) { _, _ ->
                settings.clearHistory()
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        /** Open with the API key fields showing. */
        const val EXTRA_EDIT_KEYS = "edit_keys"
    }
}
