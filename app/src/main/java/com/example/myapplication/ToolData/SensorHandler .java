// SensorHandler.java
package com.example.myapplication.utils;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

public class SensorHandler implements SensorEventListener {

    private static final String TAG = "SensorHandler";

    public interface LightSensorListener {
        void onLightLevelChanged(float lux);
        void onLightSensorUnavailable();
    }

    private final SensorManager sensorManager;
    private Sensor lightSensor;
    private final LightSensorListener listener;

    public SensorHandler(Context context, LightSensorListener listener) {
        this.listener = listener;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (lightSensor == null) {
                Log.w(TAG, "Light sensor not available on this device.");
                if (listener != null) {
                    listener.onLightSensorUnavailable();
                }
            }
        } else {
            Log.e(TAG, "Could not get SensorManager service.");
            if (listener != null) {
                listener.onLightSensorUnavailable();
            }
        }
    }

    public void registerListener() {
        if (sensorManager != null && lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Light sensor listener registered.");
        }
    }

    public void unregisterListener() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d(TAG, "Light sensor listener unregistered.");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lux = event.values[0];
            if (listener != null) {
                listener.onLightLevelChanged(lux);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 通常不需要处理光线传感器的精度变化
    }
}
