package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.util.Objects;

// RestartServiceReceiver.java (广播接收器)
public class RestartServiceReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "RestartCameraService".equals(intent.getAction())) { // 自定义action

            Log.i("RestartServiceReceiver", "Service restarted");

            Intent intent1 = new Intent(context, CameraStreamService.class);
            if (Objects.equals(MainActivity.IP_ADDRESS_, "IP_ADDRESS")){
                Toast.makeText(context, "请先设置IP地址", Toast.LENGTH_SHORT).show();
            }
            intent1.putExtra("IP_ADDRESS", MainActivity.IP_ADDRESS_);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent1);
                Log.i("RestartServiceReceiver", "Service restarted with foreground service");
            } else {
                context.startService(intent1);
            }
        }
    }
}
