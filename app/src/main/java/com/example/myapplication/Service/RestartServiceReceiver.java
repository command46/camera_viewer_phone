// --- START OF FILE RestartServiceReceiver.java (无需修改) ---
package com.example.myapplication.Service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.example.myapplication.MainActivity;
import com.example.myapplication.ToolData.JsonDataStorage;

// RestartServiceReceiver.java (广播接收器 - 无需修改)
// 它的职责是接收系统启动完成或自定义的重启广播，并尝试启动服务
public class RestartServiceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // 检查是否是系统启动完成或我们自定义的重启 Action
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "RestartCameraService".equals(intent.getAction())) {

            Log.i("RestartServiceReceiver", "接收到重启服务广播/启动完成广播");

            String savedIpAddress = JsonDataStorage.getString(context, MainActivity.KEY_IP_ADDRESS,  "");

            if (!TextUtils.isEmpty(savedIpAddress)) { // 检查IP是否有效
                Log.i("RestartServiceReceiver", "获取到保存的 IP 地址: " + savedIpAddress + "，尝试启动服务...");

                // 创建启动服务的 Intent
                Intent serviceIntent = new Intent(context, CameraStreamService.class);
                // 将 IP 地址放入 Intent，Service 的 onStartCommand 会读取
                serviceIntent.putExtra("IP_ADDRESS", savedIpAddress);
                try {
                    // 根据 Android 版本选择启动方式
                    context.startForegroundService(serviceIntent);
                    Log.i("RestartServiceReceiver", "已使用 startForegroundService 启动服务");
                    // 启动成功与否的提示由 Service 或 Activity 处理，这里不再显示 Toast
                     Toast.makeText(context, "服务正在后台重新启动...", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    // 捕获启动服务时可能发生的异常
                    Log.e("RestartServiceReceiver", "启动服务时发生错误: " + e.getMessage(), e);
                    // 显示一个简单的错误提示，但主要的用户反馈在 Activity 中
                    Toast.makeText(context, "尝试自动重启服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }

            } else {
                // 如果没有保存有效的 IP 地址，则无法重启
                Log.e("RestartServiceReceiver", "未能在 SharedPreferences 中找到有效的 IP 地址，无法重启服务。");
                Toast.makeText(context, "无法自动重启服务：缺少 IP 地址信息", Toast.LENGTH_LONG).show();
                // 这里不应该再依赖 MainActivity 的静态变量
            }
        } else {
            // 记录接收到的其他未处理的广播 Action
            Log.d("RestartServiceReceiver", "接收到未处理的广播: " + intent.getAction());
        }
    }
}