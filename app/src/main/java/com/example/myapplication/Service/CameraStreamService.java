package com.example.myapplication.Service;

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
import android.content.pm.ServiceInfo;
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
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;

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
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 5000; // Socket 连接超时时间
    private static final int IMAGE_BUFFER_SIZE = 2; // ImageReader 缓冲区大小
    private static final int JPEG_QUALITY = 70; // JPEG 压缩质量
    private static final String PREFS_NAME = "CameraServicePrefs"; // SharedPreferences 文件名
    private static final String KEY_IP_ADDRESS = "last_ip_address"; // 保存 IP 地址的 Key
    private static final String KEY_RETRY_COUNT = "retry_count"; // 保存重试次数的 Key (仅用于日志记录)
    // private static final int MAX_RETRIES = 3; // 移除最大重试次数限制
    private static final long RETRY_DELAY_MS = 5000; // 重试延迟 5 秒
    public static final String ACTION_SHOW_RETRY_FAILURE_DIALOG = "com.example.myapplication.ACTION_SHOW_RETRY_FAILURE_DIALOG"; // (此广播不再主动发送，因为会无限重试)

    // --- 成员变量 ---
    private String ipAddress; // 目标服务器 IP
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>(); // 存储 Socket 连接 (后置/前置)
    private final Map<Integer, OutputStream> outputStreams = new ConcurrentHashMap<>(); // 存储输出流
    private final Map<Integer, CameraDevice> cameraDevices = new ConcurrentHashMap<>(); // 存储打开的 CameraDevice
    private final Map<Integer, CameraCaptureSession> cameraCaptureSessions = new ConcurrentHashMap<>(); // 存储相机捕捉会话
    private final Map<Integer, ImageReader> imageReaders = new ConcurrentHashMap<>(); // 存储 ImageReader
    private final AtomicInteger activeStreamCount = new AtomicInteger(0); // 活动流计数 (用于判断何时停止服务)
    private Handler backgroundHandler; // 后台线程 Handler
    private HandlerThread backgroundThread; // 后台线程
    private Handler mainHandler; // 主线程 Handler (用于 Toast)
    private CameraManager cameraManager; // 相机管理器
    private final Map<Integer, Size> previewSizes = new ConcurrentHashMap<>(); // 存储预览尺寸
    private final IBinder binder = new LocalBinder(); // Binder (如果需要 Activity 绑定)
    private ExecutorService connectionExecutor; // 用于 Socket 连接的线程池
    private SharedPreferences sharedPreferences; // 用于存储 IP 和重试次数
    private volatile boolean isServiceRunning = false; // 标记服务是否应该运行 (用于 onDestroy 判断是否是意外停止)

    // Binder 类 (如果需要 Activity 与 Service 交互)
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
        startForegroundServiceNotification(); // 启动前台服务通知
        startBackgroundThread(); // 启动后台线程
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "无法获取 CameraManager！服务可能无法工作。");
            showToast("无法访问相机管理器");
            // 无法获取 CameraManager 是致命错误，提前停止
            stopSelfSafely();
            return;
        }
        connectionExecutor = Executors.newFixedThreadPool(2); // 创建连接线程池
        isServiceRunning = true; // 标记服务开始运行
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, ">>> 服务 onStartCommand 开始, flags=" + flags + ", startId=" + startId);

        // 检查是否是手动启动
        boolean isManualStart = false;
        if (intent != null && intent.getBooleanExtra("MANUAL_START", false)) {
            isManualStart = true;
            Log.d(TAG, "onStartCommand: 检测到手动启动标志 (MANUAL_START=true)");
        }

        // 确定 IP 地址来源
        String receivedIp = null;
        boolean isRestartAttempt = intent != null && ("RestartCameraService".equals(intent.getAction()) || intent.hasExtra("source")); // 重启广播或定时检查器触发

        if (intent != null && intent.hasExtra("IP_ADDRESS")) {
            receivedIp = intent.getStringExtra("IP_ADDRESS");
            Log.d(TAG, "onStartCommand: 从 Intent 接收到 IP: " + receivedIp);
        } else {
            Log.d(TAG, "onStartCommand: Intent 为 null 或无 IP (可能是系统重启或服务被杀死后的自启)");
        }

        // 优先使用 Intent 传递的 IP
        if (!TextUtils.isEmpty(receivedIp) && Patterns.IP_ADDRESS.matcher(receivedIp).matches()) {
            this.ipAddress = receivedIp;
            saveIpAddress(this.ipAddress); // 保存有效的 IP 地址
            Log.i(TAG, "onStartCommand: 使用来自 Intent 的有效 IP 地址: " + this.ipAddress);
        } else {
            // 如果 Intent 中没有有效 IP，尝试从 SharedPreferences 加载 (通常用于重启情况)
            this.ipAddress = sharedPreferences.getString(KEY_IP_ADDRESS, null);
            if (!TextUtils.isEmpty(this.ipAddress) && Patterns.IP_ADDRESS.matcher(this.ipAddress).matches()) {
                Log.i(TAG, "onStartCommand: 从 SharedPreferences 加载有效 IP: " + this.ipAddress);
            } else {
                Log.e(TAG, "onStartCommand: 无法获取有效 IP 地址 (Intent 和 SharedPreferences 均无效)。停止服务。");
                showToast("服务启动失败：缺少有效的 IP 地址");
                isServiceRunning = false; // 标记服务不应运行
                stopSelfSafely(); // 调用停止
                Log.i(TAG, "<<< 服务 onStartCommand 结束 (因缺少 IP 而停止)");
                return START_NOT_STICKY; // 没有有效 IP，不粘性启动
            }
        }

        // --- 重置重试计数 (仅用于日志) ---
        // 只有在手动启动时才重置计数，表示用户主动发起连接
        if (isManualStart) {
            Log.d(TAG, "onStartCommand: 手动启动，重置重试日志计数。");
            resetRetryCount();
        } else {
            Log.d(TAG, "onStartCommand: 非手动启动 (自动重启/重试)，不重置重试日志计数。当前日志计数: " + sharedPreferences.getInt(KEY_RETRY_COUNT, 0));
        }

        // --- 启动连接和相机 ---
        // 使用 compareAndSet 确保只在服务未运行时初始化连接
        // activeStreamCount 初始化为 2 (前后摄像头)，如果成功连接并打开，后续失败会递减
        if (activeStreamCount.compareAndSet(0, 2)) {
            Log.i(TAG,"onStartCommand: activeStreamCount 为 0，标记为 2 并开始连接和打开相机...");
            connectAndOpenCamerasAsync(); // 异步执行连接和打开相机
            // 不再需要 AlarmScheduler.scheduleServiceCheck(this);
        } else {
            Log.w(TAG, "onStartCommand: 服务已在运行中 (activeStreamCount=" + activeStreamCount.get() + ")，忽略新的启动请求或检查 IP 是否变化。");
            // 如果服务已在运行，检查新传入的 IP 是否与当前使用的不同
            if (isManualStart && !this.ipAddress.equals(receivedIp)) {
                Log.w(TAG, "onStartCommand: 手动启动传入新 IP (" + receivedIp + ")，但服务已在运行旧 IP ("+ this.ipAddress +")。需要先停止再启动。");
                // TODO: 可以实现先停止再重启的逻辑，或者提示用户
                showToast("服务已在运行，请先停止再使用新 IP 连接");
            } else if (isManualStart) {
                showToast("服务已在运行: " + this.ipAddress);
            }
        }

        isServiceRunning = true; // 再次确认服务应该处于运行状态
        Log.i(TAG, "<<< 服务 onStartCommand 结束 (返回 START_STICKY)");
        // 使用 START_STICKY，如果服务被系统杀死（例如内存不足），系统会尝试重新创建服务并调用 onStartCommand (Intent 可能为 null)
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        Log.w(TAG, ">>> 服务 onDestroy 开始...");

        boolean wasExpectedToRun = isServiceRunning; // 记录服务是否是意外停止
        isServiceRunning = false; // 标记服务已停止

        shutdownAndCleanup(); // 清理所有资源（相机、Socket、线程等）

        // --- 无限重试逻辑 ---
        // 检查是否是意外停止 (例如连接断开导致，而不是用户手动停止或配置错误)
        // isServiceRunning 在 shutdownAndCleanup 之前被设为 false，
        // 但如果 onDestroy 是因为 stopSelfSafely 被调用（通常由连接错误触发），
        // 那么 wasExpectedToRun 应该为 true。
        if (wasExpectedToRun) {
            int currentRetryCount = sharedPreferences.getInt(KEY_RETRY_COUNT, 0); // 获取当前日志计数
            final int nextRetryAttempt = currentRetryCount + 1; // 计算下一次尝试的日志编号
            sharedPreferences.edit().putInt(KEY_RETRY_COUNT, nextRetryAttempt).apply(); // 更新日志计数

            Log.w(TAG, "onDestroy: 检测到服务意外停止，准备使用 AlarmManager 安排无限重试 (日志尝试 " + nextRetryAttempt + ")，延迟 " + RETRY_DELAY_MS + "ms...");
            final String retryMsg = "连接中断，将在 " + (RETRY_DELAY_MS / 1000) + " 秒后重试 (第 " + nextRetryAttempt + " 次)...";
            showToast(retryMsg); // 提示用户即将重试

            // --- 使用 AlarmManager 安排重启 ---
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                // 创建一个指向 RestartServiceReceiver 的 Intent
                Intent restartIntent = new Intent(this, RestartServiceReceiver.class);
                restartIntent.setAction("RestartCameraService"); // 设置 Action，以便 Receiver 区分

                // 创建 PendingIntent
                // FLAG_IMMUTABLE 是 Android 12+ 推荐/要求的标志
                // FLAG_UPDATE_CURRENT 如果已有相同 PendingIntent 则更新它
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        this,
                        1, // requestCode - 固定值即可，因为我们总是更新同一个重试任务
                        restartIntent,
                        flags
                );

                // 计算触发时间：当前时间 + 延迟 (使用 elapsedRealtime 更适合计算时间间隔)
                long triggerAtMillis = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;

                try {
                    // 使用 setAndAllowWhileIdle 尝试在 Doze 模式下也能执行
                    // 在 Android 12+ 可能需要 SCHEDULE_EXACT_ALARM 权限，即使这里用的是 setAndAllowWhileIdle
                    // 最好在 Manifest 中声明该权限，并处理用户拒绝的情况
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                        Log.w(TAG, "onDestroy: 缺少 SCHEDULE_EXACT_ALARM 权限，尝试使用 setExact 代替 setAndAllowWhileIdle");
                        // 如果没有精确闹钟权限，退回到 setExact (仍然需要唤醒)
                        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
                        Log.i(TAG, "onDestroy: 使用 setExact 设置了闹钟 (无精确权限后备)");
                        showToast("后台重启权限受限，可能延迟");
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
                        Log.i(TAG, "onDestroy: 使用 setAndAllowWhileIdle 设置了闹钟以安排重试");
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "onDestroy: 设置闹钟时发生 SecurityException (权限不足?)", se);
                    showToast("无法安排自动重启：缺少权限");
                    // 可以在这里尝试记录更详细的错误或通知用户需要手动开启权限
                } catch (Exception e){
                    Log.e(TAG, "onDestroy: 设置闹钟时发生未知错误", e);
                    showToast("安排自动重启时出错");
                }
            } else {
                Log.e(TAG, "onDestroy: 无法获取 AlarmManager！无法安排重试。");
                showToast("无法安排自动重启");
            }
        } else {
            Log.i(TAG, "onDestroy: 服务正常停止 (isServiceRunning=false)，不安排重试。");
            // 如果是正常停止，重置重试计数器
            resetRetryCount();
        }

        super.onDestroy();
        Log.w(TAG, "<<< 服务 onDestroy 完成。");
    }

    // --- 核心逻辑代码 (基本保持不变，但增加了日志和健壮性检查) ---

    /** 重置重试计数器 (用于日志) */
    private void resetRetryCount() {
        if (sharedPreferences != null) {
            sharedPreferences.edit().putInt(KEY_RETRY_COUNT, 0).apply();
            Log.d(TAG,"重试日志计数已重置为 0");
        }
    }

    /** 保存 IP 地址到 SharedPreferences */
    private void saveIpAddress(String ip) {
        if (sharedPreferences != null && !TextUtils.isEmpty(ip) && Patterns.IP_ADDRESS.matcher(ip).matches()) {
            sharedPreferences.edit().putString(KEY_IP_ADDRESS, ip).apply();
            Log.d(TAG, "已保存 IP 地址到 SharedPreferences: " + ip);
        } else {
            Log.w(TAG, "尝试保存无效的 IP 地址: " + ip);
        }
    }

    /**
     * 异步连接 Socket 并尝试打开相机。
     */
    private void connectAndOpenCamerasAsync() {
        // 检查 IP 地址是否有效
        if (TextUtils.isEmpty(ipAddress) || !Patterns.IP_ADDRESS.matcher(ipAddress).matches()) {
            Log.e(TAG, "connectAndOpenCamerasAsync: IP 地址无效 (" + ipAddress + ")，无法启动连接！");
            showToast("无法连接：IP 地址无效");
            activeStreamCount.set(0); // 重置计数
            isServiceRunning = false; // 标记服务不应运行
            stopSelfSafely(); // 停止服务
            return;
        }

        // 检查或重新创建线程池
        if (connectionExecutor == null || connectionExecutor.isShutdown()) {
            connectionExecutor = Executors.newFixedThreadPool(2);
        }

        Log.i(TAG, "connectAndOpenCamerasAsync: 提交连接任务到线程池，目标 IP: " + ipAddress);
        // 分别提交后置和前置相机的连接任务
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_BACK, BACK_CAMERA_PORT));
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_FRONT, FRONT_CAMERA_PORT));
    }


    /**
     * 尝试连接 Socket，如果成功，则尝试在后台线程打开对应的相机。
     */
    private void connectSocketAndTryOpen(int cameraFacing, int port) {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, ">>> connectSocketAndTryOpen (" + facingStr + ") 开始...");
        Socket socket = null;
        OutputStream outputStream = null; // 初始化为 null
        String currentIp = this.ipAddress; // 获取当前 IP

        // 再次检查 IP
        if (TextUtils.isEmpty(currentIp) || !Patterns.IP_ADDRESS.matcher(currentIp).matches()) {
            Log.e(TAG, "connectSocketAndTryOpen: IP 地址无效 (" + facingStr + ")，取消连接。");
            decrementActiveStreamCountAndCheckStop(); // 减少计数并检查是否停止服务
            Log.d(TAG, "<<< connectSocketAndTryOpen (" + facingStr + ") 结束 (IP 无效)");
            return;
        }

        try {
            Log.i(TAG, "connectSocketAndTryOpen: 尝试连接 " + facingStr + " 到 " + currentIp + ":" + port);
            socket = new Socket(); // 创建新 Socket
            // 连接，设置超时
            socket.connect(new InetSocketAddress(currentIp, port), SOCKET_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(10000); // 设置读取超时 (可选)
            socket.setKeepAlive(true); // 启用 TCP KeepAlive
            outputStream = socket.getOutputStream(); // 获取输出流

            // 连接成功，存储 Socket 和输出流
            sockets.put(cameraFacing, socket);
            outputStreams.put(cameraFacing, outputStream);
            Log.i(TAG, "connectSocketAndTryOpen: " + facingStr + " Socket 连接成功。");

            // 提交打开相机任务到后台线程
            if (backgroundHandler != null) {
                final Socket finalSocket = socket; // 传递当前 Socket 引用
                Log.d(TAG, "connectSocketAndTryOpen: 提交 tryOpenCameraAfterConnect 到后台线程 (" + facingStr + ")");
                backgroundHandler.post(() -> tryOpenCameraAfterConnect(cameraFacing, finalSocket));
            } else {
                Log.e(TAG, "connectSocketAndTryOpen: 后台 Handler 为空，无法打开相机 (" + facingStr + ")！");
                closeSocket(cameraFacing); // 关闭刚建立的连接
                decrementActiveStreamCountAndCheckStop(); // 减少计数
            }

        } catch (IOException e) {
            // --- 连接失败 ---
            Log.e(TAG, "connectSocketAndTryOpen: 连接 " + facingStr + " Socket 失败: " + e.getMessage());
            decrementActiveStreamCountAndCheckStop(); // 连接失败，减少计数，可能触发重试
            // 尝试关闭可能部分打开的 Socket
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        } catch (Exception e) {
            Log.e(TAG, "connectSocketAndTryOpen: 连接或启动时意外错误 (" + facingStr + "): " + e.getMessage(), e);
            decrementActiveStreamCountAndCheckStop(); // 失败，减少计数
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        }
        Log.d(TAG, "<<< connectSocketAndTryOpen (" + facingStr + ") 结束");
    }

    /**
     * 在 Socket 连接成功后，尝试打开对应的相机。此方法应在 backgroundHandler 上运行。
     */
    private void tryOpenCameraAfterConnect(int cameraFacing, Socket connectedSocket) {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, ">>> tryOpenCameraAfterConnect (" + facingStr + ") 开始...");

        // 检查传递的 Socket 是否仍然有效且与 Map 中的一致
        Socket currentSocketInMap = sockets.get(cameraFacing);
        if (currentSocketInMap == null || currentSocketInMap != connectedSocket || !connectedSocket.isConnected() || connectedSocket.isClosed()) {
            Log.w(TAG, "tryOpenCameraAfterConnect: " + facingStr + " Socket 无效或已改变。取消打开相机。");
            // Socket 无效，但可能另一路连接还正常，这里不直接停止服务，仅返回
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (Socket无效)");
            // 注意：如果 Socket 无效，我们没有减少 activeStreamCount，因为连接阶段已经计数。
            // 如果这里 Socket 无效但之前连接成功了，可能需要在这里也调用 decrementActiveStreamCountAndCheckStop()
            // 让我们在这里添加它，以防万一连接成功后、打开相机前 Socket 断开
            if (sockets.containsKey(cameraFacing)) { // 检查是否真的持有该 facing 的资源
                closeCameraStream(cameraFacing); // 清理相关资源
                decrementActiveStreamCountAndCheckStop(); // 减少计数
                Log.w(TAG, "tryOpenCameraAfterConnect: Socket 无效，清理资源并减少计数 (" + facingStr + ")");
            }
            return;
        }

        // 检查 CameraManager
        if (cameraManager == null) {
            Log.e(TAG, "tryOpenCameraAfterConnect: CameraManager 不可用 (" + facingStr + ")");
            showToast("相机管理器错误");
            closeCameraStream(cameraFacing); // 清理相关资源
            decrementActiveStreamCountAndCheckStop(); // 减少计数并检查停止
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (无CameraManager)");
            return;
        }

        // 获取相机 ID
        String cameraId = getCameraIdForFacing(cameraFacing);
        if (cameraId == null) {
            Log.e(TAG, "tryOpenCameraAfterConnect: 未找到 Camera ID (" + facingStr + ")");
            showToast("未找到 " + facingStr + " 相机");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (无CameraID)");
            return;
        }

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "tryOpenCameraAfterConnect: 没有相机权限 (" + facingStr + ")");
            showToast("缺少相机权限");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (无权限)");
            return;
        }

        // 尝试打开相机
        try {
            Log.i(TAG, "tryOpenCameraAfterConnect: 正在打开相机 (" + facingStr + ", ID: " + cameraId + ")");
            // 调用 cameraManager.openCamera，结果在 StateCallback 中处理
            // 此调用可能抛出 CameraAccessException, IllegalStateException, SecurityException
            cameraManager.openCamera(cameraId, getCameraStateCallback(cameraFacing), backgroundHandler);
        } catch (CameraAccessException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "tryOpenCameraAfterConnect: 打开相机时出错 (" + facingStr + "): " + e.getMessage());
            showToast("相机访问/状态错误 (" + facingStr + ")");
            closeCameraStream(cameraFacing); // 清理资源
            decrementActiveStreamCountAndCheckStop(); // 减少计数
        } catch (Exception e) {
            Log.e(TAG, "tryOpenCameraAfterConnect: 打开相机时意外错误 (" + facingStr + "): " + e.getMessage(), e);
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        }
        Log.d(TAG, "<<< tryOpenCameraAfterConnect (" + facingStr + ") 结束 (已调用 openCamera)");
    }


    // --- CameraDevice.StateCallback ---
    private CameraDevice.StateCallback getCameraStateCallback(final int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                // 相机成功打开
                Log.i(TAG, ">>> CameraStateCallback.onOpened (" + facingStr + ", ID: " + camera.getId() + ")");
                // 检查 Map 中是否已存在设备，理论上不应该，除非有异常情况
                CameraDevice existingDevice = cameraDevices.put(cameraFacing, camera);
                if (existingDevice != null && existingDevice != camera) {
                    Log.w(TAG, "onOpened: " + facingStr + " Map 中已存在不同设备，关闭旧的...");
                    try { existingDevice.close(); } catch (Exception e) { /* ignore */ }
                }

                // 在后台线程创建预览会话
                if (backgroundHandler != null) {
                    Log.d(TAG,"onOpened: 提交 createCameraPreviewSession 到后台线程 (" + facingStr + ")");
                    backgroundHandler.post(() -> createCameraPreviewSession(cameraFacing));
                } else {
                    Log.e(TAG, "onOpened: 后台 Handler 为空，无法创建会话 (" + facingStr + ")！");
                    closeCameraStream(cameraFacing); // 关闭相机及相关资源
                    decrementActiveStreamCountAndCheckStop(); // 减少计数
                }
                Log.i(TAG, "<<< CameraStateCallback.onOpened (" + facingStr + ")");
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                // 相机断开连接 (可能是硬件问题或被其他应用抢占)
                Log.w(TAG, ">>> CameraStateCallback.onDisconnected (" + facingStr + ", ID: " + camera.getId() + ")");
                // 检查是否是当前使用的设备断开
                if (cameraDevices.get(cameraFacing) == camera) {
                    Log.w(TAG, "onDisconnected: 清理断开连接的相机资源 (" + facingStr + ")");
                    closeCameraStream(cameraFacing); // 清理所有相关资源
                    decrementActiveStreamCountAndCheckStop(); // <--- 触发停止和重试的关键点
                    showToast(facingStr + " 相机连接断开");
                } else {
                    Log.w(TAG, "onDisconnected: " + facingStr + " 设备不匹配或已移除？尝试关闭传入的 camera 对象。");
                    try { camera.close(); } catch (Exception e) { /* ignore */ }
                }
                Log.w(TAG, "<<< CameraStateCallback.onDisconnected (" + facingStr + ")");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                // 相机发生错误
                Log.e(TAG, ">>> CameraStateCallback.onError (" + facingStr + ", ID: " + camera.getId() + ", 错误码: " + error + " - " + errorToString(error) + ")");
                // 检查是否是当前使用的设备出错
                if (cameraDevices.get(cameraFacing) == camera) {
                    Log.e(TAG, "onError: 清理出错的相机资源 (" + facingStr + ")");
                    closeCameraStream(cameraFacing); // 清理所有相关资源
                    decrementActiveStreamCountAndCheckStop(); // <--- 触发停止和重试的关键点
                    showToast(facingStr + " 相机错误: " + errorToString(error));
                } else {
                    Log.w(TAG, "onError: " + facingStr + " 设备不匹配或已移除？尝试关闭传入的 camera 对象。");
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
        Socket associatedSocket = sockets.get(cameraFacing); // 获取关联的 Socket

        // --- 前置检查 ---
        if (cameraDevice == null) {
            Log.e(TAG, "createCameraPreviewSession: CameraDevice 为空 (" + facingStr + ")");
            // 设备为空，可能已被关闭，确保清理并减少计数
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
        Surface surface = imageReader.getSurface();
        if(surface == null || !surface.isValid()){
            Log.e(TAG, "createCameraPreviewSession: Surface 无效 (" + facingStr + ")");
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束 (Surface无效)");
            return;
        }
        // 检查 Socket 是否仍然连接
        if (associatedSocket == null || !associatedSocket.isConnected() || associatedSocket.isClosed()) {
            Log.e(TAG, "createCameraPreviewSession: Socket 已断开 (" + facingStr + ")，无法创建会话。");
            closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束 (Socket断开)");
            return;
        }

        try {
            // --- 配置 ImageReader --- (这部分应该在 openCameraForFacing 中完成)
            // 这里假设 ImageReader 已在 openCameraForFacing 中正确配置并添加到 map
            Log.d(TAG, "createCameraPreviewSession: ImageReader surface: " + surface);

            // --- 创建 CaptureRequest.Builder ---
            final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface); // 将 ImageReader 的 Surface 作为目标

            // --- 设置自动对焦、自动曝光等参数 ---
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO); // 基本自动模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); // 连续自动对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); // 自动曝光开启
            // captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, (byte)JPEG_QUALITY); // 设置 JPEG 质量 (如果需要)
            Log.d(TAG, "createCameraPreviewSession: CaptureRequest Builder 配置完成 (" + facingStr + ")");


            // --- 创建 CameraCaptureSession ---
            Log.d(TAG, "createCameraPreviewSession: 正在创建 CaptureSession (" + facingStr + ")");
            List<Surface> outputs = Collections.singletonList(surface);

            // 异步创建会话，结果在 StateCallback 中处理
            cameraDevice.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            // 会话配置成功
                            Log.i(TAG, ">>> CaptureSession.onConfigured (" + facingStr + ")");
                            // 再次检查设备和 Socket 状态，防止在回调回来之前状态已改变
                            CameraDevice currentDevice = cameraDevices.get(cameraFacing);
                            Socket currentSocket = sockets.get(cameraFacing);
                            if (currentDevice == null || currentDevice != cameraDevice) {
                                Log.w(TAG, "onConfigured: 设备已关闭或改变 (" + facingStr + ")，关闭此会话。");
                                try { session.close(); } catch (Exception e) { /* ignore */ }
                                // 设备无效，可能需要触发清理流程，但避免重复调用
                                if (sockets.containsKey(cameraFacing) && cameraDevices.get(cameraFacing) == null) {
                                    Log.w(TAG, "onConfigured: 设备无效，再次尝试清理 (" + facingStr + ")");
                                    closeCameraStream(cameraFacing);
                                    decrementActiveStreamCountAndCheckStop();
                                }
                                Log.i(TAG, "<<< CaptureSession.onConfigured (" + facingStr + ") 结束 (设备无效)");
                                return;
                            }
                            if (currentSocket == null || !currentSocket.isConnected() || currentSocket.isClosed()) {
                                Log.w(TAG, "onConfigured: Socket 已断开 (" + facingStr + ")，关闭此会话并清理。");
                                try { session.close(); } catch (Exception e) { /* ignore */ }
                                // Socket 断开是需要清理和重试的情况
                                if (sockets.containsKey(cameraFacing)){
                                    closeCameraStream(cameraFacing);
                                    decrementActiveStreamCountAndCheckStop();
                                }
                                Log.i(TAG, "<<< CaptureSession.onConfigured (" + facingStr + ") 结束 (Socket无效)");
                                return;
                            }

                            // --- 会话有效，开始重复请求 ---
                            Log.i(TAG, "onConfigured: 会话配置成功 (" + facingStr + ")");
                            cameraCaptureSessions.put(cameraFacing, session); // 保存会话引用
                            try {
                                // 设置重复请求以获取预览帧
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE); // 重置 AE 触发器
                                CaptureRequest previewRequest = captureRequestBuilder.build();
                                Log.d(TAG, "onConfigured: 设置重复请求 (setRepeatingRequest) (" + facingStr + ")");
                                session.setRepeatingRequest(previewRequest, null, backgroundHandler); // 开始预览
                                Log.i(TAG, "onConfigured: " + facingStr + " 相机预览已启动。");
                            } catch (CameraAccessException | IllegalStateException e) {
                                Log.e(TAG, "onConfigured: 启动重复请求时出错 (" + facingStr + "): ", e);
                                closeCameraStream(cameraFacing); // 出错则清理
                                decrementActiveStreamCountAndCheckStop();
                            } catch (Exception e) {
                                Log.e(TAG, "onConfigured: 启动重复请求时未知错误 (" + facingStr + "): ", e);
                                closeCameraStream(cameraFacing);
                                decrementActiveStreamCountAndCheckStop();
                            }
                            Log.i(TAG, "<<< CaptureSession.onConfigured (" + facingStr + ")");
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            // 会话配置失败
                            Log.e(TAG, ">>> CaptureSession.onConfigureFailed (" + facingStr + ")");
                            showToast("相机预览配置失败 (" + facingStr + ")");
                            closeCameraStream(cameraFacing); // 清理
                            decrementActiveStreamCountAndCheckStop(); // 减少计数
                            Log.e(TAG, "<<< CaptureSession.onConfigureFailed (" + facingStr + ")");
                        }
                    }, backgroundHandler // 在后台线程处理回调
            );
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "createCameraPreviewSession: 准备请求或创建会话时出错 (" + facingStr + "): ", e);
            closeCameraStream(cameraFacing); // 清理
            decrementActiveStreamCountAndCheckStop(); // 减少计数
        } catch (Exception e) {
            Log.e(TAG, "createCameraPreviewSession: 准备请求或创建会话时未知错误 (" + facingStr + "): ", e);
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        }
        Log.d(TAG, "<<< createCameraPreviewSession (" + facingStr + ") 结束");
    }

    /**
     * 打开指定 ID 和朝向的相机。此方法必须在 backgroundHandler 上运行。
     * 负责设置 ImageReader。
     */
    private void openCameraForFacing(String cameraId, int cameraFacing) throws CameraAccessException, IllegalStateException, SecurityException {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, ">>> openCameraForFacing (" + facingStr + ") 开始...");

        // 再次检查关联的 Socket 是否有效
        Socket associatedSocket = sockets.get(cameraFacing);
        if (associatedSocket == null || !associatedSocket.isConnected() || associatedSocket.isClosed()) {
            Log.w(TAG, "openCameraForFacing: " + facingStr + " Socket 无效。取消打开相机。");
            // 如果 Socket 无效，也需要减少计数，因为这个流程失败了
            if (sockets.containsKey(cameraFacing)) { // 检查是否真的持有该 facing 的资源
                // closeCameraStream(cameraFacing); // Socket可能已关闭，但其他资源可能还在
                decrementActiveStreamCountAndCheckStop();
                Log.w(TAG, "openCameraForFacing: Socket 无效，减少计数 (" + facingStr + ")");
            }
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (Socket无效)");
            return; // 不抛出异常，直接返回
        }

        // 获取相机特性
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            Log.e(TAG, "openCameraForFacing: 无法获取流配置 (" + facingStr + ")");
            showToast("无法获取相机配置 (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (无流配置)");
            return; // 直接返回
        }

        // --- 选择合适的预览尺寸 ---
        Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG); // 获取支持的 JPEG 输出尺寸
        if (outputSizes == null || outputSizes.length == 0) {
            Log.e(TAG, "openCameraForFacing: 不支持 JPEG 输出 (" + facingStr + ")");
            showToast("相机不支持JPEG (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (不支持JPEG)");
            return; // 直接返回
        }

        // 选择一个接近 640x480 的尺寸
        Size targetSize = new Size(640, 480);
        Size selectedSize = Collections.min(Arrays.asList(outputSizes),
                Comparator.comparingLong((Size s) -> Math.abs((long)s.getWidth() * s.getHeight() - (long)targetSize.getWidth() * targetSize.getHeight()))
                        .thenComparingInt((Size s) -> Math.abs(s.getWidth() - targetSize.getWidth())) // 优先匹配面积，其次匹配宽度
        );
        previewSizes.put(cameraFacing, selectedSize); // 存储选择的尺寸
        Log.i(TAG, "openCameraForFacing: 选择预览尺寸 " + selectedSize + " (" + facingStr + ")");

        // --- 配置 ImageReader ---
        closeReader(cameraFacing); // 先关闭可能存在的旧 Reader

        ImageReader imageReader = ImageReader.newInstance(
                selectedSize.getWidth(), selectedSize.getHeight(),
                ImageFormat.JPEG, // 使用 JPEG 格式
                IMAGE_BUFFER_SIZE // 缓冲帧数
        );
        imageReaders.put(cameraFacing, imageReader); // 保存 ImageReader 引用

        final int currentFacing = cameraFacing; // 用于 Lambda 表达式
        // 设置 ImageReader 的监听器，在后台线程处理
        imageReader.setOnImageAvailableListener(reader -> {
            // Log.v(TAG, "onImageAvailable called for " + getFacingString(currentFacing)); // 频繁日志用 v
            Size currentSize = previewSizes.get(currentFacing);
            if (currentSize != null) {
                processImageAvailable(reader, currentFacing, currentSize); // 处理图像数据
            } else {
                Log.e(TAG, "onImageAvailable: 无法获取预览尺寸 (" + getFacingString(currentFacing) + ")，尝试从图像获取");
                // 尝试从获取的图像中恢复尺寸信息
                try (Image img = reader.acquireLatestImage()) {
                    if (img != null) {
                        Size fallbackSize = new Size(img.getWidth(), img.getHeight());
                        Log.w(TAG, "onImageAvailable: 使用回退尺寸 " + fallbackSize);
                        processImageAvailable(reader, currentFacing, fallbackSize);
                    } else {
                        Log.e(TAG, "onImageAvailable: acquireLatestImage() 返回 null ("+ getFacingString(currentFacing) +")");
                        // 如果无法获取图像，可能 ImageReader 已损坏，需要清理
                        if (sockets.containsKey(currentFacing)) { closeCameraStream(currentFacing); decrementActiveStreamCountAndCheckStop(); }
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "onImageAvailable: ImageReader 状态异常 (" + getFacingString(currentFacing) + "): " + e.getMessage());
                    // ImageReader 状态异常，清理并减少计数
                    if (sockets.containsKey(currentFacing)) { closeCameraStream(currentFacing); decrementActiveStreamCountAndCheckStop(); }
                }
            }
        }, backgroundHandler);


        // --- 打开相机设备 ---
        // 再次检查权限 (理论上不会变，但更安全)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "openCameraForFacing: 打开相机时权限丢失 (" + facingStr + ")！");
            showToast("相机权限丢失");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (无权限)");
            return;
        }

        Log.d(TAG, "openCameraForFacing: 正在调用 cameraManager.openCamera (" + facingStr + ")");
        // 异步打开相机，结果在 getCameraStateCallback 中处理
        // 可能抛出 CameraAccessException, IllegalStateException, SecurityException
        cameraManager.openCamera(cameraId, getCameraStateCallback(cameraFacing), backgroundHandler);
        Log.d(TAG, "<<< openCameraForFacing (" + facingStr + ") 结束 (已调用 openCamera)");
    }

    /**
     * 处理 ImageReader 的 onImageAvailable 事件。
     * 从 Reader 获取图像，处理（旋转），然后发送。
     */
    private void processImageAvailable(ImageReader reader, int cameraFacing, Size previewSize) {
        String facingStr = getFacingString(cameraFacing);
        // Log.v(TAG, ">>> processImageAvailable (" + facingStr + ")"); // 频繁日志
        try (Image image = reader.acquireNextImage()) { // 使用 try-with-resources 自动关闭 Image
            if (image != null) {
                // --- 获取 JPEG 数据 ---
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes); // 将 ByteBuffer 数据复制到 byte 数组

                // --- 检查 Socket 状态 ---
                Socket currentSocket = sockets.get(cameraFacing);
                if (currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed()) {
                    // Socket 有效，处理并发送帧
                    processAndSendFrame(bytes, cameraFacing, previewSize.getWidth(), previewSize.getHeight());
                } else {
                    // Socket 无效，不处理帧，清理资源并减少计数
                    Log.w(TAG, "processImageAvailable: Socket 无效，跳过帧处理 (" + facingStr + ")");
                    if (sockets.containsKey(cameraFacing)) { // 确保资源存在才清理
                        closeCameraStream(cameraFacing);
                        decrementActiveStreamCountAndCheckStop();
                    }
                }
            } else {
                Log.w(TAG, "processImageAvailable: acquireNextImage() 返回 null (" + facingStr + ")");
                // 获取不到图像，可能 reader 有问题
                // if (sockets.containsKey(cameraFacing)) { closeCameraStream(cameraFacing); decrementActiveStreamCountAndCheckStop(); }
            }
        } catch (IllegalStateException e) {
            // ImageReader 可能已关闭
            Log.w(TAG, "processImageAvailable: Reader 状态错误 (" + facingStr + "): " + e.getMessage());
            // 清理并减少计数
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        } catch (Exception e) {
            // 其他意外错误
            Log.e(TAG, "processImageAvailable: 处理图像时意外错误 (" + facingStr + ")", e);
            // 清理并减少计数
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        }
        // Log.v(TAG, "<<< processImageAvailable (" + facingStr + ")");
    }


    /**
     * 处理原始 JPEG 数据，进行旋转，然后压缩并发送。
     */
    private void processAndSendFrame(byte[] jpegBytes, int cameraFacing, int width, int height) {
        String facingStr = getFacingString(cameraFacing);
        // Log.v(TAG, ">>> processAndSendFrame (" + facingStr + ")"); // 频繁日志

        // 再次检查 Socket 状态
        Socket currentSocket = sockets.get(cameraFacing);
        if (currentSocket == null || !currentSocket.isConnected() || currentSocket.isClosed()) {
            Log.w(TAG, "processAndSendFrame: Socket 无效，跳过发送 (" + facingStr + ")");
            // Socket 无效，清理并减少计数
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
            return;
        }

        Bitmap bitmap = null;
        Bitmap rotatedBitmap = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            // --- 解码 JPEG ---
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565; // 使用 RGB_565 减少内存占用
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
            if (bitmap == null) {
                Log.w(TAG, "processAndSendFrame: JPEG 解码失败 (" + facingStr + ")");
                return; // 解码失败，无法继续
            }

            // --- 计算旋转角度和是否翻转 ---
            Matrix matrix = new Matrix();
            int rotationDegrees = 0; // 默认不旋转
            boolean flipHorizontal = false; // 默认不水平翻转
            Integer sensorOrientation = 0; // 传感器方向

            // 获取传感器方向
            CameraDevice device = cameraDevices.get(cameraFacing);
            if (device != null && cameraManager != null) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(device.getId());
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (sensorOrientation == null) sensorOrientation = 0; // 默认为 0 度
                } catch (CameraAccessException e) {
                    Log.e(TAG, "processAndSendFrame: 获取传感器方向失败 (" + facingStr + "): " + e.getMessage());
                    // 获取失败，使用默认值 0
                }
            }

            // 根据传感器方向和摄像头朝向计算旋转角度
            // 注意：这里的旋转逻辑可能需要根据具体设备和期望的显示效果调整
            // 假设目标是竖屏显示
            rotationDegrees = sensorOrientation; // 直接使用传感器方向？ 可能需要调整

            if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                // 前置摄像头通常需要水平翻转镜像效果
                flipHorizontal = true;
                // 前置旋转角度可能需要额外调整，例如 (360 - sensorOrientation) % 360
                rotationDegrees = (360 - sensorOrientation) % 360; // 尝试修正前置镜像后的旋转
            } else {
                // 后置摄像头通常不需要翻转
                flipHorizontal = false;
                rotationDegrees = sensorOrientation % 360;
            }
            Log.v(TAG, "processAndSendFrame: SensorOrientation=" + sensorOrientation + ", Facing=" + facingStr + " => Rotation=" + rotationDegrees + ", Flip=" + flipHorizontal);

            matrix.postRotate(rotationDegrees); // 应用旋转
            if (flipHorizontal) {
                // 应用水平翻转
                matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
            }

            // --- 创建旋转/翻转后的 Bitmap ---
            rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            // --- 压缩为 JPEG ---
            byteArrayOutputStream = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream);
            byte[] rotatedBytes = byteArrayOutputStream.toByteArray();

            // --- 发送数据 ---
            sendFrameData(rotatedBytes, cameraFacing); // 发送带长度前缀的数据

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "processAndSendFrame: 内存不足 (" + facingStr + ")", oom);
            // 尝试回收内存，并关闭此流
            System.gc();
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } catch (Exception e) {
            Log.e(TAG, "processAndSendFrame: 处理或发送时出错 (" + facingStr + ")", e);
            // 出错则关闭此流
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } finally {
            // --- 回收 Bitmap ---
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (rotatedBitmap != null && !rotatedBitmap.isRecycled()) {
                rotatedBitmap.recycle();
            }
            // --- 关闭输出流 ---
            if (byteArrayOutputStream != null) {
                try {
                    byteArrayOutputStream.close();
                } catch (IOException e) { /* ignore */ }
            }
        }
        // Log.v(TAG, "<<< processAndSendFrame (" + facingStr + ") 结束");
    }


    /**
     * 发送带 4 字节长度前缀的帧数据。
     * 这是检测连接断开的关键点之一。
     */
    private void sendFrameData(byte[] frameData, int cameraFacing) {
        OutputStream outputStream = outputStreams.get(cameraFacing);
        Socket socket = sockets.get(cameraFacing);
        String facingStr = getFacingString(cameraFacing);

        // Log.v(TAG, ">>> sendFrameData: 尝试发送帧 facing=" + facingStr + ", length=" + frameData.length);

        // 检查流和 Socket 是否有效
        if (outputStream != null && socket != null && socket.isConnected() && !socket.isClosed()) {
            try {
                // --- 准备长度前缀 ---
                int length = frameData.length;
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length);

                // --- 发送长度 ---
                // Log.v(TAG, "sendFrameData: Writing length ("+ facingStr +")");
                outputStream.write(lengthBuffer.array());

                // --- 发送数据 ---
                // Log.v(TAG, "sendFrameData: Writing data ("+ facingStr +")");
                outputStream.write(frameData);

                // --- 刷新缓冲区，确保数据发送 ---
                // Log.v(TAG, "sendFrameData: Flushing ("+ facingStr +")");
                outputStream.flush(); // flush() 很重要，它会强制写出数据，更容易触发网络 IO 错误

                // Log.v(TAG, "<<< sendFrameData: 成功发送帧 facing=" + facingStr + ", length=" + length);

            } catch (IOException e) {
                // --- 捕获到 IO 异常，通常意味着连接已断开 ---
                Log.e(TAG, ">>> sendFrameData: 发送帧时 IO 错误 (" + facingStr + "): " + e.getMessage() + ". 可能已断开连接。");
                // 关闭此流并减少计数，触发停止和重试逻辑
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop(); // <--- 关键：触发后续流程 (onDestroy -> 重试)
                Log.e(TAG, "<<< sendFrameData: IO 错误处理完毕 (" + facingStr + ")");
            } catch (Exception e) {
                // 捕获其他可能的发送错误
                Log.e(TAG, ">>> sendFrameData: 发送帧时未知错误 (" + facingStr + "): " + e.getMessage(), e);
                closeCameraStream(cameraFacing); // 清理
                decrementActiveStreamCountAndCheckStop(); // 减少计数
                Log.e(TAG, "<<< sendFrameData: 未知错误处理完毕 (" + facingStr + ")");
            }
        } else {
            // 流或 Socket 无效
            Log.w(TAG, "sendFrameData: 无法发送，流/Socket 无效 (" + facingStr + ")");
            // 如果资源映射中还存在，则清理并减少计数
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
            // Log.v(TAG, "<<< sendFrameData: 结束 (流/Socket 无效)");
        }
    }


    // --- Helper and Cleanup Methods ---

    /**
     * 减少活动流计数，并在计数为零时安全地停止服务，触发重试逻辑。
     */
    private void decrementActiveStreamCountAndCheckStop() {
        // 原子递减并获取新值
        int remaining = activeStreamCount.decrementAndGet();
        Log.d(TAG, ">>> decrementActiveStreamCountAndCheckStop: 活动流计数减少，剩余: " + remaining);

        // 如果计数小于等于 0，表示所有流都已断开或失败
        if (remaining <= 0) {
            Log.i(TAG, "decrementActiveStreamCountAndCheckStop: 计数 <= 0，尝试设置最终计数为 0 并停止服务...");
            // 再次使用 compareAndSet 确保只有一个线程执行停止操作
            // 将 remaining (可能 < 0) 设置为 0
            if (activeStreamCount.compareAndSet(remaining, 0)) {
                Log.i(TAG, "decrementActiveStreamCountAndCheckStop: 成功设置计数为 0，调用 stopSelfSafely()...");
                // 调用 stopSelfSafely 会触发 onDestroy，进而执行重试逻辑 (如果 isServiceRunning 为 true)
                stopSelfSafely();
            } else {
                // 如果 compareAndSet 失败，说明计数在递减后到这里之间又被其他地方修改了
                // (理论上不应该发生，因为递减操作是原子的)
                // 或者已经被其他线程停止了
                Log.d(TAG, "decrementActiveStreamCountAndCheckStop: compareAndSet 失败，可能已被停止或状态改变。当前计数: " + activeStreamCount.get());
            }
        }
        Log.d(TAG, "<<< decrementActiveStreamCountAndCheckStop: 结束");
    }


    /**
     * 安全地停止服务 (确保在主线程调用 stopSelf)。
     * 这会触发 onDestroy 方法。
     */
    private void stopSelfSafely() {
        Log.i(TAG, ">>> stopSelfSafely() 被调用");
        // 不论当前状态如何，先标记服务不应再运行，避免 onDestroy 误判为意外停止
        // isServiceRunning = false; // 移动到 onDestroy 开头判断

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, "stopSelfSafely: 在主线程，直接调用 stopSelf()");
            try {
                stopSelf(); // 调用 stopSelf() 会异步触发 onDestroy()
            } catch (Exception e) {
                Log.e(TAG,"stopSelfSafely: 调用 stopSelf() 异常", e);
                // 如果 stopSelf 失败，尝试强制停止前台状态
                stopForeground(true);
            }
        } else {
            Log.d(TAG, "stopSelfSafely: 不在主线程，post stopSelf() 到主线程");
            if(mainHandler != null) {
                mainHandler.post(() -> {
                    Log.d(TAG, "stopSelfSafely: 主线程 Handler 执行 stopSelf()");
                    try {
                        stopSelf();
                    } catch (Exception e) {
                        Log.e(TAG,"stopSelfSafely: 主线程 Handler 调用 stopSelf() 异常", e);
                        stopForeground(true);
                    }
                });
            } else {
                Log.e(TAG,"stopSelfSafely: 无法 post stopSelf()，mainHandler 为 null！尝试直接 stopForeground");
                // mainHandler 可能在服务早期销毁时为 null
                stopForeground(true);
            }
        }
        Log.i(TAG, "<<< stopSelfSafely() 调用结束 (onDestroy 将异步执行)");
    }

    /** 关闭指定朝向的相机流相关的所有资源 */
    private void closeCameraStream(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        Log.w(TAG, ">>> closeCameraStream (" + facingStr + ") 开始关闭资源...");
        // 按依赖顺序关闭：会话 -> 设备 -> Reader -> Socket
        closeSession(cameraFacing);
        closeCameraDevice(cameraFacing);
        closeReader(cameraFacing);
        closeSocket(cameraFacing);
        previewSizes.remove(cameraFacing); // 移除预览尺寸记录
        Log.w(TAG, "<<< closeCameraStream (" + facingStr + ") 资源关闭完成。");
    }

    /** 关闭指定朝向的预览会话 */
    private void closeSession(int cameraFacing) {
        CameraCaptureSession session = cameraCaptureSessions.remove(cameraFacing);
        if (session != null) {
            Log.d(TAG, "closeSession: Closing session (" + getFacingString(cameraFacing) + ")");
            try {
                session.close();
            } catch (Exception e) { // CameraAccessException or IllegalStateException
                Log.e(TAG, "closeSession: Error closing session ("+getFacingString(cameraFacing)+")", e);
            }
        }
    }

    /** 关闭指定朝向的相机设备 */
    private void closeCameraDevice(int cameraFacing) {
        CameraDevice device = cameraDevices.remove(cameraFacing);
        if (device != null) {
            Log.d(TAG, "closeCameraDevice: Closing device (" + getFacingString(cameraFacing) + ", ID: " + device.getId() + ")");
            try {
                device.close();
            } catch (Exception e) { // Can be IllegalStateException if already closed
                Log.e(TAG, "closeCameraDevice: Error closing device ("+getFacingString(cameraFacing)+")", e);
            }
        }
    }

    /** 关闭指定朝向的 ImageReader */
    private void closeReader(int cameraFacing) {
        ImageReader reader = imageReaders.remove(cameraFacing);
        if (reader != null) {
            Log.d(TAG, "closeReader: Closing reader (" + getFacingString(cameraFacing) + ")");
            try {
                reader.close();
            } catch (Exception e) { // Can be IllegalStateException
                Log.e(TAG, "closeReader: Error closing reader ("+getFacingString(cameraFacing)+")", e);
            }
        }
    }

    /** 关闭指定朝向的 Socket 和输出流 */
    private void closeSocket(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        // 先关输出流
        OutputStream os = outputStreams.remove(cameraFacing);
        if (os != null) {
            Log.d(TAG, "closeSocket: Closing output stream (" + facingStr + ")");
            try { os.close(); } catch (IOException e) { /* ignore */ }
        }
        // 再关 Socket
        Socket socket = sockets.remove(cameraFacing);
        if (socket != null) {
            String remoteAddr = "N/A";
            try { remoteAddr = socket.getRemoteSocketAddress().toString(); } catch(Exception e){}
            Log.d(TAG, "closeSocket: Closing socket (" + facingStr + ", Remote: " + (socket.isClosed() ? "closed" : remoteAddr) + ")");
            if (!socket.isClosed()) {
                try { socket.close(); } catch (IOException e) { Log.e(TAG, "closeSocket: Error closing socket ("+facingStr+")", e); }
            }
        }
    }

    /** 停止所有相机流，清理所有相关资源 */
    private void stopAllCameraStreams() {
        Log.i(TAG, ">>> stopAllCameraStreams: 开始停止所有流...");
        // 遍历当前已知的相机朝向 (通常是后置和前置)
        Integer[] facings = { CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT };
        for (Integer facing : facings) {
            closeCameraStream(facing); // 对每个朝向调用清理方法
        }
        // 确保所有 Map 都被清空 (防御性编程)
        cameraCaptureSessions.clear();
        cameraDevices.clear();
        imageReaders.clear();
        outputStreams.clear();
        sockets.clear();
        previewSizes.clear();
        Log.i(TAG, "<<< stopAllCameraStreams: 所有流停止完成。");
    }


    /** 服务关闭前的最终清理方法 */
    private void shutdownAndCleanup() {
        Log.i(TAG, ">>> shutdownAndCleanup: 开始服务关闭和清理...");

        // 0. 移除主线程 Handler 上的待处理消息
        if (mainHandler != null) {
            Log.d(TAG,"shutdownAndCleanup: 移除 mainHandler 任务");
            mainHandler.removeCallbacksAndMessages(null);
        }

        // 1. 关闭网络连接线程池
        if (connectionExecutor != null && !connectionExecutor.isShutdown()) {
            Log.d(TAG,"shutdownAndCleanup: 关闭网络连接线程池...");
            connectionExecutor.shutdownNow(); // 尝试立即停止
            try {
                // 等待一段时间让任务停止
                if (!connectionExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "shutdownAndCleanup: 网络连接线程池关闭超时。");
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "shutdownAndCleanup: 等待网络线程池关闭时被打断。");
                Thread.currentThread().interrupt(); // 重新设置中断状态
            }
            connectionExecutor = null;
            Log.d(TAG,"shutdownAndCleanup: 网络连接线程池已关闭。");
        } else {
            Log.d(TAG,"shutdownAndCleanup: 网络连接线程池为 null 或已关闭。");
        }


        // 2. 在后台线程同步清理相机资源
        Handler handler = backgroundHandler;
        HandlerThread thread = backgroundThread;
        if (handler != null && thread != null && thread.isAlive()) {
            Log.d(TAG,"shutdownAndCleanup: 提交 stopAllCameraStreams 到后台线程并等待...");
            final CountDownLatch cleanupLatch = new CountDownLatch(1); // 使用 Latch 等待完成
            handler.post(() -> {
                try {
                    stopAllCameraStreams(); // 在后台线程执行清理
                } finally {
                    cleanupLatch.countDown(); // 保证 Latch 被释放
                }
            });
            try {
                // 等待后台清理完成，设置超时时间
                if (!cleanupLatch.await(2000, TimeUnit.MILLISECONDS)) { // 增加等待时间到 2 秒
                    Log.w(TAG, "shutdownAndCleanup: 等待后台清理任务超时！");
                } else {
                    Log.d(TAG, "shutdownAndCleanup: 后台清理任务完成。");
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "shutdownAndCleanup: 等待后台清理任务时被打断。");
                Thread.currentThread().interrupt();
            }
        } else {
            // 如果后台线程不可用，尝试在当前线程（可能是主线程）直接清理
            Log.w(TAG, "shutdownAndCleanup: 后台线程不可用，直接在当前线程清理...");
            stopAllCameraStreams();
        }

        // 3. 停止后台线程
        Log.d(TAG,"shutdownAndCleanup: 停止后台线程...");
        stopBackgroundThread();
        Log.d(TAG,"shutdownAndCleanup: 后台线程已停止。");

        // 4. 停止前台服务状态
        Log.d(TAG,"shutdownAndCleanup: 停止前台服务状态...");
        try {
            // Service.STOP_FOREGROUND_REMOVE 会移除通知
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            Log.e(TAG,"shutdownAndCleanup: 停止前台服务出错: " + e.getMessage());
        }
        Log.d(TAG,"shutdownAndCleanup: 前台服务状态已停止。");

        // 5. 重置活动流计数器
        activeStreamCount.set(0);
        Log.i(TAG, "<<< shutdownAndCleanup: 清理完成。");
    }

    // 启动后台线程处理相机操作
    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            Log.d(TAG, "startBackgroundThread: 启动新后台线程...");
            backgroundThread = new HandlerThread("CameraBackground", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            Looper looper = backgroundThread.getLooper(); // 获取 Looper
            if (looper != null) {
                backgroundHandler = new Handler(looper); // 创建 Handler
                Log.d(TAG, "startBackgroundThread: 后台线程 Handler 已创建。");
            } else {
                // Looper 可能在线程启动后立刻停止时为 null
                Log.e(TAG,"startBackgroundThread: 无法获取后台 Looper！");
                if (backgroundThread != null) {
                    backgroundThread.quitSafely(); // 尝试安全退出
                    try { backgroundThread.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                backgroundThread = null;
                backgroundHandler = null;
                showToast("后台处理线程启动失败");
                isServiceRunning = false; // 标记服务启动失败
                stopSelfSafely(); // 停止服务
            }
        } else {
            Log.d(TAG,"startBackgroundThread: 后台线程已在运行。");
        }
    }

    // 停止后台线程
    private void stopBackgroundThread() {
        HandlerThread threadToStop = backgroundThread; // 获取当前线程引用
        if (threadToStop != null) {
            Log.d(TAG, ">>> stopBackgroundThread: 请求停止后台线程 (ID: " + threadToStop.getThreadId() + ")");
            backgroundHandler = null; // 清除 Handler 引用
            threadToStop.quitSafely(); // 请求安全退出 Looper
            try {
                // 等待线程结束，设置超时
                threadToStop.join(1000);
                if (threadToStop.isAlive()) {
                    // 如果超时后仍在运行，尝试中断
                    Log.w(TAG,"stopBackgroundThread: 后台线程停止超时，尝试中断...");
                    threadToStop.interrupt();
                    threadToStop.join(500); // 再次等待
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "stopBackgroundThread: 等待后台线程停止时被打断。");
                Thread.currentThread().interrupt();
            }
            // 确认线程实例已清除
            if (backgroundThread == threadToStop) {
                backgroundThread = null;
            }
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
                    // 检查 Context 是否仍然有效
                    if (getApplicationContext() != null) {
                        // Log.d(TAG, "Showing Toast: " + message);
                        Toast.makeText(CameraStreamService.this, message, Toast.LENGTH_SHORT).show();
                    } else {
                        Log.w(TAG, "无法显示 Toast，application context is null。");
                    }
                } catch (Exception e) {
                    // 防止 Toast 引发崩溃，例如在服务销毁过程中调用
                    Log.e(TAG, "显示 Toast 时出错: " + e.getMessage());
                }
            });
        } else {
            // 如果 mainHandler 为 null，通常发生在服务生命周期早期或晚期
            Log.e(TAG,"无法显示 Toast: mainHandler is null for message: " + message);
        }
    }

    // 获取相机朝向的字符串表示 (用于日志)
    private String getFacingString(int cameraFacing) {
        return (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) ? "后置" : "前置";
    }

    // 将 CameraDevice.StateCallback 的错误代码转换为可读字符串
    private String errorToString(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "相机已被占用";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "达到最大相机使用数量";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "相机设备已被禁用";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "相机设备致命错误";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "相机服务致命错误";
            default: return "未知错误 (" + error + ")";
        }
    }

    // --- Notification Methods ---
    private void startForegroundServiceNotification() {
        createNotificationChannel(); // 确保通道存在

        // 构建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 使用应用图标
                .setContentTitle("相机流服务") // 通知标题
                .setContentText("正在运行...") // 初始内容
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级
                .setOngoing(true); // 设为持续性通知

        Notification notification = builder.build();

        // --- 启动前台服务 ---
        try {
            int foregroundServiceType = 0;
            // Android 10 (Q) 及以上需要指定类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC; // 添加数据同步类型 (可选)
            }
            // Android 14 (U) 及以上，如果使用相机，必须声明相机类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }

            if (foregroundServiceType != 0) {
                Log.d(TAG, "启动前台服务 (带类型): " + foregroundServiceType);
                startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
            } else {
                Log.d(TAG, "启动前台服务 (不带类型)");
                startForeground(NOTIFICATION_ID, notification); // 旧版本或未指定类型
            }
            Log.d(TAG, "前台服务通知已启动。");

        } catch (Exception e) {
            Log.e(TAG, "启动前台服务时出错: " + e.getMessage(), e);
            // 检查具体权限问题
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE_CAMERA 权限 (Android 14+)");
                } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                    showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE 权限");
                } else {
                    showToast("启动前台服务失败: " + e.getMessage());
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Android 9+ 需要 FOREGROUND_SERVICE
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                    showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE 权限");
                } else {
                    showToast("启动前台服务失败: " + e.getMessage());
                }
            } else {
                showToast("启动前台服务失败: " + e.getMessage());
            }
            isServiceRunning = false; // 标记启动失败
            stopSelfSafely(); // 停止服务
        }
    }

    // 创建通知渠道 (Android 8.0+ 需要)
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "相机流服务通道";
            String description = "用于相机流服务的后台运行通知";
            int importance = NotificationManager.IMPORTANCE_LOW; // 低重要性，避免打扰
            NotificationChannel serviceChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
            serviceChannel.setDescription(description);
            // 获取 NotificationManager 并创建通道
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                try {
                    manager.createNotificationChannel(serviceChannel);
                    Log.d(TAG, "通知通道已创建或已存在: " + NOTIFICATION_CHANNEL_ID);
                } catch (Exception e) {
                    Log.e(TAG, "创建通知通道失败: " + e.getMessage());
                }
            } else {
                Log.e(TAG, "无法获取 NotificationManager 来创建通知通道。");
            }
        }
    }

    // --- 获取相机 ID 的辅助方法 ---
    @Nullable
    private String getCameraIdForFacing(int cameraFacing) {
        if (cameraManager == null) {
            Log.e(TAG,"getCameraIdForFacing: CameraManager is null!");
            return null;
        }
        try {
            // 遍历所有可用相机 ID
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                // 获取镜头朝向
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                // 如果朝向不为空且与请求的朝向匹配，则返回此 ID
                if (facing != null && facing == cameraFacing) {
                    Log.d(TAG,"getCameraIdForFacing: Found camera ID " + cameraId + " for facing " + getFacingString(cameraFacing));
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "getCameraIdForFacing: 访问相机特性时出错: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "getCameraIdForFacing: 获取相机ID时发生意外错误", e);
        }
        // 如果没有找到匹配的相机 ID
        Log.e(TAG,"getCameraIdForFacing: No camera found for facing " + getFacingString(cameraFacing));
        return null;
    }
}