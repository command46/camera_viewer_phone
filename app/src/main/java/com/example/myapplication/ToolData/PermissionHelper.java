package com.example.myapplication.ToolData; // 请确保包名正确

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity; // 需要 AppCompatActivity 来显示 AlertDialog
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 权限相关的帮助类
 * 负责管理运行时权限（通知、相机等）的检查与请求流程，
 * 以及“后台/自启动权限引导”的显示逻辑。
 */
public class PermissionHelper {

    private static final String TAG = "PermissionHelper"; // 日志标签

    // --- 运行时权限请求码 ---
    // 确保这些请求码在你的 Activity 中是唯一的，或者根据需要调整
    public static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    public static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 102;
    // 如果有其他权限组，可以继续定义
    // public static final int STORAGE_PERMISSION_REQUEST_CODE = 103;

    // --- 后台/自启动权限引导相关 ---
    public static final String PREF_AUTO_START_GUIDANCE_SHOWN = "auto_start_guidance_shown";


    /**
     * 回调接口，当所有必需的运行时权限都被授予时调用。
     */
    public interface PermissionsGrantedCallback {
        /**
         * 当所有检查的权限都已授予时调用此方法。
         * 调用者（通常是 Activity）应在此方法中执行后续操作，例如启动服务。
         */
        void onPermissionsGranted();
    }

    //======================================================================
    // 主要的运行时权限检查与请求入口
    //======================================================================

