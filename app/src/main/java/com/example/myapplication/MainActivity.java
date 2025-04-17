package com.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color; // 保留用于图表颜色设置
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CalendarView; // 保留用于对话框
import android.widget.TextView; // 保留用于对话框
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull; // 保留以备将来使用
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider; // 引入 ViewModelProvider

import com.example.myapplication.ToolData.RecordData; // 保留用于对话框数据类型
import com.example.myapplication.databinding.ActivityMainBinding; // 引入 ViewBinding
import com.example.myapplication.utils.AnimationHelper;
import com.example.myapplication.utils.SensorHandler;
import com.example.myapplication.utils.Utils; // 引入 Utils
import com.example.myapplication.viewmodel.MainViewModel; // 引入 ViewModel

import com.github.mikephil.charting.components.XAxis; // 图表库导入
import com.github.mikephil.charting.components.YAxis; // 图表库导入
import com.github.mikephil.charting.data.Entry; // 图表库导入
import com.github.mikephil.charting.data.LineData; // 图表库导入
import com.github.mikephil.charting.data.LineDataSet; // 图表库导入
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet; // 图表库导入

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * 应用的主活动界面。
 * 经过重构后，主要职责是：
 * 1. 管理 UI 元素的显示和基本交互。
 * 2. 初始化 ViewModel 和 Helper 类。
 * 3. 将用户操作委托给 ViewModel 处理。
 * 4. 观察 ViewModel 的 LiveData 并更新 UI。
 * 5. 处理 Android 系统相关的任务（生命周期、权限请求、广播接收）。
 * 6. 实现 Helper 类所需的回调接口 (如 SensorHandler.LightSensorListener)。
 */
public class MainActivity extends AppCompatActivity implements SensorHandler.LightSensorListener {

    private static final String TAG = "MainActivity"; // 日志标签
    // 权限请求码 (如果需要兼容旧版权限处理方式)
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 1001; // 其他权限组 (如果需要)

    // 使用 ViewBinding 来安全地访问视图，替代 findViewById
    private ActivityMainBinding binding;

    // ViewModel 实例，用于管理 UI 状态和业务逻辑
    private MainViewModel viewModel;

    // Helper 类实例
    private SensorHandler sensorHandler;   // 处理传感器逻辑
    private AnimationHelper animationHelper; // 处理动画逻辑

    // ActivityResultLauncher 用于处理权限请求结果 (推荐方式)
    private ActivityResultLauncher<String> requestCameraPermissionLauncher;
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    // 标记是否在权限授予后需要启动服务 (处理权限请求的异步性)
    private boolean pendingServiceStart = false;

    // 用于接收服务重试失败广播的接收器
    private BroadcastReceiver retryFailureReceiver;
    private IntentFilter retryFailureIntentFilter; // 广播过滤条件

    // 日期格式化，主要用于月视图对话框
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // 启用全屏边缘到边缘显示

        // 初始化 ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        // 设置 Activity 的内容视图为 ViewBinding 的根视图
        setContentView(binding.getRoot());

