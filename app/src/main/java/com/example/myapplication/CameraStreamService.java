// --- START OF FILE CameraStreamService.java (包含重试逻辑) ---
package com.example.myapplication;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
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
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 5000; // 缩短连接超时以便更快重试
    private static final int IMAGE_BUFFER_SIZE = 2;
    private static final int JPEG_QUALITY = 70; // 略微降低质量可能有助于网络

    // --- SharedPreferences Keys ---
    private static final String PREFS_NAME = "CameraServicePrefs";
    private static final String KEY_IP_ADDRESS = "last_ip_address";
    private static final String KEY_RESTART_SERVICE = "restart_service_flag";
    private static final String KEY_RETRY_COUNT = "retry_count"; // 新增：重试计数键

    // --- Retry Logic ---
    private static final int MAX_RETRIES = 3; // 最大重试次数
    private static final long RETRY_DELAY_MS = 1000; // 重试延迟时间 (2秒)
    public static final String ACTION_SHOW_RETRY_FAILURE_DIALOG = "com.example.myapplication.ACTION_SHOW_RETRY_FAILURE_DIALOG"; // 新增：通知MainActivity失败的Action

    private String ipAddress;
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final Map<Integer, OutputStream> outputStreams = new ConcurrentHashMap<>();
    private final Map<Integer, CameraDevice> cameraDevices = new ConcurrentHashMap<>();
    private final Map<Integer, CameraCaptureSession> cameraCaptureSessions = new ConcurrentHashMap<>();
    private final Map<Integer, ImageReader> imageReaders = new ConcurrentHashMap<>();
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Handler mainHandler; // Handler for main thread tasks (delay, toast)
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

    // --- Service Lifecycle Methods ---

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "服务 onCreate");
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper()); // 初始化主线程 Handler
        startForegroundServiceNotification();
        startBackgroundThread();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "无法获取 CameraManager！服务可能无法工作。");
            showToast("无法访问相机管理器");
            // 不立即停止，让 onStartCommand 处理
        }
        connectionExecutor = Executors.newFixedThreadPool(2);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "服务 onStartCommand, flags=" + flags + ", startId=" + startId);

        String receivedIp = null;
        // 判断是否是重启或重试的意图
        boolean isRestartAttempt = (flags & START_FLAG_RETRY) != 0
                || (flags & START_FLAG_REDELIVERY) != 0
                || "RestartCameraService".equals(intent != null ? intent.getAction() : null);

        if (intent != null && intent.hasExtra("IP_ADDRESS")) {
            receivedIp = intent.getStringExtra("IP_ADDRESS");
            Log.d(TAG, "从 Intent 接收到 IP: " + receivedIp);
        } else {
            Log.w(TAG, "服务启动时 Intent 为 null 或无 IP (可能是系统重启或重试广播)");
            // isRestartAttempt 已经判断了这种情况
        }

        // 确定要使用的 IP 地址
        if (!TextUtils.isEmpty(receivedIp)) {
            this.ipAddress = receivedIp;
            // 如果是从 Intent 明确收到的 IP，保存它
            saveIpAddress(this.ipAddress);
            Log.i(TAG, "使用来自 Intent 的 IP 地址: " + this.ipAddress + "，并已保存。");
        } else if (isRestartAttempt) {
            // 如果是重启/重试，且 Intent 中没有 IP，则从 SharedPreferences 加载
            this.ipAddress = sharedPreferences.getString(KEY_IP_ADDRESS, null);
            if (!TextUtils.isEmpty(this.ipAddress)) {
                Log.i(TAG, "服务重启/重试，从 SharedPreferences 加载 IP 地址: " + this.ipAddress);
            } else {
                // 重启/重试时连 SharedPreferences 里的 IP 都没有，无法继续
                Log.e(TAG, "服务重启/重试，但无法从 SharedPreferences 获取有效的 IP 地址。停止。");
                showToast("服务启动失败：缺少 IP 地址");
                // 重置重试计数并停止
                resetRetryCount();
                stopSelfSafely();
                return START_NOT_STICKY; // 避免无效重启循环
            }
        } else {
            // 既不是重启/重试，Intent 里也没有 IP (正常启动流程出错)
            Log.e(TAG, "服务启动时缺少有效的 IP 地址。停止。");
            showToast("服务启动失败：无效的 IP 地址");
            resetRetryCount();
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        // --- 成功获取 IP 并决定继续 ---
        // 只要服务尝试启动（无论是首次启动还是重试），并且成功获得了 IP 地址准备连接，
        // 就应该重置重试计数。表示这次尝试是一个新的开始。
        Log.d(TAG, "成功获取 IP 并准备启动连接，重置重试计数。");
        resetRetryCount();

        // 检查是否已有活动流，避免重复启动连接
        // 使用 compareAndSet 确保原子性地检查和设置
        if (activeStreamCount.compareAndSet(0, 2)) { // 如果当前是0，则设置为2（代表两个流）并返回true
            Log.i(TAG,"activeStreamCount 为 0，开始连接和打开相机...");
            connectAndOpenCamerasAsync();
        } else {
            Log.w(TAG, "服务已在运行中 (activeStreamCount=" + activeStreamCount.get() + ")，忽略新的启动请求。");
            // 如果服务已在运行，我们认为这次“启动”意图已经满足，所以之前的重置计数仍然有效。
            // 可以选择性地检查IP是否真的变了，如果变了，可能需要先调用 shutdownAndCleanup() 再启动。
            // 为简单起见，这里忽略此复杂情况。
        }

        // 使用 START_STICKY，这样如果服务被系统杀死，系统会尝试重新启动它（会再次进入 onStartCommand）
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        Log.w(TAG, "服务 onDestroy 开始...");
        // 首先执行清理 (关闭现有连接、相机、线程池、后台线程等)
        shutdownAndCleanup();

        // 清理完成后，检查是否需要安排重试
        boolean shouldRestart = sharedPreferences.getBoolean(KEY_RESTART_SERVICE, false); // 默认不重启

        if (shouldRestart) {
            int currentRetryCount = sharedPreferences.getInt(KEY_RETRY_COUNT, 0);
            Log.d(TAG, "检查重启逻辑：shouldRestart=true, currentRetryCount=" + currentRetryCount);

            if (currentRetryCount < MAX_RETRIES) {
                // --- 还有重试机会 ---
                final int nextRetryAttempt = currentRetryCount + 1;
                // 更新 SharedPreferences 中的重试次数
                sharedPreferences.edit().putInt(KEY_RETRY_COUNT, nextRetryAttempt).apply();
                Log.i(TAG, "服务停止，准备安排重试 (尝试 " + nextRetryAttempt + "/" + MAX_RETRIES + ")，延迟 " + RETRY_DELAY_MS + "ms...");

                // 使用主线程 Handler 延迟发送广播
                // 这是因为我们需要一个延迟，而 onDestroy 本身完成后服务就没了，无法自己延迟
                mainHandler.postDelayed(() -> {
                    // 延迟任务执行时，再次确认是否真的需要发送（例如用户可能手动停止了）
                    // 这里简化处理：直接发送。如果需要更精确控制，可以在 postDelayed 前检查标记
                    Log.i(TAG, "执行延迟重试，发送 RestartCameraService 广播 (尝试 " + nextRetryAttempt + ")");
                    Intent broadcastIntent = new Intent("RestartCameraService");
                    // IP 地址会由 RestartServiceReceiver 从 SharedPreferences 读取
                    sendBroadcast(broadcastIntent);
                }, RETRY_DELAY_MS);

                // 在主线程显示 Toast 提示用户
                final String retryMsg = "连接中断，将在 " + (RETRY_DELAY_MS / 1000) + " 秒后重试 (" + nextRetryAttempt + "/" + MAX_RETRIES + ")...";
                showToast(retryMsg);

            } else {
                // --- 重试次数已用尽 ---
                Log.e(TAG, "已达到最大重试次数 (" + MAX_RETRIES + ")，放弃自动重启。");
                // 重置计数器，以便下次用户手动启动时可以重新开始计数
                resetRetryCount();

                // 发送广播通知 MainActivity 显示失败弹窗
                Log.i(TAG, "发送广播通知 MainActivity 重试失败: " + ACTION_SHOW_RETRY_FAILURE_DIALOG);
                sendBroadcast(new Intent(ACTION_SHOW_RETRY_FAILURE_DIALOG));

                // 显示最终失败的 Toast
                showToast("自动重连失败，请检查网络或服务器后手动连接");
            }
        } else {
            Log.i(TAG, "服务已停止，且未设置重启标志。");
            // 如果不自动重启，也要确保重试计数被重置
            resetRetryCount();
        }

        // 调用父类的 onDestroy，处理系统资源释放等
        super.onDestroy();
        // 确保 mainHandler 上的任务被清理（虽然 shutdownAndCleanup 中已做）
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        Log.i(TAG, "服务 onDestroy 完成。");
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
     * 异步连接 Socket 并尝试打开相机。
     */
    private void connectAndOpenCamerasAsync() {
        // 确保执行器是活动的
        if (connectionExecutor == null || connectionExecutor.isShutdown()) {
            // 如果之前的执行器已关闭，创建一个新的
            connectionExecutor = Executors.newFixedThreadPool(2);
        }

        // 确保 IP 地址有效
        if (TextUtils.isEmpty(ipAddress)) {
            Log.e(TAG, "IP 地址为空，无法启动连接！");
            activeStreamCount.set(0); // 重置计数
            stopSelfSafely(); // 触发停止流程
            return;
        }

        Log.i(TAG, "提交连接任务到线程池，目标 IP: " + ipAddress);
        // 为后置和前置相机提交连接任务
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_BACK, BACK_CAMERA_PORT));
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_FRONT, FRONT_CAMERA_PORT));
    }


    /**
     * 尝试连接 Socket，如果成功，则尝试在后台线程打开对应的相机。
     * 这是可能失败并最终触发重试的关键步骤之一。
     */
    private void connectSocketAndTryOpen(int cameraFacing, int port) {
        String facingStr = getFacingString(cameraFacing);
        Socket socket = null;
        OutputStream outputStream = null;
        // 获取当前服务实例要使用的 IP 地址
        String currentIp = this.ipAddress;

        // 再次检查 IP 是否有效
        if (TextUtils.isEmpty(currentIp)) {
            Log.e(TAG, "connectSocketAndTryOpen: IP 地址为空 (" + facingStr + ")，取消连接。");
            // 连接前 IP 就无效，减少计数，可能触发停止
            decrementActiveStreamCountAndCheckStop();
            return;
        }

        try {
            Log.i(TAG, "尝试连接 " + facingStr + " 相机 Socket 到 " + currentIp + ":" + port);
            socket = new Socket();
            // 连接，使用定义的超时时间
            socket.connect(new InetSocketAddress(currentIp, port), SOCKET_CONNECT_TIMEOUT_MS);
            // 设置读写超时和 KeepAlive
            socket.setSoTimeout(10000); // 10 秒读写超时
            socket.setKeepAlive(true); // 启用 TCP Keep-Alive

            outputStream = socket.getOutputStream();

            // --- 连接成功 ---
            // 将成功的 Socket 和流存入 Map
            sockets.put(cameraFacing, socket);
            outputStreams.put(cameraFacing, outputStream);
            Log.i(TAG, facingStr + " 相机 Socket 连接成功。");
            // 连接成功不代表最终服务运行成功，先不 Toast

            // --- 在后台线程尝试打开相机 ---
            if (backgroundHandler != null) {
                final Socket finalSocket = socket; // 在 lambda 中使用 final 变量
                backgroundHandler.post(() -> tryOpenCameraAfterConnect(cameraFacing, finalSocket));
            } else {
                Log.e(TAG, "后台 Handler 为空，无法启动相机打开流程 (" + facingStr + ")！");
                // 后台 Handler 无效，无法继续，关闭刚建立的连接并减少计数
                decrementActiveStreamCountAndCheckStop();
                closeSocket(cameraFacing); // 关闭 socket 和流
            }

        } catch (IOException e) {
            // --- 连接失败 ---
            Log.e(TAG, "连接 " + facingStr + " 相机 Socket 失败 (" + currentIp + ":" + port + "): " + e.getMessage());
            // 连接失败是触发 onDestroy->retry 的主要原因之一
            // 这里只需要减少活动流计数，如果两个流都连接失败，计数降为0会触发 stopSelf->onDestroy
            decrementActiveStreamCountAndCheckStop();
            // 确保关闭可能部分创建的 socket
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
            // 不在此处显示失败 Toast，让重试逻辑在 onDestroy 中统一处理提示

        } catch (Exception e) {
            // 其他意外错误
            Log.e(TAG, "连接或启动相机时发生意外错误 (" + facingStr + "): " + e.getMessage(), e);
            decrementActiveStreamCountAndCheckStop();
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        }
    }

    /**
     * 在 Socket 连接成功后，尝试打开对应的相机。此方法应在 backgroundHandler 上运行。
     */
    private void tryOpenCameraAfterConnect(int cameraFacing, Socket connectedSocket) {
        String facingStr = getFacingString(cameraFacing);
        // 增加日志，检查 Socket 状态
        Log.d(TAG, "准备打开 " + facingStr + " 相机 (关联 Socket: " + (connectedSocket != null && connectedSocket.isConnected() ? connectedSocket.getRemoteSocketAddress() : "已关闭/null") + ")");

        // --- 检查 Socket 状态 ---
        // 在尝试打开相机前，再次确认 Socket 是否仍然有效且是我们期望的那个
        Socket currentSocketInMap = sockets.get(cameraFacing);
        if (currentSocketInMap == null || currentSocketInMap != connectedSocket || !connectedSocket.isConnected() || connectedSocket.isClosed()) {
            Log.w(TAG, facingStr + " 相机的 Socket 在打开相机操作开始前已无效或改变。取消打开。");
            // Socket 无效，之前的关闭操作应该已经处理了计数器，这里直接返回
            return;
        }

        // --- 检查 CameraManager ---
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager 不可用，无法打开 " + facingStr + " 相机。");
            showToast("相机管理器错误");
            closeCameraStream(cameraFacing); // 关闭此流相关的资源（包括Socket）
            decrementActiveStreamCountAndCheckStop(); // 触发检查是否需要停止服务
            return;
        }

        // --- 查找 Camera ID ---
        String cameraId = getCameraIdForFacing(cameraFacing);
        if (cameraId == null) {
            Log.e(TAG, "未找到 " + facingStr + " 相机的 ID。");
            showToast("未找到 " + facingStr + " 相机");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            return;
        }

        // --- 检查权限 ---
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有相机权限，无法打开 " + facingStr + " 相机。");
            showToast("缺少相机权限");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            return;
        }

        // --- 执行打开相机 ---
        try {
            Log.i(TAG, "正在打开 " + facingStr + " 相机 (ID: " + cameraId + ")");
            // 调用 openCameraForFacing，内部会处理异常并可能触发关闭和计数减少
            openCameraForFacing(cameraId, cameraFacing);
        } catch (CameraAccessException e) {
            Log.e(TAG, "访问相机时出错 (" + facingStr + "): " + e.getMessage());
            showToast("相机访问出错 (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } catch (IllegalStateException e) {
            // 例如相机正在使用中
            Log.e(TAG, "打开相机时状态错误 (" + facingStr + "): " + e.getMessage());
            showToast("相机状态错误 (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } catch (Exception e) {
            // 其他意外错误
            Log.e(TAG, "打开相机时发生意外错误 (" + facingStr + "): " + e.getMessage(), e);
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
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
            // Socket 无效，计数器应已被处理，直接返回
            return; // 直接返回，不抛出异常
        }

        // 获取相机特性
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            Log.e(TAG, "无法获取 " + facingStr + " 相机的流配置。");
            showToast("无法获取相机配置 (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            return; // 返回而不是抛出异常
        }

        // 检查是否支持 JPEG 输出
        Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (outputSizes == null || outputSizes.length == 0) {
            Log.e(TAG, facingStr + " 相机不支持 JPEG 输出。");
            showToast("相机不支持JPEG (" + facingStr + ")");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            return;
        }

        // 选择合适的预览尺寸 (640x480 或最接近的)
        Size targetSize = new Size(640, 480);
        // 使用 Comparator 选择最接近目标尺寸的可用尺寸
        Size selectedSize = Collections.min(Arrays.asList(outputSizes),
                Comparator.comparingLong((Size s) -> Math.abs((long)s.getWidth() * s.getHeight() - (long)targetSize.getWidth() * targetSize.getHeight()))
                        .thenComparingInt((Size s) -> Math.abs(s.getWidth() - targetSize.getWidth())) // 面积优先，宽度其次
        );
        previewSizes.put(cameraFacing, selectedSize);
        Log.i(TAG, "为 " + facingStr + " 相机选择的预览尺寸: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());

        // 关闭旧的 ImageReader (如果存在) - 确保资源被正确替换
        closeReader(cameraFacing);

        // 创建新的 ImageReader
        ImageReader imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.JPEG, IMAGE_BUFFER_SIZE);
        imageReaders.put(cameraFacing, imageReader); // 存入新的 Reader

        // --- 设置 ImageAvailable Listener ---
        // 当有新的图像帧可用时，会调用此 Listener
        final int currentFacing = cameraFacing; // 在 lambda 中使用 final 变量
        imageReader.setOnImageAvailableListener(reader -> {
            Size currentSize = previewSizes.get(currentFacing); // 获取当前相机流的尺寸
            if (currentSize != null) {
                // 处理图像，内部会检查 Socket 状态并发送
                processImageAvailable(reader, currentFacing, currentSize);
            } else {
                // 如果尺寸信息丢失（理论上不应发生），记录错误并尝试后备方案
                Log.e(TAG, "处理图像时无法获取 " + getFacingString(currentFacing) + " 的预览尺寸!");
                try (Image img = reader.acquireLatestImage()) { // 使用 try-with-resources 确保关闭
                    if (img != null) {
                        // 尝试从 Image 对象获取尺寸
                        Size fallbackSize = new Size(img.getWidth(), img.getHeight());
                        processImageAvailable(reader, currentFacing, fallbackSize);
                    }
                } catch (IllegalStateException e) {
                    // ImageReader 可能已关闭
                    Log.w(TAG, "处理图像时 ImageReader 状态异常 (" + getFacingString(currentFacing) + "): " + e.getMessage());
                    // 状态异常可能意味着流已损坏，尝试关闭
                    if (sockets.containsKey(currentFacing)) {
                        closeCameraStream(currentFacing);
                        decrementActiveStreamCountAndCheckStop();
                    }
                }
            }
        }, backgroundHandler); // 指定在后台线程处理


        // 再次检查权限（理论上不需要，但保持防御性编程）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "打开相机时权限丢失 (" + facingStr + ")！");
            showToast("相机权限丢失");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            return; // 返回，不抛出异常
        }

        Log.d(TAG, "正在调用 cameraManager.openCamera 打开 " + facingStr + " 相机设备 (ID: " + cameraId + ")");
        // 调用 openCamera，这是一个异步操作，结果会在 StateCallback 中回调
        // 需要捕获 SecurityException
        try {
            cameraManager.openCamera(cameraId, getCameraStateCallback(cameraFacing), backgroundHandler);
        } catch (SecurityException se) {
            // 通常发生在权限在检查后被用户撤销的情况
            Log.e(TAG, "打开相机时发生 SecurityException (" + facingStr + ")！权限可能在检查后被撤销。", se);
            showToast("相机权限错误 (Security)");
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
            // SecurityException 不会由 throws 声明捕获，需单独 catch
        }
        // CameraAccessException 和 IllegalStateException 会被方法签名抛出，由调用者 catch
    }

    /**
     * 处理 ImageReader 的 onImageAvailable 事件。
     * 从 Reader 获取最新图像，处理（旋转、压缩），并通过 Socket 发送。
     */
    private void processImageAvailable(ImageReader reader, int cameraFacing, Size previewSize) {
        try (Image image = reader.acquireNextImage()) { // 使用 try-with-resources 自动关闭 image
            if (image != null) {
                // 可以在这里添加帧率控制逻辑，例如记录上一帧时间戳，如果间隔太短则丢弃

                // 获取图像数据
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                // 在调用 processAndSendFrame 之前，再次确认 Socket 是否仍然连接
                Socket currentSocket = sockets.get(cameraFacing);
                if (currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed()) {
                    // Socket 有效，处理并发送帧
                    processAndSendFrame(bytes, cameraFacing, previewSize.getWidth(), previewSize.getHeight());
                } else {
                    // Socket 已断开，不再处理和发送帧
                    Log.w(TAG, "Socket for " + getFacingString(cameraFacing) + " is disconnected, skipping frame processing.");
                    // 如果 Socket 已断开，需要确保关闭流程被触发（如果尚未触发）
                    if (sockets.containsKey(cameraFacing)) { // 检查是否还需要关闭（避免重复关闭）
                        closeCameraStream(cameraFacing);
                        decrementActiveStreamCountAndCheckStop(); // 触发检查
                    }
                }
            }
            // image 会在 try 块结束时自动关闭
        } catch (IllegalStateException e) {
            // Reader 可能已经关闭 (例如在关闭流程中)
            Log.w(TAG, "处理图像时 Reader 状态错误 (" + getFacingString(cameraFacing) + "): " + e.getMessage());
            // 状态错误通常意味着流不再可用，尝试关闭
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        } catch (Exception e) {
            // 其他未知错误
            Log.e(TAG, "处理图像时发生意外错误 (" + getFacingString(cameraFacing) + ")", e);
            // 发生意外错误，关闭此流
            if (sockets.containsKey(cameraFacing)) {
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        }
    }

    @Nullable
    private String getCameraIdForFacing(int cameraFacing) {
        if (cameraManager == null) return null;
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == cameraFacing) {
                    return cameraId; // 找到匹配的相机 ID
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "查找 Camera ID 时出错: " + e.getMessage());
        }
        return null; // 未找到
    }

    /**
     * 创建相机状态回调。
     * 处理相机打开成功、断开连接或发生错误的情况。
     */
    private CameraDevice.StateCallback getCameraStateCallback(final int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.i(TAG, facingStr + " 相机成功打开 (ID: " + camera.getId() + ")");
                // 检查是否是预期的设备，并存储
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

                // 相机打开成功后，创建预览会话
                // 确保创建会话也在后台线程
                if (backgroundHandler != null) {
                    backgroundHandler.post(() -> createCameraPreviewSession(cameraFacing));
                } else {
                    Log.e(TAG, "后台 Handler 为空，无法创建 " + facingStr + " 预览会话！");
                    // 无法创建会话，关闭刚打开的相机及相关资源
                    closeCameraStream(cameraFacing);
                    decrementActiveStreamCountAndCheckStop(); // 触发检查
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                // 相机设备断开连接，这是一个需要触发重试的错误场景
                Log.w(TAG, facingStr + " 相机已断开连接 (ID: " + camera.getId() + ")");
                // 清理与此 cameraFacing 相关的所有资源
                if (cameraDevices.get(cameraFacing) == camera) { // 确保是我们追踪的那个设备
                    closeCameraStream(cameraFacing); // 关闭相机、会话、Reader、Socket
                    decrementActiveStreamCountAndCheckStop(); // 减少计数并检查是否需要停止服务->触发onDestroy->重试
                    showToast(facingStr + " 相机连接断开"); // 提示用户
                } else {
                    // 回调的设备不是我们记录的设备，可能已经清理过了
                    Log.w(TAG, facingStr + "相机 onDisconnected 回调，但设备不匹配或已移除？");
                    // 尝试关闭这个未追踪的设备，以防万一
                    try { camera.close(); } catch (Exception e) { /* ignore */ }
                }
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                // 相机发生错误，这也是一个需要触发重试的错误场景
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
     * 将相机预览数据输出到 ImageReader 的 Surface。
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
                closeCameraStream(cameraFacing); // 确保关闭关联资源
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
            // 创建预览请求 Builder
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // 将 ImageReader 的 Surface 作为目标
            captureRequestBuilder.addTarget(surface);

            // 设置自动对焦和自动曝光模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); // 连续自动对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH); // 自动曝光，需要时自动闪光

            Log.d(TAG, "正在为 " + facingStr + " 创建 CaptureSession...");
            // 创建输出 Surface 列表
            List<Surface> outputs = Collections.singletonList(surface);

            // 创建 CaptureSession，结果在 StateCallback 回调
            cameraDevice.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            // 会话配置成功
                            // 再次检查相机设备和 Socket 是否仍然有效（因为这是异步回调）
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
                                // Socket 断开，需要关闭流并触发检查
                                if (sockets.containsKey(cameraFacing)){ // 再次检查避免重复关闭
                                    closeCameraStream(cameraFacing);
                                    decrementActiveStreamCountAndCheckStop();
                                }
                                return;
                            }

                            // --- 会话配置成功 ---
                            Log.i(TAG, facingStr + " 预览会话配置成功。");
                            // 保存会话实例
                            cameraCaptureSessions.put(cameraFacing, session);
                            try {
                                // 启动重复请求以开始预览（将帧数据持续发送到 ImageReader）
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE); // 确保AE稳定
                                CaptureRequest previewRequest = captureRequestBuilder.build();
                                // 设置重复请求
                                session.setRepeatingRequest(previewRequest, null, backgroundHandler);
                                Log.i(TAG, facingStr + " 相机预览已启动。");
                                // 预览成功启动，可以认为这个流的启动过程是成功的
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
                            // 会话配置失败
                            Log.e(TAG, "配置 " + facingStr + " 相机预览会话失败。");
                            // 配置失败，关闭这个流
                            closeCameraStream(cameraFacing);
                            decrementActiveStreamCountAndCheckStop();
                        }
                    }, backgroundHandler // 指定回调在后台线程执行
            );
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) { // 捕获更多可能的异常
            Log.e(TAG, "创建 " + facingStr + " 预览会话时准备请求出错: ", e);
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        }
    }

    /**
     * 处理原始 JPEG 数据，进行必要的旋转，然后压缩并通过 Socket 发送。
     */
    private void processAndSendFrame(byte[] jpegBytes, int cameraFacing, int width, int height) {
        // --- 在处理前检查 Socket ---
        Socket currentSocket = sockets.get(cameraFacing);
        if (currentSocket == null || !currentSocket.isConnected() || currentSocket.isClosed()) {
            // Log.v(TAG, "Socket for " + getFacingString(cameraFacing) + " is unavailable, skipping frame send.");
            // 如果 Socket 无效，我们不需要处理图像。确保关闭流程被触发。
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
            // 1. 解码 JPEG 数据为 Bitmap
            // 使用 BitmapFactory.Options 优化内存使用
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565; // 使用 16 位色，减少内存占用
            // options.inSampleSize = 2; // 可以通过降低采样率进一步减少内存，但会降低分辨率

            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
            if (bitmap == null) {
                Log.w(TAG, "解码 JPEG 失败 (" + getFacingString(cameraFacing) + ")");
                return; // 解码失败，无法继续
            }

            // 2. 计算旋转角度并应用 Matrix 变换
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

            // 简化旋转逻辑：假设手机竖屏状态下预览正常所需旋转角度
            // 注意：更完善的方案需要结合当前设备屏幕方向
            if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                // 前置摄像头通常是镜像的，且传感器方向可能不同
                rotation = (sensorOrientation) % 360; // 经验值，可能需要调整
                flip = true; // 前置需要水平翻转
            } else {
                // 后置摄像头
                rotation = (sensorOrientation) % 360; // 经验值
            }

            // 应用旋转
            matrix.postRotate(rotation);
            // 如果需要翻转（通常是前置）
            if (flip) {
                matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f); // 水平翻转
            }

            // 创建旋转/翻转后的 Bitmap
            rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            // 3. 将处理后的 Bitmap 压缩回 JPEG
            byteArrayOutputStream = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream);
            byte[] rotatedBytes = byteArrayOutputStream.toByteArray();

            // 4. 发送数据
            sendFrameData(rotatedBytes, cameraFacing);

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "处理帧时内存不足 (" + getFacingString(cameraFacing) + ")", oom);
            // 尝试强制 GC，但不一定有效
            System.gc();
            // 发生 OOM，关闭此流以防止持续错误
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } catch (Exception e) {
            Log.e(TAG, "处理或发送帧时出错 (" + getFacingString(cameraFacing) + ")", e);
            // 发生其他错误，关闭此流
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } finally {
            // 5. 及时回收 Bitmap 释放内存
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (rotatedBitmap != null && !rotatedBitmap.isRecycled()) rotatedBitmap.recycle();
            if (byteArrayOutputStream != null) {
                try { byteArrayOutputStream.close(); } catch (IOException e) { /* ignore */ }
            }
        }
    }


    /**
     * 发送带长度前缀的帧数据。
     * 这是可能因为网络问题失败并触发重试的另一个关键点。
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
                decrementActiveStreamCountAndCheckStop(); // 触发检查->stopSelf->onDestroy->重试
            } catch (Exception e) {
                // 其他潜在错误
                Log.e(TAG, "发送帧时未知错误 (" + getFacingString(cameraFacing) + "): " + e.getMessage(), e);
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
     * 这是触发服务停止 -> onDestroy -> 重试逻辑的关键。
     */
    private void decrementActiveStreamCountAndCheckStop() {
        // 原子递减并获取新值
        int remaining = activeStreamCount.decrementAndGet();
        Log.d(TAG, "活动流计数减少，剩余: " + remaining);
        // 只有当计数 <= 0 时才尝试停止服务
        if (remaining <= 0) {
            // 使用 compareAndSet 确保只有一个线程能成功将计数从 <=0 设置为 0 并触发停止
            // 这可以防止因并发问题导致多次调用 stopSelfSafely
            if (activeStreamCount.compareAndSet(remaining, 0)) { // 尝试将负数或0设为0
                Log.i(TAG, "所有活动流已停止或失败 (计数达到 " + remaining + ")，正在请求停止服务...");
                stopSelfSafely(); // 调用安全停止方法，这将最终触发 onDestroy
            } else {
                // 如果 compareAndSet 失败，说明 activeStreamCount 在我们检查和设置之间又变了
                // （理论上不太可能，因为 decrementAndGet 是原子的）
                // 或者已经被其他线程设置为 0 并触发了 stopSelf。
                Log.d(TAG, "decrementActiveStreamCountAndCheckStop: compareAndSet 失败，可能已被停止。当前计数: " + activeStreamCount.get());
                // 理论上不需要再次调用 stopSelfSafely
            }
        }
    }


    /**
     * 安全地停止服务，确保在主线程调用 stopSelf()。
     */
    private void stopSelfSafely() {
        Log.d(TAG, "stopSelfSafely() 被调用");
        // 确保 stopSelf() 在主线程执行，以符合 Service 生命周期要求
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, "当前在主线程，直接调用 stopSelf()");
            try {
                // stopForeground 移动到 shutdownAndCleanup 中，在 onDestroy 之前执行
                stopSelf(); // 触发 onDestroy
            } catch (Exception e) {
                Log.e(TAG,"调用 stopSelf() 时发生异常", e);
            }
        } else {
            Log.d(TAG, "当前不在主线程，post stopSelf() 到主线程 Handler");
            // 使用 mainHandler 将 stopSelf() post 到主线程队列
            if(mainHandler != null) {
                mainHandler.post(() -> {
                    Log.d(TAG, "主线程 Handler 执行 stopSelf()");
                    try {
                        // stopForeground 移动到 shutdownAndCleanup
                        stopSelf(); // 触发 onDestroy
                    } catch (Exception e) {
                        Log.e(TAG,"在主线程 Handler 中调用 stopSelf() 时发生异常", e);
                    }
                });
            } else {
                Log.e(TAG,"无法 post stopSelf()，mainHandler 为 null！");
                // 极端情况，尝试直接调用 stopSelf()，但不推荐
                // stopSelf();
            }
        }
        // 注意：调用 stopSelf() 后，onDestroy() 会被异步调用，不是立即执行。
    }

    /**
     * 关闭指定朝向的相机流相关的所有资源（相机、会话、Reader、Socket、流）。
     * 这是一个核心的清理方法，用于单个流的关闭。
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
        // 从 Map 中移除并获取会话实例
        CameraCaptureSession session = cameraCaptureSessions.remove(cameraFacing);
        if (session != null) {
            String facingStr = getFacingString(cameraFacing);
            Log.d(TAG, "正在关闭 " + facingStr + " 会话...");
            try {
                session.close(); // 关闭会话
                Log.d(TAG, facingStr + " 会话已关闭。");
            } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " 会话时出错", e); }
        }
    }

    /** 关闭指定朝向的相机设备 */
    private void closeCameraDevice(int cameraFacing) {
        // 从 Map 中移除并获取设备实例
        CameraDevice device = cameraDevices.remove(cameraFacing);
        if (device != null) {
            String facingStr = getFacingString(cameraFacing);
            Log.d(TAG, "正在关闭 " + facingStr + " 设备 (ID: " + device.getId() + ")...");
            try {
                device.close(); // 关闭相机设备
                Log.d(TAG, facingStr + " 设备已关闭。");
            } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " 设备时出错", e); }
        }
    }


    /** 关闭指定朝向的 ImageReader */
    private void closeReader(int cameraFacing) {
        // 从 Map 中移除并获取 Reader 实例
        ImageReader reader = imageReaders.remove(cameraFacing);
        if (reader != null) {
            String facingStr = getFacingString(cameraFacing);
            Log.d(TAG, "正在关闭 " + facingStr + " ImageReader...");
            try {
                reader.close(); // 关闭 ImageReader
                Log.d(TAG, facingStr + " ImageReader 已关闭。");
            } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " Reader 时出错", e); }
        }
    }

    /** 关闭指定朝向的 Socket 和输出流 */
    private void closeSocket(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        // 1. 先关闭输出流 (从 Map 中移除并获取)
        OutputStream os = outputStreams.remove(cameraFacing);
        if (os != null) {
            Log.d(TAG, "正在关闭 " + facingStr + " 输出流...");
            try { os.close(); } catch (IOException e) { /* Log.w(TAG, "关闭 " + facingStr + " 输出流时出错: " + e.getMessage()); */ } // 关闭流的异常通常可以忽略
        }
        // 2. 再关闭 Socket (从 Map 中移除并获取)
        Socket socket = sockets.remove(cameraFacing);
        if (socket != null) {
            Log.d(TAG, "正在关闭 " + facingStr + " Socket (连接到: " + (socket.isClosed() ? "已关闭" : socket.getRemoteSocketAddress()) + ")...");
            if (!socket.isClosed()) {
                try {
                    // socket.shutdownInput(); // 可选，优雅关闭的一部分
                    // socket.shutdownOutput(); // 可选
                    socket.close(); // 关闭 Socket 连接
                    Log.d(TAG, facingStr + " Socket 已关闭。");
                } catch (IOException e) { Log.e(TAG, "关闭 " + facingStr + " Socket 时出错: " + e.getMessage()); }
            } else {
                Log.d(TAG, facingStr + " Socket 已处于关闭状态。");
            }
        }
    }

    /** 停止所有相机流，清理所有相关资源 */
    private void stopAllCameraStreams() {
        Log.i(TAG, "正在停止所有相机流和相关资源...");
        // 创建 keySet 的副本进行迭代，避免 ConcurrentModificationException
        // 或者使用 ConcurrentHashMap 的 keySet()，它是弱一致性的，迭代时移除是安全的
        for (Integer facing : sockets.keySet()) { // 迭代当前存在的 socket 连接
            closeCameraStream(facing); // 关闭每个流的所有资源
        }
        // 再次确保所有 Map 都被清空（以防万一迭代中移除失败）
        cameraCaptureSessions.clear();
        cameraDevices.clear();
        imageReaders.clear();
        outputStreams.clear();
        sockets.clear();
        previewSizes.clear();
        Log.i(TAG, "所有相机相关资源已清理。");
    }


    /**
     * 关闭服务前的最终清理方法。
     * 在 onDestroy() 中被调用，负责停止所有活动、释放资源、停止线程。
     */
    private void shutdownAndCleanup() {
        Log.i(TAG, "开始服务关闭和资源清理 (shutdownAndCleanup)...");

        // 0. 停止 mainHandler 上可能存在的延迟重试任务
        if (mainHandler != null) {
            Log.d(TAG,"移除 mainHandler 上的延迟任务（如果有）");
            mainHandler.removeCallbacksAndMessages(null); // 移除所有回调和消息
        }

        // 1. 停止网络连接线程池 (中断正在进行的连接尝试)
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
                // 重新中断当前线程
                Thread.currentThread().interrupt();
            }
            connectionExecutor = null; // 释放引用
        }

        // 2. 确保所有相机流相关的资源在后台线程关闭（如果后台线程还在运行）
        Handler handler = backgroundHandler; // 获取当前 handler 引用
        HandlerThread thread = backgroundThread; // 获取当前 thread 引用
        if (handler != null && thread != null && thread.isAlive()) {
            Log.d(TAG,"提交 stopAllCameraStreams 到后台线程...");
            // 使用 CountDownLatch 来同步等待后台清理完成
            final CountDownLatch cleanupLatch = new CountDownLatch(1);
            // post 清理任务到后台线程
            handler.post(() -> {
                Log.d(TAG,"后台线程开始执行 stopAllCameraStreams...");
                try {
                    stopAllCameraStreams(); // 在后台线程执行清理
                } finally {
                    Log.d(TAG,"后台线程完成 stopAllCameraStreams。");
                    cleanupLatch.countDown(); // 通知主线程清理完成
                }
            });
            // 等待后台清理任务完成，设置超时时间
            try {
                if (!cleanupLatch.await(1500, TimeUnit.MILLISECONDS)) { // 等待最多 1.5 秒
                    Log.w(TAG, "等待后台清理任务超时！可能存在阻塞操作。");
                } else {
                    Log.d(TAG, "后台清理任务已完成。");
                }
            } catch (InterruptedException e) {
                Log.e(TAG,"等待后台清理任务时中断",e);
                Thread.currentThread().interrupt(); // 重新设置中断状态
            }

        } else {
            Log.w(TAG, "后台线程不可用，直接在当前线程尝试清理相机资源...");
            // 如果后台线程已停止或从未成功启动，直接在当前线程（可能是主线程或binder线程）尝试清理
            stopAllCameraStreams();
        }

        // 3. 停止后台线程（现在清理任务已执行或超时）
        stopBackgroundThread();

        // 4. 停止前台服务状态 (移除通知)
        Log.d(TAG,"正在停止前台服务状态...");
        // 在 onDestroy 调用 super.onDestroy() 之前执行 stopForeground 是安全的
        // 并且应该在所有资源清理之后
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE); // 移除通知
            Log.d(TAG,"前台服务状态已停止。");
        } catch (Exception e) {
            Log.e(TAG,"停止前台服务时出错: " + e.getMessage());
        }

        // 5. 将活动流计数强制设为 0，确保状态干净
        activeStreamCount.set(0);

        Log.i(TAG, "服务资源清理完成 (shutdownAndCleanup)。");
    }


    // 启动后台线程处理相机操作
    private void startBackgroundThread() {
        // 避免重复创建
        if (backgroundThread == null || !backgroundThread.isAlive()) { // 检查是否已存在且活动
            backgroundThread = new HandlerThread("CameraBackground", android.os.Process.THREAD_PRIORITY_BACKGROUND); // 设置为后台优先级
            backgroundThread.start();
            // 等待 Looper 准备好
            Looper looper = backgroundThread.getLooper(); // Looper 可能需要时间准备好
            if (looper != null) {
                backgroundHandler = new Handler(looper);
                Log.d(TAG, "后台线程已启动并获取 Handler。");
            } else {
                // 如果 Looper 获取失败，这是一个严重问题，服务无法正常工作
                Log.e(TAG,"无法获取后台线程的 Looper！");
                // 停止刚启动的线程
                if (backgroundThread != null) {
                    backgroundThread.quitSafely(); // 尝试安全退出
                    try { backgroundThread.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } // 等待一小会
                }
                backgroundThread = null; // 清理引用
                backgroundHandler = null; // 确保 handler 也置 null
                // 服务无法工作，显示提示并停止
                showToast("后台处理线程启动失败");
                stopSelfSafely(); // 触发服务停止
            }
        } else {
            Log.d(TAG,"后台线程已在运行。");
        }
    }

    // 停止后台线程
    private void stopBackgroundThread() {
        HandlerThread threadToStop = backgroundThread; // 获取当前引用
        if (threadToStop != null) {
            Log.d(TAG, "正在请求停止后台线程 (ID: " + threadToStop.getThreadId() + ")...");
            // 先将 handler 置 null，防止后续任务提交到即将停止的线程
            backgroundHandler = null;
            // 请求安全退出，会处理完当前消息队列中的消息再退出
            threadToStop.quitSafely();
            try {
                // 等待线程结束，设置超时时间
                threadToStop.join(1000); // 等待最多 1 秒
                if (threadToStop.isAlive()) {
                    // 如果超时后线程仍然活动，可能是有阻塞任务
                    Log.w(TAG,"后台线程停止超时，可能存在阻塞任务。尝试中断...");
                    threadToStop.interrupt(); // 尝试中断阻塞
                    threadToStop.join(500); // 再等一小会看是否能停止
                    if(threadToStop.isAlive()) {
                        Log.e(TAG,"后台线程中断后仍然活动！资源可能未完全释放。");
                    } else {
                        Log.d(TAG, "后台线程中断后已停止。");
                    }
                } else {
                    Log.d(TAG, "后台线程已成功停止。");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "停止后台线程时被中断。", e);
                Thread.currentThread().interrupt(); // 重新设置中断状态
            } finally {
                // 确保引用被清理
                // 比较引用，防止在等待期间 backgroundThread 被重新赋值
                if (backgroundThread == threadToStop) {
                    backgroundThread = null;
                }
            }
        } else {
            Log.d(TAG, "后台线程已经是 null，无需停止。");
        }
    }


    // 在主线程显示 Toast
    private void showToast(final String message) {
        // 使用 mainHandler 将 Toast 操作 post 到主线程队列
        if (mainHandler != null) {
            mainHandler.post(() -> {
                try {
                    // 再次检查 Context 是否仍然有效
                    if (getApplicationContext() != null) {
                        Toast.makeText(CameraStreamService.this, message, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "显示 Toast 时出错: " + e.getMessage());
                    // 可能发生在服务快速停止，Context 无效时
                }
            });
        } else {
            // 如果 mainHandler 为 null，无法安全显示 Toast
            Log.e(TAG,"无法显示 Toast: mainHandler is null");
            // 避免直接在非主线程调用 Toast 或 Looper.prepare()
        }
    }

    // 获取相机朝向的字符串表示（用于日志）
    private String getFacingString(int cameraFacing) {
        return (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) ? "后置" : "前置";
    }

    // 将 CameraDevice.StateCallback 的错误代码转换为可读字符串
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
    // 启动前台服务通知
    private void startForegroundServiceNotification() {
        createNotificationChannel(); // 确保通道存在
        // 构建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 替换为你自己的图标
                .setContentTitle("相机流服务")
                .setContentText("正在传输相机画面...")
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级，减少用户干扰
                .setOngoing(true); // 使通知不可滑动消除，表示服务正在运行

        Notification notification = builder.build();
        // 启动前台服务
        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "前台服务通知已启动。");
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务时出错: " + e.getMessage(), e);
            // 处理 Android 版本限制等问题
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                // 检查特定类型的前台服务权限
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE_CAMERA 权限 (Android 14+)");
                } else {
                    showToast("启动前台服务失败: " + e.getMessage());
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Android 9+
                // 检查通用的 FOREGROUND_SERVICE 权限
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                    showToast("启动前台服务失败：缺少 FOREGROUND_SERVICE 权限");
                } else {
                    showToast("启动前台服务失败: " + e.getMessage());
                }
            }
            else {
                showToast("启动前台服务失败: " + e.getMessage());
            }
            // 启动前台服务失败，通常意味着服务无法正常运行，停止服务
            stopSelfSafely();
        }
    }

    // 创建通知通道 (Android 8.0+ 需要)
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "相机流服务通道"; // 通道名称
            String description = "用于相机流服务的后台运行通知"; // 通道描述
            int importance = NotificationManager.IMPORTANCE_LOW; // 低重要性，不会发出声音
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
// --- END OF FILE CameraStreamService.java (包含重试逻辑) ---