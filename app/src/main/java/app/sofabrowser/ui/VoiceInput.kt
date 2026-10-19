package app.sofabrowser.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.sofabrowser.R

/**
 * Speech recognition inside Sofa's own search screen (no system voice overlay on top of it). Audio goes to the
 * device's speech service, as with any voice search; Sofa only needs the microphone permission to hand it over.
 */
class VoiceInput(private val activity: Activity, private val listener: Listener) {

    interface Listener {
        fun onListening(active: Boolean)
        /** Input level, 0 to 1, for a visual pulse. */
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onFailed(message: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    var listening = false
        private set

    /** Whether a speech service exists that Sofa can bind to. */
    val available get() = SpeechRecognizer.isRecognitionAvailable(activity)

    val hasPermission get() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        listening = true
        listener.onListening(true)
        r.startListening(intent)
    }

    fun cancel() {
        if (!listening) return
        recognizer?.cancel()
        stopped()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun stopped() {
        listening = false
        listener.onListening(false)
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            stopped()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) listener.onFailed(R.string.voice_no_match) else listener.onResult(text)
        }

        override fun onError(error: Int) {
            if (!listening) return
            stopped()
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_no_match
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.voice_network
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_needs_mic
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_busy
                SpeechRecognizer.ERROR_AUDIO -> R.string.voice_audio
                SpeechRecognizer.ERROR_CLIENT -> return
                else -> if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) R.string.voice_network
                else R.string.voice_failed
            }
            listener.onFailed(message)
        }
    }
}
