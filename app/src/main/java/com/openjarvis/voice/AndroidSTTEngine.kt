package com.openjarvis.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class AndroidSTTEngine(
    private val context: Context
) : STTEngine {

    companion object {
        const val SAMPLE_RATE = 16000
        const val SILENCE_THRESHOLD = 500
        const val SILENCE_TIMEOUT_MS = 1500L
        const val MAX_RECORDING_MS = 30000L
    }

    private var recognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null

    private val _isListening = MutableStateFlow(false)
    val isListeningState: StateFlow<Boolean> = _isListening

    private var currentCallback: ((String?) -> Unit)? = null
    private var currentErrorCallback: ((String) -> Unit)? = null
    private var pendingResult: String? = null

    private val scope =
        CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var silenceStartTime = -1L
    private var recordingStartTime = -1L

    init {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                return@post
            }

            recognizer =
                SpeechRecognizer.createSpeechRecognizer(context)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(
                        params: Bundle?
                    ) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        silenceStartTime = -1L
                    }

                    override fun onRmsChanged(
                        rmsdB: Float
                    ) {
                        if (rmsdB * 100 < SILENCE_THRESHOLD) {

                            if (silenceStartTime < 0) {
                                silenceStartTime =
                                    System.currentTimeMillis()
                            }

                            val silenceDuration =
                                System.currentTimeMillis() -
                                        silenceStartTime

                            if (
                                silenceDuration >=
                                SILENCE_TIMEOUT_MS
                            ) {
                                stopListeningEarly()
                            }

                        } else {
                            silenceStartTime = -1L
                        }
                    }

                    override fun onBufferReceived(
                        buffer: ByteArray?
                    ) {
                    }

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false

                        val errorMessage =
                            when (error) {
                                SpeechRecognizer.ERROR_AUDIO ->
                                    "Audio recording error"

                                SpeechRecognizer.ERROR_CLIENT ->
                                    "Client error"

                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                    "Missing permissions"

                                SpeechRecognizer.ERROR_NETWORK ->
                                    "Network error"

                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                    "Network timeout"

                                SpeechRecognizer.ERROR_NO_MATCH ->
                                    "No speech detected"

                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                    "Recognizer busy"

                                SpeechRecognizer.ERROR_SERVER ->
                                    "Server error"

                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    "Speech timeout"

                                else ->
                                    "Unknown error"
                            }

                        if (pendingResult.isNullOrBlank()) {
                            currentErrorCallback?.invoke(
                                errorMessage
                            )
                        }
                    }

                    override fun onResults(
                        results: Bundle?
                    ) {
                        _isListening.value = false

                        val matches =
                            results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val text =
                            matches
                                ?.firstOrNull()
                                ?.trim()

                        if (!text.isNullOrBlank()) {
                            pendingResult = text
                            currentCallback?.invoke(text)
                        } else {
                            currentErrorCallback?.invoke(
                                "No speech detected"
                            )
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {
                    }
                }
            )
        }
    }

    override fun startListening(
        onResult: (String?) -> Unit,
        onError: (String) -> Unit
    ) {
        currentCallback = onResult
        currentErrorCallback = onError
        pendingResult = null
        silenceStartTime = -1L

        mainHandler.post {

            val speechRecognizer = recognizer

            if (speechRecognizer == null) {
                onError("Speech recognition unavailable")
                return@post
            }

            try {
                val intent =
                    Intent(
                        RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                    ).apply {

                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )

                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE,
                            Locale.getDefault()
                        )

                        putExtra(
                            RecognizerIntent.EXTRA_MAX_RESULTS,
                            1
                        )

                        putExtra(
                            RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                            true
                        )

                        putExtra(
                            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                            SILENCE_TIMEOUT_MS
                        )
                    }

                speechRecognizer.startListening(intent)

            } catch (e: Exception) {
                _isListening.value = false
                onError(
                    e.message ?: "Unable to start speech recognition"
                )
            }
        }
    }

    private fun stopListeningEarly() {
        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
    }

    override fun stop(): String? {
        _isListening.value = false

        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null

        val result = pendingResult

        pendingResult = null
        currentCallback = null
        currentErrorCallback = null

        return result
    }

    override fun isListening(): Boolean {
        return _isListening.value
    }

    override fun release() {
        stop()

        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
            }

            recognizer = null
        }

        scope.cancel()
    }

    fun startAudioCapture(): Boolean {

        val bufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (bufferSize <= 0) {
            return false
        }

        return try {

            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 4
                )

            audioRecord?.startRecording()

            recordingStartTime =
                System.currentTimeMillis()

            _isListening.value = true

            true

        } catch (e: SecurityException) {
            audioRecord?.release()
            audioRecord = null
            false
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            false
        }
    }

    suspend fun captureWithVAD(): FloatArray =
        withContext(Dispatchers.IO) {

            val bufferSize =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            if (bufferSize <= 0) {
                return@withContext FloatArray(0)
            }

            val shortBuffer =
                ShortArray(
                    (bufferSize / 2).coerceAtLeast(1)
                )

            val allSamples =
                mutableListOf<Short>()

            while (
                _isListening.value &&
                audioRecord != null
            ) {

                val elapsed =
                    System.currentTimeMillis() -
                            recordingStartTime

                if (elapsed >= MAX_RECORDING_MS) {
                    break
                }

                val read =
                    try {
                        audioRecord?.read(
                            shortBuffer,
                            0,
                            shortBuffer.size
                        ) ?: break
                    } catch (_: Exception) {
                        break
                    }

                if (read <= 0) {
                    continue
                }

                for (i in 0 until read) {
                    allSamples.add(
                        shortBuffer[i]
                    )
                }

                var maxAmplitude = 0

                for (i in 0 until read) {
                    val amplitude =
                        kotlin.math.abs(
                            shortBuffer[i].toInt()
                        )

                    if (amplitude > maxAmplitude) {
                        maxAmplitude = amplitude
                    }
                }

                if (maxAmplitude < SILENCE_THRESHOLD) {

                    if (silenceStartTime < 0) {
                        silenceStartTime =
                            System.currentTimeMillis()
                    }

                    if (
                        System.currentTimeMillis() -
                        silenceStartTime >=
                        SILENCE_TIMEOUT_MS
                    ) {
                        break
                    }

                } else {
                    silenceStartTime = -1L
                }
            }

            _isListening.value = false

            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }

            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }

            audioRecord = null

            allSamples
                .map {
                    it.toFloat() / Short.MAX_VALUE
                }
                .toFloatArray()
        }
}
