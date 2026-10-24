package ru.oldzoomer.yachtapp.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class SensorReading(
    val azimuthDegrees: Float = 0f,
    val pressureHpa: Float = 0f,
    val hasPressureSensor: Boolean = false
)

class SensorDataManager(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val sensorReadings: Flow<SensorReading> = callbackFlow {
        var currentAzimuth = 0f
        var currentPressure = 0f
        var hasPressure = false

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        hasPressure = pressureSensor != null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        val orientationAngles = FloatArray(3)
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                        var azimuthRad = orientationAngles[0]
                        var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                        if (azimuthDeg < 0) azimuthDeg += 360f
                        currentAzimuth = azimuthDeg
                    }

                    Sensor.TYPE_PRESSURE -> {
                        currentPressure = event.values[0]
                        hasPressure = true
                    }
                }
                trySend(SensorReading(currentAzimuth, currentPressure, hasPressure))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        rotationSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        pressureSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Send initial state (only if pressure sensor is available)
        trySend(SensorReading(currentAzimuth, if (hasPressure) currentPressure else 0f, hasPressure))

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
