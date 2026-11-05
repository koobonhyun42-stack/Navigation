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
                tts?.setSpeechRate(0.8f)
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

        val positionText = getDirectionText(primaryTarget.cx, viewWidth)
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

        triggerVibration(smoothedDistance, positionText)
        speak(speechMessage, SpeechPriority.NORMAL)

        lastSpokenTime = now
        lastSameObjectSpokenTime = now
        lastSpokenTargetKey = targetKey
        lastSpokenDistance = smoothedDistance
    }

    private fun getRiskPriority(krName: String): Int {
        return when (krName) {
            "사람", "차량", "오토바이", "버스", "트럭" -> 1
            "자전거", "계단", "신호등", "볼라드" -> 2
            else -> 3
        }
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

    private fun getKoreanClassName(box: BoundingBox): String {
        val cleanName = box.clsName.lowercase(Locale.ROOT).trim()

        if (cleanName.isNotBlank()) {
            return when {
                cleanName.contains("person") || cleanName.contains("사람") -> "사람"
                cleanName.contains("car") || cleanName.contains("차") -> "차량"
                cleanName.contains("motorcycle") || cleanName.contains("bike") || cleanName.contains("오토바이") -> "오토바이"
                cleanName.contains("bus") -> "버스"
                cleanName.contains("truck") -> "트럭"
                cleanName.contains("bicycle") -> "자전거"
                cleanName.contains("chair") -> "의자"
                cleanName.contains("table") -> "탁자"
                cleanName.contains("stair") || cleanName.contains("stairs") -> "계단"
                cleanName == "train" -> "열차"
                cleanName == "boat" -> "보트"
                cleanName == "traffic light" -> "신호등"
                cleanName == "fire hydrant" -> "소화전"
                cleanName == "stop sign" -> "정지 표지판"
                cleanName == "parking meter" -> "주차 미터기"
                cleanName == "bench" -> "벤치"
                cleanName == "bollard" -> "볼라드"
                cleanName == "door" -> "문"
                cleanName == "pole" || cleanName == "potted plant" -> "기둥"
                cleanName == "couch" -> "소파"
                cleanName == "bed" -> "침대"
                cleanName == "toilet" -> "변기"
                cleanName == "tv" -> "텔레비전"
                cleanName == "laptop" -> "노트북"
                cleanName == "refrigerator" -> "냉장고"
                cleanName == "microwave" -> "전자레인지"
                cleanName == "oven" -> "오븐"
                cleanName == "sink" -> "싱크대"
                cleanName == "clock" -> "시계"
                cleanName == "vase" -> "꽃병"
                cleanName == "cat" -> "고양이"
                cleanName == "dog" -> "강아지"
                cleanName == "horse" -> "말"
                cleanName == "umbrella" -> "우산"
                cleanName == "backpack" -> "배낭"
                cleanName == "handbag" -> "핸드백"
                cleanName == "suitcase" -> "여행가방"
                cleanName == "bottle" -> "병"
                cleanName == "cup" -> "컵"
                cleanName == "book" -> "책"
                else -> ""
            }
        }

        return when (box.cls) {
            0 -> "사람"
            1 -> "자전거"
            2 -> "차량"
            3 -> "오토바이"
            5 -> "버스"
            7 -> "트럭"
            56 -> "의자"
            60 -> "탁자"
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