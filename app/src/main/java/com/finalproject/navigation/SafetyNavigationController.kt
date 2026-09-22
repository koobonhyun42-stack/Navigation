package com.finalproject.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.camera.core.ImageProxy
import java.util.Locale

class SafetyNavigationController(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private val detector = YoloDetector(context)
    private var lastSpokenTime = 0L
    fun processImageFrame(imageProxy: ImageProxy) {
        try {
            val results = detector.detect(imageProxy)

            if (results.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastSpokenTime > 3000) {
                    val topResult = results[0]
                    val labelName = detector.labels.getOrNull(topResult.cls) ?: "장애물"

                    speak("전방에 ${labelName}이 있습니다. 주의하세요.")
                    lastSpokenTime = currentTime
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
        }
    }

    fun onDestroy() {
        tts.stop()
        tts.shutdown()
    }

}