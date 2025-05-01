package com.example.myapplication.ToolData;


import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;

/**
 * 尝试跳转到不同厂商的自启动/后台管理/电池优化设置页面的帮助类。
 */
public class AutoStartHelper {

    private static final String TAG = "AutoStartHelper"; // 日志标签

    // --- 移除 PREF_AUTO_START_GUIDANCE_SHOWN 常量定义 ---

    /**
     * 显示引导对话框，解释为何需要后台权限，并提供跳转按钮。
     * 这个方法现在被 PermissionHelper 调用，前提是 PermissionHelper 确定需要显示引导。
     *
     * @param activity 用于显示对话框和启动 Activity 的 Activity 上下文。
     */
    public static void requestAutoStartPermission(AppCompatActivity activity) {
        // **逻辑修正**: 不再需要在这里检查标志位。
        // PermissionHelper 已经确认需要显示引导，所以我们直接显示对话框。
        Log.i(TAG, "AutoStartHelper: 显示后台权限引导对话框。");
        showGoToSettingsDialog(activity);
    }

    /**
     * 构建并显示引导对话框。
     *
     * @param activity Activity 上下文。
     */
    private static void showGoToSettingsDialog(AppCompatActivity activity) {
        // 健壮性检查：确保 activity 仍然有效
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "尝试显示引导对话框，但 Activity 无效或已结束。");
            return;
        }

        String title = "启用后台运行权限"; // 对话框标题
        String message = "为了确保服务能在后台稳定运行并在设备重启后自动启动，请在系统设置中允许应用“自启动”或“后台运行”，并将电池优化设置为“无限制”或“不优化”。\n\n（不同手机厂商设置项名称可能不同）"; // 对话框消息内容

        new AlertDialog.Builder(activity) // 使用传入的 Activity 上下文
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("去设置", (dialog, which) -> {
                    // 用户点击“去设置”按钮
                    Log.i(TAG, "用户点击 '去设置'。尝试打开设置页面并保存引导标志。");
                    // 尝试跳转到系统设置页面
                    openAutoStartSettings(activity);
                    // **重要**: 用户同意去设置后，我们将标志位设为 true，表示已经引导过。
                    // 即使跳转失败，我们也认为引导过程已经完成，避免反复打扰。
                    JsonDataStorage.saveBoolean(activity, com.example.myapplication.ToolData.PermissionHelper.PREF_AUTO_START_GUIDANCE_SHOWN, true);
                    dialog.dismiss(); // 关闭对话框
                })
                .setNegativeButton("以后再说", (dialog, which) -> {
                    // 用户点击“以后再说”按钮
                    Log.i(TAG, "用户点击 '以后再说'。对话框关闭，引导标志保持不变。");
                    // 不保存标志位，下次可能还会弹出引导（取决于 PermissionHelper 的调用时机）
                    dialog.dismiss(); // 关闭对话框
                })
                .setCancelable(false) // 设置为 false，强制用户必须选择一个按钮
                .show(); // 显示对话框
    }


    /**
     * 核心方法：尝试打开各种已知的自启动、电池优化或应用管理设置页面。
     * 注意：这些 Intent 高度依赖设备制造商和 Android 版本，可能会失效。
     *
     * @param context 上下文。
     */
    public static void openAutoStartSettings(Context context) {
        // 健壮性检查
        if (context == null) {
            Log.e(TAG, "尝试打开设置，但 Context 为 null。");
            return;
        }

        String manufacturer = Build.MANUFACTURER.toLowerCase(); // 获取小写的制造商名称
        Log.i(TAG, "尝试为制造商打开自启动/后台设置: " + manufacturer);

        boolean success = false; // 标记是否成功打开了某个设置页面

        // 定义一个包含已知厂商 Intent 的列表 (需要持续更新和测试)
        List<Intent> intents = Arrays.asList(
                // 小米 (MIUI)
                new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), // 自启动管理
                new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT), // 另一个自启动 Action
                new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")), // 神隐模式 / 电池与性能

                // 华为 (EMUI / HarmonyOS)
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")), // 应用启动管理
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")), // 受保护的应用 (旧版)
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")), // 另一个启动管理
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity")), // 权限管理

                // OPPO (ColorOS)
                new Intent().setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")), // 自启动管理 (旧)
                new Intent().setComponent(new ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")), // 自启动管理 (新)
                new Intent().setComponent(new ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")), // 耗电保护
                new Intent().setComponent(new ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")), // 耗电详情
                new Intent().setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")), // 权限隐私

                // VIVO (Funtouch OS / OriginOS)
                new Intent().setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")), // 后台高耗电
                new Intent().setComponent(new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")), // 应用白名单 (iQOO)
                new Intent().setComponent(new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")), // 后台管理 (iQOO)
                new Intent().setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")), // 权限管理

                // 三星 (One UI)
                new Intent().setComponent(new ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")), // 电池管理 (旧)
                new Intent().setComponent(new ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")), // 电池管理 (新)
                new Intent().setComponent(new ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")), // 电池管理 (国行?)
                new Intent().setComponent(new ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.AppSleepListActivity")), // 深度休眠应用程序
                new Intent().setComponent(new ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.permission.AppPermissionEditorActivity")),//权限管理

                // 一加 (OxygenOS / ColorOS based) - 可能与 OPPO 类似，或需要特定 Intent
                new Intent().setComponent(new ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")), // 关联启动管理 (旧)
                new Intent().setComponent(new ComponentName("com.oneplus.security", "com.oneplus.security.background.BackgroundAppListActivity")), // 应用后台管理 (可能存在)

                // 魅族 (Flyme)
                new Intent().setComponent(new ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")), // 后台管理
                new Intent().setComponent(new ComponentName("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity")), // 权限管理主页
                new Intent().setComponent(new ComponentName("com.meizu.safe", "com.meizu.safe.power.PowerAppPermissionActivity")) // 耗电优化/权限

                // 可以继续添加其他厂商或原生 Android 的 Intent
        );

        PackageManager pm = context.getPackageManager();

        // 遍历尝试所有已知的 Intent
        for (Intent intent : intents) {
            // 检查这个 Intent 是否可能被处理（目标 Activity 是否存在）
            // resolveActivity 比 getLaunchIntentForPackage 更准确地检查 Activity 是否存在且可处理该 Intent
            if (intent.resolveActivity(pm) != null) {
                try {
                    Log.i(TAG, "尝试启动已知 Intent: " + intent);
                    context.startActivity(intent);
                    success = true; // 标记成功
                    break; // 成功启动一个就不再尝试其他的
                } catch (ActivityNotFoundException e) {
                    Log.w(TAG, "启动失败 (ActivityNotFound): " + intent);
                    // 继续尝试下一个
                } catch (SecurityException se) {
                    Log.e(TAG, "启动时发生安全异常 (可能缺少权限或目标 Activity 未导出): " + intent, se);
                    // 继续尝试下一个
                } catch (Exception e) {
                    // 捕获其他可能的异常
                    Log.e(TAG, "启动时发生未知异常: " + intent, e);
                    // 继续尝试下一个
                }
            } else {
                Log.d(TAG, "Intent 无法解析，跳过: " + intent);
            }
        }

        // 如果所有特定厂商的 Intent 都失败了，尝试通用的设置页面
        if (!success) {
            Log.w(TAG, "未能找到特定厂商的设置页面，尝试通用设置...");
            try {
                // 1. 尝试跳转到“忽略电池优化”设置页面 (最相关)
                // 注意：这需要 <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" /> 权限才能直接请求忽略，
                // 但跳转到设置页面本身通常不需要特殊权限。
                Intent batteryIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                if (batteryIntent.resolveActivity(pm) != null) {
                    Log.i(TAG, "尝试启动通用设置：ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
                    context.startActivity(batteryIntent);
                    Toast.makeText(context, "请在此页面找到您的应用，并将其电池优化设置为“不受限制”或“不优化”", Toast.LENGTH_LONG).show();
                    success = true; // 标记成功打开了一个设置页面
                } else {
                    Log.w(TAG, "通用设置 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS 无法解析。");
                    // 2. 如果“忽略电池优化”页面打不开，尝试跳转到应用的详情设置页面
                    Intent appDetailIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", context.getPackageName(), null);
                    appDetailIntent.setData(uri);
                    if (appDetailIntent.resolveActivity(pm) != null) {
                        Log.i(TAG, "尝试启动通用设置：ACTION_APPLICATION_DETAILS_SETTINGS");
                        context.startActivity(appDetailIntent);
                        Toast.makeText(context, "无法直接打开特定设置，请在应用详情页面中查找“权限”、“电池”或“自启动”相关选项。", Toast.LENGTH_LONG).show();
                        success = true; // 标记成功打开了一个设置页面
                    } else {
                        // 3. 如果连应用详情页都打不开（极少见）
                        Log.e(TAG, "通用设置 ACTION_APPLICATION_DETAILS_SETTINGS 也无法解析！");
                        Toast.makeText(context, "无法自动跳转到系统设置。请手动前往系统设置，找到本应用，并检查“自启动”、“后台运行”及“电池优化”相关权限。", Toast.LENGTH_LONG).show();
                    }
                }
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "尝试打开通用设置时发生 ActivityNotFoundException", e);
                Toast.makeText(context, "无法自动跳转到系统设置。请手动前往。", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e(TAG, "尝试打开通用设置时发生未知异常", e);
                Toast.makeText(context, "打开设置时出错，请手动前往。", Toast.LENGTH_LONG).show();
            }
        }

        // --- 移除可能存在的 saveBoolean 调用 ---
        // 保存标志位的操作现在统一在 showGoToSettingsDialog 的 PositiveButton 点击时执行。
    }
}