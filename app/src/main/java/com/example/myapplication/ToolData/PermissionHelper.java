package com.example.myapplication.ToolData;

import android.content.Context;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 权限相关的帮助类
 * 主要负责管理“后台/自启动权限引导”的显示逻辑。
 */
public class PermissionHelper {

    private static final String TAG = "PermissionHelper"; // 日志标签

    // 将用于存储“引导是否已显示”状态的 Key 定义在这里，集中管理
    public static final String PREF_AUTO_START_GUIDANCE_SHOWN = "auto_start_guidance_shown";

    /**
     * 检查是否需要显示“后台/自启动权限”的引导，并在需要时调用 AutoStartHelper 来启动引导流程。
     * 这个方法是主要的入口点，它根据存储的标志位决定是否进行引导。
     *
     * @param activity 调用此方法的 Activity 实例。需要 Activity 上下文来显示可能的对话框。
     *                 确保传入的 activity 继承自 AppCompatActivity 或其子类。
     */
    public static void checkAndRequestBackgroundPermissionsGuidance(AppCompatActivity activity) {
        // 健壮性检查：确保 activity 不为 null
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "尝试检查权限引导，但 Activity 无效或已结束。");
            return;
        }

        // 使用 JsonDataStorage 读取标志位，检查之前是否已经显示过引导
        boolean guidanceShown = JsonDataStorage.getBoolean(activity, PREF_AUTO_START_GUIDANCE_SHOWN, false);

        if (!guidanceShown) {
            // 如果标志位为 false，表示从未显示过引导
            Log.i(TAG, "后台/自启动权限引导尚未显示，准备请求引导...");
            // 调用 AutoStartHelper 中的方法来显示解释对话框并尝试跳转到设置
            // AutoStartHelper 内部会处理对话框的显示逻辑
            AutoStartHelper.requestAutoStartPermission(activity);
        } else {
            // 如果标志位为 true，表示之前已经引导过用户
            Log.i(TAG, "后台/自启动权限引导已显示过，本次不主动弹出。");
            // 这里可以选择性地提供一个让用户手动访问设置的入口（例如，在设置菜单里）
            // 可以通过调用 AutoStartHelper.openAutoStartSettings(activity) 来实现
        }
    }

    /**
     * (可选功能) 提供一个直接尝试打开相关系统设置页面的方法。
     * 这个方法会绕过检查“引导是否已显示”的标志位。
     * 适用于应用内提供一个明确的“后台运行设置”按钮或菜单项。
     *
     * @param context 上下文环境。
     */
    public static void openBackgroundPermissionSettings(Context context) {
        // 健壮性检查：确保 context 不为 null
        if (context == null) {
            Log.w(TAG, "尝试打开后台设置，但 Context 为 null。");
            return;
        }
        Log.i(TAG, "通过 AutoStartHelper 直接尝试打开后台/自启动设置页面...");
        AutoStartHelper.openAutoStartSettings(context);
    }
}