package com.finalproject.navigation

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.camera.core.ImageProxy
import java.util.Locale
import kotlin.math.abs

class FeedbackManager(private val context: Context) : TextToSpeech.OnInitListener {
    enum class SpeechPriority {
        HIGH,
        NORMAL
    }

    private val detector = YoloDetector(context)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var isHighPrioritySpeaking = false
    private val handler = Handler(Looper.getMainLooper())

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
    private var lastPathSpokenTime = 0L

    private val pathSpeechIntervalMs = 3000L
    private val speechIntervalMs = 3000L

    private var lastVibratedTime = 0L
    private val vibrateIntervalMs = 1000L

    private var lastSpokenTargetKey = ""
    private var lastSpokenDistance = 0f
    private var lastSameObjectSpokenTime = 0L

    private val pathGuideManager = PathGuideManager()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts?.setSpeechRate(1.1f)
            }
        }
    }

    fun processImageFrame(imageProxy: ImageProxy) {
        try {
            val viewWidth = imageProxy.width
            val viewHeight = imageProxy.height

            val results = detector.detect(imageProxy)
            processDetections(results, viewWidth, viewHeight)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    fun processDetections(boxes: List<BoundingBox>, viewWidth: Int, viewHeight: Int) {
        boxes.forEach { android.util.Log.d("YOLO_TEST", "Detected class: ${it.clsName}, distance: ${it.distanceMeter}") }
        if (boxes.isEmpty()) return
        val now = System.currentTimeMillis()


        val highRiskBoxes = boxes.filter { box ->
            val krName = getKoreanClassName(box)
            val dist = if (box.distanceMeter > 0f) box.distanceMeter else 1.5f
            getRiskPriority(krName) == 1 || dist <= 1.5f
        }

        if (highRiskBoxes.isNotEmpty()) {
            processObstacleFeedback(highRiskBoxes, viewWidth, now)
            return
        }

        val pathBoxes = boxes.filter {
            it.clsName.contains("block", ignoreCase = true) ||
                    it.clsName.contains("braille", ignoreCase = true)
        }

        if (pathBoxes.isNotEmpty()) {
            val pathResult = pathGuideManager.evaluatePathAlignment(pathBoxes, viewWidth)
            if (pathResult.state != PathGuideManager.AlignmentState.LOST) {
                if (pathResult.state != PathGuideManager.AlignmentState.CENTER && (now - lastPathSpokenTime >= pathSpeechIntervalMs)) {
                    triggerVibration(1.0f, "전방")
                    speak(pathResult.message, SpeechPriority.NORMAL)
                    lastPathSpokenTime = now
                }
            }
        }
        processObstacleFeedback(boxes, viewWidth, now)
    }

    private fun processObstacleFeedback(boxes: List<BoundingBox>, viewWidth: Int, now: Long) {
        if (now - lastSpokenTime < speechIntervalMs) return

        val translatedBoxes = boxes.mapNotNull { box ->
            val krName = getKoreanClassName(box)
            if (krName.isBlank()) return@mapNotNull null

            val dist = if (box.distanceMeter > 0.1f) box.distanceMeter else 1.5f
            Pair(box, krName) to dist
        }

        if (translatedBoxes.isEmpty()) return

        val primaryPair = translatedBoxes.minWithOrNull(
            compareBy<Pair<Pair<BoundingBox, String>, Float>> { (pair, _) ->
                getRiskPriority(pair.second)
            }.thenBy { (_, dist) ->
                dist
            }
        )?.first ?: return

        val primaryTarget = primaryPair.first
        val classNameKr = primaryPair.second
        val rawDistance = if (primaryTarget.distanceMeter > 0f) primaryTarget.distanceMeter else 1.5f
        val smoothedDistance = getSmoothedDistance(classNameKr, rawDistance)

        val positionText = getDirectionText(primaryTarget, viewWidth)
        val targetKey = "$positionText-$classNameKr"

        if (targetKey == lastSpokenTargetKey && abs(smoothedDistance - lastSpokenDistance) < 0.5f) {
            if (now - lastSameObjectSpokenTime < 6000L) {
                return
            }
        }

        val speechMessage = String.format(
            Locale.KOREAN,
            "%s %.1f미터 앞 %s",
            positionText,
            smoothedDistance,
            classNameKr
        )

        val priority = if (smoothedDistance <= 1.2f) SpeechPriority.HIGH else SpeechPriority.NORMAL

        triggerVibration(smoothedDistance, positionText)
        speak(speechMessage, SpeechPriority.NORMAL)

        lastSpokenTime = now
        lastSameObjectSpokenTime = now
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

    private fun getDirectionText(box: BoundingBox, viewWidth: Int): String {
        if (viewWidth <= 0) return "전방"

        val boxWidth = box.x2 - box.x1
        val screenWidthFloat = viewWidth.toFloat()

        if (boxWidth > screenWidthFloat * 0.6f) {
            return "전방"
        }

        val leftThreshold = viewWidth * 0.35f
        val rightThreshold = viewWidth *0.65f

        return when {
            box.cx < leftThreshold -> "좌측"
            box.cx > rightThreshold -> "우측"
            else -> "전방"
        }
    }

    private fun getRiskPriority(krName: String): Int {
        return when (krName) {
            "사람", "차량" -> 1
            "자전거", "계단", "위험표지판" -> 2
            else -> 3
        }
    }

    private fun getKoreanClassName(box: BoundingBox): String {
        val cleanName = box.clsName.lowercase(Locale.ROOT).trim()

        return when {
            cleanName.contains("braille") || cleanName.contains("block") -> "점자블록"
            cleanName.contains("person") || cleanName.contains("사람") -> "사람"
            cleanName.contains("vehicle") || cleanName.contains("car") -> "차량"
            cleanName.contains("bike") || cleanName.contains("bicycle") -> "자전거"
            cleanName.contains("stair") -> "계단"
            cleanName.contains("hazard") || cleanName.contains("sign") -> "위험표지판"
            cleanName.contains("crosswalk") -> "횡단보도"
            else -> ""
        }
    }

    private fun triggerVibration(distanceMeter: Float, direction: String) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val now = System.currentTimeMillis()
        if (now - lastVibratedTime < vibrateIntervalMs) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (direction) {
                "좌측" -> {
                    val pattern = longArrayOf(0, 120, 80, 120)
                    VibrationEffect.createWaveform(pattern, -1)
                }
                "우측" -> {
                    VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                else -> {
                    if (distanceMeter <= 1.5f) {
                        VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
                    } else {
                        VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                }
            }
            vib.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            when (direction) {
                "좌측" -> vib.vibrate(longArrayOf(0, 120, 80, 120), -1)
                "우측" -> vib.vibrate(350)
                else -> {
                    if (distanceMeter <= 1.5f) vib.vibrate(400) else vib.vibrate(150)
                }
            }
        }
        lastVibratedTime = now
    }

    fun speak(text: String, priority: SpeechPriority = SpeechPriority.NORMAL) {
        if (!isTtsReady || text.isBlank()) return

        if (priority == SpeechPriority.HIGH) {
            isHighPrioritySpeaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "HighPriority")

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                isHighPrioritySpeaking = false
            }, 4000L)
            lastSpokenTime = System.currentTimeMillis()
        } else {
            if (isHighPrioritySpeaking) return

            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "NormalPriority")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        distanceHistoryMap.clear()
        lastSpokenTargetKey = ""
    }
}