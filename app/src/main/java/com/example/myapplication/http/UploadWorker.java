package com.example.myapplication.http; // 替换为你的实际包名

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

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;


public class UploadWorker extends Worker {

    private static final String TAG = "UploadWorker"; // 日志标签
    public static final String WORK_NAME = "uploadWorkFileSy stem"; // Worker 的唯一名称
    private static final String CHANNEL_ID = "upload_channel_fs"; // 通知渠道 ID
    private static final int NOTIFICATION_ID = 2; // 通知 ID (建议与 MediaStore 版本区分)

    private final Context context;
    private final ApiService apiService;
    private final NotificationManagerCompat notificationManager;
    // 使用 File API 后，通常不再需要 ContentResolver 作为主要成员变量
    // private final ContentResolver contentResolver;


    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
        // 确保 RetrofitClient 和 ApiService 已正确配置
        this.apiService = RetrofitClient.getApiService(context);
        this.notificationManager = NotificationManagerCompat.from(context);
        // this.contentResolver = context.getContentResolver();
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "UploadWorker (文件系统方式) 开始执行。");

        // --- 前置检查 ---

        // 1. 检查 MANAGE_EXTERNAL_STORAGE 权限 (Android 11+ / API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.e(TAG, "关键错误：未授予 MANAGE_EXTERNAL_STORAGE (所有文件访问) 权限。无法访问文件。");
                // 注意：后台 Worker 不适合直接弹出权限请求或引导用户去设置。
                // 最佳实践是在调度此 Worker 之前，在 Activity/Fragment 中检查并获取此权限。
                // 如果 Worker 在没有权限的情况下运行，应该直接失败。
                updateNotification("同步失败：需要“所有文件访问”权限。");
                // 可以考虑发送一个一次性的通知，提示用户去应用设置中授予权限，但这需要额外逻辑。
                return Result.failure(); // 没有权限，无法继续
            }
            Log.i(TAG,"权限检查通过：已授予 MANAGE_EXTERNAL_STORAGE 权限。");
        } else {
            // 对于 Android 10 (API 29) 及以下版本，MANAGE_EXTERNAL_STORAGE 不存在。
            // 此时依赖于旧的 READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE 运行时权限。
            // 此示例假设主要目标是 Android 11+，或者已在其他地方处理了旧版权限。
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "权限错误：未授予 READ_EXTERNAL_STORAGE 权限 (适用于 Android 10 及更低版本)。");
                updateNotification("同步失败：需要存储访问权限。");
                return Result.failure(); // 没有权限，无法继续
            }
            Log.i(TAG,"权限检查通过：已授予 READ_EXTERNAL_STORAGE 权限 (Android 10 或更低版本)。");
        }


        // 2. 检查通知权限 (Android 13+ / API 33+) - 这部分逻辑不变
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "关键错误：未授予 POST_NOTIFICATIONS (发送通知) 权限。无法显示前台服务通知。");
                // 没有通知权限，无法运行前台服务，这是致命错误。
                return Result.failure();
            }
        }

        // --- 设置前台服务 --- 这部分逻辑不变
        createNotificationChannel(); // 确保通知渠道已创建
        String initialNotificationText = "开始文件同步...";
        Notification notification = createNotification(initialNotificationText); // 创建初始通知

        ForegroundInfo foregroundInfo;
        try {
            // 从 Android 12 (API 31) 开始，前台服务启动有一些限制，确保类型正确声明
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                // 如果你的服务同时使用了摄像头或麦克风，需要在这里用 | (或运算) 添加类型
                // foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                foregroundInfo = new ForegroundInfo(NOTIFICATION_ID, notification, foregroundServiceType);
            } else {
                foregroundInfo = new ForegroundInfo(NOTIFICATION_ID, notification);
            }
            // 调用 setForegroundAsync 并等待其完成，确保服务已进入前台状态
            setForegroundAsync(foregroundInfo).get(); // 使用 get() 同步等待，确保前台设置成功
            Log.d(TAG, "成功设置为前台服务。");
        } catch (SecurityException se) {
            // 在 Android 12+，如果应用在后台时尝试启动前台服务（某些类型有限制），可能抛出异常
            Log.e(TAG, "启动前台服务时发生 SecurityException (可能是后台启动限制)", se);
            updateNotification("启动同步服务失败 (安全限制)");
            return Result.failure();
        }
        catch (Exception e) { // 包括 InterruptedException, ExecutionException 等
            Log.e(TAG, "设置前台服务时出错。", e);
            updateNotification("启动同步服务失败");
            return Result.failure(); // 设置前台失败，无法继续
        }


        // --- 核心同步逻辑 (使用 FileSystem API) ---
        try {
            // 3. 使用 File API 获取本地文件列表
            updateNotification("正在扫描本地文件...");
            // 使用 Map 存储 文件名 -> File 对象 的映射
            Map<String, File> localFiles = getLocalFilesUsingFileSystem();

            if (localFiles.isEmpty()) {
                Log.i(TAG, "在扫描的目录中未找到本地文件。");
                updateNotification("未发现需要同步的新文件。");
                return Result.success(); // 没有文件也是成功完成
            }
            Log.d(TAG, "扫描完成，本地文件数量: " + localFiles.size());
            updateNotification("发现 " + localFiles.size() + " 个本地文件，正在检查云端...");


            // 4. 获取云端文件列表 - 这部分与之前相同
            Call<FileListResponse> listCall = apiService.listCloudFiles();
            Response<FileListResponse> listResponse = listCall.execute(); // 同步执行网络请求

            if (!listResponse.isSuccessful() || listResponse.body() == null) {
                String errorBodyStr = "";
                if (listResponse.errorBody() != null) {
                    try { errorBodyStr = listResponse.errorBody().string(); } catch (IOException ignored) {}
                }
                Log.e(TAG, "获取云端文件列表失败: Code=" + listResponse.code() + ", Message=" + listResponse.message() + ", ErrorBody=" + errorBodyStr);
                String errorMsg = "错误：无法连接到云端 (" + listResponse.code() + ")";
                // 尝试从响应体中获取更具体的错误信息 (如果API设计如此)
                if (!TextUtils.isEmpty(errorBodyStr)) {
                    errorMsg += " - " + errorBodyStr.substring(0, Math.min(errorBodyStr.length(), 100)); // 限制长度
                } else if (listResponse.body() != null && listResponse.body().getError() != null) {
                    errorMsg = "服务器错误: " + listResponse.body().getError();
                }
                updateNotification(errorMsg);
                return Result.retry(); // 网络或服务器错误，值得重试
            }

            Set<String> cloudFiles = new HashSet<>(listResponse.body().getFiles());
            Log.d(TAG, "获取云端文件列表成功，数量: " + cloudFiles.size());


            // 5. 计算需要上传的文件 (本地存在，云端不存在) - 逻辑不变，基于文件名
            Set<String> filesToUploadNames = new HashSet<>(localFiles.keySet()); // 获取所有本地文件名
            filesToUploadNames.removeAll(cloudFiles); // 移除云端已有的

            Log.i(TAG, "计算需要上传的文件数量: " + filesToUploadNames.size());

            if (filesToUploadNames.isEmpty()) {
                Log.i(TAG, "所有文件已同步。");
                updateNotification("同步完成，无需上传新文件。");
                return Result.success();
            }

            // 6. 逐个上传文件 (使用 File 对象)
            int uploadedCount = 0;
            int totalToUpload = filesToUploadNames.size();
            boolean encounteredRetryableError = false; // 标记是否遇到了可重试的错误

            for (String filename : filesToUploadNames) {
                File fileToUpload = localFiles.get(filename); // 获取对应的 File 对象

                // 检查文件是否有效
                if (fileToUpload == null || !fileToUpload.exists() || !fileToUpload.isFile()) {
                    Log.w(TAG, "文件无效或不存在，跳过上传: " + filename + (fileToUpload != null ? " Path: " + fileToUpload.getAbsolutePath() : ""));
                    continue;
                }

                updateNotification("正在上传 " + (uploadedCount + 1) + "/" + totalToUpload + ": " + filename);

                // 调用新的上传方法
                UploadResult uploadResult = uploadFile(filename, fileToUpload);

                if (uploadResult == UploadResult.SUCCESS) {
                    Log.i(TAG, "成功上传: " + filename);
                    uploadedCount++;
                } else {
                    Log.e(TAG, "上传失败: " + filename + " (路径: " + fileToUpload.getAbsolutePath() + ") - 结果: " + uploadResult);
                    updateNotification("上传文件 " + filename + " 时出错。"); // 可以在这里显示更具体的错误，但可能太长

                    if (uploadResult == UploadResult.RETRYABLE_ERROR) {
                        encounteredRetryableError = true; // 标记遇到了可重试的网络/服务器错误
                        Log.w(TAG, "遇到可重试错误，将安排稍后重试整个任务。");
                    } else if (uploadResult == UploadResult.FILE_NOT_FOUND) {
                        // 文件在尝试上传时被删除了？从列表中移除或标记，避免下次还尝试
                        localFiles.remove(filename); // 从当前任务中移除
                    } else {
                        // 对于配置错误或不可恢复的错误，记录日志，但继续尝试其他文件
                        Log.e(TAG, "遇到不可重试的错误: " + uploadResult);
                    }

                    // 可以选择在此处短暂暂停，避免错误信息刷屏太快
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt();}
                    // 即使单个文件上传失败，也继续尝试上传其他文件
                }
            } // end of for loop

            Log.i(TAG, "上传循环结束。成功上传文件数: " + uploadedCount);
            updateNotification("同步完成。本次上传 " + uploadedCount + " 个文件。");

            // 如果在循环中遇到了任何可重试的错误，则返回 retry，让 WorkManager 稍后重新运行整个任务
            if (encounteredRetryableError) {
                Log.w(TAG, "由于上传过程中遇到可重试错误，将请求 WorkManager 重试。");
                return Result.retry();
            }

            // 所有计划上传的文件都尝试过了，并且没有遇到需要重试的错误
            return Result.success();

        } catch (IOException e) { // 通常是 listCloudFiles 或 uploadFile 中的网络或文件 IO 异常
            Log.e(TAG, "同步过程中发生 IOException (网络或文件访问错误)", e);
            updateNotification("同步失败：网络或文件读写错误。");
            return Result.retry(); // IO 异常通常值得重试
        } catch (IllegalStateException e) { // 可能来自 RetrofitClient 初始化或其他状态问题
            Log.e(TAG, "同步过程中发生 IllegalStateException (Retrofit 配置或 IP 问题?)", e);
            updateNotification("同步失败：" + e.getMessage());
            return Result.failure(); // 配置问题，标记为失败，不应重试
        } catch (SecurityException e) { // 理论上应该在开始时捕获，但作为后备
            Log.e(TAG, "同步过程中发生 SecurityException (权限问题?)", e);
            updateNotification("同步失败：权限被拒绝。");
            return Result.failure(); // 权限问题，标记为失败
        } catch (Exception e) { // 捕获所有其他意外错误
            Log.e(TAG, "同步过程中发生未预料的错误", e);
            updateNotification("同步失败：" + e.getMessage());
            return Result.failure(); // 未知错误，标记为失败
        } finally {
            Log.d(TAG, "UploadWorker (文件系统方式) 执行结束。");
            // WorkManager 会自动处理前台服务的停止，无需手动调用 stopForeground
        }
    }

    /**
     * 使用 java.io.File API 扫描预定义的公共目录。
     * 需要 MANAGE_EXTERNAL_STORAGE (Android 11+) 或 READ_EXTERNAL_STORAGE (Android 10-) 权限。
     * 警告：如果目录包含大量文件或子目录，此操作可能非常耗时。
     *
     * @return Map<String, File> 文件名到 File 对象的映射。
     */
    private Map<String, File> getLocalFilesUsingFileSystem() {
        Map<String, File> foundFiles = new HashMap<>();
        List<File> directoriesToScan = new ArrayList<>();

        // --- ★★★ 定义要扫描的目录 ★★★ ---
        // **务必谨慎选择！** 扫描所有内容效率极低。
        // 添加你关心的标准公共目录。
        // 使用 Environment.getExternalStoragePublicDirectory() 获取标准公共目录
        directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));      // 相机照片/视频 (通常包含子目录如 Camera)
        directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)); // 图片 (Screenshots, etc.)
        directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));   // 视频
        directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));    // 音乐
        // 根据需要添加其他目录, 例如:
        // directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)); // 有声读物
        // directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)); // 铃声
        // directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)); // 播客

        // **极端情况**：扫描整个外部存储根目录 (非常不推荐，除非是文件管理器类应用)
        // File rootDir = Environment.getExternalStorageDirectory();
        // directoriesToScan.add(rootDir);
        // 如果扫描根目录，**强烈建议** 在 scanDirectoryRecursive 中添加排除逻辑，跳过 /Android 目录等。

        Log.d(TAG, "开始递归扫描选定的目录...");
        for (File dir : directoriesToScan) {
            if (dir != null && dir.exists() && dir.isDirectory()) {
                Log.d(TAG, "正在扫描目录: " + dir.getAbsolutePath());
                try {
                    scanDirectoryRecursive(dir, foundFiles);
                } catch (Exception e) {
                    // 捕获扫描单个顶级目录时可能发生的意外错误
                    Log.e(TAG, "扫描目录时出错: " + dir.getAbsolutePath(), e);
                }
            } else {
                Log.w(TAG, "目录不存在或不是一个目录，跳过: " + (dir != null ? dir.getAbsolutePath() : "null"));
            }
        }

        Log.i(TAG, "通过文件系统扫描找到的本地文件总数: " + foundFiles.size());
        return foundFiles;
    }

    /**
     * 递归地扫描一个目录，并将找到的文件添加到映射中。
     *
     * @param directory 要扫描的目录 File 对象。
     * @param fileMap   用于存储结果的 Map (文件名 -> File 对象)。
     */
    private void scanDirectoryRecursive(File directory, Map<String, File> fileMap) {
        // 使用 listFiles() 获取目录内容
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                // 增加日志输出频率可能影响性能，仅在调试时使用详细日志
                // Log.v(TAG, "检查: " + file.getAbsolutePath());

                // 捕获处理单个文件/目录时可能发生的异常，防止中断整个扫描过程
                try {
                    if (file.isDirectory()) {
                        // ★★★ 可选：在此处添加排除逻辑 ★★★
                        // 例如，跳过隐藏目录、Android 系统目录等
                        if (file.getName().startsWith(".")) {
                            // Log.v(TAG, "跳过隐藏目录: " + file.getName());
                            continue; // 跳过隐藏目录
                        }
                        // 绝对避免扫描 /Android 目录下的 data 和 obb，这些是应用私有数据
                        if (file.getAbsolutePath().contains("/Android/data") || file.getAbsolutePath().contains("/Android/obb")) {
                            // Log.v(TAG, "跳过 Android 私有数据目录: " + file.getName());
                            continue;
                        }
                        // 递归进入子目录
                        scanDirectoryRecursive(file, fileMap);
                    } else if (file.isFile()) {
                        // 确保是文件，并且通常我们不关心空文件
                        if (file.length() > 0) {
                            String filename = file.getName();
                            if (!TextUtils.isEmpty(filename)) {
                                // 文件名作为 Key。如果遇到同名文件，此实现会用后面找到的覆盖前面的。
                                // 如果需要更复杂的冲突处理（例如，保留最新的），需要修改这里的逻辑。
                                fileMap.put(filename, file);
                                // Log.v(TAG, "添加到列表: " + filename + " Path: " + file.getAbsolutePath());
                            } else {
                                Log.w(TAG, "发现文件名为空的文件，跳过: " + file.getAbsolutePath());
                            }
                        } else {
                             Log.v(TAG, "跳过空文件: " + file.getName());
                        }
                    }
                } catch (SecurityException se) {
                    // 即使有 MANAGE_EXTERNAL_STORAGE，访问某些系统或特殊文件/目录仍可能被拒绝
                    Log.e(TAG, "访问被拒绝 (SecurityException): " + file.getAbsolutePath());
                    // 继续扫描其他文件/目录
                } catch (Exception e) {
                    // 捕获其他潜在错误，例如读取目录属性失败等
                    Log.e(TAG, "处理文件/目录时出错: " + file.getAbsolutePath(), e);
                    // 继续扫描
                }
            } // end for loop
        } else {
            // listFiles() 返回 null 通常意味着 IO 错误或没有读取该目录的权限
            Log.w(TAG, "listFiles() 返回 null，无法读取目录内容: " + directory.getAbsolutePath() + " (可能是 IO 错误或权限问题)");
        }
    }

    // 定义上传结果的枚举，方便处理不同的失败情况
    private enum UploadResult {
        SUCCESS,          // 上传成功
        FILE_NOT_FOUND,   // 文件在上传前找不到了 (例如被用户删除)
        IO_ERROR,         // 读文件或网络传输中的 IO 错误 (非特定服务器错误)
        CONFIG_ERROR,     // 配置错误 (例如 Retrofit 未初始化, IP 地址无效)
        UNEXPECTED_ERROR, // 其他未预料的异常
        RETRYABLE_ERROR   // 可重试的错误 (通常是网络问题或服务器端临时错误 5xx)
    }


    /**
     * 使用文件的 File 对象上传文件。直接从文件流式传输数据，内存效率高。
     *
     * @param filename 要在 multipart 请求中使用的文件名。
     * @param file     要上传的文件的 File 对象。
     * @return UploadResult 枚举值，指示上传结果。
     */
    private UploadResult uploadFile(String filename, File file) {
        try {
            // 1. 获取文件的 MIME 类型 (更健壮的方式)
            String mimeType = getMimeTypeFromFile(file); // 使用辅助方法获取
            if (mimeType == null) {
                mimeType = "application/octet-stream"; // 默认的二进制流类型
                Log.w(TAG, "无法确定文件 MIME 类型: " + file.getName() + "。使用默认类型: " + mimeType);
            }
            Log.d(TAG, "准备上传文件: " + filename + ", 路径: " + file.getAbsolutePath() + ", 大小: " + file.length() + " bytes, MIME: " + mimeType);

            // 2. ★★★ 创建直接从文件流式传输的 RequestBody (高效!) ★★★
            RequestBody requestBody = RequestBody.create(file, MediaType.parse(mimeType));

            // 3. 创建 MultipartBody.Part
            // "file" 是与服务器端约定的表单字段名 (part name)
            MultipartBody.Part body = MultipartBody.Part.createFormData("file", filename, requestBody);

            // 4. 执行上传的网络请求 (同步方式)
            Call<ResponseBody> uploadCall = apiService.uploadFile(body);
            Response<ResponseBody> uploadResponse = uploadCall.execute(); // 网络请求在此发生

            // 5. 检查服务器响应
            if (uploadResponse.isSuccessful()) {
                // HTTP 状态码 2xx 表示成功
                // 可选：读取 responseBody 如果服务器返回了有用的信息
                // responseBody.string(); // 注意：只能读取一次
                return UploadResult.SUCCESS;
            } else {
                // 上传失败，记录服务器返回的错误信息
                String errorBodyStr = "";
                try {
                    if(uploadResponse.errorBody() != null) errorBodyStr = uploadResponse.errorBody().string(); // 读取错误响应体
                } catch(IOException ignored) {} // 读取错误体也可能失败
                Log.e(TAG, "上传失败: " + filename + ". 服务器响应码: " + uploadResponse.code() + ", 消息: " + uploadResponse.message() + ", 错误体: " + errorBodyStr);

                // 根据 HTTP 状态码判断是否可重试
                if (uploadResponse.code() >= 500) {
                    // 5xx 通常表示服务器端错误，可以稍后重试
                    return UploadResult.RETRYABLE_ERROR;
                } else if (uploadResponse.code() == 408 || uploadResponse.code() == 429) {
                    // 408 (Request Timeout), 429 (Too Many Requests) 也可能适合重试
                    return UploadResult.RETRYABLE_ERROR;
                }
                else {
                    // 其他 4xx 错误 (如 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found)
                    // 通常表示客户端请求有问题或权限不足，重试通常无效
                    return UploadResult.IO_ERROR; // 归类为 IO 错误，但不标记为可重试
                }
            }

        } catch (FileNotFoundException e) {
            // 这个异常理论上不应该在这里发生，因为我们在调用前检查了 file.exists()
            // 但以防万一（例如文件在检查后到创建 RequestBody 之间被删除）
            Log.e(TAG, "上传时文件未找到 (FileNotFoundException): " + file.getAbsolutePath(), e);
            return UploadResult.FILE_NOT_FOUND;
        } catch (IOException e) {
            // 这个 IOException 可能是读取文件时发生的 (虽然少见，因为 RequestBody.create 处理了流)
            // 更常见的是网络连接问题 (例如超时、连接被拒绝、DNS 解析失败等)
            Log.e(TAG, "上传过程中发生 IOException (网络或文件读取错误): " + filename, e);
            // 网络相关的 IOException 通常是可重试的
            return UploadResult.RETRYABLE_ERROR;
        } catch (IllegalStateException e) {
            // 通常来自 RetrofitClient 未正确初始化 (例如 IP 地址未设置)
            Log.e(TAG, "上传时发生 IllegalStateException (Retrofit 配置问题?): " + e.getMessage());
            // 这是配置错误，重试无效
            return UploadResult.CONFIG_ERROR;
        } catch (Exception e) {
            // 捕获所有其他未预料的运行时异常
            Log.e(TAG, "上传时发生意外错误: " + filename, e);
            return UploadResult.UNEXPECTED_ERROR;
        }
    }

    /**
     * 辅助方法：根据文件获取其 MIME 类型。
     * 会尝试从文件扩展名推断。
     * @param file 要检查的文件
     * @return MIME 类型字符串，如果无法确定则返回 null。
     */
    @Nullable
    private String getMimeTypeFromFile(File file) {
        String mimeType = null;
        if (file != null && file.exists()) {
            mimeType = getMimeTypeFromExtension(file.getPath());
        }
        return mimeType;
    }

    /**
     * 辅助方法：从文件路径（主要是扩展名）获取 MIME 类型。
     * @param filePath 文件路径或 URL
     * @return MIME 类型字符串，如果无法确定则返回 null。
     */
    @Nullable
    private String getMimeTypeFromExtension(String filePath) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(filePath);
        if (!TextUtils.isEmpty(extension)) {
            // 使用 Android 提供的 MimeTypeMap 根据扩展名查找
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        return null;
    }


    // --- 通知相关的辅助方法 (与之前版本基本相同) ---

    /**
     * 创建通知渠道 (仅在 Android O / API 26 及以上版本需要)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "文件上传服务"; // 渠道名称 (用户可见)
            String description = "显示文件上传状态和进度的通知"; // 渠道描述 (用户可见)
            int importance = NotificationManager.IMPORTANCE_LOW; // 重要性设为 Low，避免打扰
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // 获取 NotificationManager 服务
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "通知渠道已创建或已存在: " + CHANNEL_ID);
            } else {
                Log.e(TAG, "无法获取 NotificationManager 服务来创建渠道。");
            }
        }
    }

    /**
     * 创建用于前台服务和状态更新的通知对象。
     * @param contentText 通知的主要文本内容。
     * @return 构建好的 Notification 对象。
     */
    private Notification createNotification(String contentText) {
        // ★★★ 确保你的项目中有一个名为 ic_sync (或其他你选择的名字) 的 drawable 图标 ★★★
        // 否则应用会崩溃。可以使用 Android Studio 的 Vector Asset 工具创建。
        // int smallIconResId = R.drawable.ic_sync; // 替换为你的同步图标
        // 如果没有特定图标，可以使用默认的启动图标作为后备：
        int smallIconResId = R.mipmap.ic_launcher; // 使用应用启动图标作为示例

        // 检查图标资源是否存在（可选，但有助于调试）
        try {
            context.getResources().getDrawable(smallIconResId, null);
        } catch (Exception e) {
            Log.e(TAG, "!!! 通知小图标资源未找到: " + smallIconResId + " !!! 使用默认图标代替。");
            smallIconResId = android.R.drawable.stat_sys_upload; // 使用系统上传图标
        }


        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("文件同步") // 通知标题
                .setContentText(contentText) // 通知内容
                .setSmallIcon(smallIconResId) // ★★★ 设置通知的小图标 (状态栏显示) ★★★
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级，避免声音和震动
                .setOngoing(true) // 使通知持续显示，不可被用户轻易划掉 (直到服务停止)
                .setOnlyAlertOnce(true); // 只有第一次显示通知时发出提示（如果优先级高的话），后续更新不打扰
        return builder.build();
    }

    /**
     * 更新状态通知的内容。
     * @param contentText 新的通知文本。
     */
    private void updateNotification(String contentText) {
        // 再次检查通知权限 (虽然不太可能在 Worker 运行时被撤销，但更安全)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "无法更新通知，因为 POST_NOTIFICATIONS 权限未被授予。");
                return; // 如果没有权限，则不尝试发送通知
            }
        }
        try {
            Notification notification = createNotification(contentText); // 创建带有新文本的通知
            notificationManager.notify(NOTIFICATION_ID, notification); // 使用相同的 ID 更新通知
            Log.d(TAG, "通知已更新: " + contentText);
        } catch (Exception e) {
            // 捕获更新通知时可能发生的罕见异常
            Log.e(TAG, "更新通知时失败", e);
        }
    }
}