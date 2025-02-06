package com.example.myapplication;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CameraStreamService extends Service {

    private static final String TAG = "CameraStreamService";
    private static final String NOTIFICATION_CHANNEL_ID = "CameraStreamChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int BACK_CAMERA_PORT = 12345;
    private static final int FRONT_CAMERA_PORT = 12346;
    private static final long FRAME_DELAY_MS = 105; // 500 milliseconds delay between frames

    private String ipAddress;
    private Map<Integer, Socket> sockets = new HashMap<>();
    private Map<Integer, OutputStream> outputStreams = new HashMap<>();
    private final Map<Integer, CameraDevice> cameraDevices = new HashMap<>();
    private Map<Integer, CameraCaptureSession> cameraCaptureSessions = new HashMap<>();
    private Map<Integer, ImageReader> imageReaders = new HashMap<>();
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Size previewSize;
    private boolean isStreamingEnabled = true; // 控制画面发送开关
    private long lastFrameTime = 0; // 上次发送帧的时间

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        CameraStreamService getService() {
            return CameraStreamService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        startForegroundServiceNotification();
        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (intent != null) {
            ipAddress = intent.getStringExtra("IP_ADDRESS");
            if (ipAddress != null && !ipAddress.isEmpty()) {
                connectToSockets();
            } else {
                Log.e(TAG, "IP Address is null or empty");
                stopSelf();
                Toast.makeText(this, "IP地址无效，服务停止", Toast.LENGTH_SHORT).show();
            }
        }
        return START_STICKY;
    }


    private void startForegroundServiceNotification() {
        createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentTitle("双服务系统直播服务")
                .setContentText("正在后台传输前后服务系统画面...")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        Notification notification = builder.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "服务通道",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void connectToSockets() {
        new Thread(() -> {
            try {
                Socket backSocket = new Socket();
                int timeout = 15000;
                backSocket.connect(new InetSocketAddress(ipAddress, BACK_CAMERA_PORT), timeout);
                sockets.put(CameraCharacteristics.LENS_FACING_BACK, backSocket);
                outputStreams.put(CameraCharacteristics.LENS_FACING_BACK, backSocket.getOutputStream());
                Log.d(TAG, "后置服务系统 Socket 连接成功");
                runOnUiThread(() -> Toast.makeText(this, "后置服务系统已连接到电脑", Toast.LENGTH_SHORT).show());

                Socket frontSocket = new Socket();
                frontSocket.connect(new InetSocketAddress(ipAddress, FRONT_CAMERA_PORT), timeout);
                sockets.put(CameraCharacteristics.LENS_FACING_FRONT, frontSocket);
                outputStreams.put(CameraCharacteristics.LENS_FACING_FRONT, frontSocket.getOutputStream());
                Log.d(TAG, "前置服务系统 Socket 连接成功");
                runOnUiThread(() -> Toast.makeText(this, "前置服务系统已连接到电脑", Toast.LENGTH_SHORT).show());

                openCameras();
            } catch (IOException e) {
                Log.e(TAG, "Socket 连接失败: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "连接电脑失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                stopSelf();
            }
        }).start();
    }


    private void runOnUiThread(Runnable runnable) {
        new Handler(getMainLooper()).post(runnable);
    }


    private void openCameras() {
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            String backCameraId = null;
            String frontCameraId = null;

            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backCameraId = cameraId;
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        frontCameraId = cameraId;
                    }
                }
            }

            if (backCameraId == null || frontCameraId == null) {
                Log.e(TAG, "未找到后置或前置服务系统");
                runOnUiThread(() -> Toast.makeText(this, "未找到后置或前置", Toast.LENGTH_SHORT).show());
                stopSelf();
                return;
            }

            openCameraForFacing(cameraManager, backCameraId, CameraCharacteristics.LENS_FACING_BACK);
            openCameraForFacing(cameraManager, frontCameraId, CameraCharacteristics.LENS_FACING_FRONT);


        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening cameras: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(this, "打开服务系统失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            stopSelf();
        }
    }

    private void openCameraForFacing(CameraManager cameraManager, String cameraId, int cameraFacing) throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            Log.e(TAG, "Cannot get stream configuration map for camera facing: " + cameraFacing);
            runOnUiThread(() -> Toast.makeText(this, "无法获取服务系统配置", Toast.LENGTH_SHORT).show());
            stopSelf();
            return;
        }

        Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (outputSizes == null || outputSizes.length == 0) {
            Log.e(TAG, "No supported JPEG sizes for camera facing: " + cameraFacing);
            runOnUiThread(() -> Toast.makeText(this, "不支持JPEG格式", Toast.LENGTH_SHORT).show());
            stopSelf();
            return;
        }

        Size targetSize = new Size(480, 640);
        Size bestSize = null;
        int minDiff = Integer.MAX_VALUE;
        for (Size size : outputSizes) {
            int diff = Math.abs(size.getWidth() - targetSize.getWidth()) + Math.abs(size.getHeight() - targetSize.getHeight());
            if (diff < minDiff) {
                minDiff = diff;
                bestSize = size;
            }
        }
        previewSize = bestSize != null ? bestSize : outputSizes[0];

        Log.d(TAG, "选择的预览尺寸 for camera facing " + cameraFacing + ": " + previewSize.getWidth() + "x" + previewSize.getHeight());

        ImageReader imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.JPEG, 2);
        imageReaders.put(cameraFacing, imageReader);

        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            private long lastFrameTime = 0; // 局部变量跟踪每个 ImageReader 的上次帧时间

            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    if (isStreamingEnabled) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastFrameTime >= FRAME_DELAY_MS) {
                            lastFrameTime = currentTime; // 更新上次帧时间
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = buffer.remaining() > 0 ? new byte[buffer.remaining()] : new byte[0]; // Check if buffer has data
                            buffer.get(bytes);
                            image.close();

                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap != null) {
                                Matrix matrix = new Matrix();
                                matrix.postRotate(90);

                                if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                                    matrix.postScale(1, -1);
                                    Log.d(TAG, "Apply vertical flip for front camera");
                                }

                                Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream);
                                byte[] rotatedBytes = byteArrayOutputStream.toByteArray();
                                sendFrame(rotatedBytes, cameraFacing);
                                bitmap.recycle();
                                rotatedBitmap.recycle();
                                Log.d(TAG, "获取并发送处理后的帧数据, 大小: " + rotatedBytes.length + " 字节, Camera Facing: " + cameraFacing);
                            } else {
                                Log.w(TAG, "BitmapFactory.decodeByteArray failed for camera facing: " + cameraFacing);
                                sendFrame(bytes, cameraFacing);
                            }
                        } else {
                            image.close(); // 距离上次发送时间不足，跳过这帧并关闭 Image
                            Log.d(TAG, "Frame skipped due to delay, Camera Facing: " + cameraFacing);
                        }
                    } else {
                        image.close(); // Streaming is disabled, close the Image
                        Log.d(TAG, "Frame discarded, streaming disabled, Camera Facing: " + cameraFacing);
                    }


                } else {
                    Log.w(TAG, "ImageReader 获取到空图像 for camera facing: " + cameraFacing);
                }
            }
        }, backgroundHandler);


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted in Service, this should not happen.");
            runOnUiThread(() -> Toast.makeText(this, "Camera permission not granted", Toast.LENGTH_SHORT).show());
            stopSelf();
            return;
        }

        Log.d(TAG, "打开服务系统: " + cameraId + ", Facing: " + cameraFacing);
        cameraManager.openCamera(cameraId, cameraStateCallbacks.get(cameraFacing), backgroundHandler);

    }

    private Map<Integer, CameraDevice.StateCallback> cameraStateCallbacks = new HashMap<>();
    {
        cameraStateCallbacks.put(CameraCharacteristics.LENS_FACING_BACK, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.d(TAG, "后置服务系统 opened 成功打开服务系统");
                cameraDevices.put(CameraCharacteristics.LENS_FACING_BACK, camera);
                createCameraPreviewSession(CameraCharacteristics.LENS_FACING_BACK);
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.e(TAG, "后置服务系统 disconnected");
                camera.close();
                cameraDevices.remove(CameraCharacteristics.LENS_FACING_BACK);
                stopSelf();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, "后置服务系统 error: " + error);
                camera.close();
                cameraDevices.remove(CameraCharacteristics.LENS_FACING_BACK);
                stopSelf();
            }
        });

        cameraStateCallbacks.put(CameraCharacteristics.LENS_FACING_FRONT, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.d(TAG, "前置服务系统 opened 成功打开服务系统");
                cameraDevices.put(CameraCharacteristics.LENS_FACING_FRONT, camera);
                createCameraPreviewSession(CameraCharacteristics.LENS_FACING_FRONT);
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.e(TAG, "前置服务系统 disconnected");
                camera.close();
                cameraDevices.remove(CameraCharacteristics.LENS_FACING_FRONT);
                stopSelf();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, "前置服务系统 error: " + error);
                camera.close();
                cameraDevices.remove(CameraCharacteristics.LENS_FACING_FRONT);
                stopSelf();
            }
        });
    }


    private void createCameraPreviewSession(int cameraFacing) {
        try {
            CameraDevice cameraDevice = cameraDevices.get(cameraFacing);
            ImageReader imageReader = imageReaders.get(cameraFacing);
            if (cameraDevice == null || imageReader == null) {
                Log.e(TAG, "CameraDevice or ImageReader is null for facing: " + cameraFacing);
                return;
            }
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            CameraDevice currentCameraDevice = cameraDevices.get(cameraFacing);
                            if (currentCameraDevice == null) {
                                return;
                            }
                            cameraCaptureSessions.put(cameraFacing, session);
                            try {
                                CaptureRequest previewRequest = captureRequestBuilder.build();
                                CameraCaptureSession currentSession = cameraCaptureSessions.get(cameraFacing);
                                if (currentSession != null) {
                                    currentSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
                                    Log.d(TAG, "Camera preview session started for facing: " + cameraFacing + " 服务系统预览会话已启动");
                                } else {
                                    Log.e(TAG, "CaptureSession is null for facing: " + cameraFacing);
                                }
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error starting preview session for facing: " + cameraFacing + ": " + e.getMessage());
                                stopSelf();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure camera session for facing: " + cameraFacing);
                            stopSelf();
                        }
                    }, backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session for facing: " + cameraFacing + ": " + e.getMessage());
            stopSelf();
        }
    }


    private void sendFrame(byte[] frameData, int cameraFacing) {
        OutputStream outputStream = outputStreams.get(cameraFacing);
        if (outputStream != null) {
            try {
                // 1. 获取帧数据长度
                int length = frameData.length;

                // 2. 将长度转换为 4 字节大端字节序的字节数组
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                lengthBuffer.order(ByteOrder.BIG_ENDIAN); // 设置为大端字节序
                lengthBuffer.putInt(length);
                byte[] lengthBytes = lengthBuffer.array();

                // 3. 先发送长度信息
                outputStream.write(lengthBytes);

                // 4. 再发送帧数据
                outputStream.write(frameData);

                outputStream.flush();
                Log.d(TAG, "Frame sent, size: " + frameData.length + " bytes, Camera Facing: " + cameraFacing + ", Length bytes sent: " + lengthBytes.length); // 添加日志
            } catch (IOException e) {
                Log.e(TAG, "Error sending frame for camera facing: " + cameraFacing + ": " + e.getMessage());
                stopCameraStream();
                closeSockets();
                stopSelf();
            }
        } else {
            Log.e(TAG, "outputStream is null for camera facing: " + cameraFacing + ", cannot send frame");
        }
    }

    private void stopCameraStream() {
        for (CameraCaptureSession session : cameraCaptureSessions.values()) {
            if (session != null) {
                session.close();
            }
        }
        cameraCaptureSessions.clear();
        for (CameraDevice device : cameraDevices.values()) {
            if (device != null) {
                device.close();
            }
        }
        cameraDevices.clear();
        for (ImageReader reader : imageReaders.values()) {
            if (reader != null) {
                reader.close();
            }
        }
        imageReaders.clear();
    }


    private void closeSockets() {
        for (Socket socket : sockets.values()) {
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Socket closed");
                } catch (IOException e) {
                    Log.e(TAG, "Error closing socket: " + e.getMessage());
                }
            }
        }
        sockets.clear();
        outputStreams.clear();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        stopCameraStream();
        closeSockets();
        stopBackgroundThread();
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while stopping background thread: " + e.getMessage());
            }
        }
    }
}