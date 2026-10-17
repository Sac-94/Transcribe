package com.transcribe.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.transcribe.app.data.Settings
import com.transcribe.app.data.TranscribeException
import com.transcribe.app.databinding.ActivityShareBinding
import com.transcribe.app.ui.userMessage
import com.transcribe.app.util.TextActions
import com.transcribe.app.util.TranslateLauncher
import kotlinx.coroutines.launch

/**
 * The share target. When someone picks "Transcribe" in WhatsApp's or Telegram's share sheet this
 * opens as a small card at the bottom of the screen, on top of the chat, transcribes the voice
 * message and offers Copy / Share / Translate. Closing it drops the person back in the chat.
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var settings: Settings
    private val viewModel: ShareViewModel by viewModels()

    private var transcript = ""
    private var translation: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        // A long transcript scrolls inside the card instead of covering the whole chat.
        binding.resultScroll.maxHeightPx = (resources.displayMetrics.heightPixels * 0.4f).toInt()

        setupButtons()

        viewModel.start(sharedUris(intent))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun setupButtons() {
        binding.closeButton.setOnClickListener { finish() }
        binding.openAppButton.setOnClickListener { openFullApp(editKeys = false) }
        binding.settingsButton.setOnClickListener { openFullApp(editKeys = true) }
        binding.retryButton.setOnClickListener { viewModel.retry() }

        binding.copyButton.setOnClickListener {
            TextActions.copy(this, "Transcription", transcript)
        }
        binding.shareButton.setOnClickListener {
            TextActions.share(this, listOfNotNull(transcript, translation).joinToString("\n\n"))
        }
        binding.translateButton.setOnClickListener {
            if (!TranslateLauncher.open(this, transcript, settings.targetLanguage)) {
                Toast.makeText(this, R.string.translate_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(state: ShareViewModel.State) {
        val working = state is ShareViewModel.State.Working
        val done = state is ShareViewModel.State.Done
        val failed = state is ShareViewModel.State.Failed

        // A tap outside must not throw away a transcription that is still running.
        setFinishOnTouchOutside(!working)

        binding.progressRow.isVisible = working
        binding.resultScroll.isVisible = done
        binding.actionRow.isVisible = done
        binding.errorText.isVisible = failed
        binding.errorActions.isVisible = failed

        when (state) {
            is ShareViewModel.State.Working -> {
                binding.statusText.text = if (state.total > 1) {
                    getString(R.string.status_transcribing_n, state.current, state.total)
                } else {
                    getString(R.string.status_transcribing)
                }
            }

            is ShareViewModel.State.Done -> {
                transcript = state.transcript
                translation = state.translation
                binding.resultText.text = state.transcript

                val translationShown = state.translation ?: state.translationError?.let {
                    getString(R.string.translation_failed, it)
                }
                binding.translationLabel.isVisible = translationShown != null
                binding.translationText.isVisible = translationShown != null
                binding.translationText.text = translationShown.orEmpty()
            }

            is ShareViewModel.State.Failed -> {
                val error = state.error
                binding.errorText.text = error.userMessage(this)
                binding.settingsButton.isVisible = error.needsKey
                // Retrying only helps when the problem could be temporary.
                binding.retryButton.isVisible = error.kind in RETRYABLE
            }
        }
    }

    private fun openFullApp(editKeys: Boolean) {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_EDIT_KEYS, editKeys)
        startActivity(open)
        finish()
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { found.add(it) }

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { found.addAll(it) }
        }
        // Some apps attach the audio only as ClipData.
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { found.add(it) }
            }
        }
        return found.toList()
    }

    private companion object {
        val RETRYABLE = setOf(
            TranscribeException.Kind.NETWORK,
            TranscribeException.Kind.API,
            TranscribeException.Kind.UNREADABLE,
            TranscribeException.Kind.UNKNOWN
        )
    }
}
