package com.example.myapplication.http; // 替换为你的实际包名

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.myapplication.R;
import com.example.myapplication.model.FileListResponse;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;


public class UploadWorker extends Worker {

    private static final String TAG = "UploadWorker"; // 日志标签
    public static final String WORK_NAME = "uploadWorkFileSystem"; // Worker 的唯一名称 (修正变量名)
    private static final String CHANNEL_ID = "upload_channel_fs"; // 通知渠道 ID
    private static final int NOTIFICATION_ID = 2; // 通知 ID
    private static final long RETRY_DELAY_MS = 5000; // 内部重试循环的延迟时间（5秒）

    private final Context context;
    private final ApiService apiService;
    private final NotificationManagerCompat notificationManager;


    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
        // 注意：在 Worker 构造函数中获取 ApiService 可能因 IP 未设置而抛出异常
        // 更好的做法是在 doWork 开始时获取，或者确保调度 Worker 前 IP 已设置
        // 这里暂时保持原样，但需注意潜在问题
        this.apiService = RetrofitClient.getApiService(context);
        this.notificationManager = NotificationManagerCompat.from(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "UploadWorker (文件系统方式) 开始执行。");

        // --- 0. 前置检查 ---
        // 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "关键错误：未授予 POST_NOTIFICATIONS 权限。无法显示前台服务通知。");
                // 无法运行前台服务是致命错误
                return Result.failure();
            }
        }

        // --- 1. 设置前台服务 ---
        createNotificationChannel(); // 确保通知渠道已创建
        String initialNotificationText = "准备文件同步...";
        Notification initialNotification = createNotification(initialNotificationText);

        ForegroundInfo foregroundInfo;
        try {
            int foregroundServiceType = 0;
            // Android 10 (Q) 及以上需要指定类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            }
            // Android 14 (U) 对前台服务类型有更严格要求，如果涉及相机/麦克风/位置，需要明确声明
            // 对于纯数据同步，DATA_SYNC 通常足够

            if (foregroundServiceType != 0) {
                foregroundInfo = new ForegroundInfo(NOTIFICATION_ID, initialNotification, foregroundServiceType);
            } else {
                foregroundInfo = new ForegroundInfo(NOTIFICATION_ID, initialNotification);
            }
            // 设置为前台服务
            setForegroundAsync(foregroundInfo).get(); // 使用 get() 同步等待，确保成功
            Log.d(TAG, "成功设置为前台服务。");
        } catch (SecurityException se) {
            Log.e(TAG, "启动前台服务时发生 SecurityException (可能是后台启动限制或权限不足)", se);
            // 检查具体权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ActivityCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少 FOREGROUND_SERVICE 权限");
            }
            return Result.failure(); // 无法启动前台服务，失败
        } catch (Exception e) {
            Log.e(TAG, "设置前台服务时出错。", e);
            return Result.failure(); // 设置前台失败，无法继续
        }


        // --- 2. 无限重试循环 ---
        int retryAttempt = 0;
        while (true) { // 无限循环，直到成功或遇到不可重试的错误
            try {
                // 在每次重试开始前检查存储权限
                if (!checkStoragePermission()) {
                    Log.e(TAG, "存储权限检查失败 (尝试 " + (retryAttempt + 1) + ")");
                    updateNotification("同步失败：缺少存储权限");
                    return Result.failure(); // 权限问题，直接失败
                }

                Log.i(TAG, "开始同步流程 (尝试 " + (retryAttempt + 1) + ")");
                updateNotification("正在扫描本地文件 (尝试 " + (retryAttempt + 1) + ")");

                // 3. 使用 File API 获取本地文件列表
                Map<String, File> localFiles = getLocalFilesUsingFileSystem();
                if (isStopped()) { Log.w(TAG,"任务在扫描本地文件后被停止"); return Result.failure();} // 检查任务是否被停止

                if (localFiles.isEmpty()) {
                    Log.i(TAG, "在扫描的目录中未找到本地文件。同步完成。");
                    updateNotification("同步完成，未发现新文件。");
                    return Result.success(); // 没有文件也是成功完成
                }
                Log.d(TAG, "扫描完成，本地文件数量: " + localFiles.size());
                updateNotification("发现 " + localFiles.size() + " 个本地文件，正在检查云端...");


                // 4. 获取云端文件列表
                Call<FileListResponse> listCall = apiService.listCloudFiles();
                Response<FileListResponse> listResponse = listCall.execute(); // 同步执行网络请求
                if (isStopped()) { Log.w(TAG,"任务在获取云端列表后被停止"); return Result.failure();}

                if (!listResponse.isSuccessful() || listResponse.body() == null) {
                    String errorBodyStr = "";
                    if (listResponse.errorBody() != null) { try { errorBodyStr = listResponse.errorBody().string(); } catch (IOException ignored) {} }
                    Log.e(TAG, "获取云端文件列表失败: Code=" + listResponse.code() + ", Message=" + listResponse.message() + ", ErrorBody=" + errorBodyStr);

                    // 判断是否是可重试错误
                    if (isRetryableError(listResponse.code(), null)) {
                        throw new RetryableException("获取云端列表失败 (Code: " + listResponse.code() + ")"); // 抛出自定义异常触发重试
                    } else {
                        String errorMsg = "错误：无法连接到云端 (" + listResponse.code() + ")";
                        updateNotification(errorMsg);
                        return Result.failure(); // 不可重试的错误
                    }
                }

                Set<String> cloudFiles = new HashSet<>(listResponse.body().getFiles());
                Log.d(TAG, "获取云端文件列表成功，数量: " + cloudFiles.size());

                // 5. 计算需要上传的文件 (本地存在，云端不存在)
                Set<String> filesToUploadNames = new HashSet<>(localFiles.keySet());
                filesToUploadNames.removeAll(cloudFiles);

                Log.i(TAG, "计算需要上传的文件数量: " + filesToUploadNames.size());
                if (filesToUploadNames.isEmpty()) {
                    Log.i(TAG, "所有文件已同步。");
                    updateNotification("同步完成，无需上传新文件。");
                    return Result.success(); // 无需上传，成功
                }

                // 6. 逐个上传文件
                int uploadedCount = 0;
                int totalToUpload = filesToUploadNames.size();
                boolean uploadLoopSuccess = true; // 标记本次上传循环是否完全成功

                for (String filename : filesToUploadNames) {
                    if (isStopped()) { Log.w(TAG,"任务在上传循环中被停止"); return Result.failure();} // 检查任务是否被停止

                    File fileToUpload = localFiles.get(filename);
                    if (fileToUpload == null || !fileToUpload.exists() || !fileToUpload.isFile()) {
                        Log.w(TAG, "文件无效或不存在，跳过上传: " + filename);
                        continue;
                    }

                    updateNotification("正在上传 " + (uploadedCount + 1) + "/" + totalToUpload + ": " + filename);

                    // 调用上传方法
                    UploadResult uploadResult = uploadFile(filename, fileToUpload);
                    if (isStopped()) { Log.w(TAG,"任务在上传文件 "+ filename +" 后被停止"); return Result.failure();}

                    if (uploadResult == UploadResult.SUCCESS) {
                        Log.i(TAG, "成功上传: " + filename);
                        uploadedCount++;
                    } else {
                        Log.e(TAG, "上传失败: " + filename + " - 结果: " + uploadResult);
                        uploadLoopSuccess = false; // 标记上传循环中有失败

                        if (uploadResult == UploadResult.RETRYABLE_ERROR) {
                            // 如果单个文件上传失败且是可重试错误，抛出异常以触发外层重试循环
                            throw new RetryableException("上传文件 " + filename + " 时遇到可重试错误");
                        } else if (uploadResult == UploadResult.FILE_NOT_FOUND) {
                            Log.w(TAG, "文件在上传时找不到了: " + filename);
                            // 文件丢失，不再尝试，继续下一个文件
                            updateNotification("文件 " + filename + " 已消失，跳过");
                        } else {
                            // 不可重试的错误 (配置、未知等)
                            Log.e(TAG, "遇到不可重试的上传错误: " + filename + " - " + uploadResult);
                            updateNotification("上传 " + filename + " 时发生错误，请检查配置或文件");
                            // 对于不可重试的错误，可以选择继续上传其他文件，或直接标记整个 Worker 失败
                            // 这里选择继续尝试上传其他文件，但最终结果可能是 failure
                            // return Result.failure(); // 如果希望任何不可重试错误都导致 Worker 失败，则取消注释此行
                        }
                        // 短暂暂停，避免错误日志刷屏
                        try { TimeUnit.MILLISECONDS.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt();}
                    }
                } // end of for loop (uploading files)

                // 如果执行到这里，表示本次上传循环尝试了所有文件
                if (uploadLoopSuccess) {
                    Log.i(TAG, "所有计划上传的文件均已成功处理 (尝试 " + (retryAttempt + 1) + ")");
                    updateNotification("同步完成。本次上传 " + uploadedCount + " 个文件。");
                    return Result.success(); // 所有文件上传成功，跳出无限重试循环
                } else {
                    // 虽然循环结束了，但中间可能遇到了非 Retryable 的失败
                    // 如果上面没有直接 return failure，这里可以判断一下是否需要失败
                    Log.w(TAG, "上传循环完成，但中间有失败项 (非重试类型) (尝试 " + (retryAttempt + 1) + ")");
                    updateNotification("部分文件上传失败，请检查日志");
                    return Result.failure(); // 标记为失败
                }

            } catch (RetryableException e) {
                // 捕获到自定义的可重试异常
                Log.w(TAG, "捕获到可重试异常 (尝试 " + (retryAttempt + 1) + "): " + e.getMessage());
                retryAttempt++; // 增加重试计数
                updateNotification("连接失败，正在重试 (第 " + retryAttempt + " 次)...");
                Log.w(TAG, "将在 " + (RETRY_DELAY_MS / 1000) + " 秒后重试...");
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS); // 等待指定时间
                } catch (InterruptedException ie) {
                    Log.w(TAG, "重试等待被打断，停止 Worker。");
                    Thread.currentThread().interrupt(); // 重新设置中断状态
                    return Result.failure(); // 等待被打断，任务失败
                }
                // continue; // 继续下一次外层 while 循环

            } catch (IOException e) { // 网络或文件 IO 异常
                Log.e(TAG, "同步过程中发生 IOException (尝试 " + (retryAttempt + 1) + ")", e);
                // 判断是否可重试
                if (isRetryableError(-1, e)) { // 传入-1表示非HTTP错误码
                    retryAttempt++;
                    updateNotification("网络或文件错误，正在重试 (第 " + retryAttempt + " 次)...");
                    Log.w(TAG, "将在 " + (RETRY_DELAY_MS / 1000) + " 秒后重试 (IOException)...");
                    try { TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return Result.failure(); }
                    // continue;
                } else {
                    updateNotification("同步失败：网络或文件读写错误。");
                    return Result.failure(); // 不可重试的 IO 错误
                }
            } catch (IllegalStateException e) { // Retrofit 配置问题等
                Log.e(TAG, "同步过程中发生 IllegalStateException (尝试 " + (retryAttempt + 1) + ")", e);
                updateNotification("同步失败：配置错误 - " + e.getMessage());
                return Result.failure(); // 配置问题，标记为失败
            } catch (SecurityException e) { // 权限问题
                Log.e(TAG, "同步过程中发生 SecurityException (尝试 " + (retryAttempt + 1) + ")", e);
                updateNotification("同步失败：权限被拒绝。");
                return Result.failure(); // 权限问题，标记为失败
            } catch (Exception e) { // 捕获所有其他意外错误
                Log.e(TAG, "同步过程中发生未预料的错误 (尝试 " + (retryAttempt + 1) + ")", e);
                updateNotification("同步失败：" + e.getMessage());
                return Result.failure(); // 未知错误，标记为失败
            }

            // 检查任务是否在尝试下一次循环前被停止
            if (isStopped()) {
                Log.w(TAG,"任务在重试循环的末尾被停止");
                return Result.failure();
            }
        } // end of while(true) loop

        // 理论上不会执行到这里，因为循环是无限的，除非成功或失败跳出
        // Log.i(TAG, "UploadWorker (文件系统方式) 执行意外结束。");
        // return Result.failure();
    }

    /** 检查存储权限 */
    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            if (!Environment.isExternalStorageManager()) {
                Log.e(TAG, "权限检查失败：未授予 MANAGE_EXTERNAL_STORAGE 权限。");
                return false;
            }
            Log.v(TAG,"权限检查通过：MANAGE_EXTERNAL_STORAGE 已授予。");
            return true;
        } else { // Android 10 及以下
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "权限检查失败：未授予 READ_EXTERNAL_STORAGE 权限 (Android 10 及以下)。");
                return false;
            }
            // 可选：检查写入权限，如果需要删除或修改文件
            // if (ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) { ... }
            Log.v(TAG,"权限检查通过：READ_EXTERNAL_STORAGE 已授予 (Android 10 及以下)。");
            return true;
        }
    }

    /** 判断错误是否可重试 */
    private boolean isRetryableError(int httpStatusCode, @Nullable Exception e) {
        if (e instanceof IOException) {
            // 网络相关的 IOException 通常是可重试的
            // 需要更细致的判断可以检查 e.getMessage() 或 e 的具体类型 (e.g., SocketTimeoutException)
            Log.w(TAG, "isRetryableError: IOException detected, considered retryable.");
            return true;
        }
        // HTTP 5xx 服务器错误通常可重试
        if (httpStatusCode >= 500 && httpStatusCode < 600) {
            Log.w(TAG, "isRetryableError: HTTP status code " + httpStatusCode + " (Server Error), considered retryable.");
            return true;
        }
        // 特定客户端错误也可重试，如 408 (Timeout) 或 429 (Too Many Requests)
        if (httpStatusCode == 408 || httpStatusCode == 429) {
            Log.w(TAG, "isRetryableError: HTTP status code " + httpStatusCode + ", considered retryable.");
            return true;
        }
        // 其他情况（如 4xx 客户端错误、非 IO 异常）通常不可重试
        Log.d(TAG, "isRetryableError: Error (Code: " + httpStatusCode + ", Exception: " + (e != null ? e.getClass().getSimpleName() : "null") + ") considered NOT retryable.");
        return false;
    }

    /** 自定义异常，用于触发内部重试循环 */
    private static class RetryableException extends IOException {
        public RetryableException(String message) {
            super(message);
        }
    }


    /**
     * 使用 java.io.File API 扫描预定义的公共目录。
     * @return Map<String, File> 文件名到 File 对象的映射。
     */
    private Map<String, File> getLocalFilesUsingFileSystem() {
        Map<String, File> foundFiles = new HashMap<>();
        List<File> directoriesToScan = new ArrayList<>();

        // --- 定义要扫描的目录 ---
        // 添加标准公共目录
        addPublicDirectory(directoriesToScan, Environment.DIRECTORY_DCIM);      // 相机照片/视频
        addPublicDirectory(directoriesToScan, Environment.DIRECTORY_PICTURES); // 图片
        addPublicDirectory(directoriesToScan, Environment.DIRECTORY_MOVIES);   // 视频
        // 根据需要添加其他目录...

        Log.d(TAG, "开始递归扫描选定的目录 (" + directoriesToScan.size() + " 个)...");
        for (File dir : directoriesToScan) {
            if (isStopped()) { Log.w(TAG,"任务在扫描目录 "+ dir.getName() +" 前被停止"); break;}
            Log.d(TAG, "正在扫描目录: " + dir.getAbsolutePath());
            try {
                scanDirectoryRecursive(dir, foundFiles);
            } catch (Exception e) {
                Log.e(TAG, "扫描目录时出错: " + dir.getAbsolutePath(), e);
            }
        }

        Log.i(TAG, "通过文件系统扫描找到的本地文件总数: " + foundFiles.size());
        return foundFiles;
    }

    /** 安全地添加公共目录到扫描列表 */
    private void addPublicDirectory(List<File> list, String type) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(type);
            if (dir != null) {
                if (!dir.exists()) {
                    // 尝试创建目录，虽然不一定需要，但有助于发现问题
                    if (!dir.mkdirs()) {
                        Log.w(TAG, "无法创建公共目录: " + dir.getAbsolutePath());
                    }
                }
                if (dir.isDirectory()) { // 确保是目录
                    list.add(dir);
                } else {
                    Log.w(TAG, "公共路径不是一个目录: " + dir.getAbsolutePath());
                }
            } else {
                Log.w(TAG, "无法获取公共目录: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "添加公共目录时出错: " + type, e);
        }
    }


    /**
     * 递归地扫描一个目录，并将找到的文件添加到映射中。
     */
    private void scanDirectoryRecursive(File directory, Map<String, File> fileMap) {
        if (isStopped()) { Log.w(TAG,"任务在递归扫描 "+ directory.getName() +" 时被停止"); return; } // 检查停止状态

        File[] files = directory.listFiles(); // 获取目录内容

        if (files != null) {
            for (File file : files) {
                if (isStopped()) { Log.w(TAG,"任务在处理文件 "+ file.getName() +" 时被停止"); break; }

                try {
                    if (file.isDirectory()) {
                        // --- 排除特定目录 ---
                        String absPath = file.getAbsolutePath();
                        String name = file.getName();
                        // 跳过隐藏目录
                        if (name.startsWith(".")) { continue; }
                        // 避免扫描 Android 数据目录
                        if (absPath.contains("/Android/data") || absPath.contains("/Android/obb")) { continue; }
                        // 可选：跳过其他不想扫描的目录
                        // if (name.equalsIgnoreCase("cache") || name.equalsIgnoreCase("temp")) { continue; }

                        scanDirectoryRecursive(file, fileMap); // 递归进入子目录
                    } else if (file.isFile()) {
                        // 确保是文件且有内容
                        if (file.length() > 0) {
                            String filename = file.getName();
                            if (!TextUtils.isEmpty(filename)) {
                                // 使用文件名作为 Key，后找到的同名文件会覆盖前者
                                fileMap.put(filename, file);
                            } else {
                                Log.w(TAG, "发现文件名为空的文件， 跳过: " + file.getAbsolutePath());
                            }
                        }
                        // else { Log.v(TAG, "跳过空文件: " + file.getName()); } // 减少日志量
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "访问被拒绝 (SecurityException): " + file.getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "处理文件/目录时出错: " + file.getAbsolutePath(), e);
                }
            } // end for loop
        } else {
            Log.w(TAG, "listFiles() 返回 null，无法读取目录内容: " + directory.getAbsolutePath());
        }
    }

    // 定义上传结果的枚举
    private enum UploadResult {
        SUCCESS,          // 上传成功
        FILE_NOT_FOUND,   // 文件在上传前找不到了
        IO_ERROR,         // 读文件或网络传输中的 IO 错误 (非特定服务器错误, 不可重试)
        CONFIG_ERROR,     // 配置错误 (例如 Retrofit 未初始化)
        UNEXPECTED_ERROR, // 其他未预料的异常
        RETRYABLE_ERROR   // 可重试的错误 (网络问题或服务器端临时错误 5xx, 408, 429)
    }


    /**
     * 上传文件。
     * @param filename 文件名
     * @param file     要上传的 File 对象
     * @return UploadResult 枚举值，指示上传结果
     */
    private UploadResult uploadFile(String filename, File file) {
        if (isStopped()) { Log.w(TAG,"任务在上传文件 "+ filename +" 开始时被停止"); return UploadResult.UNEXPECTED_ERROR;} // 开始上传前检查

        try {
            // 1. 获取 MIME 类型
            String mimeType = getMimeTypeFromFile(file);
            if (mimeType == null) {
                mimeType = "application/octet-stream"; // 默认类型
                Log.w(TAG, "无法确定文件 MIME 类型: " + file.getName() + "。使用默认类型。");
            }
            Log.d(TAG, "准备上传: " + filename + ", Path: " + file.getAbsolutePath() + ", Size: " + file.length() + ", MIME: " + mimeType);

            // 2. 创建 RequestBody (从文件流式传输)
            RequestBody requestBody = RequestBody.create(file, MediaType.parse(mimeType));

            // 3. 创建 MultipartBody.Part
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", filename, requestBody);

            // 4. 执行上传请求
            Call<ResponseBody> uploadCall = apiService.uploadFile(body);
            Response<ResponseBody> uploadResponse = uploadCall.execute(); // 同步执行

            if (isStopped()) { Log.w(TAG,"任务在上传文件 "+ filename +" 网络请求后被停止"); return UploadResult.UNEXPECTED_ERROR;} // 网络请求后检查

            // 5. 处理响应
            if (uploadResponse.isSuccessful()) {
                Log.d(TAG, "上传成功响应: " + filename + " (Code: " + uploadResponse.code() + ")");
                // 确保关闭响应体是个好习惯，即使我们不读取它
                if (uploadResponse.body() != null) {
                    uploadResponse.body().close();
                }
                return UploadResult.SUCCESS;
            } else {
                // 上传失败
                String errorBodyStr = "";
                try { if(uploadResponse.errorBody() != null) errorBodyStr = uploadResponse.errorBody().string(); } catch(IOException ignored) {}
                Log.e(TAG, "上传失败: " + filename + ". Code: " + uploadResponse.code() + ", Msg: " + uploadResponse.message() + ", ErrBody: " + errorBodyStr);

                // 根据错误码判断是否可重试
                if (isRetryableError(uploadResponse.code(), null)) {
                    return UploadResult.RETRYABLE_ERROR;
                } else {
                    // 不可重试的 HTTP 错误
                    return UploadResult.IO_ERROR; // 归类为 IO 错误 (不可重试类型)
                }
            }

        } catch (FileNotFoundException e) {
            Log.e(TAG, "上传时文件未找到 (FileNotFoundException): " + file.getAbsolutePath(), e);
            return UploadResult.FILE_NOT_FOUND;
        } catch (IOException e) {
            // 这个 IOException 可能是读取文件时的错误，或更常见的网络连接错误
            Log.e(TAG, "上传过程中发生 IOException: " + filename, e);
            // 网络相关的 IOException 视为可重试
            return UploadResult.RETRYABLE_ERROR;
        } catch (IllegalStateException e) {
            // RetrofitClient 未初始化等配置问题
            Log.e(TAG, "上传时发生 IllegalStateException (配置问题?): " + e.getMessage());
            return UploadResult.CONFIG_ERROR;
        } catch (Exception e) {
            // 其他未预料的异常
            Log.e(TAG, "上传时发生意外错误: " + filename, e);
            return UploadResult.UNEXPECTED_ERROR;
        }
    }

    /** 辅助方法：根据文件获取其 MIME 类型 */
    @Nullable
    private String getMimeTypeFromFile(File file) {
        String mimeType = null;
        if (file != null && file.exists()) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath());
            if (!TextUtils.isEmpty(extension)) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
            }
        }
        return mimeType;
    }


    // --- 通知相关的辅助方法 ---

    /** 创建通知渠道 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "文件上传服务";
            String description = "显示文件上传状态";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                // Log.d(TAG, "通知渠道已创建或已存在: " + CHANNEL_ID);
            } else {
                Log.e(TAG, "无法获取 NotificationManager 来创建渠道。");
            }
        }
    }

    /** 创建通知对象 */
    private Notification createNotification(String contentText) {
        // 使用一个标准的系统图标作为示例
        int smallIconResId = R.drawable.ic_light_sensor_foreground;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("文件同步")
                .setContentText(contentText)
                .setSmallIcon(smallIconResId)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // 持续性通知
                .setOnlyAlertOnce(true); // 仅首次提醒
        return builder.build();
    }

    /** 更新通知内容 */
    private void updateNotification(String contentText) {
        // 再次检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "无法更新通知：缺少 POST_NOTIFICATIONS 权限。");
                return;
            }
        }
        try {
            Notification notification = createNotification(contentText);
            notificationManager.notify(NOTIFICATION_ID, notification); // 使用相同 ID 更新
            Log.d(TAG, "通知已更新: " + contentText);
        } catch (Exception e) {
            Log.e(TAG, "更新通知时失败", e);
        }
    }
}