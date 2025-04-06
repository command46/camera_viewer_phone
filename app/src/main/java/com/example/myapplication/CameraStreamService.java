// --- START OF FILE CameraStreamService.java (Fixed) ---
package com.example.myapplication;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences; // 引入 SharedPreferences
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
import android.os.Looper; // 引入 Looper
import android.text.TextUtils; // 引入 TextUtils
import android.util.Log;
import android.util.Size; // 确保引入 Size
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator; // 确保引入 Comparator
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CameraStreamService extends Service {

    private static final String TAG = "CameraStreamService";
    private static final String NOTIFICATION_CHANNEL_ID = "CameraStreamChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int BACK_CAMERA_PORT = 12345;
    private static final int FRONT_CAMERA_PORT = 12346;
    // private static final long FRAME_DELAY_MS = 100; // 可以根据需要调整或移除简单的延时
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 10000;
    private static final int IMAGE_BUFFER_SIZE = 2; // 每个相机使用2个图像缓冲区
    private static final int JPEG_QUALITY = 75; // JPEG 压缩质量

    // --- SharedPreferences Keys ---
    private static final String PREFS_NAME = "CameraServicePrefs";
    private static final String KEY_IP_ADDRESS = "last_ip_address";
    private static final String KEY_RESTART_SERVICE = "restart_service_flag"; // 新增：重启标志的键

    private String ipAddress; // 当前服务实例使用的IP地址
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final Map<Integer, OutputStream> outputStreams = new ConcurrentHashMap<>();
    private final Map<Integer, CameraDevice> cameraDevices = new ConcurrentHashMap<>();
    private final Map<Integer, CameraCaptureSession> cameraCaptureSessions = new ConcurrentHashMap<>();
    private final Map<Integer, ImageReader> imageReaders = new ConcurrentHashMap<>();
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private CameraManager cameraManager;
    private final Map<Integer, Size> previewSizes = new ConcurrentHashMap<>();

    private final IBinder binder = new LocalBinder();
    private ExecutorService connectionExecutor;
    private SharedPreferences sharedPreferences; // 持有 SharedPreferences 引用


    public class LocalBinder extends Binder {
        CameraStreamService getService() {
            return CameraStreamService.this;
        }
    }

    // --- Service Lifecycle Methods ---

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "服务 onCreate");
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); // 初始化 SharedPreferences
        startForegroundServiceNotification();
        startBackgroundThread();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "无法获取 CameraManager！服务可能无法工作。");
            showToast("无法访问相机管理器");
        }
        // 使用缓存线程池可能更灵活，但固定线程池也可以
        connectionExecutor = Executors.newFixedThreadPool(2); // 用于网络连接
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "服务 onStartCommand, flags=" + flags + ", startId=" + startId);

        String receivedIp = null;
        boolean isRestart = (flags & START_FLAG_RETRY) != 0 || (flags & START_FLAG_REDELIVERY) != 0;

        if (intent != null) {
            receivedIp = intent.getStringExtra("IP_ADDRESS");
            Log.d(TAG, "从 Intent 接收到 IP: " + receivedIp);
        } else {
            Log.w(TAG, "服务启动时 Intent 为 null (可能是系统重启或 START_STICKY 行为)");
            isRestart = true; // Intent 为 null 通常意味着是某种形式的重启
        }

        // 确定要使用的 IP 地址
        if (!TextUtils.isEmpty(receivedIp)) {
            this.ipAddress = receivedIp;
            // 保存这次成功接收到的 IP 地址
            saveIpAddress(this.ipAddress);
            Log.i(TAG, "使用来自 Intent 的 IP 地址: " + this.ipAddress + "，并已保存。");
        } else if (isRestart) {
            // 如果是重启且没有从 Intent 获得 IP，尝试从 SharedPreferences 加载
            this.ipAddress = sharedPreferences.getString(KEY_IP_ADDRESS, null);
            if (!TextUtils.isEmpty(this.ipAddress)) {
                Log.i(TAG, "服务重启，从 SharedPreferences 加载 IP 地址: " + this.ipAddress);
            } else {
                Log.e(TAG, "服务重启，但无法从 SharedPreferences 获取有效的 IP 地址。服务将停止。");
                showToast("服务重启失败：缺少 IP 地址");
                stopSelfSafely();
                return START_NOT_STICKY; // 返回 NOT_STICKY 避免无效重启循环
            }
        } else {
            // 非重启，但 Intent 中没有有效的 IP 地址 (理论上不应发生，除非调用者代码有误)
            Log.e(TAG, "服务启动时 Intent 中缺少有效的 IP 地址。服务将停止。");
            showToast("服务启动失败：无效的 IP 地址");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        // 检查是否已有活动流，避免重复启动连接
        // 使用 compareAndSet 确保原子性地检查和设置
        if (activeStreamCount.compareAndSet(0, 2)) { // 如果当前是0，则设置为2并返回true
            Log.i(TAG,"activeStreamCount 为 0，开始连接和打开相机...");
            connectAndOpenCamerasAsync();
        } else {
            Log.w(TAG, "服务已在运行中 (activeStreamCount=" + activeStreamCount.get() + ")，忽略新的连接请求。检查 IP 是否变化?");
            // 如果 IP 地址变化了，可能需要先停止旧的连接再开始新的
            // 这里简化处理：如果服务已在运行，则不重新启动连接。
            // 你可以根据需求添加更复杂的逻辑，例如判断 IP 是否真的变了，如果变了则先 shutdown 再重启。
        }

        return START_STICKY; // 希望服务在被杀后能被系统尝试重启
    }


    @Override
    public void onDestroy() {
        Log.w(TAG, "服务 onDestroy 开始...");
        // 首先执行清理
        shutdownAndCleanup();

        // 清理完成后，检查是否需要发送重启广播
        // 从 SharedPreferences 读取重启标志
        boolean shouldRestart = sharedPreferences.getBoolean(KEY_RESTART_SERVICE, false); // 默认不重启

        if (shouldRestart) {
            Log.i(TAG, "检测到需要重启服务，发送广播...");
            Intent broadcastIntent = new Intent("RestartCameraService");
            // 可以选择性地将 IP 放入广播，但 Receiver 主要依赖 SharedPreferences
            // broadcastIntent.putExtra("IP_ADDRESS", sharedPreferences.getString(KEY_IP_ADDRESS, null));
            sendBroadcast(broadcastIntent);
            showToast("服务已停止，正在尝试自动重新连接..."); // 修改提示语
        } else {
            Log.i(TAG, "服务已停止，且未设置重启标志。");
        }

        super.onDestroy(); // 调用父类方法
        Log.i(TAG, "服务 onDestroy 完成。");
    }

    // --- Core Logic Methods ---

    /** 保存 IP 地址到 SharedPreferences */
    private void saveIpAddress(String ip) {
        if (sharedPreferences != null && !TextUtils.isEmpty(ip)) {
            sharedPreferences.edit().putString(KEY_IP_ADDRESS, ip).apply();
        }
    }

    /**
     * 异步连接 Socket 并尝试打开相机。
     */
    private void connectAndOpenCamerasAsync() {
        // 重置或确保执行器是活动的
        if (connectionExecutor == null || connectionExecutor.isShutdown()) {
            connectionExecutor = Executors.newFixedThreadPool(2);
        }

        // 确保 IP 地址有效
        if (TextUtils.isEmpty(ipAddress)) {
            Log.e(TAG, "IP 地址为空，无法启动连接！");
            // 设置计数为0并尝试停止服务
            activeStreamCount.set(0);
            stopSelfSafely();
            return;
        }

        Log.i(TAG, "提交连接任务到线程池...");
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_BACK, BACK_CAMERA_PORT));
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_FRONT, FRONT_CAMERA_PORT));
    }


    /**
     * 尝试连接 Socket，如果成功，则尝试在后台线程打开对应的相机。
     */
    private void connectSocketAndTryOpen(int cameraFacing, int port) {
        String facingStr = getFacingString(cameraFacing);
        Socket socket = null;
        OutputStream outputStream = null;
        String currentIp = this.ipAddress; // 获取当前实例的 IP 地址

        // 再次检查 IP
        if (TextUtils.isEmpty(currentIp)) {
            Log.e(TAG, "connectSocketAndTryOpen: IP 地址为空 (" + facingStr + ")，取消连接。");
            decrementActiveStreamCountAndCheckStop();
            return;
        }

        try {
            Log.i(TAG, "尝试连接 " + facingStr + " 相机 Socket 到 " + currentIp + ":" + port);
            socket = new Socket();
            // 设置 SO_REUSEADDR 选项，有助于快速重启监听端
            // socket.setReuseAddress(true); // 在客户端通常不需要，但在服务器端有用
            socket.connect(new InetSocketAddress(currentIp, port), SOCKET_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(10000); // 设置读取超时（例如10秒）
            socket.setKeepAlive(true); // 启用 TCP Keep-Alive

            outputStream = socket.getOutputStream();

            // --- 连接成功 ---
            sockets.put(cameraFacing, socket);
            outputStreams.put(cameraFacing, outputStream);
            Log.i(TAG, facingStr + " 相机 Socket 连接成功。");
            showToast(facingStr + " 相机已连接"); // 改为中文

            // --- 立即尝试打开相机 (在后台线程) ---
            if (backgroundHandler != null) {
                final Socket finalSocket = socket; // 在 lambda 中使用 final 变量
                backgroundHandler.post(() -> tryOpenCameraAfterConnect(cameraFacing, finalSocket));
            } else {
                Log.e(TAG, "后台 Handler 为空，无法启动相机打开流程 (" + facingStr + ")！");
                decrementActiveStreamCountAndCheckStop();
                closeSocket(cameraFacing); // 关闭刚刚建立的连接
            }

        } catch (IOException e) {
            Log.e(TAG, "连接 " + facingStr + " 相机 Socket 失败 (" + currentIp + ":" + port + "): " + e.getMessage());
            showToast("连接失败 (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            // 确保关闭可能部分创建的 socket
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        } catch (Exception e) {
            Log.e(TAG, "连接或启动相机时发生意外错误 (" + facingStr + "): " + e.getMessage());
            decrementActiveStreamCountAndCheckStop();
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        }
    }

    /**
     * 在 Socket 连接成功后，尝试打开对应的相机。此方法应在 backgroundHandler 上运行。
     * 注意：移除了 stream 参数，因为 stream 会在 socket 关闭时一起关闭。
     */
    private void tryOpenCameraAfterConnect(int cameraFacing, Socket connectedSocket) {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, "准备打开 " + facingStr + " 相机 (关联 Socket: " + (connectedSocket.isConnected() ? connectedSocket.getRemoteSocketAddress() : "已关闭") + ")"); // 增加状态检查

        // --- 检查 Socket 状态 ---
        // 在尝试打开相机前，再次确认 Socket 是否仍然有效且是我们期望的那个
        Socket currentSocketInMap = sockets.get(cameraFacing);
        if (currentSocketInMap == null || currentSocketInMap != connectedSocket || !connectedSocket.isConnected() || connectedSocket.isClosed()) {
            Log.w(TAG, facingStr + " 相机的 Socket 在打开相机操作开始前已无效或改变。取消打开。");
            // 无需减少计数，因为 socket 关闭/移除时应该已经处理过
            return;
        }

        // --- 检查 CameraManager ---
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager 不可用，无法打开 " + facingStr + " 相机。");
            showToast("相机管理器错误");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing); // 关闭对应的 socket
            return;
        }

        // --- 查找 Camera ID ---
        String cameraId = getCameraIdForFacing(cameraFacing);
        if (cameraId == null) {
            Log.e(TAG, "未找到 " + facingStr + " 相机的 ID。");
            showToast("未找到 " + facingStr + " 相机");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
            return;
        }

        // --- 检查权限 ---
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有相机权限，无法打开 " + facingStr + " 相机。");
            showToast("缺少相机权限");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
            return;
        }

        // --- 执行打开相机 ---
        try {
            Log.i(TAG, "正在打开 " + facingStr + " 相机 (ID: " + cameraId + ")");
            openCameraForFacing(cameraId, cameraFacing); // 移除 socket 参数，内部会检查 map
        } catch (CameraAccessException e) {
            Log.e(TAG, "访问相机时出错 (" + facingStr + "): " + e.getMessage());
            showToast("相机访问出错 (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
        } catch (IllegalStateException e) {
            // 例如相机正在使用中
            Log.e(TAG, "打开相机时状态错误 (" + facingStr + "): " + e.getMessage());
            showToast("相机状态错误 (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
        } catch (Exception e) {
            Log.e(TAG, "打开相机时发生意外错误 (" + facingStr + "): " + e.getMessage());
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
        }
    }

    /**
     * 打开指定 ID 和朝向的相机。此方法必须在 backgroundHandler 上运行。
     * 内部会检查关联的 Socket 是否仍然有效。
     */
    private void openCameraForFacing(String cameraId, int cameraFacing) throws CameraAccessException, IllegalStateException {
        String facingStr = getFacingString(cameraFacing);

        // 在打开相机硬件前，检查关联的 Socket 是否仍然在 Map 中且有效
        Socket associatedSocket = sockets.get(cameraFacing);
        if (associatedSocket == null || !associatedSocket.isConnected() || associatedSocket.isClosed()) {
            Log.w(TAG, facingStr + " 相机的 Socket 在打开相机硬件前已关闭或无效。取消打开操作。");
            // 如果 socket 是在这里发现无效的，可能需要确保计数器已正确处理
            // 但通常 closeSocket 会处理计数器
            return; // 直接返回，不抛出异常
        }

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            Log.e(TAG, "无法获取 " + facingStr + " 相机的流配置。");
            showToast("无法获取相机配置 (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
            return;
        }

        Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (outputSizes == null || outputSizes.length == 0) {
            Log.e(TAG, facingStr + " 相机不支持 JPEG 输出。");
            showToast("相机不支持JPEG (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
            return;
        }

        // 选择合适的预览尺寸
        Size targetSize = new Size(640, 480); // 可以调整
        // *** FIX START: Explicitly type lambda parameter 's' ***
        Size selectedSize = Collections.min(Arrays.asList(outputSizes),
                Comparator.comparingLong((Size s) -> Math.abs((long)s.getWidth() * s.getHeight() - (long)targetSize.getWidth() * targetSize.getHeight()))
                        .thenComparingInt((Size s) -> Math.abs(s.getWidth() - targetSize.getWidth())) // 优先匹配面积，其次匹配宽度
        );
        // *** FIX END ***
        previewSizes.put(cameraFacing, selectedSize);
        Log.i(TAG, "为 " + facingStr + " 相机选择的预览尺寸: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());

        // 关闭旧的 ImageReader (如果存在)
        closeReader(cameraFacing);

        // 创建新的 ImageReader
        ImageReader imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.JPEG, IMAGE_BUFFER_SIZE);
        imageReaders.put(cameraFacing, imageReader); // 存入新的 Reader

        // --- 设置 ImageAvailable Listener ---
        // 使用 final 变量确保 lambda 捕获正确的值
        final int currentFacing = cameraFacing;
        imageReader.setOnImageAvailableListener(reader -> {
            Size currentSize = previewSizes.get(currentFacing); // 获取当前尺寸
            if (currentSize != null) {
                processImageAvailable(reader, currentFacing, currentSize);
            } else {
                // 如果尺寸信息丢失，尝试从 reader 获取，或记录错误并返回
                Log.e(TAG, "处理图像时无法获取 " + getFacingString(currentFacing) + " 的预览尺寸!");
                try (Image img = reader.acquireLatestImage()) { // 使用 try-with-resources
                    if (img != null) {
                        // 可选：尝试从 Image 获取尺寸
                         Size fallbackSize = new Size(img.getWidth(), img.getHeight());
                         processImageAvailable(reader, currentFacing, fallbackSize);
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "处理图像时 ImageReader 状态异常 (" + getFacingString(currentFacing) + "): " + e.getMessage());
                }
            }
        }, backgroundHandler);


        // 再次检查权限（理论上不需要，但保持防御性编程）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "打开相机时权限丢失 (" + facingStr + ")！");
            showToast("相机权限丢失");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
            return;
        }

        Log.d(TAG, "正在调用 cameraManager.openCamera 打开 " + facingStr + " 相机设备 (ID: " + cameraId + ")");
        // 调用 openCamera，这是一个异步操作
        // 需要处理 SecurityException
        try {
            cameraManager.openCamera(cameraId, getCameraStateCallback(cameraFacing), backgroundHandler);
        } catch (SecurityException se) {
            Log.e(TAG, "打开相机时发生 SecurityException (" + facingStr + ")！权限可能在检查后被撤销。", se);
            showToast("相机权限错误 (Security)");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
        }
    }


    /**
     * 处理 ImageReader 的 onImageAvailable 事件。
     */
    private void processImageAvailable(ImageReader reader, int cameraFacing, Size previewSize) {
        try (Image image = reader.acquireNextImage()) { // 使用 try-with-resources 自动关闭 image
            if (image != null) {
                // 帧率控制可以放在这里，如果需要的话

                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                // 在调用 processAndSendFrame 之前，再次确认 Socket 是否仍然连接
                Socket currentSocket = sockets.get(cameraFacing);
                if (currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed()) {
                    processAndSendFrame(bytes, cameraFacing, previewSize.getWidth(), previewSize.getHeight());
                } else {
                    // Socket 已断开，不再处理和发送帧
                    Log.w(TAG, "Socket for " + getFacingString(cameraFacing) + " is disconnected, skipping frame processing.");
                    // 可能需要触发关闭流程（如果尚未触发）
                    if (sockets.containsKey(cameraFacing)) { // 检查是否还需要关闭
                        closeCameraStream(cameraFacing);
                        decrementActiveStreamCountAndCheckStop();
                    }
                }
            }
        } catch (IllegalStateException e) {
            // Reader 可能已经关闭
            Log.w(TAG, "处理图像时 Reader 状态错误 (" + getFacingString(cameraFacing) + "): " + e.getMessage());
        } catch (Exception e) {
            // 其他未知错误
            Log.e(TAG, "处理图像时发生意外错误 (" + getFacingString(cameraFacing) + ")", e);
            // 考虑是否关闭流
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        }
        // image 会在 try-with-resources 结束时自动关闭
    }

    @Nullable
    private String getCameraIdForFacing(int cameraFacing) {
        if (cameraManager == null) return null;
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == cameraFacing) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "查找 Camera ID 时出错: " + e.getMessage());
        }
        return null; // 未找到
    }

    /**
     * 创建相机状态回调。
     */
    private CameraDevice.StateCallback getCameraStateCallback(final int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.i(TAG, facingStr + " 相机成功打开 (ID: " + camera.getId() + ")");
                // 检查是否是预期的设备
                boolean exists = cameraDevices.containsKey(cameraFacing);
                if (!exists || cameraDevices.get(cameraFacing) != camera) {
                    // 如果 Map 中已存在其他设备（理论上不应发生，除非清理不当），先关闭旧的
                    if (exists) {
                        Log.w(TAG, "onOpened: " + facingStr + " 已存在不同的设备，关闭旧设备...");
                        closeCameraDevice(cameraFacing); // 只关闭设备，不影响流的其他部分
                    }
                    cameraDevices.put(cameraFacing, camera); // 存储新打开的设备
                } else {
                    Log.d(TAG, facingStr + " 相机 onOpened 回调，设备已存在且匹配。");
                }

                // 确保创建会话也在后台线程
                if (backgroundHandler != null) {
                    backgroundHandler.post(() -> createCameraPreviewSession(cameraFacing));
                } else {
                    Log.e(TAG, "后台 Handler 为空，无法创建 " + facingStr + " 预览会话！");
                    // *** FIX START: Call closeCameraStream instead of closeCamera ***
                    closeCameraStream(cameraFacing); // 关闭刚打开的相机和关联的 Socket
                    // *** FIX END ***
                    decrementActiveStreamCountAndCheckStop(); // 计数处理
                    // closeSocket(cameraFacing) 已包含在 closeCameraStream 中
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.w(TAG, facingStr + " 相机已断开连接 (ID: " + camera.getId() + ")");
                // 清理与此 cameraFacing 相关的所有资源
                if (cameraDevices.get(cameraFacing) == camera) { // 确保是我们追踪的那个设备
                    closeCameraStream(cameraFacing); // 关闭相机、会话、Reader、Socket
                    decrementActiveStreamCountAndCheckStop(); // 减少计数并检查
                    showToast(facingStr + " 相机连接断开"); // 改为中文
                } else {
                    Log.w(TAG, facingStr + "相机 onDisconnected 回调，但设备不匹配或已移除？");
                    // 尝试关闭这个未追踪的设备，以防万一
                    try { camera.close(); } catch (Exception e) { /* ignore */ }
                }
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, facingStr + " 相机错误 (ID: " + camera.getId() + ")，错误代码: " + errorToString(error));
                if (cameraDevices.get(cameraFacing) == camera) {
                    closeCameraStream(cameraFacing);
                    decrementActiveStreamCountAndCheckStop();
                    showToast(facingStr + " 相机错误: " + errorToString(error));
                } else {
                    Log.w(TAG, facingStr + "相机 onError 回调，但设备不匹配或已移除？");
                    try { camera.close(); } catch (Exception e) { /* ignore */ }
                }
            }
        };
    }

    /**
     * 创建预览会话。应在 backgroundHandler 上运行。
     */
    private void createCameraPreviewSession(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        CameraDevice cameraDevice = cameraDevices.get(cameraFacing);
        ImageReader imageReader = imageReaders.get(cameraFacing);

        // --- 前置检查 ---
        if (cameraDevice == null) {
            Log.e(TAG, "无法创建会话: " + facingStr + " 的 CameraDevice 为空 (可能已关闭)。");
            // 如果相机已关闭，计数应已处理。确保 socket 也关闭。
            if (sockets.containsKey(cameraFacing)) {
                closeSocket(cameraFacing); // 关闭关联的 socket
                decrementActiveStreamCountAndCheckStop(); // 确保计数正确
            }
            return;
        }
        if (imageReader == null) {
            Log.e(TAG, "无法创建会话: " + facingStr + " 的 ImageReader 为空。");
            closeCameraStream(cameraFacing); // 关闭相机和 socket
            decrementActiveStreamCountAndCheckStop();
            return;
        }
        // 检查 ImageReader Surface 的有效性
        android.view.Surface surface = imageReader.getSurface();
        if(surface == null || !surface.isValid()){
            Log.e(TAG, "无法创建会话: " + facingStr + " 的 ImageReader Surface 无效。");
            closeCameraStream(cameraFacing); // 关闭相机和 socket
            decrementActiveStreamCountAndCheckStop();
            return;
        }

        // 检查 Socket 是否仍然连接
        Socket associatedSocket = sockets.get(cameraFacing);
        if (associatedSocket == null || !associatedSocket.isConnected() || associatedSocket.isClosed()) {
            Log.e(TAG, "无法创建会话: " + facingStr + " 的 Socket 已断开。");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            return;
        }

        // --- 创建会话 ---
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface); // 使用获取到的 Surface

            // 可选: 设置自动对焦, 自动曝光等
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); // 连续自动对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH); // 自动曝光，自动闪光灯（如果可用）

            Log.d(TAG, "正在为 " + facingStr + " 创建 CaptureSession...");
            // 使用 List.of() 创建不可变列表 (API 21+)
            List<Surface> outputs = Collections.singletonList(surface);

            cameraDevice.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            // 再次检查相机设备和 Socket 是否仍然有效
                            CameraDevice currentDevice = cameraDevices.get(cameraFacing);
                            Socket currentSocket = sockets.get(cameraFacing);
                            if (currentDevice == null || currentDevice != cameraDevice) {
                                Log.w(TAG, facingStr + " 相机在会话配置完成时已关闭或改变。");
                                try { session.close(); } catch (Exception e) { /* ignore */ }
                                return;
                            }
                            if (currentSocket == null || !currentSocket.isConnected() || currentSocket.isClosed()) {
                                Log.w(TAG, facingStr + " 的 Socket 在会话配置完成时已断开。");
                                try { session.close(); } catch (Exception e) { /* ignore */ }
                                // 触发关闭流程
                                if (sockets.containsKey(cameraFacing)){ // 再次检查避免重复关闭
                                    closeCameraStream(cameraFacing);
                                    decrementActiveStreamCountAndCheckStop();
                                }
                                return;
                            }

                            // --- 会话配置成功 ---
                            Log.i(TAG, facingStr + " 预览会话配置成功。");
                            cameraCaptureSessions.put(cameraFacing, session);
                            try {
                                // 启动重复请求以开始预览
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE); // 确保AE稳定
                                CaptureRequest previewRequest = captureRequestBuilder.build();
                                session.setRepeatingRequest(previewRequest, null, backgroundHandler);
                                Log.i(TAG, facingStr + " 相机预览已启动。");
                            } catch (CameraAccessException | IllegalStateException e) {
                                Log.e(TAG, "启动 " + facingStr + " 预览重复请求时出错: ", e);
                                closeCameraStream(cameraFacing);
                                decrementActiveStreamCountAndCheckStop();
                            } catch (Exception e) { // 捕获其他潜在异常
                                Log.e(TAG, "启动 " + facingStr + " 预览时发生未知错误: ", e);
                                closeCameraStream(cameraFacing);
                                decrementActiveStreamCountAndCheckStop();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "配置 " + facingStr + " 相机预览会话失败。");
                            // 配置失败，关闭这个流
                            closeCameraStream(cameraFacing);
                            decrementActiveStreamCountAndCheckStop();
                        }
                    }, backgroundHandler
            );
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) { // 捕获更多可能的异常
            Log.e(TAG, "创建 " + facingStr + " 预览会话时准备请求出错: ", e);
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        }
    }

    /**
     * 处理原始 JPEG 数据，旋转并发送。
     */
    private void processAndSendFrame(byte[] jpegBytes, int cameraFacing, int width, int height) {
        // --- 在处理前检查 Socket ---
        Socket currentSocket = sockets.get(cameraFacing);
        if (currentSocket == null || !currentSocket.isConnected() || currentSocket.isClosed()) {
            // Log.v(TAG, "Socket for " + getFacingString(cameraFacing) + " is unavailable, skipping frame send.");
            // 如果 Socket 无效，我们不需要处理图像。可能需要关闭流（如果尚未关闭）。
            if (sockets.containsKey(cameraFacing)) { // 确保只关闭一次
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
            return; // 不再处理和发送
        }

        Bitmap bitmap = null;
        Bitmap rotatedBitmap = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            // 使用 BitmapFactory.Options 优化内存使用
            BitmapFactory.Options options = new BitmapFactory.Options();
            // options.inMutable = true; // 允许复用 Bitmap 内存 (如果需要进一步优化)
            options.inPreferredConfig = Bitmap.Config.RGB_565; // 使用更少的内存，可能牺牲色彩质量

            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
            if (bitmap == null) {
                Log.w(TAG, "解码 JPEG 失败 (" + getFacingString(cameraFacing) + ")");
                return;
            }

            Matrix matrix = new Matrix();
            int rotation = 0;
            boolean flip = false;

            // 获取相机传感器方向 (如果设备存在)
            Integer sensorOrientation = 0; // 默认值
            CameraDevice device = cameraDevices.get(cameraFacing);
            if (device != null && cameraManager != null) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(device.getId());
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (sensorOrientation == null) sensorOrientation = 0; // 处理 null 情况
                } catch (CameraAccessException e) {
                    Log.e(TAG, "获取传感器方向失败 (" + getFacingString(cameraFacing) + "): " + e.getMessage());
                }
            }

            // 简化旋转逻辑：假设竖屏，可以根据需要结合屏幕方向调整
            if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                // 前置摄像头通常是镜像的，且传感器方向可能不同
                rotation = (sensorOrientation ) % 360; // 尝试补偿传感器方向
                flip = true; // 前置需要水平翻转
            } else {
                // 后置摄像头
                rotation = (sensorOrientation ) % 360; // 尝试补偿传感器方向
            }
            matrix.postRotate(rotation);
            if (flip) {
                // 水平翻转 (前置镜像)
                matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
            }

            // 应用旋转和翻转
            rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            // 压缩处理
            byteArrayOutputStream = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream);
            byte[] rotatedBytes = byteArrayOutputStream.toByteArray();

            // 发送数据
            sendFrameData(rotatedBytes, cameraFacing);

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "处理帧时内存不足 (" + getFacingString(cameraFacing) + ")", oom);
            // 尝试减少 JPEG 质量或分辨率
            // 强制 GC 可能有帮助，但不保证
            System.gc();
            // 关闭此流以防持续 OOM
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } catch (Exception e) {
            Log.e(TAG, "处理或发送帧时出错 (" + getFacingString(cameraFacing) + ")", e);
            // 发生其他错误，关闭此流
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } finally {
            // 回收 Bitmap 释放内存
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (rotatedBitmap != null && !rotatedBitmap.isRecycled()) rotatedBitmap.recycle();
            if (byteArrayOutputStream != null) {
                try { byteArrayOutputStream.close(); } catch (IOException e) { /* ignore */ }
            }
        }
    }


    /**
     * 发送带长度前缀的帧数据。
     */
    private void sendFrameData(byte[] frameData, int cameraFacing) {
        OutputStream outputStream = outputStreams.get(cameraFacing);
        Socket socket = sockets.get(cameraFacing); // 用于检查连接状态

        if (outputStream != null && socket != null && socket.isConnected() && !socket.isClosed()) {
            try {
                int length = frameData.length;
                // 长度使用网络字节序 (Big Endian)
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length);

                // 写入操作可能需要同步，如果多个线程可能访问同一个流
                // 但这里 Camera 回调和发送都在同一个 backgroundHandler 上，所以不需要额外同步
                outputStream.write(lengthBuffer.array()); // 发送 4 字节长度
                outputStream.write(frameData);            // 发送实际数据
                outputStream.flush();                     // 确保数据发送出去
                // Log.v(TAG, "已发送帧: facing=" + getFacingString(cameraFacing) + ", length=" + length);

            } catch (IOException e) {
                // 网络错误是常见的失败原因
                Log.e(TAG, "发送帧时 IO 错误 (" + getFacingString(cameraFacing) + "): " + e.getMessage());
                // 发送失败，意味着连接可能已断开，关闭此流
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            } catch (Exception e) {
                // 其他潜在错误
                Log.e(TAG, "发送帧时未知错误 (" + getFacingString(cameraFacing) + "): " + e.getMessage());
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        } else {
            // Log.d(TAG, "无法发送帧，输出流/Socket 无效或已关闭 (" + getFacingString(cameraFacing) + ")");
            // 如果流/socket 无效，确保清理流程被触发
            if (sockets.containsKey(cameraFacing)) { // 检查是否还需要清理
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        }
    }


    // --- Helper and Cleanup Methods ---

    /**
     * 减少活动流计数，并在计数为零时安全地停止服务。
     */
    private void decrementActiveStreamCountAndCheckStop() {
        int remaining = activeStreamCount.decrementAndGet(); // 原子递减并获取新值
        Log.d(TAG, "活动流计数减少，剩余: " + remaining);
        if (remaining <= 0) {
            // 使用 compareAndSet 防止计数器变为负数后多次触发停止
            if (activeStreamCount.compareAndSet(remaining, 0)) {
                Log.i(TAG, "所有活动流已停止或失败 (计数达到 " + remaining + ")，正在请求停止服务...");
                stopSelfSafely(); // 调用安全停止方法
            } else {
                // 如果 compareAndSet 失败，说明在检查和设置之间计数器又发生了变化（理论上不太可能，因为递减是原子操作）
                // 或者 remaining 已经是 0，再次设置为 0 也没问题。
                Log.d(TAG, "decrementActiveStreamCountAndCheckStop: compareAndSet( " + remaining + ", 0) 失败，当前计数: " + activeStreamCount.get());
                // 如果当前计数确实<=0，还是应该停止
                if (activeStreamCount.get() <= 0) {
                    Log.i(TAG, "计数器已为 " + activeStreamCount.get() + "，重新请求停止服务...");
                    stopSelfSafely();
                }
            }
        }
    }


    /**
     * 安全地停止服务，尝试在主线程调用 stopSelf()。
     */
    private void stopSelfSafely() {
        Log.d(TAG, "stopSelfSafely() 被调用");
        // 确保 stopSelf() 在主线程执行，以符合 Service 生命周期要求
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, "当前在主线程，直接调用 stopSelf()");
            try {
                stopForeground(Service.STOP_FOREGROUND_REMOVE); // 先尝试移除前台状态
                stopSelf(); // 触发 onDestroy
            } catch (Exception e) {
                Log.e(TAG,"调用 stopSelf() 时发生异常", e);
            }
        } else {
            Log.d(TAG, "当前不在主线程，post stopSelf() 到主线程 Handler");
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "主线程 Handler 执行 stopSelf()");
                try {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE); // 先尝试移除前台状态
                    stopSelf(); // 触发 onDestroy
                } catch (Exception e) {
                    Log.e(TAG,"在主线程 Handler 中调用 stopSelf() 时发生异常", e);
                }
            });
        }
        // 注意：调用 stopSelf() 后，onDestroy() 会被异步调用，不是立即执行。
    }

    /**
     * 关闭指定朝向的相机流相关的所有资源（相机、会话、Reader、Socket、流）。
     * 增加日志和空检查。
     */
    private void closeCameraStream(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        Log.i(TAG, "正在关闭 " + facingStr + " 相机流的所有资源...");
        // 按照依赖关系的反向顺序关闭：会话 -> 设备 -> Reader -> Socket/Stream
        closeSession(cameraFacing);
        closeCameraDevice(cameraFacing);
        closeReader(cameraFacing);
        closeSocket(cameraFacing); // 这个方法关闭 Socket 和 OutputStream

        // 移除预览尺寸信息
        previewSizes.remove(cameraFacing);
        Log.d(TAG, facingStr + " 流资源关闭完成。");
    }

    /** 关闭指定朝向的预览会话 */
    private void closeSession(int cameraFacing) {
        CameraCaptureSession session = cameraCaptureSessions.remove(cameraFacing);
        if (session != null) {
            String facingStr = getFacingString(cameraFacing);
            Log.d(TAG, "正在关闭 " + facingStr + " 会话...");
            try {
                session.close();
                Log.d(TAG, facingStr + " 会话已关闭。");
            } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " 会话时出错", e); }
        }
    }

    /** 关闭指定朝向的相机设备 */
    private void closeCameraDevice(int cameraFacing) {
        CameraDevice device = cameraDevices.remove(cameraFacing);
        if (device != null) {
            String facingStr = getFacingString(cameraFacing);
            Log.d(TAG, "正在关闭 " + facingStr + " 设备 (ID: " + device.getId() + ")...");
            try {
                device.close();
                Log.d(TAG, facingStr + " 设备已关闭。");
            } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " 设备时出错", e); }
        }
    }


    /** 关闭指定朝向的 ImageReader */
    private void closeReader(int cameraFacing) {
        ImageReader reader = imageReaders.remove(cameraFacing);
        if (reader != null) {
            String facingStr = getFacingString(cameraFacing);
            Log.d(TAG, "正在关闭 " + facingStr + " ImageReader...");
            try {
                reader.close();
                Log.d(TAG, facingStr + " ImageReader 已关闭。");
            } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " Reader 时出错", e); }
        }
    }

    /** 关闭指定朝向的 Socket 和输出流 */
    private void closeSocket(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        // 先关闭输出流
        OutputStream os = outputStreams.remove(cameraFacing);
        if (os != null) {
            Log.d(TAG, "正在关闭 " + facingStr + " 输出流...");
            try { os.close(); } catch (IOException e) { Log.w(TAG, "关闭 " + facingStr + " 输出流时出错: " + e.getMessage()); }
        }
        // 再关闭 Socket
        Socket socket = sockets.remove(cameraFacing);
        if (socket != null) {
            Log.d(TAG, "正在关闭 " + facingStr + " Socket (连接到: " + socket.getRemoteSocketAddress() + ")...");
            if (!socket.isClosed()) {
                try {
                    // socket.shutdownInput(); // 可选，优雅关闭的一部分
                    // socket.shutdownOutput(); // 可选
                    socket.close();
                    Log.d(TAG, facingStr + " Socket 已关闭。");
                } catch (IOException e) { Log.e(TAG, "关闭 " + facingStr + " Socket 时出错: " + e.getMessage()); }
            } else {
                Log.d(TAG, facingStr + " Socket 已处于关闭状态。");
            }
        }
    }

    // closeSpecificSocket 方法不再需要，因为 Socket 关闭现在由 closeSocket 处理

    /** 停止所有相机流，清理所有资源 */
    private void stopAllCameraStreams() {
        Log.i(TAG, "正在停止所有相机流和相关资源...");
        // 创建 keySet 的副本进行迭代，避免 ConcurrentModificationException
        // 使用 ConcurrentHashMap 的 keySet() 是弱一致性的，迭代时移除是安全的
        for (Integer facing : sockets.keySet()) { // 迭代当前存在的 socket 连接
            closeCameraStream(facing); // 关闭每个流的所有资源
        }
        // 确保所有 Map 都被清空（即使迭代中移除失败）
        cameraCaptureSessions.clear();
        cameraDevices.clear();
        imageReaders.clear();
        outputStreams.clear();
        sockets.clear();
        previewSizes.clear();
        Log.i(TAG, "所有相机相关资源已清理。");
    }


    /** 关闭服务前的最终清理 */
    private void shutdownAndCleanup() {
        Log.i(TAG, "开始服务关闭和资源清理 (shutdownAndCleanup)...");
        activeStreamCount.set(0); // 强制将计数器设置为0，防止后续意外操作

        // 1. 关闭网络连接执行器，中断正在进行的连接尝试
        if (connectionExecutor != null) {
            Log.d(TAG,"正在关闭网络连接线程池...");
            connectionExecutor.shutdownNow(); // 立即停止所有任务
            try {
                // 等待一小段时间让任务停止
                if (!connectionExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "网络连接线程池关闭超时。");
                } else {
                    Log.d(TAG,"网络连接线程池已关闭。");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "等待网络连接线程池关闭时被中断。", e);
                connectionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            connectionExecutor = null; // 释放引用
        }

        // 2. 确保所有相机流相关的资源在后台线程关闭（如果线程还在）
        Handler handler = backgroundHandler; // 获取当前 handler 引用
        HandlerThread thread = backgroundThread; // 获取当前 thread 引用
        if (handler != null && thread != null && thread.isAlive()) {
            Log.d(TAG,"提交 stopAllCameraStreams 到后台线程...");
            // 使用 post 而不是直接调用，确保队列中的任务（如图像处理）能先完成或被取消
            final CountDownLatch cleanupLatch = new CountDownLatch(1); // 用于等待清理完成
            handler.post(() -> {
                Log.d(TAG,"后台线程开始执行 stopAllCameraStreams...");
                try {
                    stopAllCameraStreams();
                } finally {
                    Log.d(TAG,"后台线程完成 stopAllCameraStreams。");
                    cleanupLatch.countDown(); // 通知清理完成
                }
                // 不在此处停止后台线程，由外部统一停止
            });
            // 等待后台清理任务完成，设置超时
            try {
                if (!cleanupLatch.await(1500, TimeUnit.MILLISECONDS)) { // 等待最多 1.5 秒
                    Log.w(TAG, "等待后台清理任务超时！");
                } else {
                    Log.d(TAG, "后台清理任务已完成。");
                }
            } catch (InterruptedException e) {
                Log.e(TAG,"等待后台清理任务时中断",e);
                Thread.currentThread().interrupt();
            }

        } else {
            Log.w(TAG, "后台线程不可用，直接在当前线程尝试清理相机资源...");
            // 如果后台线程已停止或从未成功启动，直接尝试清理
            stopAllCameraStreams();
        }

        // 3. 停止后台线程（现在清理任务已执行或超时）
        stopBackgroundThread();

        // 4. 停止前台服务状态
        Log.d(TAG,"正在停止前台服务状态...");
        // 确保在主线程或安全地调用
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE); // 移除通知
        } else {
            new Handler(Looper.getMainLooper()).post(() -> stopForeground(Service.STOP_FOREGROUND_REMOVE));
        }


        Log.i(TAG, "服务资源清理完成 (shutdownAndCleanup)。");
    }


    // startBackgroundThread 方法保持不变...
    private void startBackgroundThread() {
        // 避免重复创建
        if (backgroundThread == null || !backgroundThread.isAlive()) { // 检查是否已存在且活动
            backgroundThread = new HandlerThread("CameraBackground", android.os.Process.THREAD_PRIORITY_BACKGROUND); // 设置为后台优先级
            backgroundThread.start();
            // 等待 Looper 准备好
            Looper looper = backgroundThread.getLooper();
            if (looper != null) {
                backgroundHandler = new Handler(looper);
                Log.d(TAG, "后台线程已启动并获取 Handler。");
            } else {
                Log.e(TAG,"无法获取后台线程的 Looper！");
                // 停止线程
                backgroundThread.quitSafely();
                try { backgroundThread.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                backgroundThread = null;
                backgroundHandler = null; // 确保 handler 也置 null
            }
        } else {
            Log.d(TAG,"后台线程已在运行。");
        }
    }

    // stopBackgroundThread 方法保持不变...
    private void stopBackgroundThread() {
        HandlerThread threadToStop = backgroundThread; // 获取当前引用
        if (threadToStop != null) {
            Log.d(TAG, "正在请求停止后台线程 (ID: " + threadToStop.getThreadId() + ")...");
            // 先将 handler 置 null，防止后续任务提交
            backgroundHandler = null;
            threadToStop.quitSafely(); // 安全退出，处理完当前消息
            try {
                threadToStop.join(1000); // 等待最多 1 秒
                if (threadToStop.isAlive()) {
                    Log.w(TAG,"后台线程停止超时，可能存在阻塞任务。尝试中断...");
                    threadToStop.interrupt(); // 尝试中断阻塞
                    threadToStop.join(500); // 再等一小会
                    if(threadToStop.isAlive()) {
                        Log.e(TAG,"后台线程中断后仍然活动！");
                    } else {
                        Log.d(TAG, "后台线程中断后已停止。");
                    }
                } else {
                    Log.d(TAG, "后台线程已停止。");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "停止后台线程时被中断。", e);
                Thread.currentThread().interrupt(); // 重新设置中断状态
            } finally {
                // 确保引用被清理
                if (backgroundThread == threadToStop) { // 检查是否还是原来的那个线程
                    backgroundThread = null;
                }
            }
        } else {
            Log.d(TAG, "后台线程已经是 null，无需停止。");
        }
    }


    // showToast 方法保持不变...
    private void showToast(final String message) {
        // 确保 Toast 在主线程显示
        // 检查 Looper 是否准备好
        if (Looper.myLooper() == null) {
            Looper.prepare(); // 在非 Looper 线程调用 Toast 需要 prepare
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try {
                // 检查 Context 是否仍然有效
                if (getApplicationContext() != null) {
                    Toast.makeText(CameraStreamService.this, message, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "显示 Toast 时出错: " + e.getMessage());
                // 可能发生在服务快速停止，Context 无效时
            }
        });
    }

    // getFacingString 方法保持不变...
    private String getFacingString(int cameraFacing) {
        return (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) ? "后置" : "前置";
    }

    // 将 CameraDevice.StateCallback 的错误代码转换为字符串
    private String errorToString(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "相机已被占用";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "达到最大相机使用数量";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "相机设备已被禁用";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "相机设备致命错误";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "相机服务致命错误";
            default:
                return "未知错误 (" + error + ")";
        }
    }

    // --- Notification Methods ---
    private void startForegroundServiceNotification() {
        createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 确保这个 drawable 存在
                .setContentTitle("相机流服务") // 修改标题
                .setContentText("正在传输相机画面...") // 修改内容
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级
                .setOngoing(true); // 使通知不可滑动消除

        Notification notification = builder.build();
        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "前台服务通知已启动。");
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务时出错: " + e.getMessage());
            // 处理 Android 版本限制等问题 (例如 targetSDK >= 34 需要 FOREGROUND_SERVICE_CAMERA 权限)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE_CAMERA 权限 (Android 14+)");
                } else {
                    showToast("启动前台服务失败: " + e.getMessage());
                }
            } else {
                showToast("启动前台服务失败: " + e.getMessage());
            }
            stopSelfSafely(); // 启动失败则停止服务
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "相机流服务通道"; // 使用 CharSequence
            String description = "用于相机流服务的后台运行通知";
            int importance = NotificationManager.IMPORTANCE_LOW; // 低优先级
            NotificationChannel serviceChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
            serviceChannel.setDescription(description);
            // serviceChannel.setSound(null, null); // 明确设置无声音
            // serviceChannel.enableVibration(false); // 明确设置无振动

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                try {
                    manager.createNotificationChannel(serviceChannel);
                    Log.d(TAG, "通知通道已创建或已存在。");
                } catch (Exception e) {
                    Log.e(TAG, "创建通知通道失败: " + e.getMessage());
                }
            } else {
                Log.e(TAG, "无法获取 NotificationManager。");
            }
        }
    }

}
// --- END OF FILE CameraStreamService.java (Fixed) ---