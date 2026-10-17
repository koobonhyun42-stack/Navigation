package com.finalproject.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class PowerSaveManager (
    context: Context,
    private val onStateChanged: (isPowerSaveMode: Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastMovementTime = System.currentTimeMillis()
    private var isPowerSaveMode = false

    companion object {
        private const val MOVEMENT_THRESHOLD = 1.8f
        private const val IDLE_TIMEOUT_MS = 5000L
    }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x= event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val acceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat() - SensorManager.GRAVITY_EARTH

        val now = System.currentTimeMillis()

        if (kotlin.math.abs(acceleration) > MOVEMENT_THRESHOLD) {
            lastMovementTime = now
            if (isPowerSaveMode) {
                isPowerSaveMode = false
                onStateChanged(false)
            }
        } else if (!isPowerSaveMode && (now - lastMovementTime > IDLE_TIMEOUT_MS)) {
            isPowerSaveMode = true
            onStateChanged(true)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}