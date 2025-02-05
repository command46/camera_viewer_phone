package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private final boolean StartPhotoService = true;
    private final boolean StartVideoService = true;
    private final boolean StartCameraStreamService = false;

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private Button connectButton;
    private EditText ipAddressEditText;
    private String ipAddress;
    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE

    };

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private TextView lightSensorTextView;

    private LineChart lightChart;
    private LineDataSet lightDataSet;
    private int dataSetSize = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化传感器
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorTextView = findViewById(R.id.lightSensorTextView);

        if (lightSensor == null) {
            lightSensorTextView.setText("Light Sensor: Not Available");
        }

        connectButton = findViewById(R.id.connectButton);
        ipAddressEditText = findViewById(R.id.ipAddressEditText);
        connectButton.setOnClickListener(v -> {
            ipAddress = ipAddressEditText.getText().toString().trim();
            if (ipAddress.isEmpty()) {
                Toast.makeText(this, "请输入地址", Toast.LENGTH_SHORT).show();
                return;
            }
            checkCameraPermissionAndStartService();
        });
        SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
        String savedIpAddress = sharedPreferences.getString("IP_ADDRESS", "");
        ipAddressEditText.setText(savedIpAddress);

        lightChart = findViewById(R.id.lightChart);
        setupLightChart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (lightSensor != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lightLevel = event.values[0];
            lightSensorTextView.setText(String.format("Light Sensor: %.1f lux", lightLevel));

            addLightEntry(lightLevel);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理精度变化
    }

    private void checkCameraPermissionAndStartService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            startService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startService();
            } else {
                Toast.makeText(this, "摄像头权限被拒绝，应用即将退出", Toast.LENGTH_SHORT).show();
                finish(); // 拒绝权限则退出应用
            }
        } else if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startService();
            } else {
                Toast.makeText(this, "需要所有权限才能启动服务", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startService(){
        if (StartVideoService){
            startVideoService();
        }
        if (StartPhotoService){
            startPhotoService();
        }
        if (StartCameraStreamService){
            startCameraStreamService();
        }
    }

    private void startCameraStreamService() {
        Log.d(TAG, "Starting CameraStreamService with IP: " + ipAddress);
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        serviceIntent.putExtra("IP_ADDRESS", ipAddress);
        startForegroundService(serviceIntent); // 使用 startForegroundService 以便在后台稳定运行
        Log.d(TAG, "CameraStreamService started");
    }
    private void startVideoService() {
        if (checkAndRequestPermissions()) {
            Log.d(TAG, "Starting VideoService with IP: " + ipAddress);
            Intent serviceIntent = new Intent(this, VideoService.class);
            serviceIntent.putExtra("IP_ADDRESS", ipAddress);
            startForegroundService(serviceIntent);
            Log.d(TAG, "VideoService started");
        }
    }
    private void startPhotoService() { // 启动 PhotoService
        Log.d(TAG, "Starting PhotoService with IP: " + ipAddress);
        Intent serviceIntent = new Intent(this, PhotoService.class); // 启动 PhotoService
        serviceIntent.putExtra("IP_ADDRESS", ipAddress);
        startForegroundService(serviceIntent); // 使用 startForegroundService
        Log.d(TAG, "PhotoService started");
    }

    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(this, permission) != 
                PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, 
                permissionsNeeded.toArray(new String[0]), 
                PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }
    @Override
    protected void onStop() {
        super.onStop();
        // 保存当前输入的 IP 地址到 SharedPreferences
        SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("IP_ADDRESS", ipAddressEditText.getText().toString());
        editor.apply();
    }

    private void setupLightChart() {
        lightChart.getDescription().setEnabled(false);
        lightChart.setTouchEnabled(false);
        lightChart.setDragEnabled(false);
        lightChart.setScaleEnabled(false);
        lightChart.setDrawGridBackground(false);
        lightChart.setPinchZoom(false);
        lightChart.setBackgroundColor(Color.WHITE);
        lightChart.setMaxHighlightDistance(300);

        XAxis x = lightChart.getXAxis();
        x.setEnabled(false);

        YAxis y = lightChart.getAxisLeft();
        y.setLabelCount(6, false);
        y.setTextColor(Color.BLACK);
        y.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);
        y.setDrawGridLines(false);
        y.setAxisLineColor(Color.BLACK);

        lightChart.getAxisRight().setEnabled(false);

        lightDataSet = new LineDataSet(null, "Light");
        lightDataSet.setLineWidth(2f);
        lightDataSet.setColor(Color.BLUE);
        lightDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        lightDataSet.setDrawValues(false);
        lightDataSet.setDrawCircles(false);
        lightDataSet.setHighLightColor(Color.BLUE);

        LineData data = new LineData(lightDataSet);
        lightChart.setData(data);
    }

    private void addLightEntry(float lightLevel) {
        LineData data = lightChart.getData();

        if (data != null) {
            ILineDataSet set = data.getDataSetByIndex(0);

            if (set == null) {
                set = createLightSet();
                data.addDataSet(set);
            }

            data.addEntry(new Entry(set.getEntryCount(), lightLevel), 0);
            data.notifyDataChanged();

            lightChart.notifyDataSetChanged();
            lightChart.setVisibleXRangeMaximum(dataSetSize);
            lightChart.moveViewToX(data.getEntryCount());
        }
    }

    private LineDataSet createLightSet() {
        LineDataSet set = new LineDataSet(null, "Light");
        set.setLineWidth(2f);
        set.setColor(Color.BLUE);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        set.setDrawValues(false);
        set.setDrawCircles(false);
        set.setHighLightColor(Color.BLUE);
        return set;
    }
}