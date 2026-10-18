package com.finalproject.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.abs

class FeedbackManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val distanceHistoryMap = mutableMapOf<String, ArrayDeque<Float>>()
    private val windowSize = 5

    private var lastSpokenTime = 0L
    private val speechIntervalMs = 3000L

    private var lastVibratedTime = 0L
    private val vibrateIntervalMs = 1000L

    private var lastSpokenTargetKey = ""
    private var lastSpokenDistance = 0f

    private val pathGuideManager = PathGuideManager()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true


                tts?.setSpeechRate(0.8f)
            }
        }
    }

    fun processDetections(boxes: List<BoundingBox>, viewWidth: Int, viewHeight: Int) {
        if (boxes.isEmpty()) return
        val now = System.currentTimeMillis()

        val pathBoxes = boxes.filter {
            it.clsName.contains("block", ignoreCase = true) ||
                    it.clsName.contains("braille", ignoreCase = true)
        }

        if (pathBoxes.isNotEmpty()) {
            val pathResult = pathGuideManager.evaluatePathAlignment(pathBoxes, viewWidth)
            if (pathResult.state != PathGuideManager.AlignmentState.CENTER && (now - lastSpokenTime >= speechIntervalMs)) {
                speak(pathResult.message)
                lastSpokenTime = now
                return
            }
        }

        if (now - lastSpokenTime < speechIntervalMs) return

        val validBoxes = boxes.filter { it.distanceMeter in 0.1f..5.0f }
        val primaryTarget = if (validBoxes.isNotEmpty()) {
            validBoxes.minByOrNull { it.distanceMeter }
        } else {
            boxes.filter { it.w > 0f && it.h > 0f }.maxByOrNull { it.w * it.h }
        } ?: return

        val classNameKr = getKoreanClassName(primaryTarget.clsName)
        val rawDistance = if (primaryTarget.distanceMeter > 0f) primaryTarget.distanceMeter else 1.0f
        val smoothedDistance = getSmoothedDistance(classNameKr, rawDistance)

        val positionText = getDirectionText(primaryTarget.cx, viewWidth)
        val targetKey = "$positionText-$classNameKr"

        if (targetKey == lastSpokenTargetKey && abs(smoothedDistance - lastSpokenDistance) < 0.5f) {
            return
        }

        val speechMessage = String.format(
            Locale.KOREAN,
            "%s %.1f미터 앞 %s",
            positionText,
            smoothedDistance,
            classNameKr
        )

        triggerVibration(smoothedDistance)
        speak(speechMessage)

        lastSpokenTime = now
        lastSpokenTargetKey = targetKey
        lastSpokenDistance = smoothedDistance
    }

    private fun getSmoothedDistance(key: String, rawDistance: Float): Float {
        val history = distanceHistoryMap.getOrPut(key) { ArrayDeque() }
        if (history.size >= windowSize) {
            history.removeFirst()
        }
        history.addLast(rawDistance)
        return history.average().toFloat()
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
                distanceMeter in 0.1f..1.5f -> {
                    VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                distanceMeter in 1.5f..3.0f -> {
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
                distanceMeter in 0.1f..1.5f -> vib.vibrate(500)
                distanceMeter in 1.5f..3.0f -> vib.vibrate(longArrayOf(0, 150, 100, 150), -1)
                else -> vib.vibrate(100)
            }
        }
        lastVibratedTime = now
    }

    fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ObstacleGuidance")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        distanceHistoryMap.clear()
        lastSpokenTargetKey = ""
    }
}