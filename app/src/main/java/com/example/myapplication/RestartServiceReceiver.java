// --- START OF FILE RestartServiceReceiver.java (Modified) ---
package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences; // 引入 SharedPreferences
import android.os.Build;
import android.text.TextUtils; // 引入 TextUtils 用于检查空字符串
import android.util.Log;
import android.widget.Toast;

// RestartServiceReceiver.java (广播接收器 - 修改后)
public class RestartServiceReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "CameraServicePrefs"; // SharedPreferences 文件名
    private static final String KEY_IP_ADDRESS = "last_ip_address"; // IP 地址的键名

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "RestartCameraService".equals(intent.getAction())) { // 自定义action

            Log.i("RestartServiceReceiver", "接收到重启服务广播/启动完成广播");

            // 从 SharedPreferences 获取 IP 地址
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String savedIpAddress = prefs.getString(KEY_IP_ADDRESS, null); // 尝试获取保存的IP

            if (!TextUtils.isEmpty(savedIpAddress)) { // 检查IP是否有效
                Log.i("RestartServiceReceiver", "获取到保存的 IP 地址: " + savedIpAddress + "，尝试启动服务...");

                Intent serviceIntent = new Intent(context, CameraStreamService.class);
                serviceIntent.putExtra("IP_ADDRESS", savedIpAddress); // 使用保存的IP地址

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                        Log.i("RestartServiceReceiver", "已使用 startForegroundService 启动服务");
                    } else {
                        context.startService(serviceIntent);
                        Log.i("RestartServiceReceiver", "已使用 startService 启动服务");
                    }
                    // Toast.makeText(context, "服务正在后台重新启动...", Toast.LENGTH_SHORT).show(); // 可选提示
                } catch (Exception e) {
                    Log.e("RestartServiceReceiver", "启动服务时发生错误: " + e.getMessage());
                    Toast.makeText(context, "自动重启服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }

            } else {
                // 如果没有保存有效的 IP 地址，则无法重启
                Log.e("RestartServiceReceiver", "未能在 SharedPreferences 中找到有效的 IP 地址，无法重启服务。");
                Toast.makeText(context, "无法自动重启服务：缺少 IP 地址信息", Toast.LENGTH_LONG).show();
                // 这里不应该再依赖 MainActivity.IP_ADDRESS_
            }
        } else {
            Log.d("RestartServiceReceiver", "接收到未处理的广播: " + intent.getAction());
        }
    }
}
// --- END OF FILE RestartServiceReceiver.java (Modified) ---