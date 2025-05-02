package com.example.myapplication.ToolData; // 替换为你的包名

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 权限相关的帮助类
 * 负责管理运行时权限（通知、相机、传统存储）的检查与请求流程，
 * 以及特殊权限 MANAGE_EXTERNAL_STORAGE (All Files Access) 的检查与引导，
 * 还有“后台/自启动权限引导”的显示逻辑。
 */
public class PermissionHelper {

    private static final String TAG = "PermissionHelper";

    // --- 运行时权限请求码 ---
    public static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    public static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 102;
    public static final int LEGACY_STORAGE_PERMISSION_REQUEST_CODE = 103; // 用于 API <= 29

    // --- 后台/自启动权限引导相关 ---
    public static final String PREF_AUTO_START_GUIDANCE_SHOWN = "auto_start_guidance_shown";

    /**
     * 回调接口，当所有必需的运行时和特殊权限都被授予时调用。
     */
    public interface PermissionsGrantedCallback {
        void onPermissionsGranted();
    }

    //======================================================================
    // 主要的权限检查与请求入口 (包含 MANAGE_EXTERNAL_STORAGE)
    //======================================================================

    /**
     * 检查并请求服务运行所必需的核心权限。
     * 顺序: 存储 (MANAGE_EXTERNAL_STORAGE 或 READ_EXTERNAL_STORAGE) -> 通知 -> 相机
     *
     * @param activity Activity 实例。
     * @param manageStorageLauncher 用于启动 MANAGE_EXTERNAL_STORAGE 设置页面的 Launcher。
     * @param callback 当所有权限都满足时将被调用的回调。
     * @return true 如果所有权限已满足并调用了回调；false 如果发起了权限请求或跳转，需要等待结果。
     */
    public static boolean checkAndRequestEssentialPermissions(
            @NonNull AppCompatActivity activity,
            @NonNull ActivityResultLauncher<Intent> manageStorageLauncher, // 需要传入 Launcher
            @NonNull PermissionsGrantedCallback callback) {
        Log.d(TAG, "开始检查核心权限 (含存储)...");

        // 1. 检查存储权限 (根据 Android 版本区分)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11 (API 30) 或更高
            if (!Environment.isExternalStorageManager()) {
                Log.i(TAG, "检查：MANAGE_EXTERNAL_STORAGE 权限未授予 (API 30+)。");
                showManageExternalStorageRationale(activity, manageStorageLauncher);
                return false; // 跳转到设置或显示了理由，等待结果
            }
            Log.d(TAG, "检查：MANAGE_EXTERNAL_STORAGE 权限已满足 (API 30+)。");
        } else { // Android 10 (API 29) 或更低
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "检查：READ_EXTERNAL_STORAGE 权限未授予 (API <= 29)。");
                requestPermissionWithRationale(activity,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        LEGACY_STORAGE_PERMISSION_REQUEST_CODE,
                        "需要存储权限",
                        "应用需要读取您设备上的文件以进行上传。请授予存储访问权限。");
                // 如果还需要写入，也应检查 WRITE_EXTERNAL_STORAGE (API <= 29)
                // if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) { ... }
                return false; // 发起了请求或显示了理由，等待结果
            }
            Log.d(TAG, "检查：READ_EXTERNAL_STORAGE 权限已满足 (API <= 29)。");
        }

        // 2. 检查通知权限 (Android 13+) - 逻辑不变
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "检查：通知权限未授予 (Android 13+)。");
                requestPermissionWithRationale(activity,
                        Manifest.permission.POST_NOTIFICATIONS,
                        NOTIFICATION_PERMISSION_REQUEST_CODE,
                        "需要通知权限",
                        "应用需要在后台运行时显示服务状态通知。请授予通知权限。");
                return false;
            }
            Log.d(TAG, "检查：通知权限已满足 (Android 13+)。");
        } else {
            Log.d(TAG, "检查：低于 Android 13，无需运行时通知权限。");
        }

        // 3. 检查相机权限 - 逻辑不变
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "检查：相机权限未授予。");
            requestPermissionWithRationale(activity,
                    Manifest.permission.CAMERA,
                    CAMERA_PERMISSION_REQUEST_CODE,
                    "需要相机权限",
                    "应用需要使用相机来提供服务。请授予相机权限。");
            return false;
        }
        Log.d(TAG, "检查：相机权限已满足。");

        // --- 如果代码执行到这里，说明所有必要权限都已授予 ---
        Log.i(TAG, "所有核心运行时和特殊权限已就绪！");
        callback.onPermissionsGranted(); // 调用回调
        return true; // 所有权限满足
    }

    /**
     * 显示需要 MANAGE_EXTERNAL_STORAGE 权限的理由对话框，并引导用户去设置。
     *
     * @param activity Activity 上下文
     * @param manageStorageLauncher 用于启动设置页面的 Launcher
     */
    private static void showManageExternalStorageRationale(
            @NonNull AppCompatActivity activity,
            @NonNull ActivityResultLauncher<Intent> manageStorageLauncher) {

        new AlertDialog.Builder(activity)
                .setTitle("需要“所有文件访问权限”")
                .setMessage("为了能够查找并上传您设备上的所有类型文件（不仅仅是媒体文件），本应用需要“所有文件访问”权限。\n\n" +
                        "请在接下来的系统设置页面中，找到本应用并开启此权限。\n\n" +
                        "(警告：此权限非常敏感，请确保您信任本应用)")
                .setPositiveButton("前往设置", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                    intent.setData(uri);
                    try {
                        Log.i(TAG, "启动 ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 设置页面...");
                        manageStorageLauncher.launch(intent); // 使用 Launcher 启动
                    } catch (Exception e) {
                        Log.e(TAG, "无法启动“所有文件访问权限”设置页面", e);
                        // 备用方案：跳转到应用详情页
                        Intent fallbackIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        fallbackIntent.setData(uri);
                        try {
                            activity.startActivity(fallbackIntent);
                            Toast.makeText(activity,"无法直接打开权限设置，请在应用设置中手动查找并开启“文件和媒体”->“允许管理所有文件”", Toast.LENGTH_LONG).show();
                        } catch (Exception innerEx) {
                            Log.e(TAG, "无法启动应用详情设置页面", innerEx);
                            Toast.makeText(activity,"无法打开应用设置，请手动操作。", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    Toast.makeText(activity, "未授予“所有文件访问权限”，文件上传功能将受限。", Toast.LENGTH_LONG).show();
                })
                .setCancelable(false) // 最好不要让用户轻易取消这个解释
                .show();
    }


    // requestPermissionWithRationale 方法保持不变
    private static void requestPermissionWithRationale(@NonNull AppCompatActivity activity,
                                                       @NonNull String permission,
                                                       int requestCode,
                                                       @NonNull String rationaleTitle,
                                                       @NonNull String rationaleMessage) {
        // ... (代码与之前版本相同) ...
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            Log.d(TAG, "显示权限 [" + permission + "] 的理由...");
            new AlertDialog.Builder(activity)
                    .setTitle(rationaleTitle)
                    .setMessage(rationaleMessage)
                    .setPositiveButton("去授权", (dialog, which) -> {
                        Log.d(TAG, "用户同意理由，发起权限 [" + permission + "] 请求 (Code: " + requestCode + ")");
                        ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                        Log.w(TAG, "用户取消了权限 [" + permission + "] 的理由对话框。");
                        Toast.makeText(activity, rationaleTitle + "未授予，相关功能可能无法使用", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } else {
            Log.d(TAG, "无需显示理由，直接发起权限 [" + permission + "] 请求 (Code: " + requestCode + ")");
            ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
        }
    }

    // handlePermissionDenied 方法需要更新以处理 LEGACY_STORAGE
    public static void handlePermissionDenied(@NonNull Activity activity,
                                              @NonNull String permission,
                                              @NonNull int[] grantResults,
                                              @NonNull String permissionNameForToast) {
        // ... (之前的逻辑基本不变) ...
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            Log.w(TAG, "权限 [" + permission + "] 被用户拒绝。");
            if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                Log.w(TAG, "用户已拒绝权限 [" + permission + "] 并不再询问。提示手动设置。");
                showManuallyEnablePermissionDialog(activity, permissionNameForToast);
            } else {
                Toast.makeText(activity, "必须授予 " + permissionNameForToast + " 权限才能使用相关功能！", Toast.LENGTH_LONG).show();
            }
        }
    }

    // showManuallyEnablePermissionDialog 方法保持不变
    private static void showManuallyEnablePermissionDialog(@NonNull Activity activity, @NonNull String permissionName) {
        // ... (代码与之前版本相同) ...
        new AlertDialog.Builder(activity)
                .setTitle(permissionName + "权限已被禁用")
                .setMessage("您已禁用 " + permissionName + " 权限并不再提示。请在应用设置中手动开启权限，以确保相关功能可以正常使用。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                    intent.setData(uri);
                    try {
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "跳转到应用设置失败", e);
                        Toast.makeText(activity, "无法自动跳转，请手动前往系统设置 -> 应用 -> " + getAppName(activity) + " -> 权限，并开启所需权限。", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // getAppName 方法保持不变
    private static String getAppName(Context context) { /* ... */
        try {
            PackageManager packageManager = context.getPackageManager();
            return (String) packageManager.getApplicationLabel(context.getApplicationInfo());
        } catch (Exception e) {
            Log.e(TAG, "获取应用名称失败", e);
            return "本应用"; // 返回通用名称
        }
    }

    // --- 后台/自启动权限引导 (保留，可以在权限都获取后再调用) ---
    public static void checkAndRequestBackgroundPermissionsGuidance(AppCompatActivity activity) {
        // ... (代码与之前版本相同，依赖 JsonDataStorage 和 AutoStartHelper) ...
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "尝试检查后台权限引导，但 Activity 无效或已结束。");
            return;
        }
        try {
            boolean guidanceShown = JsonDataStorage.getBoolean(activity, PREF_AUTO_START_GUIDANCE_SHOWN, false);
            if (!guidanceShown) {
                Log.i(TAG, "后台/自启动权限引导尚未显示，准备请求引导...");
                AutoStartHelper.requestAutoStartPermission(activity);
            } else {
                Log.i(TAG, "后台/自启动权限引导已显示过，本次不主动弹出。");
            }
        } catch (NoClassDefFoundError e) {
            Log.e(TAG, "无法找到 JsonDataStorage 或 AutoStartHelper 类！后台引导功能无法使用。", e);
            Toast.makeText(activity, "后台引导功能依赖缺失，无法执行。", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "检查后台权限引导时发生错误。", e);
        }
    }

    public static void openBackgroundPermissionSettings(Context context) {
        // ... (代码与之前版本相同，依赖 AutoStartHelper) ...
        if (context == null) {
            Log.w(TAG, "尝试打开后台设置，但 Context 为 null。");
            return;
        }
        try {
            Log.i(TAG, "通过 AutoStartHelper 直接尝试打开后台/自启动设置页面...");
            AutoStartHelper.openAutoStartSettings(context);
        } catch (NoClassDefFoundError e) {
            Log.e(TAG, "无法找到 AutoStartHelper 类！打开后台设置功能无法使用。", e);
            Toast.makeText(context, "后台设置功能依赖缺失，无法执行。", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "打开后台设置时发生错误。", e);
        }
    }
}