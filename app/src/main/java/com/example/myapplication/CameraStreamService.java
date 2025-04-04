// --- START OF FILE CameraStreamService.java (Optimized for Early Camera Open) ---
package com.example.myapplication;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context; // Import Context
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
import androidx.annotation.Nullable; // Import Nullable
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat; // Use ContextCompat for permissions

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger; // For tracking active cameras/sockets

public class CameraStreamService extends Service {

    private static final String TAG = "CameraStreamService";
    private static final String NOTIFICATION_CHANNEL_ID = "CameraStreamChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int BACK_CAMERA_PORT = 12345;
    private static final int FRONT_CAMERA_PORT = 12346;
    private static final long FRAME_DELAY_MS = 100;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 10000;
    private static final int IMAGE_BUFFER_SIZE = 2;
    private static final int JPEG_QUALITY = 75;

    private String ipAddress;
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final Map<Integer, OutputStream> outputStreams = new ConcurrentHashMap<>();
    private final Map<Integer, CameraDevice> cameraDevices = new ConcurrentHashMap<>();
    private final Map<Integer, CameraCaptureSession> cameraCaptureSessions = new ConcurrentHashMap<>();
    private final Map<Integer, ImageReader> imageReaders = new ConcurrentHashMap<>();
    // 原子计数器，跟踪活动（已连接或正在尝试）的流数量
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private CameraManager cameraManager; // Cache CameraManager
    // 注意：这里用 Map 存储每个相机的预览尺寸，因为它们可能不同
    private final Map<Integer, Size> previewSizes = new ConcurrentHashMap<>();

