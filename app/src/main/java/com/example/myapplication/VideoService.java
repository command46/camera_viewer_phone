package com.example.myapplication;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.*;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoService extends Service {
    private static final String TAG = "VideoService"; // 日志标签
    private static final int NOTIFICATION_ID = 2; // 通知ID
    private static final String CHANNEL_ID = "VideoServiceChannel"; // 通知渠道ID
    private static final int VIDEO_LENGTH_MS = 15000; // 视频长度，毫秒
    private static final String SERVER_IP = "192.168.31.214"; // 服务器IP地址
    private static final int SERVER_PORT = 12346; // 视频服务端口
    private static final int SOCKET_TIMEOUT_MS = 10000; // Socket连接超时时间，毫秒
    private CameraManager cameraManager; // 相机管理器
    private CameraDevice cameraDevice; // 相机设备
    private CameraCaptureSession cameraCaptureSession; // 相机捕获会话
    private MediaRecorder mediaRecorder; // 媒体录制器
    private Handler mainHandler; // 主线程Handler
    private String currentCameraId; // 当前使用的相机ID
    private Size videoSize; // 视频尺寸
    private Surface recorderSurface; // 录制器Surface
    private Timer timer; // 定时器，用于循环录制
    private boolean isRecording = false; // 是否正在录制
    private Notification notification; // 前台服务通知
    private Semaphore cameraOpenCloseLock = new Semaphore(1); // 信号量，用于同步相机打开/关闭操作
    private AtomicBoolean isConnecting = new AtomicBoolean(false); // 原子布尔值，表示是否正在连接服务器

    private File currentVideoFile; // 当前视频文件

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "服务创建"); // 添加中文日志
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();

        // 检查所需权限
        if (!checkPermissions()) {
            Log.e(TAG, "权限检查失败，停止服务"); // 添加中文日志
            stopSelf();
            return;
        }

        // 构建前台服务通知
        notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("视频服务")
                .setContentText("服务正在前台运行")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.i(TAG, "前台服务启动成功"); // 添加中文日志
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务失败", e); // 添加中文日志
            stopSelf();
        }
    }

    // 检查权限
    private boolean checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少相机或录音权限"); // 添加中文日志
            Toast.makeText(this, "缺少必要权限，服务无法启动", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "服务启动命令"); // 添加中文日志
        if (intent != null) {
            String ipAddress = intent.getStringExtra("IP_ADDRESS");
            if (ipAddress != null && !ipAddress.isEmpty()) {
                cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
                initializeCamera();
                startRecordingLoop();
            } else {
                Log.e(TAG, "IP地址为空"); // 添加中文日志
                stopSelf();
                Toast.makeText(this, "IP地址无效，服务停止", Toast.LENGTH_SHORT).show();
            }
        }
        return START_STICKY;
    }

    // 初始化相机
    private void initializeCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少相机或录音权限"); // 添加中文日志
                return;
            }

            String[] cameraIds = cameraManager.getCameraIdList();
            currentCameraId = cameraIds[0]; // Start with the first camera

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));

            Log.d(TAG, "使用相机: " + currentCameraId + ", 视频尺寸: " + videoSize.getWidth() + "x" + videoSize.getHeight()); // 添加中文日志

        } catch (CameraAccessException e) {
            Log.e(TAG, "访问相机失败", e); // 添加中文日志
        }
    }

    // 选择合适的视频尺寸
    private Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "找不到合适的视频尺寸"); // 添加中文日志
        return choices[choices.length - 1];
    }

    // 启动循环录制
    private void startRecordingLoop() {
        Log.d(TAG, "启动循环录制"); // 添加中文日志
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "定时器任务运行, 是否正在录制: " + isRecording); // 添加中文日志
                if (!isRecording) {
                    startRecording();
                }
            }
        }, 0, VIDEO_LENGTH_MS + 2000); // 延迟0毫秒，每隔 VIDEO_LENGTH_MS + 1000 毫秒执行一次
    }

    // 启动录制
    private void startRecording() {
        Log.d(TAG, "开始录制"); // 添加中文日志
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少权限"); // 添加中文日志
                return;
            }

            setupMediaRecorder();
            String cameraId = getFirstAvailableCamera();
            if (cameraId == null) {
                Log.e(TAG, "没有可用的相机"); // 添加中文日志
                return;
            }
            currentCameraId = cameraId;

            Log.d(TAG, "打开相机: " + currentCameraId); // 添加中文日志
            Log.d(TAG, "startRecording: 尝试获取相机打开/关闭锁"); // 添加中文日志
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("等待相机打开超时.");
            }
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    Log.d(TAG, "startRecording: 相机打开后释放相机打开/关闭锁"); // 添加中文日志
                    Log.d(TAG, "相机打开成功"); // 添加中文日志
                    cameraDevice = camera;
                    try {
                        createCaptureSession();
                    } catch (Exception e) {
                        Log.e(TAG, "创建捕获会话失败", e); // 添加中文日志
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    Log.d(TAG, "startRecording: 相机断开连接后释放相机打开/关闭锁"); // 添加中文日志
                    Log.d(TAG, "相机断开连接"); // 添加中文日志
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cameraOpenCloseLock.release();
                    Log.d(TAG, "startRecording: 相机出错后释放相机打开/关闭锁"); // 添加中文日志
                    Log.e(TAG, "相机错误: " + error); // 添加中文日志
                    camera.close();
                }
            }, mainHandler);

        } catch (Exception e) {
            cameraOpenCloseLock.release();
            Log.e(TAG, "启动录制失败", e); // 添加中文日志
        }
    }

    // 获取第一个可用的相机ID
    private String getFirstAvailableCamera() {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length > 0) {
                Log.d(TAG, "找到相机: " + cameraIds[0]); // 添加中文日志
                return cameraIds[0];
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "获取相机列表失败", e); // 添加中文日志
        }
        return null;
    }

    // 创建相机捕获会话
    private void createCaptureSession() {
        Log.d(TAG, "创建捕获会话"); // 添加中文日志
        try {
            recorderSurface = mediaRecorder.getSurface(); // 获取mediaRecorder的surface
            List<Surface> surfaces = Arrays.asList(recorderSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "捕获会话配置完成"); // 添加中文日志
                    cameraCaptureSession = session;
                    try {
                        CaptureRequest.Builder builder =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(recorderSurface);
                        session.setRepeatingRequest(builder.build(), null, mainHandler);
                        mediaRecorder.start();
                        isRecording = true;
                        Log.d(TAG, "录制开始"); // 添加中文日志

                        // 设置定时器停止录制
                        mainHandler.postDelayed(() -> {
                            Log.d(TAG, "延迟后停止录制"); // 添加中文日志
                            stopRecording();
                        }, VIDEO_LENGTH_MS);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "启动相机预览失败", e); // 添加中文日志
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "配置捕获会话失败"); // 添加中文日志
                }
            }, mainHandler);

        } catch (Exception e) {
            Log.e(TAG, "创建捕获会话失败", e); // 添加中文日志
        }
    }

    // 停止录制
    private void stopRecording() {
        if (!isRecording) return;

        Log.d(TAG, "停止录制中...");
        isRecording = false;
        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
            Log.d(TAG, "录制停止"); // 添加中文日志
            Thread.sleep(300);
            // 保存视频文件
            File videoFile = currentVideoFile; // 获取当前视频文件
            Log.d(TAG, "视频文件路径: " + videoFile.getAbsolutePath());

            Log.d(TAG, "文件写入完成，开始发送: " + videoFile.getAbsolutePath());
            try {
                sendFileToServer(videoFile);
            } catch (Exception e) {
                Log.e(TAG, "发送视频到服务器失败", e); // 添加中文日志
            }

            closeCamera();

        } catch (RuntimeException e) {
            Log.e(TAG, "停止录制失败", e); // 添加中文日志
        } catch(Exception e){
            Log.e(TAG, "停止录制时发生其他异常", e);
        }
    }



    private void closeCamera() {
        Log.d(TAG, "closeCamera: 尝试获取相机打开/关闭锁"); // 添加中文日志
        try {
            cameraOpenCloseLock.acquire();
            Log.d(TAG, "closeCamera: 相机打开/关闭锁已获取"); // 添加中文日志
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("尝试锁定相机关闭时中断.", e);
        } finally {
            cameraOpenCloseLock.release();
            Log.d(TAG, "closeCamera: 相机打开/关闭锁已释放"); // 添加中文日志
        }
    }


    // 设置媒体录制器
    private void setupMediaRecorder() {
        Log.d(TAG, "设置媒体录制器"); // 添加中文日志
        try {
            currentVideoFile = getOutputMediaFile(); // 获取输出文件
            if (currentVideoFile == null) {
                Log.e(TAG, "创建输出文件失败"); // 添加中文日志
                return;
            }

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(currentVideoFile.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(10000000);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoSize(1280, 720);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOrientationHint(90);

            mediaRecorder.prepare();
            Log.d(TAG, "媒体录制器准备成功"); // 添加中文日志
        } catch (IOException e) {
            Log.e(TAG, "准备媒体录制器失败: " + e.getMessage()); // 添加中文日志
        }
    }

    // 获取输出媒体文件
    private File getOutputMediaFile() {
        File mediaStorageDir = new File(getFilesDir() + "/videos");

        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.e(TAG, "创建目录失败"); // 添加中文日志
                return null;
            }
        }


        String timeStamp = new SimpleDateFormat("dd-HH-mm", Locale.getDefault()).format(new Date());
        String baseFileName = "VID_" + timeStamp;
        String extension = ".mp4";
        File outputFile = new File(mediaStorageDir.getPath() + File.separator + baseFileName + extension);

        // 如果文件已存在，添加序号
        int sequence = 1;
        while (outputFile.exists()) {
            outputFile = new File(mediaStorageDir.getPath() + File.separator +
                    baseFileName + "_" + sequence + extension);
            sequence++;
        }

        Log.d(TAG, "生成输出文件名: " + outputFile.getName()); // 添加中文日志
        return outputFile;
    }

    // 发送文件到服务器
    private void sendFileToServer(File file) {
        if (!file.exists()) {
            Log.d(TAG, "跳过发送: 文件不存在"); // 添加中文日志
            return;
        }

        new Thread(() -> {
            isConnecting.set(true);
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(SERVER_IP, SERVER_PORT), SOCKET_TIMEOUT_MS); // Add timeout
                Log.d(TAG, "连接到服务器"); // 添加中文日志

                String fileName = null;
                try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                     FileInputStream fis = new FileInputStream(file)) {

                    fileName = file.getName();
                    Log.d(TAG, "发送视频名字: " + fileName); // 添加中文日志
                    dos.writeUTF(fileName);
                    dos.flush();

                    long fileSize = file.length(); // Get file size
                    dos.writeLong(fileSize); // Send file size first
                    dos.flush();

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, bytesRead);
                    }
                    dos.flush();

                    Log.d(TAG, "文件发送成功: " + fileName); // 添加中文日志

                    if (!file.delete()) {
                        Log.e(TAG, "删除文件失败: " + fileName); // 添加中文日志
                    }
                } catch (IOException e) {
                    Log.e(TAG, "发送文件失败: " + fileName + ", 错误: " + e.getMessage()); // 添加中文日志
                }
            } catch (IOException e) {
                Log.e(TAG, "连接到服务器失败: " + SERVER_IP + ":" + SERVER_PORT + ", 错误: " + e.getMessage()); // 添加中文日志
            } finally {
                isConnecting.set(false);
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "关闭Socket出错", e); // 添加中文日志
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
        Log.d(TAG, "服务销毁"); // 添加中文日志
        if (timer != null) {
            timer.cancel();
        }
        closeCamera();
        if (mediaRecorder != null) {
            mediaRecorder.release();
        }
    }

    // 创建通知渠道
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "前台服务渠道",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}