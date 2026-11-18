package com.finalproject.navigation

import android.content.Context

// 화면에서 바꾸는 사용자 설정을 저장하는 곳
object UiSettings {
    private const val PREFS = "ui_settings"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_VIBRATION = "vibration"

    // 말하기 속도 단계 (느리게, 보통, 빠르게) 이부분은 아직 연결 안되어있음
    const val RATE_SLOW = 0.6f
    const val RATE_NORMAL = 0.8f
    const val RATE_FAST = 1.1f

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboarded(context: Context) = prefs(context).getBoolean(KEY_ONBOARDED, false)
    fun setOnboarded(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ONBOARDED, value).apply()

    fun speechRate(context: Context) = prefs(context).getFloat(KEY_SPEECH_RATE, RATE_NORMAL)
    fun setSpeechRate(context: Context, value: Float) =
        prefs(context).edit().putFloat(KEY_SPEECH_RATE, value).apply()

    fun vibrationEnabled(context: Context) = prefs(context).getBoolean(KEY_VIBRATION, true)
    fun setVibrationEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_VIBRATION, value).apply()
}
