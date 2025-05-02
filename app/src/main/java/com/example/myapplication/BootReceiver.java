package com.example.myapplication; // 替换为你的包名

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.myapplication.http.UploadWorker;

import java.util.concurrent.TimeUnit;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "启动完成，调度周期性上传任务");
            schedulePeriodicUploadWork(context);
        }
    }

    // 公共静态方法，方便在其他地方调用调度逻辑
    public static void schedulePeriodicUploadWork(Context context) {
        // 1. 定义约束条件
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        // 注意：WorkManager 的周期最短为 15 分钟
        PeriodicWorkRequest periodicWorkRequest =
                new PeriodicWorkRequest.Builder(UploadWorker.class, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        // .setInitialDelay(5, TimeUnit.MINUTES) // 可选：设置初始延迟
                        .addTag(UploadWorker.WORK_NAME) // 添加标签方便管理
                        .build();

        // 3. 将任务加入队列，使用 unique name 保证只有一个实例
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UploadWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // KEEP: 如果已存在同名任务，则保留现有任务，不重新调度
                // REPLACE: 如果已存在，则取消现有任务，用新的替换
                periodicWorkRequest
        );

        Log.i(TAG, "Periodic upload work scheduled/verified.");
    }
}