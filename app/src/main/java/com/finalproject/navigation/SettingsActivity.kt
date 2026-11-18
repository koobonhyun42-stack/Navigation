package com.finalproject.navigation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.finalproject.navigation.databinding.ActivitySettingsBinding
import java.util.Locale

// 설정 화면: 말하기 속도, 진동 켜기/끄기, 진동 느껴 보기, 첫 사용 안내 다시 보기
class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivitySettingsBinding

    // 바꾼 속도를 바로 들려주기 위한 음성
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        listOf(binding.tvSettingsTitle, binding.tvSpeechTitle, binding.tvVibrationTitle,
            binding.tvVibrationTry)
            .forEach { ViewCompat.setAccessibilityHeading(it, true) }
        tts = TextToSpeech(this, this)

        val rate = UiSettings.speechRate(this)
        binding.rgSpeechRate.check(when {
            rate < UiSettings.RATE_NORMAL -> R.id.rbSlow
            rate > UiSettings.RATE_NORMAL -> R.id.rbFast
            else -> R.id.rbNormal
        })
        binding.rgSpeechRate.setOnCheckedChangeListener { _, checkedId ->
            val (newRate, label) = when (checkedId) {
                R.id.rbSlow -> UiSettings.RATE_SLOW to R.string.set_speech_slow
                R.id.rbFast -> UiSettings.RATE_FAST to R.string.set_speech_fast
                else -> UiSettings.RATE_NORMAL to R.string.set_speech_normal
            }
            UiSettings.setSpeechRate(this, newRate)
            // 고른 속도로 예시 문장을 들려줌 (톡백이 "선택됨"을 읽은 뒤에 나오도록 조금 늦춤)
            val delay = if (isScreenReaderOn()) 900L else 0L
            binding.root.postDelayed({
                speak(getString(R.string.set_speech_sample, getString(label)), newRate)
            }, delay)
        }

        binding.swVibration.isChecked = UiSettings.vibrationEnabled(this)
        binding.swVibration.setOnCheckedChangeListener { _, checked ->
            UiSettings.setVibrationEnabled(this, checked)
            // 켜면 짧게 한 번 울려서 확인시켜 줌
            if (checked) vibrateSample()
            // 톡백은 스위치 상태를 스스로 읽으므로, 톡백이 꺼져 있을 때만 말함
            if (!isScreenReaderOn()) {
                speak(getString(if (checked) R.string.set_vibration_on else R.string.set_vibration_off),
                    UiSettings.speechRate(this))
            }
        }

        // 진동 느껴 보기: 진동을 꺼 두었어도 어떤 느낌인지 알 수 있게 그대로 울림
        binding.btnVibLeft.setOnClickListener { vibratePattern(PATTERN_LEFT) }
        binding.btnVibRight.setOnClickListener { vibratePattern(PATTERN_RIGHT) }
        binding.btnVibFront.setOnClickListener { vibratePattern(PATTERN_FRONT_NEAR) }

        binding.btnOnboarding.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java)
                .putExtra(OnboardingActivity.EXTRA_REVIEW, true))
        }
        binding.btnSettingsBack.setOnClickListener { finish() }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = tts?.setLanguage(Locale.KOREAN)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun speak(text: String, rate: Float) {
        if (!ttsReady || isFinishing) return
        tts?.setSpeechRate(rate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "settings")
    }

    private fun vibrateSample() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(200)
        }
    }

    // 진동 모양 하나를 한 번 울림
    private fun vibratePattern(pattern: LongArray) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun isScreenReaderOn(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        // 실제 경고 진동과 같은 모양 (FeedbackManager.triggerVibration 값과 맞춰 둠. 그쪽이 바뀌면 같이 바꿔야 함)
        // 형식: [쉬는 시간, 울리는 시간, 쉬는 시간, 울리는 시간 ...] 밀리초
        private val PATTERN_LEFT = longArrayOf(0, 120, 80, 120)   // 왼쪽: 짧게 두 번
        private val PATTERN_RIGHT = longArrayOf(0, 350)           // 오른쪽: 길게 한 번
        private val PATTERN_FRONT_NEAR = longArrayOf(0, 400)      // 앞쪽 가까움(1.5미터 이내): 더 길게 한 번
    }
}
