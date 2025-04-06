// --- START OF FILE CameraStreamService.java (增强日志和检查) ---
package com.example.myapplication;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.Comparator;
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
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 5000;
    private static final int IMAGE_BUFFER_SIZE = 2;
    private static final int JPEG_QUALITY = 70;
    private static final String PREFS_NAME = "CameraServicePrefs";
    private static final String KEY_IP_ADDRESS = "last_ip_address";
    private static final String KEY_RESTART_SERVICE = "restart_service_flag";
    private static final String KEY_RETRY_COUNT = "retry_count";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000; // 调试时可以缩短延迟为 1 秒
    public static final String ACTION_SHOW_RETRY_FAILURE_DIALOG = "com.example.myapplication.ACTION_SHOW_RETRY_FAILURE_DIALOG";

    private String ipAddress;
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final Map<Integer, OutputStream> outputStreams = new ConcurrentHashMap<>();
    private final Map<Integer, CameraDevice> cameraDevices = new ConcurrentHashMap<>();
    private final Map<Integer, CameraCaptureSession> cameraCaptureSessions = new ConcurrentHashMap<>();
    private final Map<Integer, ImageReader> imageReaders = new ConcurrentHashMap<>();
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Handler mainHandler;
    private CameraManager cameraManager;
    private final Map<Integer, Size> previewSizes = new ConcurrentHashMap<>();
    private final IBinder binder = new LocalBinder();
    private ExecutorService connectionExecutor;
    private SharedPreferences sharedPreferences;


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
        Log.i(TAG, "服务 onCreate");
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        startForegroundServiceNotification();
        startBackgroundThread();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "无法获取 CameraManager！服务可能无法工作。");
            showToast("无法访问传感器管理器");
        }
        connectionExecutor = Executors.newFixedThreadPool(2);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, ">>> 服务 onStartCommand 开始, flags=" + flags + ", startId=" + startId);

        // --- 新增：检查是否是手动启动 ---
        boolean isManualStart = false;
        if (intent != null && intent.getBooleanExtra("MANUAL_START", false)) {
            isManualStart = true;
            Log.d(TAG, "onStartCommand: 检测到手动启动标志 (MANUAL_START=true)");
        }
        // --- 结束新增 ---

        String receivedIp = null;
        boolean isRestartAttempt = (flags & START_FLAG_RETRY) != 0
                || (flags & START_FLAG_REDELIVERY) != 0
                || "RestartCameraService".equals(intent != null ? intent.getAction() : null);

        if (intent != null && intent.hasExtra("IP_ADDRESS")) {
            receivedIp = intent.getStringExtra("IP_ADDRESS");
            Log.d(TAG, "onStartCommand: 从 Intent 接收到 IP: " + receivedIp);
        } else {
            Log.w(TAG, "onStartCommand: Intent 为 null 或无 IP (系统重启或重试广播?)");
        }

        if (!TextUtils.isEmpty(receivedIp)) {
            this.ipAddress = receivedIp;
            // 保存IP地址（无论是手动还是自动重启获得的有效IP都应保存，以便下次重启能用）
            saveIpAddress(this.ipAddress);
            Log.i(TAG, "onStartCommand: 使用来自 Intent/保存的 IP 地址: " + this.ipAddress);
        } else if (isRestartAttempt && !isManualStart) { // 只有在自动重启时才尝试从 SharedPreferences 加载
            this.ipAddress = sharedPreferences.getString(KEY_IP_ADDRESS, null);
            if (!TextUtils.isEmpty(this.ipAddress)) {
                Log.i(TAG, "onStartCommand: 服务重启/重试，从 SharedPreferences 加载 IP: " + this.ipAddress);
            } else {
                Log.e(TAG, "onStartCommand: 服务重启/重试，但无法从 SharedPreferences 获取 IP。停止。");
                showToast("服务启动失败：缺少 IP 地址");
                // 即使加载IP失败，也应该重置计数器，因为这次重启周期结束了
                resetRetryCount();
                stopSelfSafely();
                Log.i(TAG, "<<< 服务 onStartCommand 结束 (因缺少 IP 而停止)");
                return START_NOT_STICKY;
            }
        } else { // 手动启动但 Intent 中无 IP，或非重启尝试但无 IP
            Log.e(TAG, "onStartCommand: 启动时缺少有效 IP 地址。停止。");
            showToast("服务启动失败：无效的 IP 地址");
            resetRetryCount(); // 启动失败，重置计数
            stopSelfSafely();
            Log.i(TAG, "<<< 服务 onStartCommand 结束 (因缺少 IP 而停止)");
            return START_NOT_STICKY;
        }

        // --- 修改：只有在手动启动时才重置计数 ---
        if (isManualStart) {
            Log.d(TAG, "onStartCommand: 手动启动，重置重试计数。");
            resetRetryCount();
        } else {
            Log.d(TAG, "onStartCommand: 非手动启动 (自动重启)，不重置重试计数。当前计数: " + sharedPreferences.getInt(KEY_RETRY_COUNT, -1)); // Log 查看当前计数值
        }
        // --- 结束修改 ---


        // 检查是否已有活动流...
        if (activeStreamCount.compareAndSet(0, 2)) {
            Log.i(TAG,"onStartCommand: activeStreamCount 为 0，开始连接和打开相机...");
            connectAndOpenCamerasAsync();
        } else {
            Log.w(TAG, "onStartCommand: 服务已在运行中 (activeStreamCount=" + activeStreamCount.get() + ")，忽略新的启动请求。");
            // 如果服务已在运行，并且是手动启动，可能需要考虑先停止再启动，或者直接忽略。
            // 当前逻辑是忽略，如果需要确保手动启动总是覆盖，需要添加停止逻辑。
            // 对于自动重启，如果服务已在运行（可能之前的停止流程未完成？），忽略是合理的。
        }

        Log.i(TAG, "<<< 服务 onStartCommand 结束 (返回 START_STICKY)");
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        Log.w(TAG, ">>> 服务 onDestroy 开始...");
        shutdownAndCleanup();

        boolean shouldRestart = sharedPreferences.getBoolean(KEY_RESTART_SERVICE, false);
        Log.d(TAG, "onDestroy: 检查重启逻辑，shouldRestart=" + shouldRestart);

        if (shouldRestart) {
            int currentRetryCount = sharedPreferences.getInt(KEY_RETRY_COUNT, 0);
            Log.d(TAG, "onDestroy: 当前重试次数 = " + currentRetryCount);

            if (currentRetryCount < MAX_RETRIES) {
                final int nextRetryAttempt = currentRetryCount + 1;
                sharedPreferences.edit().putInt(KEY_RETRY_COUNT, nextRetryAttempt).apply();
                Log.i(TAG, "onDestroy: 准备使用 AlarmManager 安排重试 (尝试 " + nextRetryAttempt + "/" + MAX_RETRIES + ")，延迟 " + RETRY_DELAY_MS + "ms...");
                Toast.makeText(this, "服务即将重启 (尝试 " + nextRetryAttempt + "/" + MAX_RETRIES + ")", Toast.LENGTH_LONG).show();
                // --- 使用 AlarmManager 安排重启 ---
                AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    // 创建一个发送 "RestartCameraService" 广播的 Intent
                    Intent restartIntent = new Intent(this, RestartServiceReceiver.class); // 直接指向 Receiver
                    restartIntent.setAction("RestartCameraService"); // 设置 Action

                    // 创建 PendingIntent
                    // FLAG_IMMUTABLE 是推荐的标志，表示内容不可变
                    // FLAG_UPDATE_CURRENT 如果已有相同 PendingIntent 则更新它
                    // 使用不同的 requestCode (如 nextRetryAttempt) 可以创建不同的 PendingIntent，如果需要的话
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                            this,
                            1, // requestCode - 可以使用固定值或根据需要变化
                            restartIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );

                    // 计算触发时间：当前时间 + 延迟
                    long triggerAtMillis = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;

                    try {
                        // 设置一次性闹钟
                        // setExactAndAllowWhileIdle 允许在低电耗模式下执行，但需要权限 (SCHEDULE_EXACT_ALARM)
                        // setAndAllowWhileIdle 允许在低电耗模式下执行，但不精确
                        // set() 不保证在低电耗模式下执行
                        // 为了简单起见，先使用 set() 或 setAndAllowWhileIdle (如果API >= 23)
                        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
                        Log.i(TAG, "onDestroy: 使用 setAndAllowWhileIdle 设置了闹钟");
                    } catch (SecurityException se) {
                        // 处理 Android 12+ 可能需要的 SCHEDULE_EXACT_ALARM 权限问题
                        Log.e(TAG, "onDestroy: 设置精确闹钟权限不足！", se);
                        showToast("无法安排自动重启：缺少权限");
                        // 可以尝试设置非精确闹钟作为后备
                        // alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
                    } catch (Exception e){
                        Log.e(TAG, "onDestroy: 设置闹钟时出错！", e);
                        showToast("安排自动重启时出错");
                    }

                } else {
                    Log.e(TAG, "onDestroy: 无法获取 AlarmManager！无法安排重启。");
                    showToast("无法安排自动重启");
                }
                // --- AlarmManager 设置结束 ---

                final String retryMsg = "连接中断，将在 " + (RETRY_DELAY_MS / 1000) + " 秒后尝试重新连接 (" + nextRetryAttempt + "/" + MAX_RETRIES + ")...";
                showToast(retryMsg); // Toast 仍然可能因为时机问题不显示，但 AlarmManager 是可靠的

            } else {
                Log.e(TAG, "onDestroy: 已达到最大重试次数 (" + MAX_RETRIES + ")，放弃自动重启。");
                resetRetryCount();
                Log.i(TAG, "onDestroy: 发送广播通知 MainActivity 重试失败: " + ACTION_SHOW_RETRY_FAILURE_DIALOG);
                sendBroadcast(new Intent(ACTION_SHOW_RETRY_FAILURE_DIALOG));
                showToast("自动重连失败，请检查网络或服务器后手动连接");
            }
        } else {
            Log.i(TAG, "onDestroy: 服务已停止，且未设置重启标志。");
            resetRetryCount();
        }

        super.onDestroy();
        // 不再需要清理 mainHandler 上的延迟任务，因为它已被 AlarmManager 替代
        Log.w(TAG, "<<< 服务 onDestroy 完成。");
    }

    // --- Core Logic Methods ---

    /** 重置重试计数器 */
    private void resetRetryCount() {
        if (sharedPreferences != null) {
            sharedPreferences.edit().putInt(KEY_RETRY_COUNT, 0).apply();
            Log.d(TAG,"重试计数已重置为 0");
        }
    }

    /** 保存 IP 地址到 SharedPreferences */
    private void saveIpAddress(String ip) {
        if (sharedPreferences != null && !TextUtils.isEmpty(ip)) {
            sharedPreferences.edit().putString(KEY_IP_ADDRESS, ip).apply();
        }
    }

    /**
     * 异步连接 Socket 并尝试打开传感器。
     */
    private void connectAndOpenCamerasAsync() {
        if (connectionExecutor == null || connectionExecutor.isShutdown()) {
            connectionExecutor = Executors.newFixedThreadPool(2);
        }
        if (TextUtils.isEmpty(ipAddress)) {
            Log.e(TAG, "connectAndOpenCamerasAsync: IP 地址为空，无法启动连接！");
            activeStreamCount.set(0);
            stopSelfSafely();
            return;
        }
        Log.i(TAG, "connectAndOpenCamerasAsync: 提交连接任务到线程池，目标 IP: " + ipAddress);
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_BACK, BACK_CAMERA_PORT));
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_FRONT, FRONT_CAMERA_PORT));
    }


    /**
     * 尝试连接 Socket，如果成功，则尝试在后台线程打开对应的传感器。
     */
    private void connectSocketAndTryOpen(int cameraFacing, int port) {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, ">>> connectSocketAndTryOpen (" + facingStr + ") 开始...");
        Socket socket = null;
        OutputStream outputStream;
        String currentIp = this.ipAddress;

        if (TextUtils.isEmpty(currentIp)) {
            Log.e(TAG, "connectSocketAndTryOpen: IP 地址为空 (" + facingStr + ")，取消。");
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< connectSocketAndTryOpen (" + facingStr + ") 结束 (IP 为空)");
            return;
        }

        try {
            Log.i(TAG, "connectSocketAndTryOpen: 尝试连接 " + facingStr + " 到 " + currentIp + ":" + port);
            socket = new Socket();
            socket.connect(new InetSocketAddress(currentIp, port), SOCKET_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(10000);
            socket.setKeepAlive(true);
            outputStream = socket.getOutputStream();

            sockets.put(cameraFacing, socket);
            outputStreams.put(cameraFacing, outputStream);
            Log.i(TAG, "connectSocketAndTryOpen: " + facingStr + " Socket 连接成功。");

            if (backgroundHandler != null) {
                final Socket finalSocket = socket;
                Log.d(TAG, "connectSocketAndTryOpen: 提交 tryOpenCameraAfterConnect 到后台线程 (" + facingStr + ")");
                backgroundHandler.post(() -> tryOpenCameraAfterConnect(cameraFacing, finalSocket));
            } else {
                Log.e(TAG, "connectSocketAndTryOpen: 后台 Handler 为空，无法打开传感器 (" + facingStr + ")！");
                closeSocket(cameraFacing); // 关闭刚建立的连接
                decrementActiveStreamCountAndCheckStop(); // 减少计数
            }

        } catch (IOException e) {
            Log.e(TAG, "connectSocketAndTryOpen: 连接 " + facingStr + " Socket 失败: " + e.getMessage());
            decrementActiveStreamCountAndCheckStop(); // 连接失败，减少计数
            if (!socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        } catch (Exception e) {
            Log.e(TAG, "connectSocketAndTryOpen: 连接或启动时意外错误 (" + facingStr + "): " + e.getMessage(), e);
            decrementActiveStreamCountAndCheckStop();
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        }
        Log.d(TAG, "<<< connectSocketAndTryOpen (" + facingStr + ") 结束");
    }

    /**
     * 在 Socket 连接成功后，尝试打开对应的传感器。此方法应在 backgroundHandler 上运行。
     */
    private void tryOpenCameraAfterConnect(int cameraFacing, Socket connectedSocket) {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, ">>> tryOpenCameraAfterConnect (" + facingStr + ") 开始...");

        Socket currentSocketInMap = sockets.get(cameraFacing);
        if (currentSocketInMap == null || currentSocketInMap != connectedSocket || !connectedSocket.isConnected() || connectedSocket.isClosed()) {
            Log.w(TAG, "tryOpenCameraAfterConnect: " + facingStr + " Socket 无效或已改变。取消。");
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (Socket无效)");
            return;
        }
        if (cameraManager == null) {
            Log.e(TAG, "tryOpenCameraAfterConnect: CameraManager 不可用 (" + facingStr + ")");
            showToast("传感器管理器错误");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (无CameraManager)");
            return;
        }
        String cameraId = getCameraIdForFacing(cameraFacing);
        if (cameraId == null) {
            Log.e(TAG, "tryOpenCameraAfterConnect: 未找到 Camera ID (" + facingStr + ")");
            showToast("未找到 " + facingStr + " 传感器");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (无CameraID)");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "tryOpenCameraAfterConnect: 没有传感器权限 (" + facingStr + ")");
            showToast("缺少传感器权限");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (无权限)");
            return;
        }

        try {
            Log.i(TAG, "tryOpenCameraAfterConnect: 正在打开传感器 (" + facingStr + ", ID: " + cameraId + ")");
            openCameraForFacing(cameraId, cameraFacing);
        } catch (CameraAccessException | IllegalStateException e) { // Catch specific exceptions from openCameraForFacing signature
            Log.e(TAG, "tryOpenCameraAfterConnect: 打开传感器时出错 (" + facingStr + "): " + e.getMessage());
            showToast("传感器访问/状态错误 (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } catch (Exception e) { // Catch other potential runtime exceptions during openCameraForFacing
            Log.e(TAG, "tryOpenCameraAfterConnect: 打开传感器时意外错误 (" + facingStr + "): " + e.getMessage(), e);
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        }
        Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束");
    }

    /**
     * 打开指定 ID 和朝向的传感器。此方法必须在 backgroundHandler 上运行。
     */
    private void openCameraForFacing(String cameraId, int cameraFacing) throws CameraAccessException, IllegalStateException, SecurityException { // Added SecurityException
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, ">>> openCameraForFacing (" + facingStr + ") 开始...");

        Socket associatedSocket = sockets.get(cameraFacing);
        if (associatedSocket == null || !associatedSocket.isConnected() || associatedSocket.isClosed()) {
            Log.w(TAG, "openCameraForFacing: " + facingStr + " Socket 无效。取消打开。");
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (Socket无效)");
            return; // Don't throw exception if socket is already gone
        }

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            Log.e(TAG, "openCameraForFacing: 无法获取流配置 (" + facingStr + ")");
            showToast("无法获取传感器配置 (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (无流配置)");
            return; // Return instead of throwing
        }

        Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (outputSizes == null || outputSizes.length == 0) {
            Log.e(TAG, "openCameraForFacing: 不支持 JPEG 输出 (" + facingStr + ")");
            showToast("传感器不支持JPEG (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (不支持JPEG)");
            return;
        }

        Size targetSize = new Size(640, 480);
        Size selectedSize = Collections.min(Arrays.asList(outputSizes),
                Comparator.comparingLong((Size s) -> Math.abs((long)s.getWidth() * s.getHeight() - (long)targetSize.getWidth() * targetSize.getHeight()))
                        .thenComparingInt((Size s) -> Math.abs(s.getWidth() - targetSize.getWidth()))
        );
        previewSizes.put(cameraFacing, selectedSize);
        Log.i(TAG, "openCameraForFacing: 选择预览尺寸 " + selectedSize + " (" + facingStr + ")");

        closeReader(cameraFacing); // Close existing reader if any

        ImageReader imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.JPEG, IMAGE_BUFFER_SIZE);
        imageReaders.put(cameraFacing, imageReader);

        final int currentFacing = cameraFacing;
        imageReader.setOnImageAvailableListener(reader -> {
            // Log inside listener to see if it's being called
            // Log.v(TAG, "onImageAvailable called for " + getFacingString(currentFacing));
            Size currentSize = previewSizes.get(currentFacing);
            if (currentSize != null) {
                processImageAvailable(reader, currentFacing, currentSize);
            } else {
                Log.e(TAG, "onImageAvailable: 无法获取预览尺寸 (" + getFacingString(currentFacing) + ")");
                try (Image img = reader.acquireLatestImage()) {
                    if (img != null) {
                        Size fallbackSize = new Size(img.getWidth(), img.getHeight());
                        processImageAvailable(reader, currentFacing, fallbackSize);
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "onImageAvailable: ImageReader 状态异常 (" + getFacingString(currentFacing) + "): " + e.getMessage());
                    if (sockets.containsKey(currentFacing)) { closeCameraStream(currentFacing); decrementActiveStreamCountAndCheckStop(); }
                }
            }
        }, backgroundHandler);

        // Re-check permission just before calling openCamera (though unlikely to change)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "openCameraForFacing: 打开传感器时权限丢失 (" + facingStr + ")！");
            showToast("传感器权限丢失");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (无权限)");
            return;
        }

        Log.d(TAG, "openCameraForFacing: 正在调用 cameraManager.openCamera (" + facingStr + ")");
        // This call is asynchronous, result in getCameraStateCallback
        // Throws CameraAccessException, IllegalStateException, SecurityException
        cameraManager.openCamera(cameraId, getCameraStateCallback(cameraFacing), backgroundHandler);
        Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (调用 openCamera)");
    }

    /**
     * 处理 ImageReader 的 onImageAvailable 事件。
     */
    private void processImageAvailable(ImageReader reader, int cameraFacing, Size previewSize) {
        // Log.v(TAG, ">>> processImageAvailable (" + getFacingString(cameraFacing) + ")"); // Frequent log, use v
        try (Image image = reader.acquireNextImage()) {
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                Socket currentSocket = sockets.get(cameraFacing);
                if (currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed()) {
                    processAndSendFrame(bytes, cameraFacing, previewSize.getWidth(), previewSize.getHeight());
                } else {
                    Log.w(TAG, "processImageAvailable: Socket 无效，跳过帧处理 (" + getFacingString(cameraFacing) + ")");
                    if (sockets.containsKey(cameraFacing)) {
                        closeCameraStream(cameraFacing);
                        decrementActiveStreamCountAndCheckStop();
                    }
                }
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "processImageAvailable: Reader 状态错误 (" + getFacingString(cameraFacing) + "): " + e.getMessage());
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        } catch (Exception e) {
            Log.e(TAG, "processImageAvailable: 意外错误 (" + getFacingString(cameraFacing) + ")", e);
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        }
        // Log.v(TAG, "<<< processImageAvailable (" + getFacingString(cameraFacing) + ")");
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
            Log.e(TAG, "getCameraIdForFacing: 出错: " + e.getMessage());
        }
        return null;
    }

    /**
     * 创建传感器状态回调。
     */
    private CameraDevice.StateCallback getCameraStateCallback(final int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.i(TAG, ">>> CameraStateCallback.onOpened (" + facingStr + ", ID: " + camera.getId() + ")");
                boolean exists = cameraDevices.containsKey(cameraFacing);
                if (!exists || cameraDevices.get(cameraFacing) != camera) {
                    if (exists) {
                        Log.w(TAG, "onOpened: " + facingStr + " 已存在不同设备，关闭旧的...");
                        closeCameraDevice(cameraFacing);
                    }
                    cameraDevices.put(cameraFacing, camera);
                } else {
                    Log.d(TAG, "onOpened: " + facingStr + " 设备已存在且匹配。");
                }

                if (backgroundHandler != null) {
                    Log.d(TAG,"onOpened: 提交 createCameraPreviewSession 到后台线程 (" + facingStr + ")");
                    backgroundHandler.post(() -> createCameraPreviewSession(cameraFacing));
                } else {
                    Log.e(TAG, "onOpened: 后台 Handler 为空，无法创建会话 (" + facingStr + ")！");
                    closeCameraStream(cameraFacing);
                    decrementActiveStreamCountAndCheckStop();
                }
                Log.i(TAG, "<<< CameraStateCallback.onOpened (" + facingStr + ")");
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.w(TAG, ">>> CameraStateCallback.onDisconnected (" + facingStr + ", ID: " + camera.getId() + ")");
                if (cameraDevices.get(cameraFacing) == camera) {
                    Log.w(TAG, "onDisconnected: 清理断开连接的传感器资源 (" + facingStr + ")");
                    closeCameraStream(cameraFacing);
                    decrementActiveStreamCountAndCheckStop(); // <--- 触发停止和重试的关键点
                    showToast(facingStr + " 传感器连接断开");
                } else {
                    Log.w(TAG, "onDisconnected: " + facingStr + " 设备不匹配或已移除？");
                    try { camera.close(); } catch (Exception e) { /* ignore */ }
                }
                Log.w(TAG, "<<< CameraStateCallback.onDisconnected (" + facingStr + ")");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, ">>> CameraStateCallback.onError (" + facingStr + ", ID: " + camera.getId() + ", 错误码: " + error + " - " + errorToString(error) + ")");
                if (cameraDevices.get(cameraFacing) == camera) {
                    Log.e(TAG, "onError: 清理出错的传感器资源 (" + facingStr + ")");
                    closeCameraStream(cameraFacing);
                    decrementActiveStreamCountAndCheckStop(); // <--- 触发停止和重试的关键点
                    showToast(facingStr + " 传感器错误: " + errorToString(error));
                } else {
                    Log.w(TAG, "onError: " + facingStr + " 设备不匹配或已移除？");
                    try { camera.close(); } catch (Exception e) { /* ignore */ }
                }
                Log.e(TAG, "<<< CameraStateCallback.onError (" + facingStr + ")");
            }
        };
    }

    /**
     * 创建预览会话。应在 backgroundHandler 上运行。
     */
    private void createCameraPreviewSession(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, ">>> createCameraPreviewSession (" + facingStr + ") 开始...");
        CameraDevice cameraDevice = cameraDevices.get(cameraFacing);
        ImageReader imageReader = imageReaders.get(cameraFacing);

        if (cameraDevice == null) {
            Log.e(TAG, "createCameraPreviewSession: CameraDevice 为空 (" + facingStr + ")");
            if (sockets.containsKey(cameraFacing)) { closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop(); }
            Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束 (无设备)");
            return;
        }
        if (imageReader == null) {
            Log.e(TAG, "createCameraPreviewSession: ImageReader 为空 (" + facingStr + ")");
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束 (无Reader)");
            return;
        }
        android.view.Surface surface = imageReader.getSurface();
        if(surface == null || !surface.isValid()){
            Log.e(TAG, "createCameraPreviewSession: Surface 无效 (" + facingStr + ")");
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束 (Surface无效)");
            return;
        }
        Socket associatedSocket = sockets.get(cameraFacing);
        if (associatedSocket == null || !associatedSocket.isConnected() || associatedSocket.isClosed()) {
            Log.e(TAG, "createCameraPreviewSession: Socket 已断开 (" + facingStr + ")");
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束 (Socket断开)");
            return;
        }

        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            Log.d(TAG, "createCameraPreviewSession: 正在创建 CaptureSession (" + facingStr + ")");
            List<Surface> outputs = Collections.singletonList(surface);

            cameraDevice.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.i(TAG, ">>> CaptureSession.onConfigured (" + facingStr + ")");
                            CameraDevice currentDevice = cameraDevices.get(cameraFacing);
                            Socket currentSocket = sockets.get(cameraFacing);
                            if (currentDevice == null || currentDevice != cameraDevice) {
                                Log.w(TAG, "onConfigured: 设备已关闭或改变 (" + facingStr + ")");
                                try { session.close(); } catch (Exception e) { /* ignore */ }
                                Log.i(TAG, "<<< CaptureSession.onConfigured (" + facingStr + ") 结束 (设备无效)");
                                return;
                            }
                            if (currentSocket == null || !currentSocket.isConnected() || currentSocket.isClosed()) {
                                Log.w(TAG, "onConfigured: Socket 已断开 (" + facingStr + ")");
                                try { session.close(); } catch (Exception e) { /* ignore */ }
                                if (sockets.containsKey(cameraFacing)){ closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop(); }
                                Log.i(TAG, "<<< CaptureSession.onConfigured (" + facingStr + ") 结束 (Socket无效)");
                                return;
                            }

                            Log.i(TAG, "onConfigured: 会话配置成功 (" + facingStr + ")");
                            cameraCaptureSessions.put(cameraFacing, session);
                            try {
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                                CaptureRequest previewRequest = captureRequestBuilder.build();
                                Log.d(TAG, "onConfigured: 设置重复请求 (" + facingStr + ")");
                                session.setRepeatingRequest(previewRequest, null, backgroundHandler);
                                Log.i(TAG, "onConfigured: " + facingStr + " 传感器预览已启动。");
                            } catch (CameraAccessException | IllegalStateException e) {
                                Log.e(TAG, "onConfigured: 启动重复请求时出错 (" + facingStr + "): ", e);
                                closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
                            } catch (Exception e) {
                                Log.e(TAG, "onConfigured: 启动重复请求时未知错误 (" + facingStr + "): ", e);
                                closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
                            }
                            Log.i(TAG, "<<< CaptureSession.onConfigured (" + facingStr + ")");
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, ">>> CaptureSession.onConfigureFailed (" + facingStr + ")");
                            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
                            Log.e(TAG, "<<< CaptureSession.onConfigureFailed (" + facingStr + ")");
                        }
                    }, backgroundHandler
            );
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "createCameraPreviewSession: 准备请求时出错 (" + facingStr + "): ", e);
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
        }
        Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束");
    }

    /**
     * 处理原始 JPEG 数据，旋转并发送。
     */
    private void processAndSendFrame(byte[] jpegBytes, int cameraFacing, int width, int height) {
        Socket currentSocket = sockets.get(cameraFacing);
        if (currentSocket == null || !currentSocket.isConnected() || currentSocket.isClosed()) {
            Log.w(TAG, "processAndSendFrame: Socket 无效，跳过发送 (" + getFacingString(cameraFacing) + ")");
            if (sockets.containsKey(cameraFacing)) { closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop(); }
            return;
        }

        Bitmap bitmap = null;
        Bitmap rotatedBitmap = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
            if (bitmap == null) { Log.w(TAG, "processAndSendFrame: 解码失败 (" + getFacingString(cameraFacing) + ")"); return; }

            Matrix matrix = new Matrix();
            int rotation;
            boolean flip = false;
            Integer sensorOrientation = 0;
            CameraDevice device = cameraDevices.get(cameraFacing);
            if (device != null && cameraManager != null) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(device.getId());
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (sensorOrientation == null) sensorOrientation = 0;
                } catch (CameraAccessException e) { Log.e(TAG, "processAndSendFrame: 获取传感器方向失败 (" + getFacingString(cameraFacing) + "): " + e.getMessage()); }
            }
            if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                rotation = (sensorOrientation ) % 360; // Simpler rotation logic
                flip = true;
            } else {
                rotation = (sensorOrientation ) % 360; // Simpler rotation logic
            }
            matrix.postRotate(rotation);
            if (flip) { matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f); }

            rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            byteArrayOutputStream = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream);
            byte[] rotatedBytes = byteArrayOutputStream.toByteArray();

            // 发送数据，内部有异常处理
            sendFrameData(rotatedBytes, cameraFacing);

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "processAndSendFrame: 内存不足 (" + getFacingString(cameraFacing) + ")", oom);
            System.gc();
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
        } catch (Exception e) {
            Log.e(TAG, "processAndSendFrame: 处理或发送时出错 (" + getFacingString(cameraFacing) + ")", e);
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (rotatedBitmap != null && !rotatedBitmap.isRecycled()) rotatedBitmap.recycle();
            if (byteArrayOutputStream != null) { try { byteArrayOutputStream.close(); } catch (IOException e) { /* ignore */ } }
        }
        // Log.v(TAG, "<<< processAndSendFrame (" + getFacingString(cameraFacing) + ") 结束");
    }


    /**
     * 发送带长度前缀的帧数据。
     * 这是检测连接断开的关键点。
     */
    private void sendFrameData(byte[] frameData, int cameraFacing) {
        OutputStream outputStream = outputStreams.get(cameraFacing);
        Socket socket = sockets.get(cameraFacing);
        String facingStr = getFacingString(cameraFacing);

        // 增加发送前的日志
        // Log.v(TAG, ">>> sendFrameData: 尝试发送帧 facing=" + facingStr + ", length=" + frameData.length);

        if (outputStream != null && socket != null && socket.isConnected() && !socket.isClosed()) {
            try {
                int length = frameData.length;
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length);

                // Log.v(TAG, "sendFrameData: Writing length ("+ facingStr +")");
                outputStream.write(lengthBuffer.array());
                // Log.v(TAG, "sendFrameData: Writing data ("+ facingStr +")");
                outputStream.write(frameData);
                // Log.v(TAG, "sendFrameData: Flushing ("+ facingStr +")");
                outputStream.flush(); // <--- 强制发送，更容易触发 IOException

                // Log.v(TAG, "<<< sendFrameData: 成功发送帧 facing=" + facingStr + ", length=" + length);

            } catch (IOException e) {
                // --- 捕获到 IO 异常，这通常意味着连接已断开 ---
                Log.e(TAG, ">>> sendFrameData: 发送帧时 IO 错误 (" + facingStr + "): " + e.getMessage() + ". 可能已断开连接。");
                // 关闭此流并减少计数，触发停止和重试逻辑
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop(); // <--- 关键：触发后续流程
                Log.e(TAG, "<<< sendFrameData: IO 错误处理完毕 (" + facingStr + ")");
            } catch (Exception e) {
                Log.e(TAG, ">>> sendFrameData: 发送帧时未知错误 (" + facingStr + "): " + e.getMessage(), e);
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
                Log.e(TAG, "<<< sendFrameData: 未知错误处理完毕 (" + facingStr + ")");
            }
        } else {
            Log.w(TAG, "sendFrameData: 无法发送，流/Socket 无效 (" + facingStr + ")");
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
            // Log.v(TAG, "<<< sendFrameData: 结束 (流/Socket 无效)");
        }
    }


    // --- Helper and Cleanup Methods ---

    /**
     * 减少活动流计数，并在计数为零时安全地停止服务。
     */
    private void decrementActiveStreamCountAndCheckStop() {
        int remaining = activeStreamCount.decrementAndGet();
        // 增加日志，显示计数值变化
        Log.d(TAG, ">>> decrementActiveStreamCountAndCheckStop: 活动流计数减少，剩余: " + remaining);
        if (remaining <= 0) {
            // 增加日志，显示即将尝试停止服务
            Log.i(TAG, "decrementActiveStreamCountAndCheckStop: 计数 <= 0，尝试设置最终计数为 0 并停止服务...");
            if (activeStreamCount.compareAndSet(remaining, 0)) {
                Log.i(TAG, "decrementActiveStreamCountAndCheckStop: 成功设置计数为 0，调用 stopSelfSafely()...");
                stopSelfSafely(); // 触发 onDestroy -> 重试逻辑
            } else {
                Log.d(TAG, "decrementActiveStreamCountAndCheckStop: compareAndSet 失败，可能已被停止。当前计数: " + activeStreamCount.get());
            }
        }
        Log.d(TAG, "<<< decrementActiveStreamCountAndCheckStop: 结束");
    }


    /**
     * 安全地停止服务，确保在主线程调用 stopSelf()。
     */
    private void stopSelfSafely() {
        Log.i(TAG, ">>> stopSelfSafely() 被调用");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, "stopSelfSafely: 在主线程，直接调用 stopSelf()");
            try {
                stopSelf(); // 触发 onDestroy
            } catch (Exception e) { Log.e(TAG,"stopSelfSafely: 调用 stopSelf() 异常", e); }
        } else {
            Log.d(TAG, "stopSelfSafely: 不在主线程，post stopSelf() 到主线程");
            if(mainHandler != null) {
                mainHandler.post(() -> {
                    Log.d(TAG, "stopSelfSafely: 主线程 Handler 执行 stopSelf()");
                    try { stopSelf(); } catch (Exception e) { Log.e(TAG,"stopSelfSafely: 主线程 Handler 调用 stopSelf() 异常", e); }
                });
            } else { Log.e(TAG,"stopSelfSafely: 无法 post stopSelf()，mainHandler 为 null！"); }
        }
        Log.i(TAG, "<<< stopSelfSafely() 调用结束 (onDestroy 将异步执行)");
    }

    /**
     * 关闭指定朝向的传感器流相关的所有资源。
     */
    private void closeCameraStream(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        Log.w(TAG, ">>> closeCameraStream (" + facingStr + ") 开始关闭资源..."); // Use warning level for closing events
        closeSession(cameraFacing);
        closeCameraDevice(cameraFacing);
        closeReader(cameraFacing);
        closeSocket(cameraFacing);
        previewSizes.remove(cameraFacing);
        Log.w(TAG, "<<< closeCameraStream (" + facingStr + ") 资源关闭完成。");
    }

    /** 关闭指定朝向的预览会话 */
    private void closeSession(int cameraFacing) {
        CameraCaptureSession session = cameraCaptureSessions.remove(cameraFacing);
        if (session != null) {
            Log.d(TAG, "closeSession: Closing session (" + getFacingString(cameraFacing) + ")");
            try { session.close(); } catch (Exception e) { Log.e(TAG, "closeSession: Error closing session ("+getFacingString(cameraFacing)+")", e); }
        }
    }

    /** 关闭指定朝向的传感器设备 */
    private void closeCameraDevice(int cameraFacing) {
        CameraDevice device = cameraDevices.remove(cameraFacing);
        if (device != null) {
            Log.d(TAG, "closeCameraDevice: Closing device (" + getFacingString(cameraFacing) + ", ID: " + device.getId() + ")");
            try { device.close(); } catch (Exception e) { Log.e(TAG, "closeCameraDevice: Error closing device ("+getFacingString(cameraFacing)+")", e); }
        }
    }


    /** 关闭指定朝向的 ImageReader */
    private void closeReader(int cameraFacing) {
        ImageReader reader = imageReaders.remove(cameraFacing);
        if (reader != null) {
            Log.d(TAG, "closeReader: Closing reader (" + getFacingString(cameraFacing) + ")");
            try { reader.close(); } catch (Exception e) { Log.e(TAG, "closeReader: Error closing reader ("+getFacingString(cameraFacing)+")", e); }
        }
    }

    /** 关闭指定朝向的 Socket 和输出流 */
    private void closeSocket(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        OutputStream os = outputStreams.remove(cameraFacing);
        if (os != null) {
            Log.d(TAG, "closeSocket: Closing output stream (" + facingStr + ")");
            try { os.close(); } catch (IOException e) { /* ignore */ }
        }
        Socket socket = sockets.remove(cameraFacing);
        if (socket != null) {
            Log.d(TAG, "closeSocket: Closing socket (" + facingStr + ", Remote: " + (socket.isClosed() ? "closed" : socket.getRemoteSocketAddress()) + ")");
            if (!socket.isClosed()) {
                try { socket.close(); } catch (IOException e) { Log.e(TAG, "closeSocket: Error closing socket ("+facingStr+")", e); }
            }
        }
    }

    /** 停止所有传感器流，清理所有相关资源 */
    private void stopAllCameraStreams() {
        Log.i(TAG, ">>> stopAllCameraStreams: 开始停止所有流...");
        // Iterate over a copy of keys or use ConcurrentHashMap's safe iterator
        Object[] keys = sockets.keySet().toArray(); // Simple way to get a snapshot
        for (Object key : keys) {
            if (key instanceof Integer) {
                closeCameraStream((Integer) key);
            }
        }
        // Ensure maps are cleared
        cameraCaptureSessions.clear();
        cameraDevices.clear();
        imageReaders.clear();
        outputStreams.clear();
        sockets.clear();
        previewSizes.clear();
        Log.i(TAG, "<<< stopAllCameraStreams: 所有流停止完成。");
    }


    /**
     * 关闭服务前的最终清理方法。
     */
    private void shutdownAndCleanup() {
        Log.i(TAG, ">>> shutdownAndCleanup: 开始服务关闭和清理...");

        // 0. 停止延迟任务
        if (mainHandler != null) {
            Log.d(TAG,"shutdownAndCleanup: 移除 mainHandler 任务");
            mainHandler.removeCallbacksAndMessages(null);
        }

        // 1. 关闭网络连接线程池
        if (connectionExecutor != null) {
            Log.d(TAG,"shutdownAndCleanup: 关闭网络连接线程池...");
            connectionExecutor.shutdownNow();
            try {
                if (!connectionExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "shutdownAndCleanup: 网络连接线程池关闭超时。");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            connectionExecutor = null;
            Log.d(TAG,"shutdownAndCleanup: 网络连接线程池已关闭。");
        }

        // 2. 清理传感器资源 (同步等待后台线程完成)
        Handler handler = backgroundHandler;
        HandlerThread thread = backgroundThread;
        if (handler != null && thread != null && thread.isAlive()) {
            Log.d(TAG,"shutdownAndCleanup: 提交 stopAllCameraStreams 到后台线程并等待...");
            final CountDownLatch cleanupLatch = new CountDownLatch(1);
            handler.post(() -> {
                try { stopAllCameraStreams(); } finally { cleanupLatch.countDown(); }
            });
            try {
                if (!cleanupLatch.await(1500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "shutdownAndCleanup: 等待后台清理任务超时！");
                } else {
                    Log.d(TAG, "shutdownAndCleanup: 后台清理任务完成。");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } else {
            Log.w(TAG, "shutdownAndCleanup: 后台线程不可用，直接清理...");
            stopAllCameraStreams();
        }

        // 3. 停止后台线程
        Log.d(TAG,"shutdownAndCleanup: 停止后台线程...");
        stopBackgroundThread();
        Log.d(TAG,"shutdownAndCleanup: 后台线程已停止。");

        // 4. 停止前台服务状态
        Log.d(TAG,"shutdownAndCleanup: 停止前台服务状态...");
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE); } catch (Exception e) { Log.e(TAG,"shutdownAndCleanup: 停止前台服务出错: " + e.getMessage()); }
        Log.d(TAG,"shutdownAndCleanup: 前台服务状态已停止。");

        // 5. 强制重置计数器
        activeStreamCount.set(0);
        Log.i(TAG, "<<< shutdownAndCleanup: 清理完成。");
    }


    // 启动后台线程处理传感器操作
    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            Log.d(TAG, "startBackgroundThread: 启动新后台线程...");
            backgroundThread = new HandlerThread("CameraBackground", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            Looper looper = backgroundThread.getLooper();
            if (looper != null) {
                backgroundHandler = new Handler(looper);
                Log.d(TAG, "startBackgroundThread: 后台线程 Handler 已创建。");
            } else {
                Log.e(TAG,"startBackgroundThread: 无法获取后台 Looper！");
                if (backgroundThread != null) { backgroundThread.quitSafely(); try { backgroundThread.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                backgroundThread = null; backgroundHandler = null;
                showToast("后台处理线程启动失败");
                stopSelfSafely();
            }
        } else {
            Log.d(TAG,"startBackgroundThread: 后台线程已在运行。");
        }
    }

    // 停止后台线程
    private void stopBackgroundThread() {
        HandlerThread threadToStop = backgroundThread;
        if (threadToStop != null) {
            Log.d(TAG, ">>> stopBackgroundThread: 请求停止后台线程 (ID: " + threadToStop.getThreadId() + ")");
            backgroundHandler = null;
            threadToStop.quitSafely();
            try {
                threadToStop.join(1000);
                if (threadToStop.isAlive()) {
                    Log.w(TAG,"stopBackgroundThread: 后台线程停止超时，尝试中断...");
                    threadToStop.interrupt();
                    threadToStop.join(500);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (backgroundThread == threadToStop) { backgroundThread = null; }
            Log.d(TAG, "<<< stopBackgroundThread: 停止完成 (线程状态: " + (threadToStop.isAlive() ? "还在运行!" : "已停止") + ")");
        } else {
            Log.d(TAG, "stopBackgroundThread: 后台线程已为 null。");
        }
    }


    // 在主线程显示 Toast
    private void showToast(final String message) {
        if (mainHandler != null) {
            mainHandler.post(() -> {
                try {
                    if (getApplicationContext() != null) {
                        // Log.d(TAG, "Showing Toast: " + message); // Add log before showing toast
                        Toast.makeText(CameraStreamService.this, message, Toast.LENGTH_SHORT).show();
                    } else {
                        Log.w(TAG, "Cannot show Toast, application context is null.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "显示 Toast 时出错: " + e.getMessage());
                }
            });
        } else {
            Log.e(TAG,"无法显示 Toast: mainHandler is null for message: " + message);
        }
    }

    // 获取传感器朝向的字符串表示
    private String getFacingString(int cameraFacing) {
        return (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) ? "后置" : "前置";
    }

    // 将 CameraDevice.StateCallback 的错误代码转换为字符串
    private String errorToString(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "传感器已被占用";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "达到最大传感器使用数量";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "传感器设备已被禁用";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "传感器设备致命错误";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "传感器服务致命错误";
            default: return "未知错误 (" + error + ")";
        }
    }

    // --- Notification Methods ---
    private void startForegroundServiceNotification() {
        // ... (代码保持不变) ...
        createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("传感器流服务")
                .setContentText("正在传输传感器画面...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        Notification notification = builder.build();
        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "前台服务通知已启动。");
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务时出错: " + e.getMessage(), e);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_CAMERA) != PackageManager.PERMISSION_GRANTED) showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE_CAMERA 权限 (Android 14+)");
                else showToast("启动前台服务失败: " + e.getMessage());
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE 权限");
                else showToast("启动前台服务失败: " + e.getMessage());
            }
            stopSelfSafely();
        }
    }

    private void createNotificationChannel() {
        CharSequence name = "传感器流服务通道";
        String description = "用于传感器流服务的后台运行通知";
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel serviceChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        serviceChannel.setDescription(description);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            try { manager.createNotificationChannel(serviceChannel); } catch (Exception e) { Log.e(TAG, "创建通知通道失败: " + e.getMessage()); }
        } else Log.e(TAG, "无法获取 NotificationManager。");
    }
}
// --- END OF FILE CameraStreamService.java (增强日志和检查) ---