        // 处理窗口 Insets (边距)，避免系统栏遮挡内容
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
             Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
             v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
             return insets;
        });

        // 初始化 ViewModel
        // ViewModelProvider 会确保在 Activity 重建时获取到同一个 ViewModel 实例
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // 初始化 Helper 类
        // 将 this (Activity) 作为 LightSensorListener 传递给 SensorHandler
        sensorHandler = new SensorHandler(this, this);
        animationHelper = new AnimationHelper(this);

        // 初始化权限请求启动器
        setupPermissionLaunchers();

        // 初始化 UI 组件 (如 Spinner 适配器)
        setupUI();
        // 设置视图的监听器
        setupListeners();

        // 开始观察 ViewModel 中的 LiveData 数据变化
        observeViewModel();

        // 初始化光照强度图表 (可以考虑移到 ChartHelper)
        setupLightChart();

        // 设置广播接收器
        setupRetryFailureReceiver();

         // 检查通知权限 (应用启动时或需要时检查)
        // 相机权限在点击连接按钮时检查
        checkNotificationPermission();
        Log.d(TAG,"MainActivity onCreate 完成");
    }

    /**
     * 初始化 UI 组件，例如设置 Spinner 的适配器。
     */
    private void setupUI() {
        // 创建 Spinner 的 ArrayAdapter
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.pleasure_levels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // 将适配器设置给 Spinner (使用 ViewBinding)
        binding.pleasureLevelSpinner.setAdapter(adapter);
        Log.d(TAG,"Spinner 适配器设置完成");
    }

    /**
     * 统一设置所有视图的事件监听器。
     * 将用户操作委托给 ViewModel 处理。
     */
    private void setupListeners() {
        // 连接按钮点击事件
        binding.connectButton.setOnClickListener(v -> {
            Log.d(TAG,"连接按钮被点击");
            // 获取 IP 输入框的文本
            String currentIp = binding.ipAddressEditText.getText().toString().trim();
            // 调用 ViewModel 的方法来处理连接尝试
            viewModel.attemptConnection(currentIp);
        });

        // “失败时重启”开关状态变化事件
        binding.CameraStreamServiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG,"重启开关状态改变: " + isChecked);
            // 调用 ViewModel 保存新的开关状态
            viewModel.saveRestartPreference(isChecked);
        });

        // 计数器减号按钮点击事件
        binding.decrementButton.setOnClickListener(v -> {
             Log.d(TAG,"减号按钮被点击");
             // 调用 ViewModel 更新计数器 (-1)
             viewModel.updateCounter(-1);
        });
        // 计数器加号按钮点击事件
        binding.incrementButton.setOnClickListener(v -> {
            Log.d(TAG,"加号按钮被点击");
            // 调用 ViewModel 更新计数器 (+1)
            viewModel.updateCounter(1);
        });

        // 爽感等级 Spinner 选择事件
        binding.pleasureLevelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Spinner 的 position 是从 0 开始的，我们需要对应 1-5 的等级
                int selectedLevel = position + 1;
                 Log.d(TAG,"Spinner 选择变化，位置: " + position + ", 等级: " + selectedLevel);
                 // 调用 ViewModel 设置新的爽感等级 (只有在等级变化时 ViewModel 内部才会处理)
                 viewModel.setPleasureLevel(selectedLevel);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                 // 用户没有选择任何项，通常无需处理
            }
        });

        // “查看月视图”按钮点击事件
        binding.viewMonthlyDataButton.setOnClickListener(v -> {
            Log.d(TAG,"查看月视图按钮被点击");
            // 显示月视图对话框
            showMonthlyViewDialog();
        });
         Log.d(TAG,"所有监听器设置完成");
    }

    /**
     * 观察 ViewModel 中的 LiveData。
     * 当 LiveData 的数据发生变化时，更新对应的 UI 元素。
     */
    private void observeViewModel() {
        // 观察 IP 地址 LiveData
        viewModel.ipAddress.observe(this, ip -> {
            // 避免在用户输入时被 ViewModel 的初始加载覆盖
            if (!binding.ipAddressEditText.hasFocus()) {
                 binding.ipAddressEditText.setText(ip);
                 Log.v(TAG,"观察到 IP 地址变化: " + ip);
            }
        });
        // 观察重启开关状态 LiveData
        viewModel.restartServiceEnabled.observe(this, enabled -> {
             binding.CameraStreamServiceSwitch.setChecked(enabled);
             Log.v(TAG,"观察到重启开关状态变化: " + enabled);
        });
        // 观察计数值 LiveData
        viewModel.counter.observe(this, count -> {
             binding.countTextView.setText(String.valueOf(count));
             Log.v(TAG,"观察到计数值变化: " + count);
        });
        // 观察爽感等级 LiveData
        viewModel.pleasureLevel.observe(this, level -> {
            // 将等级 (1-5) 转换为 Spinner 的位置 (0-4)
            int position = level - 1;
             // 检查位置是否有效
             if (position >= 0 && position < binding.pleasureLevelSpinner.getAdapter().getCount()) {
                  // 只有当 Spinner 当前选择的位置与 LiveData 的值不同时才更新
                  // 避免因代码设置 Spinner 值而再次触发 onItemSelected 监听器导致循环
                 if (binding.pleasureLevelSpinner.getSelectedItemPosition() != position) {
                      binding.pleasureLevelSpinner.setSelection(position);
                      Log.v(TAG,"观察到爽感等级变化，更新 Spinner 位置: " + position);
                 }
             }
        });

        // 观察光照强度 LiveData
        viewModel.lightLevel.observe(this, lux -> {
            // 更新光照强度文本显示，格式化为一位小数
            binding.lightSensorTextView.setText(getString(R.string.lux, String.format(Locale.getDefault(), "%.1f", lux)));
            // 将新的光照强度值添加到图表中
            addChartEntry(lux);
            Log.v(TAG,"观察到光照强度变化: " + lux);
        });

        // 观察光线传感器可用状态 LiveData
         viewModel.isLightSensorAvailable.observe(this, available -> {
             if (!available) {
                 // 如果传感器不可用，更新文本提示，并清空图表
                 binding.lightSensorTextView.setText(getString(R.string.lux_unavailable)); // 使用 strings.xml 中定义的字符串
                 binding.lightChart.clear(); // 清除图表数据
                 binding.lightChart.invalidate(); // 刷新图表显示
                 Log.w(TAG,"观察到光线传感器不可用");
             }
             // 可以选择性地在这里禁用图表的交互
             binding.lightChart.setTouchEnabled(available);
         });

        // 观察 Toast 消息事件 LiveData
        viewModel.toastMessage.observe(this, event -> {
            // 使用 Event.getContentIfNotHandled() 确保消息只显示一次
            String message = event.getContentIfNotHandled();
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                Log.d(TAG,"观察到 Toast 事件: " + message);
            }
        });

         // 观察播放动画事件 LiveData
         viewModel.playAnimationEvent.observe(this, event -> {
             // 确保事件未被处理过
            if (event.getContentIfNotHandled() != null) {
                Log.d(TAG,"观察到播放动画事件");
                // 调用 AnimationHelper 播放动画
                // 使用 counterTextView 作为动画效果的视觉锚点 (粒子从此位置散开)
                animationHelper.playExplosionAnimation((ViewGroup) getWindow().getDecorView(), binding.countTextView);
            }
         });

        // 观察启动服务事件 LiveData
        viewModel.startServiceEvent.observe(this, event -> {
            // 确保事件未被处理过，并且值为 true (表示要启动)
            Boolean start = event.getContentIfNotHandled();
             if (start != null && start) {
                 Log.d(TAG,"观察到启动服务事件，开始检查权限并启动服务流程");
                 // 调用方法检查权限并启动服务
                 checkPermissionsAndStartService();
             }
        });
         Log.d(TAG,"ViewModel 观察器设置完成");
    }


    // --- SensorHandler.LightSensorListener 接口实现 ---
    /**
     * 当 SensorHandler 检测到光照强度变化时调用此方法。
     * @param lux 当前的光照强度值。
     */
    @Override
    public void onLightLevelChanged(float lux) {
        // 将获取到的光照强度值传递给 ViewModel 进行处理和状态更新
        viewModel.updateLightLevel(lux);
        // Log.v(TAG, "光线传感器回调: " + lux); // 可以取消注释以进行调试，但会产生大量日志
    }

    /**
     * 当 SensorHandler 检测到光线传感器不可用时调用此方法。
     */
    @Override
    public void onLightSensorUnavailable() {
        Log.w(TAG,"光线传感器回调: 不可用");
        // 通知 ViewModel 传感器状态变为不可用
        viewModel.setLightSensorAvailable(false);
    }

    // --- 权限处理 ---

    /**
     * 初始化 ActivityResultLauncher 用于处理权限请求结果。
     */
    private void setupPermissionLaunchers() {
         // 处理相机权限请求结果
         requestCameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
             if (isGranted) {
                 Log.i(TAG, "相机权限已授予。");
                 // 如果之前因为等待相机权限而设置了 pendingServiceStart 标记，现在可以继续启动服务
                 if (pendingServiceStart) {
                      Log.d(TAG,"相机权限授予后，继续启动服务...");
                      // 在启动服务前，最好再次确认通知权限（如果需要）是否也已授予
                      checkNotificationPermissionAndProceed();
                 }
             } else {
                 Log.w(TAG, "相机权限被拒绝。");
                 Toast.makeText(this, "需要相机权限才能启动服务", Toast.LENGTH_SHORT).show();
                 // 因为缺少必要权限，取消服务启动意图
                 pendingServiceStart = false;
             }
         });

        // 处理通知权限请求结果 (Android 13+)
        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                 Log.i(TAG, "通知权限已授予。");
                 // 如果之前因为等待此权限而设置了 pendingServiceStart 标记，现在可以继续启动服务
                 // （前提是相机权限也已经授予）
                 if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                     Log.d(TAG,"通知权限授予后，继续启动服务...");
                     startCameraStreamService();
                 }
             } else {
                 Log.w(TAG, "通知权限被拒绝。");
                 // 可以显示一个对话框解释为什么需要这个权限
                 showNotificationPermissionRationale();
                 // 服务仍然可以尝试启动，但需要告知用户前台服务通知可能无法显示
                 Toast.makeText(this, "缺少通知权限，前台服务可能无法正常运行", Toast.LENGTH_LONG).show();
                  // 如果服务启动流程正在等待，并且相机权限已授予，则继续启动（但带有警告）
                 if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                      Log.d(TAG,"通知权限被拒绝，但仍尝试启动服务...");
                      startCameraStreamService();
                 }
             }
        });
         Log.d(TAG,"权限请求启动器设置完成");
    }

    /**
     * 检查相机和通知权限（如果需要），并在权限满足时启动服务。
     * 如果缺少权限，则发起权限请求。
     */
    private void checkPermissionsAndStartService() {
        Log.d(TAG,"检查权限并准备启动服务...");
        // 设置标记，表示我们打算启动服务，权限请求的回调会检查这个标记
        pendingServiceStart = true;

        // 检查相机权限
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        // 检查通知权限 (仅限 Android 13 及以上)
        boolean notificationGranted = true; // 默认对于 Android 13 以下为 true
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             notificationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
             Log.d(TAG,"Android 13+: 通知权限状态: " + notificationGranted);
         } else {
              Log.d(TAG,"Android 13 以下: 通知权限无需请求。");
         }

         // 情况分析：
         if (cameraGranted && notificationGranted) {
             // 1. 所有必需权限都已授予
             Log.i(TAG, "所有必需权限已授予，直接启动服务。");
             startCameraStreamService();
         } else if (!cameraGranted) {
             // 2. 缺少相机权限 (最优先请求)
             Log.i(TAG, "缺少相机权限，正在请求...");
             requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
             // 通知权限将在相机权限授予后检查 (见 requestCameraPermissionLauncher 回调)
         } else { // cameraGranted is true, but notificationGranted is false (on Android 13+)
             // 3. 缺少通知权限 (相机权限已有)
             Log.i(TAG, "缺少通知权限 (Android 13+)，正在检查/请求...");
             checkNotificationPermission(); // 这个方法会处理请求或显示理由
         }
    }

    /**
     * 专门检查通知权限 (Android 13+)。
     * 如果未授予，则根据情况请求权限或显示请求理由对话框。
     * 如果已授予，并且服务启动流程正在等待此权限，则继续启动服务。
     */
     private void checkNotificationPermission() {
         // 只在 Android 13 (TIRAMISU) 及以上版本检查
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             // 检查权限状态
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                  Log.d(TAG,"通知权限尚未授予 (Android 13+)。");
                  // 检查是否应该显示请求理由 (用户之前拒绝过但未选择“不再询问”)
                  if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                     // 显示解释对话框
                      Log.d(TAG,"需要显示通知权限请求理由。");
                     showNotificationPermissionRationale();
                 } else {
                     // 直接请求权限
                      Log.d(TAG,"发起通知权限请求...");
                      requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                 }
             } else {
                  // 权限已经授予
                  Log.d(TAG,"通知权限已授予 (Android 13+)。");
                  // 如果服务启动流程正在等待通知权限（并且相机权限也已满足），则继续
                  checkNotificationPermissionAndProceed();
             }
         } else {
              // Android 13 以下无需处理通知权限
              Log.d(TAG,"Android 13 以下，无需检查通知权限。");
              // 如果服务启动流程仅因版本判断而“等待”通知权限，现在可以继续了（只要相机权限满足）
              if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                   startCameraStreamService();
              }
         }
     }

      /**
       * 在相机权限或其他前置条件满足后，再次确认通知权限（如果需要）是否满足，
       * 如果都满足，则继续启动服务。
       */
     private void checkNotificationPermissionAndProceed() {
         boolean canProceed = true;
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                  Log.w(TAG,"准备启动服务，但通知权限仍未授予 (Android 13+)。");
                  // 如果需要严格要求通知权限才能启动服务，可以在这里 return 或 再次请求/提示
                  // 当前逻辑是：即使通知权限没有，也尝试启动，但在请求时已给出提示
                  // canProceed = false; // 取消注释则强制要求通知权限
                  // 再次请求可能导致循环，最好由用户手动在设置中开启
                  checkNotificationPermission(); // 再次检查，可能会显示 rationale 或请求
                  canProceed = false; // 再次检查后，不立即启动，等待回调
              }
         }

         if (canProceed && pendingServiceStart) {
             Log.d(TAG,"所有权限检查通过，正式启动服务。");
             startCameraStreamService();
         } else if (pendingServiceStart) {
              Log.d(TAG,"权限检查未完全通过或服务启动意图已取消，不启动服务。");
              // pendingServiceStart = false; // 可选：如果 checkNotificationPermission() 再次发起请求，这里暂时不取消意图
         }
     }

    /**
     * 显示一个对话框，向用户解释为什么需要通知权限。
     * (仅在 Android 13+ 且 shouldShowRequestPermissionRationale 返回 true 时调用)
     */
    private void showNotificationPermissionRationale() {
        Log.d(TAG,"显示通知权限请求理由对话框。");
        new AlertDialog.Builder(this)
                .setTitle("需要通知权限")
                .setMessage("应用需要此权限来显示正在运行的服务状态，这对于前台服务是必要的，否则服务可能无法正常启动或被系统意外终止。")
                .setPositiveButton("好的", (dialog, which) -> {
                    // 用户点击“好的”，再次发起权限请求
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                         Log.d(TAG,"用户同意再次请求通知权限。");
                         requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                      }
                 })
                .setNegativeButton("取消", (dialog, which) -> {
                     // 用户点击“取消”，提示用户服务可能受影响
                     Log.w(TAG,"用户拒绝在理由对话框中授予通知权限。");
                     Toast.makeText(this, "未授予通知权限，服务可能无法正常显示状态", Toast.LENGTH_SHORT).show();
                     // 如果服务启动流程正在等待，并且相机权限已授予，则继续启动（但带有警告）
                     if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                          Log.d(TAG,"用户取消通知权限，但仍尝试启动服务...");
                          startCameraStreamService();
                     }
                 })
                .show();
    }


    // --- 服务启动 ---

    /**
     * 负责创建 Intent 并启动 CameraStreamService。
     * 此方法应在所有必需权限都已授予后调用。
     */
    private void startCameraStreamService() {
         // 再次检查 pendingServiceStart 标记，确保启动意图仍然有效
         if (!pendingServiceStart) {
             Log.w(TAG,"尝试启动服务，但 pendingServiceStart 为 false，可能是权限被拒绝或流程已取消。");
             return;
         }
         // 重置标记，表示本次启动流程即将完成（或失败）
         pendingServiceStart = false;

         // 从 ViewModel 获取最新的 IP 地址和重启设置状态
         String ip = viewModel.ipAddress.getValue();
         Boolean restart = viewModel.restartServiceEnabled.getValue();

         // 对获取的值进行最终校验
         if (ip == null || !Utils.isValidIpAddress(ip)) { // 使用 Utils 校验 IP
             Toast.makeText(this, "无法启动服务：IP 地址无效", Toast.LENGTH_SHORT).show();
             Log.e(TAG,"启动服务失败：从 ViewModel 获取的 IP 地址无效或为空。");
             return;
         }
         if (restart == null) restart = true; // 如果 LiveData 尚未初始化，给一个默认值

        // 创建启动服务的 Intent
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        // 将 IP 地址和重启设置作为 extra 数据传递给 Service
        serviceIntent.putExtra("ip_address", ip);
        serviceIntent.putExtra("restart_on_failure", restart);
        // 不再传递 retry_count，让 Service 内部管理重试逻辑

        try {
            // 使用 ContextCompat.startForegroundService 启动前台服务
            // 这会告诉系统应用打算运行一个前台服务，即使应用在后台也允许启动
            ContextCompat.startForegroundService(this, serviceIntent);
            Log.i(TAG, "尝试启动 CameraStreamService - IP: " + ip + ", 重启: " + restart);
            Toast.makeText(this, "正在启动连接服务...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // 捕获启动服务时可能发生的异常 (例如，在某些极端情况下，即使调用了 startForegroundService 也可能失败)
            Log.e(TAG, "启动前台服务失败", e);
             Toast.makeText(this, "启动服务时发生错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    // --- 广播接收器处理 ---

    /**
     * 初始化用于接收服务重试失败通知的 BroadcastReceiver。
     */
    private void setupRetryFailureReceiver() {
        retryFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 确认接收到的广播是我们期望的 Action
                if (CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG.equals(intent.getAction())) {
                    Log.w(TAG, "接收到服务重试失败的广播通知。");
                    // 显示提示对话框
                    showRetryFailedDialog();
                }
            }
        };
        // 创建 IntentFilter，只接收来自 CameraStreamService 的特定 Action
        retryFailureIntentFilter = new IntentFilter(CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG);
        Log.d(TAG,"重试失败广播接收器设置完成");
    }

    /**
     * 显示一个对话框，告知用户服务自动重连失败。
     * 提供“知道了”和“尝试重连”选项。
     */
    private void showRetryFailedDialog() {
        // 确保 Activity 仍然处于活跃状态，避免在已销毁的 Activity 上显示对话框
        if (!isFinishing() && !isDestroyed()) {
            // 确保在 UI 线程执行对话框的显示
            runOnUiThread(() -> {
                 Log.d(TAG,"显示重试失败对话框。");
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("连接失败")
                    .setMessage("尝试自动重新连接服务失败。请检查网络连接和服务器状态，然后尝试手动连接。")
                    .setPositiveButton("知道了", (dialog, which) -> dialog.dismiss()) // 关闭对话框
                    .setNegativeButton("尝试重连", (dialog, which) -> {
                         // 用户点击“尝试重连”，触发 ViewModel 的连接逻辑
                        Log.d(TAG,"用户在重试失败对话框中点击了“尝试重连”。");
                        String currentIp = binding.ipAddressEditText.getText().toString().trim();
                         viewModel.attemptConnection(currentIp); // 委托给 ViewModel
                         dialog.dismiss();
                    })
                    .setCancelable(false) // 不允许点击对话框外部区域取消
                    .show();
            });
        } else {
             Log.w(TAG,"尝试显示重试失败对话框，但 Activity 状态不适合显示 (isFinishing=" + isFinishing() + ", isDestroyed=" + isDestroyed() + ")");
        }
    }

    // --- 图表设置和更新 (示例，可移至 ChartHelper) ---

    /**
     * 初始化光照强度折线图的基本设置。
     */
    private void setupLightChart() {
        Log.d(TAG,"设置光照强度图表...");
        // 基本配置
        binding.lightChart.getDescription().setEnabled(false); // 禁用描述文本
        binding.lightChart.setTouchEnabled(true);      // 启用触摸交互
        binding.lightChart.setDragEnabled(true);       // 启用拖拽
        binding.lightChart.setScaleEnabled(true);      // 启用缩放
        binding.lightChart.setPinchZoom(true);       // 启用双指缩放
        binding.lightChart.setBackgroundColor(Color.DKGRAY); // 设置背景色 (示例)
        binding.lightChart.setDrawGridBackground(false); // 不绘制网格背景

        // X 轴设置
        XAxis xAxis = binding.lightChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM); // X轴在底部
        xAxis.setDrawGridLines(false);                // 不绘制X轴网格线
        xAxis.setTextColor(Color.WHITE);              // X轴文字颜色
        // xAxis.setAxisMinimum(0f); // 可以设置X轴最小值
        // xAxis.setValueFormatter(...); // 可以设置X轴标签格式 (例如时间戳)

        // Y 轴 (左侧) 设置
        YAxis leftAxis = binding.lightChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);                 // 绘制Y轴网格线
        leftAxis.setTextColor(Color.WHITE);              // Y轴文字颜色
        leftAxis.setAxisMinimum(0f);                   // Y轴最小值通常为0 (光照强度)
        // leftAxis.setAxisMaximum(...); // 可以设置Y轴最大值

        // 禁用右侧 Y 轴
        binding.lightChart.getAxisRight().setEnabled(false);

        // 创建空的 LineData 并设置给图表
        LineData data = new LineData();
        binding.lightChart.setData(data);
        binding.lightChart.invalidate(); // 刷新图表
        Log.d(TAG,"图表设置完成");
    }

    /**
     * 向光照强度图表中添加一个新的数据点。
     * @param luxValue 新的光照强度值。
     */
    private void addChartEntry(float luxValue) {
        // 获取图表现有的 LineData 对象
        LineData data = binding.lightChart.getData();

        if (data != null) {
            // 获取第一个数据集 (ILineDataSet)，如果没有则创建
            ILineDataSet set = data.getDataSetByIndex(0);
            if (set == null) {
                set = createSet(); // 创建新的数据集
                data.addDataSet(set); // 将新数据集添加到 LineData 中
            }

            // 添加新的数据点 Entry
            // X 值使用数据集中现有的点数作为索引，实现时间序列效果
            // Y 值是传入的光照强度值
            data.addEntry(new Entry(set.getEntryCount(), luxValue), 0); // 0 是数据集的索引

            // 通知 LineData 数据已发生变化
            data.notifyDataChanged();

            // 通知图表数据已发生变化，需要重绘
            binding.lightChart.notifyDataSetChanged();

            // 限制图表可见的 X 轴范围，避免显示过多的数据点导致性能下降或显示混乱
            binding.lightChart.setVisibleXRangeMaximum(100); // 最多显示最近的 100 个点

            // (可选) 自动滚动图表，使最新的数据点始终可见
            binding.lightChart.moveViewToX(data.getEntryCount());

            // Log.v(TAG, "图表添加新数据点: (" + (set.getEntryCount()-1) + ", " + luxValue + ")"); // Debugging log
        }
    }

    /**
     * 创建并配置一个新的 LineDataSet (数据集) 用于图表。
     * @return 配置好的 LineDataSet 对象。
     */
    private LineDataSet createSet() {
        // 创建数据集，初始没有数据，设置标签 "光照强度 (lux)"
        LineDataSet set = new LineDataSet(null, "光照强度 (lux)");
        set.setAxisDependency(YAxis.AxisDependency.LEFT); // 数据依赖左侧 Y 轴
        set.setColor(Color.CYAN);              // 折线颜色
        set.setCircleColor(Color.WHITE);        // 数据点圆圈颜色
        set.setLineWidth(2f);                 // 折线宽度
        set.setCircleRadius(3f);              // 数据点圆圈半径
        set.setFillAlpha(65);                 // 填充区域透明度
        set.setFillColor(Color.CYAN);         // 填充区域颜色
        set.setHighLightColor(Color.rgb(244, 117, 117)); // 点击高亮线颜色
        set.setValueTextColor(Color.WHITE);    // 数据点上的文本颜色 (如果显示的话)
        set.setValueTextSize(9f);             // 数据点上的文本大小
        set.setDrawValues(false);             // 不在数据点上绘制具体数值
        set.setDrawCircles(false);             // 不绘制数据点圆圈 (可选，优化性能)
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); // 使用平滑的曲线模式
        set.setDrawFilled(true);              // 启用填充区域绘制
        Log.d(TAG,"创建了新的图表数据集 (LineDataSet)");
        return set;
    }


     // --- 月视图对话框 ---
    /**
     * 显示包含日历和记录详情的月视图对话框。
     */
    @SuppressLint("StringFormatMatches") // 抑制 getString 格式化参数的 Lint 警告
    private void showMonthlyViewDialog() {
        Log.d(TAG,"显示月视图对话框...");
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        // 加载对话框布局
        // TODO: 如果dialog_monthly_view.xml使用了ViewBinding，应该用 DialogMonthlyViewBinding.inflate(inflater)
        View dialogView = inflater.inflate(R.layout.dialog_monthly_view, null);
        builder.setView(dialogView); // 设置对话框视图
        builder.setTitle(R.string.monthly_view_title); // 设置对话框标题

        // 获取对话框中的视图控件
        CalendarView calendarView = dialogView.findViewById(R.id.calendarViewDialog);
        TextView detailsTextView = dialogView.findViewById(R.id.detailsTextViewDialog);

        // 从 ViewModel 获取所有记录数据 (ViewModel 从 JsonDataStorage 获取)
        final Map<String, RecordData> allRecords = viewModel.getAllRecords();
        Log.d(TAG,"从 ViewModel 获取到 " + (allRecords != null ? allRecords.size() : 0) + " 条记录用于月视图");

        // 设置日历日期选择监听器
        calendarView.setOnDateChangeListener((view, year, month, dayOfMonth) -> {
            // month 是从 0 开始的，需要加 1 才能得到实际月份
            Calendar selectedCalendar = Calendar.getInstance();
            selectedCalendar.set(year, month, dayOfMonth); // 设置选中的年月日
            // 将选中日期格式化为与存储键一致的格式 "yyyy-MM-dd"
            String selectedDateKey = dateFormat.format(selectedCalendar.getTime());
            Log.d(TAG,"日历日期变化: " + selectedDateKey);

            // 从所有记录中查找选中日期的数据
            RecordData selectedRecord = allRecords.get(selectedDateKey);
            // 根据是否找到记录，更新详情 TextView 的内容
            if (selectedRecord != null) {
                detailsTextView.setText(getString(R.string.record_details_format,
                        selectedRecord.getCount(), selectedRecord.getPleasureLevel()));
                Log.d(TAG,"找到记录: 次数=" + selectedRecord.getCount() + ", 爽感=" + selectedRecord.getPleasureLevel());
            } else {
                detailsTextView.setText(R.string.no_record_found); // 使用 strings.xml 中的字符串
                Log.d(TAG,"未找到记录");
            }
        });

        // 设置对话框的“关闭”按钮
        builder.setPositiveButton(R.string.close, (dialog, which) -> dialog.dismiss());

        // 创建并显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();

        // --- (可选) 初始化对话框时显示当天的数据 ---
        // 获取当前时间戳
        long initialMillis = Calendar.getInstance().getTimeInMillis();
        // 将日历视图设置到今天 (最后两个参数控制是否动画和是否触发监听器，这里不触发)
        calendarView.setDate(initialMillis, false, false);
        // 手动获取今天的数据并显示
        Calendar initialCalendar = Calendar.getInstance();
        initialCalendar.setTimeInMillis(initialMillis);
        String initialDateKey = dateFormat.format(initialCalendar.getTime());
        RecordData initialRecord = allRecords.get(initialDateKey);
        if (initialRecord != null) {
             detailsTextView.setText(getString(R.string.record_details_format,
                     initialRecord.getCount(), initialRecord.getPleasureLevel()));
         } else {
             detailsTextView.setText(R.string.no_record_found);
         }
        Log.d(TAG,"月视图对话框已初始化并显示当天数据");
        // --- 结束可选部分 ---
    }

    // --- Android Activity 生命周期方法 ---

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // 抑制 Android 14 对注册 Receiver 的 Lint 警告
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG,"MainActivity onResume");
        // 注册传感器监听器
        sensorHandler.registerListener();

         // 注册广播接收器
         // 确保接收器和过滤器不为空
         if (retryFailureReceiver != null && retryFailureIntentFilter != null) {
             // Android 14 (API 34) 及以上版本需要明确指定导出行为
             // 对于应用内广播，应使用 RECEIVER_NOT_EXPORTED
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                 registerReceiver(retryFailureReceiver, retryFailureIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                 Log.d(TAG,"已注册重试失败广播接收器 (Android 14+, NOT_EXPORTED)");
             } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
                 // Android 13 也推荐明确指定，虽然 Lint 可能只在 14 上警告
                 registerReceiver(retryFailureReceiver, retryFailureIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                 Log.d(TAG,"已注册重试失败广播接收器 (Android 13, NOT_EXPORTED)");
             }
             else {
                 // Android 13 以下版本，不需要指定导出标志
                 registerReceiver(retryFailureReceiver, retryFailureIntentFilter);
                 Log.d(TAG,"已注册重试失败广播接收器 (Android 13 以下)");
             }
         }
        // 可以在 onResume 时再次检查权限，以防用户在设置中更改了权限
        // checkNotificationPermission(); // 如果需要更频繁地检查
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG,"MainActivity onPause");
        // 注销传感器监听器，释放资源
        sensorHandler.unregisterListener();

         // 注销广播接收器，防止内存泄漏
         try {
             if (retryFailureReceiver != null) {
                 unregisterReceiver(retryFailureReceiver);
                 Log.d(TAG,"重试失败广播接收器已注销。");
             }
         } catch (IllegalArgumentException e) {
             // 如果接收器尚未注册或已被注销，会抛出此异常，可以安全地忽略
             Log.w(TAG, "尝试注销接收器时出错 (可能未注册或已注销)", e);
         }
    }

     @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG,"MainActivity onDestroy");
        // 清理 ViewBinding 引用，帮助垃圾回收
        binding = null;
    }

}
