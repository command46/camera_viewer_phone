package com.example.myapplication; // 替换为你的包名

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Configuration;

public class MyApplication extends Application implements Configuration.Provider {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("MyApplication", "Application Created.");
        Log.d("MyApplication", "WorkManager initialization potentially triggered (if manual setup needed).");
    }

    // 3. 实现 getWorkManagerConfiguration 方法
    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        // 提供你的 WorkManager 配置
        // 可以是默认配置，或添加自定义设置
        return new Configuration.Builder()
                // 例如，设置日志级别
                .setMinimumLoggingLevel(Log.INFO)
                // 可以设置自定义执行器等
                // .setExecutor(...)
                .build();
    }
}