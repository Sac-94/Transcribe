package com.transcribe.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.transcribe.app.data.Settings
import com.transcribe.app.data.TranscribeException
import com.transcribe.app.data.Transcriber
import com.transcribe.app.data.transcribeAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs the transcription behind the share window. It lives in a ViewModel so turning the phone
 * (or any other configuration change) doesn't throw away an upload that is already running.
 */
class ShareViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        class Working(val current: Int, val total: Int) : State

        class Done(
            val transcript: String,
            val translation: String?,
            val translationError: String?
        ) : State

        class Failed(val error: TranscribeException) : State
    }

    private val transcriber = Transcriber(application, Settings(application))
    private val mutableState = MutableStateFlow<State>(State.Working(1, 1))
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var uris: List<Uri> = emptyList()
    private var job: Job? = null

    /** Only the first call does anything, so recreating the activity doesn't restart the work. */
    fun start(shared: List<Uri>) {
        if (job != null || uris.isNotEmpty()) return
        if (shared.isEmpty()) {
            mutableState.value = State.Failed(TranscribeException(TranscribeException.Kind.NO_AUDIO))
            return
        }
        uris = shared
        run()
    }

    fun retry() {
        if (uris.isNotEmpty() && job?.isActive != true) run()
    }

    private fun run() {
        job = viewModelScope.launch {
            try {
                val batch = transcriber.transcribeAll(uris) { current, total ->
                    mutableState.value = State.Working(current, total)
                }
                mutableState.value = State.Done(batch.transcript, batch.translation, batch.translationError)
            } catch (e: TranscribeException) {
                mutableState.value = State.Failed(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = State.Failed(
                    TranscribeException(TranscribeException.Kind.UNKNOWN, e.message, e)
                )
            }
        }
    }
}
