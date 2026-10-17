package com.finalproject.navigation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceCommandManager (
    private val context: Context,
    private val onCommandReceived: (Command) -> Unit
) {
    enum class Command {
        START,
        PAUSE,
        STATUS,
        UNKNOWN
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
        }
    }

    fun startListening() {
        if (isListening || speechRecognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }
        speechRecognizer?.startListening(intent)
        isListening = true
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun createListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spokenText = matches?.firstOrNull()?.lowercase() ?: ""

            Log.d("VoiceCommand", "인식된 음성: $spokenText")

            val command = when {
                spokenText.contains("시작") || spokenText.contains("안내") -> Command.START
                spokenText.contains("정지") || spokenText.contains("멈춰") -> Command.PAUSE
                spokenText.contains("상태") || spokenText.contains("정보") -> Command.STATUS
                else -> Command.UNKNOWN
            }

            onCommandReceived(command)
        }

        override fun onError(error: Int) {
            isListening = false
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}