package com.example.myapplication.http; // 替换为你的包名

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.myapplication.R;
import com.example.myapplication.model.FileListResponse;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class UploadWorker extends Worker {

    private static final String TAG = "UploadWorker";
    public static final String WORK_NAME = "uploadWork";
    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1;

    private final Context context;
    private final ApiService apiService;
    private final NotificationManagerCompat notificationManager;


    // 定义要监控的目录 (需要根据实际情况和权限模型调整)
    // 注意：直接访问公共存储在新版 Android 上可能受限
    // 使用 getExternalFilesDir 更可靠，不需要特殊权限，但文件对其他应用不可见
    // private static final File FOLDER_TO_WATCH = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
    private static final File FOLDER_TO_WATCH = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);


    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
        this.apiService = RetrofitClient.getApiService(); // 获取 ApiService 实例
        this.notificationManager = NotificationManagerCompat.from(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "UploadWorker started.");

        // 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show foreground notification.");
                // 无法显示前台通知，根据策略可能需要失败或尝试非前台运行（但可能被杀）
                return Result.failure();
            }
        }


        // 1. 创建通知渠道和初始通知
        createNotificationChannel();
        String initialNotificationText = "Starting file sync...";
        Notification notification = createNotification(initialNotificationText);

        // 2. 设置为前台服务
        ForegroundInfo foregroundInfo;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 需要指定 foregroundServiceType
            foregroundInfo = new ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            foregroundInfo = new ForegroundInfo(NOTIFICATION_ID, notification);
        }

        try {
            // 异步设置前台服务状态
            ListenableFuture<Void> foregroundFuture = setForegroundAsync(foregroundInfo);
            foregroundFuture.addListener(() -> {
                // 可以在这里添加设置成功后的逻辑，但通常不需要
                Log.d(TAG, "Successfully set foreground.");
            }, Runnable::run); // 使用直接执行器

        } catch (IllegalStateException e) {
            // 在 Android 12+，如果应用在后台，启动前台服务有限制
            Log.e(TAG, "Error setting foreground service. App might be in background on Android 12+ without required exemption.", e);
            // 返回失败，可能需要用户打开应用一次或获取后台启动权限
            return Result.failure();
        } catch (SecurityException se) {
            Log.e(TAG, "Missing FOREGROUND_SERVICE permission?", se);
            return Result.failure(); // 缺少权限
        } catch (Exception e) { // 捕获其他潜在异常
            Log.e(TAG, "Unexpected error setting foreground service.", e);
            return Result.failure();
        }

        // --- 核心同步逻辑 ---
        try {
            // 检查监控目录
            if (FOLDER_TO_WATCH == null || !FOLDER_TO_WATCH.exists() || !FOLDER_TO_WATCH.isDirectory()) {
                Log.e(TAG, "Folder to watch is invalid or doesn't exist: " + (FOLDER_TO_WATCH != null ? FOLDER_TO_WATCH.getAbsolutePath() : "null"));
                updateNotification("Error: Watched folder invalid.");
                return Result.failure();
            }

            // 3. 获取本地文件列表
            updateNotification("Scanning local files...");
            File[] localFileObjects = FOLDER_TO_WATCH.listFiles(File::isFile);
            Set<String> localFiles;
            if (localFileObjects != null) {
                // 使用 Stream API (Java 8+) 或传统循环
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    localFiles = Arrays.stream(localFileObjects)
                            .map(File::getName)
                            .collect(Collectors.toSet());
                } else {
                    localFiles = new HashSet<>();
                    for(File f : localFileObjects) {
                        localFiles.add(f.getName());
                    }
                }
            } else {
                localFiles = Collections.emptySet();
            }

            if (localFiles.isEmpty()) {
                Log.i(TAG, "No local files found in " + FOLDER_TO_WATCH.getAbsolutePath());
                updateNotification("No local files to sync.");
                return Result.success(); // 没有文件也是成功
            }
            Log.d(TAG, "Local files found: " + localFiles.size());
            updateNotification("Found " + localFiles.size() + " local files. Checking cloud...");


            // 4. 获取云端文件列表 (同步执行网络请求，因为 doWork 已经在后台线程)
            Call<FileListResponse> listCall = apiService.listCloudFiles();
            Response<FileListResponse> listResponse = listCall.execute(); // 同步执行

            if (!listResponse.isSuccessful() || listResponse.body() == null) {
                Log.e(TAG, "Failed to get cloud file list: " + listResponse.code() + " - " + listResponse.message());
                if (listResponse.body() != null && listResponse.body().getError() != null) {
                    Log.e(TAG, "Server error: " + listResponse.body().getError());
                    updateNotification("Server error: " + listResponse.body().getError());
                } else {
                    updateNotification("Error: Cannot reach cloud (" + listResponse.code() + ")");
                }
                return Result.retry(); // 网络或服务器问题，稍后重试
            }

            Set<String> cloudFiles = new HashSet<>(listResponse.body().getFiles());
            Log.d(TAG, "Cloud files found: " + cloudFiles.size());


            // 5. 计算需要上传的文件 (本地存在 但 云端不存在)
            Set<String> filesToUpload = new HashSet<>(localFiles);
            filesToUpload.removeAll(cloudFiles); // 从本地文件集合中移除云端已有的

            Log.i(TAG, "Files to upload: " + filesToUpload.size());

            if (filesToUpload.isEmpty()) {
                Log.i(TAG, "All files are synced.");
                updateNotification("Sync complete. No new files.");
                return Result.success();
            }


            // 6. 逐个上传文件
            int uploadedCount = 0;
            int totalToUpload = filesToUpload.size();
            for (String filename : filesToUpload) {
                updateNotification("Uploading " + (uploadedCount + 1) + "/" + totalToUpload + ": " + filename);
                File fileToUpload = new File(FOLDER_TO_WATCH, filename);

                if (!fileToUpload.exists() || !fileToUpload.canRead()) {
                    Log.w(TAG, "File " + filename + " not found or cannot be read, skipping.");
                    continue;
                }

                // 创建 RequestBody
                // 注意：MIME 类型需要更智能的判断，这里简化为 "image/*" 或 "video/*" 等
                String mimeType = getMimeType(filename);
                RequestBody requestFile = RequestBody.create(fileToUpload, MediaType.parse(mimeType));


                // 创建 MultipartBody.Part
                MultipartBody.Part body = MultipartBody.Part.createFormData("file", fileToUpload.getName(), requestFile);

                // 执行上传 (同步)
                try {
                    Call<ResponseBody> uploadCall = apiService.uploadFile(body);
                    Response<ResponseBody> uploadResponse = uploadCall.execute(); // 同步执行

                    if (uploadResponse.isSuccessful()) {
                        Log.i(TAG, "Successfully uploaded " + filename);
                        uploadedCount++;
                    } else {
                        Log.e(TAG, "Failed to upload " + filename + ": " + uploadResponse.code() + " - " + uploadResponse.message());
                        // 可以根据错误码决定是否重试整个任务
                        updateNotification("Error uploading " + filename + ". Skipping.");
                        // 短暂暂停避免通知刷屏太快
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        // 暂时跳过此文件，继续尝试其他文件
                    }
                } catch (IOException ioException) {
                    Log.e(TAG, "IOException during upload of " + filename, ioException);
                    updateNotification("Network error uploading " + filename + ".");
                    // 网络问题通常应该重试整个任务
                    return Result.retry();
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error during upload of " + filename, e);
                    updateNotification("Error uploading " + filename + ".");
                    // 其他未知错误，也可能需要重试
                    return Result.retry();
                }
            }

            Log.i(TAG, "Finished uploading " + uploadedCount + " files.");
            updateNotification("Sync complete. Uploaded " + uploadedCount + " files.");
            return Result.success();

        } catch (IOException e) {
            Log.e(TAG, "IOException during sync process (e.g., listing files or network)", e);
            updateNotification("Sync failed: Network or File IO error.");
            return Result.retry(); // IO 错误通常适合重试
        } catch (Exception e) {
            Log.e(TAG, "An unexpected error occurred during the sync process", e);
            updateNotification("Sync failed: " + e.getMessage());
            return Result.failure(); // 未知错误，标记为失败
        } finally {
            Log.d(TAG, "UploadWorker finished.");
            // WorkManager 通常会在 Worker 结束后自动移除通知
            // 如果需要手动移除，可以在这里调用 notificationManager.cancel(NOTIFICATION_ID);
            // 但通常不需要
        }
    }

    // --- Notification Helper Methods ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Upload Service";
            String description = "Notifications for file upload status";
            int importance = NotificationManager.IMPORTANCE_LOW; // 低重要性，避免过多打扰
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // 从 Context 获取 NotificationManager
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created.");
            } else {
                Log.e(TAG, "NotificationManager service not found.");
            }
        }
    }

    private Notification createNotification(String contentText) {

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("File Sync")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // !! 确保你有这个 drawable 资源 !!
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级
                .setOngoing(true) // 使通知持续，表示正在进行的任务
                .setOnlyAlertOnce(true); // 只有第一次显示时发出提示音
        // .setContentIntent(pendingIntent); // 设置点击意图

        return builder.build();
    }

    private void updateNotification(String contentText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 检查通知权限，如果更新时权限丢失，则无法更新
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot update notification, POST_NOTIFICATIONS permission not granted.");
                return;
            }
        }
        Notification notification = createNotification(contentText);
        notificationManager.notify(NOTIFICATION_ID, notification); // 使用 NotificationManagerCompat
        Log.d(TAG, "Notification updated: " + contentText);
    }

    // 简单的 MIME 类型判断 (可以根据需要扩展)
    private String getMimeType(String filename) {
        String extension = "";
        int i = filename.lastIndexOf('.');
        if (i > 0) {
            extension = filename.substring(i + 1).toLowerCase();
        }
        switch (extension) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
                return "image/" + extension;
            case "mp4":
                return "video/mp4";
            case "mov":
                return "video/quicktime";
            // 添加更多类型...
            default:
                return "application/octet-stream"; // 通用二进制类型
        }
    }
}