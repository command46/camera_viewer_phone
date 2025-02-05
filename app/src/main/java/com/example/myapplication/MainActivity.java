package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
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
}