    /**
     * 检查并请求服务运行所必需的核心运行时权限（当前为通知和相机）。
     * 如果权限已全部授予，则立即调用 callback.onPermissionsGranted()。
     * 如果缺少权限，则会发起权限请求，调用者需要在 Activity 的 onRequestPermissionsResult 中处理结果，
     * 并在权限被授予后 *再次调用此方法* 来继续检查流程。
     *
     * @param activity Activity 实例，需要用于请求权限和显示对话框。
     * @param callback 当所有权限都满足时将被调用的回调。
     * @return true 如果所有权限已满足并调用了回调；false 如果发起了权限请求，需要等待结果。
     */
    public static boolean checkAndRequestEssentialPermissions(@NonNull AppCompatActivity activity, @NonNull PermissionsGrantedCallback callback) {
        Log.d(TAG, "开始检查核心运行时权限...");

        // 1. 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "检查：通知权限未授予 (Android 13+)。");
                requestPermissionWithRationale(activity,
                        Manifest.permission.POST_NOTIFICATIONS,
                        NOTIFICATION_PERMISSION_REQUEST_CODE,
                        "需要通知权限",
                        "应用需要在后台运行时显示服务状态通知，以确保其正常运行并告知您连接状态。请授予通知权限。");
                return false; // 发起了请求或显示了理由，等待结果
            }
            Log.d(TAG, "检查：通知权限已满足 (Android 13+)。");
        } else {
            Log.d(TAG, "检查：低于 Android 13，无需运行时通知权限。");
        }

        // 2. 检查相机权限
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "检查：相机权限未授予。");
            requestPermissionWithRationale(activity,
                    Manifest.permission.CAMERA,
                    CAMERA_PERMISSION_REQUEST_CODE,
                    "需要相机权限",
                    "应用需要使用相机来提供摄像头流服务。请授予相机权限。");
            return false; // 发起了请求或显示了理由，等待结果
        }
        Log.d(TAG, "检查：相机权限已满足。");

        // --- 如果代码执行到这里，说明所有必要权限都已授予 ---
        Log.i(TAG, "所有核心运行时权限已就绪！");
        callback.onPermissionsGranted(); // 调用回调
        return true; // 所有权限满足
    }

    /**
     * 请求单个权限，并处理显示理由 (Rationale) 的逻辑。
     *
     * @param activity      用于请求权限和显示对话框的 Activity。
     * @param permission    要请求的权限。
     * @param requestCode   请求码。
     * @param rationaleTitle 对话框标题。
     * @param rationaleMessage 对话框消息。
     */
    private static void requestPermissionWithRationale(@NonNull AppCompatActivity activity,
                                                       @NonNull String permission,
                                                       int requestCode,
                                                       @NonNull String rationaleTitle,
                                                       @NonNull String rationaleMessage) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            // 系统建议显示理由给用户
            Log.d(TAG, "显示权限 [" + permission + "] 的理由...");
            new AlertDialog.Builder(activity)
                    .setTitle(rationaleTitle)
                    .setMessage(rationaleMessage)
                    .setPositiveButton("去授权", (dialog, which) -> {
                        // 用户同意，发起权限请求
                        Log.d(TAG, "用户同意理由，发起权限 [" + permission + "] 请求 (Code: " + requestCode + ")");
                        ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                        // 用户拒绝，可以提示功能受限
                        Log.w(TAG, "用户取消了权限 [" + permission + "] 的理由对话框。");
                        Toast.makeText(activity, rationaleTitle + "未授予，相关功能可能无法使用", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } else {
            // 不需要显示理由（首次请求或用户选了“不再询问”）
            Log.d(TAG, "无需显示理由，直接发起权限 [" + permission + "] 请求 (Code: " + requestCode + ")");
            ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
            // 如果是“不再询问”导致的无需理由，ActivityCompat.requestPermissions 不会弹出系统对话框
            // 用户需要在 onRequestPermissionsResult 回调中被引导去设置
        }
    }

    /**
     * 在 Activity 的 onRequestPermissionsResult 中调用此方法，
     * 以便在用户拒绝权限（尤其是勾选“不再询问”后）时引导他们去设置。
     *
     * @param activity      调用此方法的 Activity。
     * @param permission    被拒绝的权限。
     * @param grantResults  权限请求结果数组 (通常只关心第一个元素)。
     * @param permissionNameForToast 权限的友好名称，用于 Toast 提示 (例如 "通知", "相机")。
     */
    public static void handlePermissionDenied(@NonNull Activity activity,
                                              @NonNull String permission,
                                              @NonNull int[] grantResults,
                                              @NonNull String permissionNameForToast) {
        // 检查是否真的被拒绝了
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            Log.w(TAG, "权限 [" + permission + "] 被用户拒绝。");
            // 检查用户是否勾选了“不再询问”
            if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                // 用户勾选了“不再询问”并拒绝
                Log.w(TAG, "用户已拒绝权限 [" + permission + "] 并不再询问。提示手动设置。");
                showManuallyEnablePermissionDialog(activity, permissionNameForToast);
            } else {
                // 用户本次拒绝，但没有勾选“不再询问”
                Toast.makeText(activity, "必须授予 " + permissionNameForToast + " 权限才能使用相关功能！", Toast.LENGTH_LONG).show();
            }
        }
    }


    /**
     * 显示一个对话框，提示用户需要手动去应用设置中开启权限。
     * @param activity Activity 上下文
     * @param permissionName 权限的友好名称 (e.g., "通知", "相机")
     */
    private static void showManuallyEnablePermissionDialog(@NonNull Activity activity, @NonNull String permissionName) {
        new AlertDialog.Builder(activity)
                .setTitle(permissionName + "权限已被禁用")
                .setMessage("您已禁用 " + permissionName + " 权限并不再提示。请在应用设置中手动开启权限，以确保相关功能可以正常使用。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    // 跳转到应用的设置页面
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


    //======================================================================
    // 后台/自启动权限引导 (保留原有逻辑)
    //======================================================================

    /**
     * 检查是否需要显示“后台/自启动权限”的引导，并在需要时调用 AutoStartHelper 来启动引导流程。
     * 这个方法是主要的入口点，它根据存储的标志位决定是否进行引导。
     * 建议在获取所有运行时权限之后调用此方法。
     *
     * @param activity 调用此方法的 Activity 实例。需要 Activity 上下文来显示可能的对话框。
     *                 确保传入的 activity 继承自 AppCompatActivity 或其子类。
     */
    public static void checkAndRequestBackgroundPermissionsGuidance(AppCompatActivity activity) {
        // 健壮性检查
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "尝试检查后台权限引导，但 Activity 无效或已结束。");
            return;
        }

        // !! 注意：这里假设 JsonDataStorage 和 AutoStartHelper 类存在且可用 !!
        // !! 如果它们不存在或有修改，需要相应调整 !!
        try {
            // 使用 JsonDataStorage 读取标志位 (需要确保 JsonDataStorage 实现存在)
            boolean guidanceShown = JsonDataStorage.getBoolean(activity, PREF_AUTO_START_GUIDANCE_SHOWN, false);

            if (!guidanceShown) {
                Log.i(TAG, "后台/自启动权限引导尚未显示，准备请求引导...");
                // 调用 AutoStartHelper 中的方法来显示解释对话框并尝试跳转 (需要确保 AutoStartHelper 实现存在)
                AutoStartHelper.requestAutoStartPermission(activity);
                // 可以在 AutoStartHelper 内部或这里更新标志位
                 JsonDataStorage.saveBoolean(activity, PREF_AUTO_START_GUIDANCE_SHOWN, true);
            } else {
                Log.i(TAG, "后台/自启动权限引导已显示过，本次不主动弹出。");
            }
        } catch (NoClassDefFoundError e) {
            Log.e(TAG, "无法找到 JsonDataStorage 或 AutoStartHelper 类！后台引导功能无法使用。", e);
            Toast.makeText(activity, "后台引导功能依赖缺失，无法执行。", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "检查后台权限引导时发生错误。", e);
            // 其他可能的异常
        }
    }

    /**
     * (可选功能) 提供一个直接尝试打开相关系统设置页面的方法。
     * 适用于应用内提供一个明确的“后台运行设置”按钮或菜单项。
     *
     * @param context 上下文环境。
     */
    public static void openBackgroundPermissionSettings(Context context) {
        if (context == null) {
            Log.w(TAG, "尝试打开后台设置，但 Context 为 null。");
            return;
        }
        try {
            Log.i(TAG, "通过 AutoStartHelper 直接尝试打开后台/自启动设置页面...");
            // 调用 AutoStartHelper 中的方法尝试跳转 (需要确保 AutoStartHelper 实现存在)
            AutoStartHelper.openAutoStartSettings(context);
        } catch (NoClassDefFoundError e) {
            Log.e(TAG, "无法找到 AutoStartHelper 类！打开后台设置功能无法使用。", e);
            Toast.makeText(context, "后台设置功能依赖缺失，无法执行。", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "打开后台设置时发生错误。", e);
            // 其他可能的异常
        }
    }

    /**
     * 获取应用程序名称
     */
    private static String getAppName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            return (String) packageManager.getApplicationLabel(context.getApplicationInfo());
        } catch (Exception e) {
            Log.e(TAG, "获取应用名称失败", e);
            return "本应用"; // 返回通用名称
        }
    }
}