    private final IBinder binder = new LocalBinder();
    private ExecutorService connectionExecutor;

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
        startForegroundServiceNotification();
        startBackgroundThread();
        // 获取 CameraManager 一次
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "无法获取 CameraManager！服务可能无法工作。");
            showToast("无法访问相机管理器");
            // 这里不立即停止，允许网络连接尝试，但相机打开会失败
        }
        connectionExecutor = Executors.newFixedThreadPool(2);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "服务 onStartCommand");
        if (intent != null) {
            ipAddress = intent.getStringExtra("IP_ADDRESS");
            if (ipAddress != null && !ipAddress.isEmpty()) {
                Log.i(TAG, "收到 IP 地址: " + ipAddress);
                // 检查是否已有活动流，避免重复启动连接
                if (activeStreamCount.get() == 0) {
                    connectAndOpenCamerasAsync();
                } else {
                    Log.w(TAG, "服务已在运行中，忽略新的启动命令。");
                }
            } else {
                Log.e(TAG, "IP 地址为空或无效。停止服务。");
                showToast("IP 地址无效，服务停止");
                stopSelfSafely(); // 使用安全停止方法
            }
        } else {
            Log.w(TAG, "服务因 null intent 启动。");
            // START_STICKY 可能会导致这种情况。如果 ipAddress 丢失，则停止。
            if (ipAddress == null || ipAddress.isEmpty()) {
                showToast("服务重启异常，缺少 IP 地址");
                stopSelfSafely();
            } else {
                // 如果 IP 存在，但流已停止，尝试重新连接
                if (activeStreamCount.get() == 0) {
                    Log.i(TAG, "尝试重新连接...");
                    connectAndOpenCamerasAsync();
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "服务 onDestroy");
        shutdownAndCleanup();
        Log.i(TAG, "服务已停止。");
        // Optional: 可以在这里发送广播尝试重启服务，如果需要的话
        // Intent broadcastIntent = new Intent("RestartCameraService");
        // sendBroadcast(broadcastIntent);
        // showToast("正在尝试重新链接");
    }

    // --- Core Logic Methods ---

    private void connectAndOpenCamerasAsync() {
        // 标记有两个流需要尝试启动
        activeStreamCount.set(2); // 重置计数器为目标流数量

        if (connectionExecutor == null || connectionExecutor.isShutdown()) {
            connectionExecutor = Executors.newFixedThreadPool(2);
        }
        // 提交连接任务
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_BACK, BACK_CAMERA_PORT));
        connectionExecutor.submit(() -> connectSocketAndTryOpen(CameraCharacteristics.LENS_FACING_FRONT, FRONT_CAMERA_PORT));

        // **移除**原来的 checkConnectionsAndOpenCamera 延迟检查。
        // 服务的停止将由连接失败、相机打开失败或最后一个活动流关闭来驱动。
    }

    /**
     * 尝试连接 Socket，如果成功，则尝试在后台线程打开对应的相机。
     */
    private void connectSocketAndTryOpen(int cameraFacing, int port) {
        String facingStr = getFacingString(cameraFacing);
        Socket socket = null;
        OutputStream outputStream = null;

        try {
            Log.i(TAG, "尝试连接 " + facingStr + " 相机 Socket 到 " + ipAddress + ":" + port);
            socket = new Socket();
            socket.connect(new InetSocketAddress(ipAddress, port), SOCKET_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(5000); // 读取超时
            socket.setKeepAlive(true);

            outputStream = socket.getOutputStream();

            // --- 连接成功 ---
            sockets.put(cameraFacing, socket);
            outputStreams.put(cameraFacing, outputStream);
            Log.i(TAG, facingStr + " 相机 Socket 连接成功。");
            showToast(facingStr + " Camera Connected");

            // --- 立即尝试打开相机 (在后台线程) ---
            if (backgroundHandler != null) {
                final Socket finalSocket = socket; // 在 lambda 中使用 final 变量
                final OutputStream finalOutputStream = outputStream;
                backgroundHandler.post(() -> tryOpenCameraAfterConnect(cameraFacing, finalSocket, finalOutputStream));
            } else {
                Log.e(TAG, "后台 Handler 为空，无法启动相机打开流程！");
                // 连接成功但无法打开相机，需要减少活动计数
                decrementActiveStreamCountAndCheckStop();
                closeSocket(cameraFacing); // 关闭刚刚建立的连接
            }

        } catch (IOException e) {
            Log.e(TAG, "连接 " + facingStr + " 相机 Socket 失败: " + e.getMessage());
            // e.printStackTrace(); // 可以取消注释以查看完整堆栈
            showToast("连接失败 (" + facingStr + "): " + e.getMessage());
            // 连接失败，减少活动计数并检查是否需要停止服务
            decrementActiveStreamCountAndCheckStop();
            // 确保关闭可能部分创建的 socket
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        } catch (Exception e) { // 捕获其他可能的异常
            Log.e(TAG, "连接或启动相机时发生意外错误 (" + facingStr + "): " + e.getMessage());
            decrementActiveStreamCountAndCheckStop();
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ioException) { /* ignore */ }
            }
        }
    }

    /**
     * 在 Socket 连接成功后，尝试打开对应的相机。此方法应在 backgroundHandler 上运行。
     */
    private void tryOpenCameraAfterConnect(int cameraFacing, Socket connectedSocket, OutputStream connectedStream) {
        String facingStr = getFacingString(cameraFacing);
        Log.d(TAG, "准备打开 " + facingStr + " 相机...");

        // 确保 CameraManager 可用
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager 不可用，无法打开 " + facingStr + " 相机。");
            showToast("相机管理器错误");
            decrementActiveStreamCountAndCheckStop();
            closeSpecificSocket(connectedSocket, connectedStream); // 关闭对应的 socket
            return;
        }

        // 查找 Camera ID
        String cameraId = getCameraIdForFacing(cameraFacing);
        if (cameraId == null) {
            Log.e(TAG, "未找到 " + facingStr + " 相机的 ID。");
            showToast("未找到 " + facingStr + " 相机");
            decrementActiveStreamCountAndCheckStop();
            closeSpecificSocket(connectedSocket, connectedStream);
            return;
        }

        // 检查权限 (非常重要)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有相机权限，无法打开 " + facingStr + " 相机。");
            showToast("缺少相机权限");
            decrementActiveStreamCountAndCheckStop();
            closeSpecificSocket(connectedSocket, connectedStream);
            // 考虑发送广播或通知用户需要权限
            return;
        }

        // --- 执行打开相机 ---
        try {
            Log.i(TAG, "正在打开 " + facingStr + " 相机 (ID: " + cameraId + ")");
            // openCameraForFacing 现在需要确保在调用 openCamera 前检查 Socket 是否仍然有效
            openCameraForFacing(cameraId, cameraFacing, connectedSocket);
        } catch (CameraAccessException e) {
            Log.e(TAG, "访问相机时出错 (" + facingStr + "): " + e.getMessage());
            showToast("相机访问出错 (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            closeSpecificSocket(connectedSocket, connectedStream);
        } catch (Exception e) { // 捕获其他可能的异常
            Log.e(TAG, "打开相机时发生意外错误 (" + facingStr + "): " + e.getMessage());
            decrementActiveStreamCountAndCheckStop();
            closeSpecificSocket(connectedSocket, connectedStream);
        }
    }


    /**
     * 打开指定 ID 和朝向的相机。此方法必须在 backgroundHandler 上运行。
     * 添加 socket 参数以在打开前检查连接状态。
     */
    private void openCameraForFacing(String cameraId, int cameraFacing, Socket associatedSocket) throws CameraAccessException {
        String facingStr = getFacingString(cameraFacing);

        // 在打开相机前，再次检查 Socket 是否仍然连接且未关闭
        if (associatedSocket == null || !associatedSocket.isConnected() || associatedSocket.isClosed()) {
            Log.w(TAG, facingStr + " 相机的 Socket 在打开相机前已关闭或无效。取消打开操作。");
            // 无需减少计数，因为之前在 socket 关闭时已经处理过
            return; // 直接返回，不抛出异常，因为这不是相机本身的错误
        }

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            Log.e(TAG, "无法获取 " + facingStr + " 相机的流配置。");
            showToast("无法获取相机配置 (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing); // 关闭对应的 socket
            return; // 这里需要 return，避免继续执行
        }

        Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (outputSizes == null || outputSizes.length == 0) {
            Log.e(TAG, facingStr + " 相机不支持 JPEG 输出。");
            showToast("相机不支持JPEG (" + facingStr + ")");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
            return;
        }

        // 选择预览尺寸
        Size targetSize = new Size(640, 480); // 可以调整目标尺寸
        Size selectedSize = Collections.min(Arrays.asList(outputSizes), (s1, s2) ->
                Long.compare(Math.abs(s1.getWidth() * s1.getHeight() - targetSize.getWidth() * targetSize.getHeight()),
                        Math.abs(s2.getWidth() * s2.getHeight() - targetSize.getWidth() * targetSize.getHeight()))
        );
        previewSizes.put(cameraFacing, selectedSize); // 存储该相机的尺寸

        Log.i(TAG, "为 " + facingStr + " 相机选择的预览尺寸: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());

        // 创建 ImageReader
        ImageReader imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.JPEG, IMAGE_BUFFER_SIZE);
        imageReaders.put(cameraFacing, imageReader);

        imageReader.setOnImageAvailableListener(reader -> {
            // 使用 final 变量捕获当前 facing 和 size
            final int currentFacing = cameraFacing;
            final Size currentSize = previewSizes.get(currentFacing);
            if (currentSize == null) {
                Log.e(TAG, "无法获取 " + getFacingString(currentFacing) + " 的预览尺寸！");
                return;
            }
            processImageAvailable(reader, currentFacing, currentSize);
        }, backgroundHandler);


        // 再次检查权限（理论上之前检查过）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "打开相机时权限丢失 (" + facingStr + ")！");
            showToast("相机权限丢失");
            decrementActiveStreamCountAndCheckStop();
            closeSocket(cameraFacing);
            return;
        }

        Log.d(TAG, "正在调用 cameraManager.openCamera 打开 " + facingStr + " 相机设备...");
        cameraManager.openCamera(cameraId, getCameraStateCallback(cameraFacing), backgroundHandler);
    }

    /**
     * 处理 ImageReader 的 onImageAvailable 事件。
     */
    private void processImageAvailable(ImageReader reader, int cameraFacing, Size previewSize) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image != null) {
                // isStreamingEnabled 可以在这里添加，如果需要全局开关的话
                // 帧率控制 (可以使用更简单的基于时间的控制)
                // if (SystemClock.elapsedRealtime() - lastFrameTime < FRAME_DELAY_MS) {
                //     image.close();
                //     return;
                // }
                // lastFrameTime = SystemClock.elapsedRealtime();

                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                // 处理和发送帧
                processAndSendFrame(bytes, cameraFacing, previewSize.getWidth(), previewSize.getHeight());

            }
        } catch (Exception e) {
            Log.e(TAG, "处理图像时出错 (" + getFacingString(cameraFacing) + ")", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }


    /**
     * 获取指定朝向的 Camera ID。
     * @return Camera ID 或 null (如果未找到或出错)
     */
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
                Log.i(TAG, facingStr + " 相机成功打开。");
                cameraDevices.put(cameraFacing, camera);
                // 确保创建会话也在后台线程
                if (backgroundHandler != null) {
                    backgroundHandler.post(() -> createCameraPreviewSession(cameraFacing));
                } else {
                    Log.e(TAG, "后台 Handler 为空，无法创建预览会话！");
                    closeCamera(cameraFacing); // 关闭刚打开的相机
                    // 这里的计数已经在打开失败时处理，或将在后续 createSession 失败时处理
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.w(TAG, facingStr + " 相机已断开连接。");
                camera.close(); // 关闭设备
                cameraDevices.remove(cameraFacing);
                // 相机断开连接，对应流停止，减少计数并检查
                decrementActiveStreamCountAndCheckStop();
                // 相关的 socket 可能仍然存在，如果需要也关闭
                closeSocket(cameraFacing);
                showToast(facingStr + " Camera Disconnected");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, facingStr + " 相机错误，代码: " + error);
                camera.close();
                cameraDevices.remove(cameraFacing);
                // 相机错误，对应流停止，减少计数并检查
                decrementActiveStreamCountAndCheckStop();
                closeSocket(cameraFacing);
                showToast(facingStr + " Camera Error: " + error);
            }
        };
    }

    /**
     * 创建预览会话。应在 backgroundHandler 上运行。
     */
    private void createCameraPreviewSession(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        try {
            CameraDevice cameraDevice = cameraDevices.get(cameraFacing);
            ImageReader imageReader = imageReaders.get(cameraFacing);
            if (cameraDevice == null || imageReader == null) {
                Log.e(TAG, "无法创建会话: " + facingStr + " 的 CameraDevice 或 ImageReader 为空。");
                // 如果是相机已关闭导致的，计数已减少。如果是 ImageReader 问题，需要减少计数
                if(cameraDevices.containsKey(cameraFacing)) { // 检查相机是否还在，如果是 reader 问题
                    decrementActiveStreamCountAndCheckStop();
                    closeCamera(cameraFacing); // 关闭对应的相机和socket
                    closeSocket(cameraFacing);
                }
                return;
            }

            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            // Optional: 添加自动对焦等设置
            // captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            // 检查相机设备是否仍然有效
                            if (!cameraDevices.containsKey(cameraFacing)) {
                                Log.w(TAG, facingStr + " 相机在会话配置完成前已关闭。");
                                session.close(); // 关闭刚创建的会话
                                return;
                            }
                            cameraCaptureSessions.put(cameraFacing, session);
                            try {
                                CaptureRequest previewRequest = captureRequestBuilder.build();
                                session.setRepeatingRequest(previewRequest, null, backgroundHandler);
                                Log.i(TAG, facingStr + " 相机预览会话已启动。");
                            } catch (CameraAccessException | IllegalStateException e) {
                                Log.e(TAG, "启动 " + facingStr + " 预览时出错: ", e);
                                // 启动预览失败，关闭这个流
                                closeCameraStream(cameraFacing);
                                decrementActiveStreamCountAndCheckStop();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "配置 " + facingStr + " 相机预览会话失败。");
                            // 配置失败，关闭这个流
                            closeCameraStream(cameraFacing); // 清理相机资源
                            decrementActiveStreamCountAndCheckStop(); // 减少计数并检查
                        }
                    }, backgroundHandler // 确保回调在后台线程
            );
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "创建 " + facingStr + " 预览会话时出错: ", e);
            // 创建会话失败，关闭这个流
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        }
    }

    /**
     * 处理原始 JPEG 数据，旋转并发送。
     */
    private void processAndSendFrame(byte[] jpegBytes, int cameraFacing, int width, int height) {
        Bitmap bitmap = null;
        Bitmap rotatedBitmap = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (bitmap == null) {
                Log.w(TAG, "解码 JPEG 失败 (" + getFacingString(cameraFacing) + ")");
                return; // 不发送损坏的数据
            }

            Matrix matrix = new Matrix();
            if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                matrix.postRotate(270);
                matrix.postScale(-1, 1, width / 2f, height / 2f); // 水平翻转
            } else {
                matrix.postRotate(90);
            }

            rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);

            byteArrayOutputStream = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream);
            byte[] rotatedBytes = byteArrayOutputStream.toByteArray();

            sendFrameData(rotatedBytes, cameraFacing);

        } catch (Exception e) {
            Log.e(TAG, "处理或发送帧时出错 (" + getFacingString(cameraFacing) + ")", e);
            // 发送/处理失败，可能网络断开或资源问题，关闭此流
            closeCameraStream(cameraFacing);
            decrementActiveStreamCountAndCheckStop();
        } finally {
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
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length);

                outputStream.write(lengthBuffer.array()); // 发送长度
                outputStream.write(frameData);            // 发送数据
                outputStream.flush();                     // 确保发送
                // Log.v(TAG, "Frame sent: facing=" + cameraFacing + ", length=" + length);

            } catch (IOException e) {
                Log.e(TAG, "发送帧时 IO 错误 (" + getFacingString(cameraFacing) + "): " + e.getMessage());
                // 发送失败，网络问题，关闭此流
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        } else {
            // Log.w(TAG, "无法发送帧，输出流/Socket 无效 (" + getFacingString(cameraFacing) + ")");
            // 如果 socket 意外关闭，也清理资源
            if (sockets.containsKey(cameraFacing)) { // 检查是否还在 map 中
                closeCameraStream(cameraFacing);
                decrementActiveStreamCountAndCheckStop();
            }
        }
    }

    // --- Helper and Cleanup Methods ---

    /**
     * 减少活动流计数，并在计数为零时停止服务。
     */
    private void decrementActiveStreamCountAndCheckStop() {
        if (activeStreamCount.decrementAndGet() <= 0) {
            Log.i(TAG, "所有活动流已停止或失败，正在停止服务...");
            stopSelfSafely();
        } else {
            Log.d(TAG, "一个流已停止/失败，剩余活动流计数: " + activeStreamCount.get());
        }
    }

    /**
     * 安全地停止服务（在主线程或后台线程调用均可）。
     */
    private void stopSelfSafely() {
        // 确保停止操作在合适的线程执行，如果已在后台线程则直接执行
        if (backgroundHandler != null && Thread.currentThread() != backgroundThread) {
            backgroundHandler.post(this::stopSelf);
        } else if (Thread.currentThread() == getMainLooper().getThread()) {
            stopSelf();
        } else {
            // 如果在其他线程，尝试 post 到主线程停止
            new Handler(getMainLooper()).post(this::stopSelf);
        }
        // 如果 backgroundHandler 为 null（例如 onCreate 失败），可能需要直接 stopSelf()
        // 但正常流程下，onDestroy 会被调用并进行清理
    }


    /**
     * 关闭指定朝向的相机流相关的所有资源（相机、会话、Reader、Socket、流）。
     */
    private void closeCameraStream(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        Log.i(TAG, "正在关闭 " + facingStr + " 相机流的所有资源...");
        closeCamera(cameraFacing); // 关闭相机设备和会话
        closeReader(cameraFacing); // 关闭 ImageReader
        closeSocket(cameraFacing); // 关闭 Socket 和输出流
    }

    /** 关闭指定朝向的相机设备和预览会话 */
    private void closeCamera(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        CameraCaptureSession session = cameraCaptureSessions.remove(cameraFacing);
        if (session != null) {
            try { session.close(); } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " 会话出错", e); }
        }
        CameraDevice device = cameraDevices.remove(cameraFacing);
        if (device != null) {
            try { device.close(); } catch (Exception e) { Log.e(TAG, "关闭 " + facingStr + " 设备出错", e); }
        }
    }

    /** 关闭指定朝向的 ImageReader */
    private void closeReader(int cameraFacing) {
        ImageReader reader = imageReaders.remove(cameraFacing);
        if (reader != null) {
            try { reader.close(); } catch (Exception e) { Log.e(TAG, "关闭 " + getFacingString(cameraFacing) + " Reader 出错", e); }
        }
    }

    /** 关闭指定朝向的 Socket 和输出流 */
    private void closeSocket(int cameraFacing) {
        String facingStr = getFacingString(cameraFacing);
        OutputStream os = outputStreams.remove(cameraFacing);
        if (os != null) {
            try { os.close(); } catch (IOException e) { Log.w(TAG, "关闭 " + facingStr + " 输出流出错: " + e.getMessage()); }
        }
        Socket socket = sockets.remove(cameraFacing);
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
                Log.d(TAG, facingStr + " Socket 已关闭。");
            } catch (IOException e) { Log.e(TAG, "关闭 " + facingStr + " Socket 时出错: " + e.getMessage()); }
        }
    }

    /** 关闭特定的 Socket 和流（用于连接失败时清理） */
    private void closeSpecificSocket(Socket socket, OutputStream stream) {
        if (stream != null) {
            try { stream.close(); } catch (IOException e) { /* ignore */ }
        }
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    /** 停止所有相机流 */
    private void stopAllCameraStreams() {
        Log.i(TAG, "正在停止所有相机流...");
        // 使用 keyset 迭代避免 ConcurrentModificationException
        Integer[] facingKeys = cameraDevices.keySet().toArray(new Integer[0]);
        for (Integer facing : facingKeys) {
            closeCameraStream(facing); // 关闭每个流的所有资源
        }
        // 再次确认清理 maps
        cameraCaptureSessions.clear();
        cameraDevices.clear();
        imageReaders.clear();
        outputStreams.clear();
        sockets.clear();
        previewSizes.clear();
        Log.i(TAG, "所有相机资源已关闭。");
    }


    private void shutdownAndCleanup() {
        Log.i(TAG, "开始服务关闭和资源清理...");
        // 确保清理操作在后台线程执行（如果线程还存在）
        if (backgroundHandler != null) {
            // 使用 post 而不是直接调用，确保队列中的任务能完成
            backgroundHandler.post(this::stopAllCameraStreams);
        } else {
            // 如果后台线程已停止或从未成功启动，直接尝试清理
            stopAllCameraStreams();
        }

        // 停止后台线程
        stopBackgroundThread();

        // 关闭网络连接执行器
        if (connectionExecutor != null) {
            connectionExecutor.shutdown();
            try {
                if (!connectionExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    connectionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                connectionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 停止前台服务状态
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        activeStreamCount.set(0); // 重置计数器
    }

    private void startBackgroundThread() {
        // 避免重复创建
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
            Log.d(TAG, "后台线程已启动。");
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500); // 等待最多 500ms
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "后台线程已停止。");
            } catch (InterruptedException e) {
                Log.e(TAG, "停止后台线程时被中断。", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void showToast(final String message) {
        // 确保 Toast 在主线程显示
        new Handler(getMainLooper()).post(() -> Toast.makeText(CameraStreamService.this, message, Toast.LENGTH_SHORT).show());
    }

    private String getFacingString(int cameraFacing) {
        return (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) ? "后置" : "前置";
    }

    // --- Notification Methods ---
    private void startForegroundServiceNotification() {
        createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 使用前景图标
                .setContentTitle("传感流服务")
                .setContentText("正在传输前后传感数据...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true); // 使通知不可滑动消除

        Notification notification = builder.build();
        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "前台服务已启动。");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "传感流服务通道",
                    NotificationManager.IMPORTANCE_LOW // 低优先级，减少打扰
            );
            serviceChannel.setDescription("用于传感流服务的通知通道");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "通知通道已创建。");
            } else {
                Log.e(TAG, "无法获取 NotificationManager。");
            }
        }
    }
}
// --- END OF FILE CameraStreamService.java (Optimized for Early Camera Open) ---