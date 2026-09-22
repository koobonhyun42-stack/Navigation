package com.finalproject.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

class FeedbackManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastSpokenTime = 0L
    private val speechIntervalMs = 2500L

    private var lastVibratedTime = 0L
    private val vibrateIntervalMs = 1000L

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            }
        }
    }

    fun processDetections(boxes: List<BoundingBox>, viewWidth: Int, viewHeight: Int = 0) {
        if (boxes.isEmpty()) return

        val now = System.currentTimeMillis()

        if (now - lastSpokenTime < speechIntervalMs) return

        val validBoxes = boxes.filter { it.distanceMeter in 0.1f..5.0f }
        val primaryTarget = if (validBoxes.isNotEmpty()) {
            validBoxes.minByOrNull { it.distanceMeter }
        } else {
            boxes.filter { it.w > 0f && it.h > 0f }.maxByOrNull { it.w * it.h }
        } ?: return

        val distance = primaryTarget.distanceMeter
        val positionText = getDirectionText(primaryTarget.cx, viewWidth)
        val classNameKr = getKoreanClassName(primaryTarget.clsName)

        val speechMessage = if (distance > 0f) {
            String.format(Locale.KOREAN, "%s %.1f미터 앞 %s", positionText, distance, classNameKr)
        } else {
            "$positionText $classNameKr"
        }

        triggerVibration(distance)
        speak(speechMessage)

        lastSpokenTime = now
    }

    private fun getDirectionText(cx: Float, viewWidth: Int): String {
        if (viewWidth <= 0) return "전방"
        val leftThreshold = viewWidth * 0.35f
        val rightThreshold = viewWidth * 0.65f

        return when {
            cx < leftThreshold -> "좌측"
            cx > rightThreshold -> "우측"
            else -> "전방"
        }
    }

    private fun getKoreanClassName(clsName: String): String {
        return when (clsName.lowercase(Locale.ROOT)) {
            "person" -> "사람"
            "car" -> "차량"
            "chair" -> "의자"
            "table" -> "탁자"
            "bicycle" -> "자전거"
            "motorcycle" -> "오토바이"
            "bollard" -> "볼라드"
            "stair", "stairs" -> "계단"
            "door" -> "문"
            "pole" -> "전주"
            else -> if (clsName.isNotBlank()) clsName else "장애물"
        }
    }

    private fun triggerVibration(distanceMeter: Float) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val now = System.currentTimeMillis()
        if (now - lastVibratedTime < vibrateIntervalMs) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when {
                distanceMeter <= 1.5f -> {
                    VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                distanceMeter <= 3.0f -> {
                    val pattern = longArrayOf(0, 150, 100, 150)
                    VibrationEffect.createWaveform(pattern, -1)
                }
                else -> {
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
            vib.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            when {
                distanceMeter <= 1.5f -> vib.vibrate(500)
                distanceMeter <= 3.0f -> vib.vibrate(longArrayOf(0, 150, 100, 150), -1)
                else -> vib.vibrate(100)
            }
        }
        lastVibratedTime = now
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ObstacleGuidance")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}