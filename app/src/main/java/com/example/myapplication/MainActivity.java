package com.example.myapplication; // 替换为你的包名

// 基础和 UI 相关的导入

import static com.example.myapplication.ToolData.Tools.dpToPx;
import static com.example.myapplication.ToolData.Tools.showMonthlyViewDialog;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.example.myapplication.Service.CameraStreamService;
import com.example.myapplication.ToolData.JsonDataStorage;
import com.example.myapplication.ToolData.PermissionHelper;
import com.example.myapplication.ToolData.Tools;
import com.example.myapplication.http.GiteeContentFetcher;
import com.example.myapplication.http.UploadWorker;
import com.github.mikephil.charting.charts.LineChart;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * 应用主活动界面，负责用户交互、传感器数据显示、服务控制和权限请求。
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener, PermissionHelper.PermissionsGrantedCallback {
    // --- 常量定义 ---
    public static final String KEY_IP_ADDRESS = "last_ip_address"; // IP 地址存储 Key
    public static final String KEY_RESTART_SERVICE = "restart_service_flag"; // 重启服务标志 Key (如果使用)
    private static final String MANUAL_WORK_NAME = "manualUploadWork"; // 手动上传任务名称
    private static final String TAG = "MainActivity"; // 日志标签

    // --- UI 控件 ---
    private Button connectButton; // 连接按钮
    private EditText ipAddressEditText; // IP 输入框
    private TextView lightSensorTextView; // 光线传感器文本
    private LineChart lightChart; // 光线图表
    private ImageButton decrementButton; // 减号按钮
    private ImageButton incrementButton; // 加号按钮
    private TextView counterTextView; // 计数文本
    private Spinner pleasureLevelSpinner; // 爽感等级选择器
    private Button viewMonthlyDataButton; // 查看月视图按钮

    // --- 状态变量 ---
    private String ipAddress; // 当前有效的 IP 地址
    private String todayDateKey = ""; // 当天日期 Key (格式: yyyy-MM-dd)

    // --- 传感器相关 ---
    private SensorManager sensorManager; // 传感器管理器
    private Sensor lightSensor; // 光线传感器

    // --- 日期格式化 ---
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    // --- 权限请求启动器 ---
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher; // 通知权限 (API 33+)
    private ActivityResultLauncher<Intent> manageExternalStorageLauncher;       // 所有文件访问权限 (API 30+)

    // --- 广播接收器 ---
    private BroadcastReceiver retryFailureReceiver; // 接收服务重试失败广播
    private IntentFilter retryFailureIntentFilter; // 广播过滤器

    // --- 其他 ---
    private final Random random = new Random(); // 随机数生成器 (用于动画)

    // --- Activity 生命周期方法 ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // 启用沉浸式体验
        setContentView(R.layout.activity_main); // 设置布局

        // 处理窗口边衬，避免系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化日期 Key
        todayDateKey = dateFormat.format(Calendar.getInstance().getTime());

        // 初始化
        initSensors(); // 传感器
        initViews(); // 视图
        initPermissionLaunchers(); // 权限启动器
        loadSavedPreferences(); // 加载保存的设置
        setupListeners(); // 设置监听器
        setupLightChart(); // 设置图表
        setupRetryFailureReceiver(); // 设置广播接收器
        loadTodayData(); // 加载今日数据

        // 应用启动时进行初始权限检查 (不触发手动同步)
        Log.d(TAG, "onCreate: 执行初始权限检查...");
        checkPermissionsAndTriggerActions(false);

        // 检查是否需要后台/自启动权限引导 (仅首次运行)
        Log.d(TAG, "onCreate: 检查后台/自启动引导...");
        PermissionHelper.checkAndRequestBackgroundPermissionsGuidance(this);

        // Gitee 获取器 (似乎与权限无关)
        GiteeContentFetcher giteeContentFetcher = new GiteeContentFetcher();
        fetchContentFromGitee(giteeContentFetcher);
    }

    @SuppressLint({"UnspecifiedRegisterReceiverFlag"}) // 忽略 registerReceiver 的标志警告
    @Override
    protected void onResume() {
        super.onResume();
        // 注册光线传感器监听
        if (lightSensor != null && sensorManager != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        // 重新检查所有文件访问权限，以防用户在后台更改设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d(TAG, "onResume: MANAGE_EXTERNAL_STORAGE 权限未授予，可能在操作时再次触发检查。");
                // 可以考虑禁用依赖此权限的按钮
                // connectButton.setEnabled(false);
            } else {
                // connectButton.setEnabled(true); // 确保按钮可用
            }
        }

        // 注册广播接收器
        if (retryFailureReceiver != null && retryFailureIntentFilter != null) {
            try {
                // 根据 Android 版本使用不同的注册方法
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.registerReceiver(this, retryFailureReceiver, retryFailureIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(retryFailureReceiver, retryFailureIntentFilter);
                }
                Log.d(TAG, "已注册服务重试失败广播接收器");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "注册广播接收器失败 (可能已注册): " + e.getMessage());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册传感器监听
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        // 取消注册广播接收器
        if (retryFailureReceiver != null) {
            try {
                unregisterReceiver(retryFailureReceiver);
                Log.d(TAG, "已取消注册服务重试失败广播接收器");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "取消注册广播接收器失败 (可能未注册): " + e.getMessage());
            }
        }
    }

    // onStop() 可以保持为空

    // --- 初始化方法 ---

        /** 初始化传感器 */
    private void initSensors() {
        // 获取系统服务前进行空值检查
        Object service = getSystemService(Context.SENSOR_SERVICE);
        if (!(service instanceof SensorManager)) {
            Log.e(TAG, "无法获取 SensorManager 实例");
            return;
        }
        sensorManager = (SensorManager) service;

        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorTextView = findViewById(R.id.lightSensorTextView);

        if (lightSensor == null) {
            // 使用专用字符串资源表示不可用状态更清晰
            lightSensorTextView.setText(R.string.lux); // 假设已定义 R.string.lux_unavailable
            Log.w(TAG, "设备不支持光线传感器。");
        } else {
            // 提示需要注册监听器才能接收数据
            Log.i(TAG, "光线传感器已就绪，请注册监听器以开始监听数据。");
        }
    }


    /** 初始化视图控件 */
    private void initViews() {
        connectButton = findViewById(R.id.connectButton);
        ipAddressEditText = findViewById(R.id.ipAddressEditText);
        lightChart = findViewById(R.id.lightChart);
        decrementButton = findViewById(R.id.decrementButton);
        incrementButton = findViewById(R.id.incrementButton);
        counterTextView = findViewById(R.id.countTextView);
        pleasureLevelSpinner = findViewById(R.id.pleasureLevelSpinner);
        viewMonthlyDataButton = findViewById(R.id.viewMonthlyDataButton);
    }

    /** 初始化所有权限相关的 ActivityResultLauncher */
    private void initPermissionLaunchers() {
        // 通知权限启动器 (Android 13+)
        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            // 结果由 onRequestPermissionsResult -> PermissionHelper 处理流程驱动
            Log.d(TAG, "通知权限结果返回 (由 onRequestPermissionsResult 处理)");
        });

        // 所有文件访问权限启动器 (Android 11+)
        manageExternalStorageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> { // 处理从设置页面返回的结果
                    Log.d(TAG, "从 MANAGE_EXTERNAL_STORAGE 设置页面返回。");
                    // 从设置返回后，总是重新检查权限状态并继续流程
                    checkPermissionsAndTriggerActions(false); // 重新检查，但不自动触发手动同步
                });
    }

    /** 设置控件监听器 */
    private void setupListeners() {
        // 连接按钮
        connectButton.setOnClickListener(v -> {
            Log.d(TAG, "连接按钮点击。");
            String currentIp = ipAddressEditText.getText().toString().trim();
            // 验证 IP (使用 Tools.isValidIpAddress)
            if (!Tools.isValidIpAddress(currentIp)) {
                Toast.makeText(this, "请输入有效的 IP 地址", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "连接按钮: IP 无效 - " + currentIp);
                return;
            }
            // IP 有效，保存并开始权限检查流程
            this.ipAddress = currentIp;
            JsonDataStorage.saveString(this, KEY_IP_ADDRESS, currentIp);
            Log.d(TAG, "连接按钮: IP 有效 (" + currentIp + ")。开始权限检查...");
            checkPermissionsAndTriggerActions(true); // true: 表示这次检查源自按钮点击，成功后应触发手动同步
        });

        // 其他按钮和 Spinner 监听器 (保持不变)
        decrementButton.setOnClickListener(v -> updateCounter(-1));
        incrementButton.setOnClickListener(v -> updateCounter(1));
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.pleasure_levels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pleasureLevelSpinner.setAdapter(adapter);
        pleasureLevelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { saveCurrentState(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        viewMonthlyDataButton.setOnClickListener(v -> showMonthlyViewDialog(this));
    }

    /** 初始化图表 */
    private void setupLightChart() {
        Tools.setupLightChart(lightChart, this);
    }

    /** 初始化服务失败广播接收器 */
    private void setupRetryFailureReceiver() {
        retryFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG.equals(intent.getAction())) {
                    Log.w(TAG, "接收到服务重试失败广播");
                    showRetryFailedDialog(); // 显示失败对话框
                }
            }
        };
        retryFailureIntentFilter = new IntentFilter(CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG);
    }

    // --- 数据加载和保存方法 (保持不变) ---
    private void loadSavedPreferences() {
        String savedIp = JsonDataStorage.getString(this, KEY_IP_ADDRESS, "");
        this.ipAddress = savedIp;
        ipAddressEditText.setText(savedIp);
        Log.i(TAG, "已加载保存的 IP: " + savedIp);
    }
    private void loadTodayData() {
        int[] data = Tools.loadTodayData(this);
        int count = data[0];
        int pleasureLevel = data[1];
        Log.d(TAG, "加载今日 (" + todayDateKey + ") 数据: 次数=" + count + ", 爽感=" + pleasureLevel);
        counterTextView.setText(String.valueOf(count));
        int spinnerPosition = Math.max(0, Math.min(pleasureLevel - 1, pleasureLevelSpinner.getAdapter().getCount() - 1));
        pleasureLevelSpinner.setSelection(spinnerPosition);
    }
    private void saveCurrentState() {
        int currentCount = 0;
        try { currentCount = Integer.parseInt(counterTextView.getText().toString()); } catch (NumberFormatException e) { Log.e(TAG, "解析计数错误", e); }
        int selectedPleasureLevel = Math.max(1, Math.min(pleasureLevelSpinner.getSelectedItemPosition() + 1, pleasureLevelSpinner.getAdapter().getCount()));
        Log.d(TAG, "保存当前状态 - 日期: " + todayDateKey + ", 次数: " + currentCount + ", 爽感: " + selectedPleasureLevel);
        JsonDataStorage.saveRecord(this, todayDateKey, currentCount, selectedPleasureLevel);
    }
    private void updateCounter(int change) {
        int currentCount = 0;
        try { currentCount = Integer.parseInt(counterTextView.getText().toString()); } catch (NumberFormatException e) { Log.e(TAG, "解析计数错误", e); }
        int newCount = Math.max(0, currentCount + change); // 确保不小于 0
        counterTextView.setText(String.valueOf(newCount));
        if (change > 0) {
            playExplosionAnimation(); // 增加时播放动画
        }
        saveCurrentState(); // 保存状态
    }

    // --- 权限处理 ---

    /**
     * 启动权限检查流程的核心方法。
     * @param triggerManualSyncOnGranted 如果权限检查最终通过，是否应触发一次手动文件同步。
     */
    private void checkPermissionsAndTriggerActions(boolean triggerManualSyncOnGranted) {
        Log.d(TAG, "checkPermissionsAndTriggerActions 调用，触发手动同步标记: " + triggerManualSyncOnGranted);

        // 调用 PermissionHelper 进行检查，传入所有文件访问权限的启动器和回调
        boolean permissionsAlreadyGranted = PermissionHelper.checkAndRequestEssentialPermissions(
                this,
                manageExternalStorageLauncher,
                this // 实现 PermissionsGrantedCallback 接口
        );

        if (permissionsAlreadyGranted) {
            Log.i(TAG, "检查时所有权限已满足。");
            // 如果权限一开始就满足，直接执行后续逻辑
            onPermissionsGrantedLogic(triggerManualSyncOnGranted);
        } else {
            Log.i(TAG, "缺少权限或需要用户操作 (请求权限/跳转设置)。等待回调...");
            // 权限不足，禁用按钮，等待用户通过弹窗或设置页面交互
            connectButton.setEnabled(false);
        }
    }

    // --- 实现 PermissionHelper 的回调接口 ---
    @Override
    public void onPermissionsGranted() {
        Log.i(TAG, "PermissionHelper.onPermissionsGranted 回调：所有权限现已授予！");
        // 此回调在所有权限（运行时 + 特殊权限）都满足后被调用

        // 重新启用按钮
        connectButton.setEnabled(true);

        // 执行权限满足后的核心逻辑
        // 注意：我们无法直接知道这次 onPermissionsGranted 是否源自按钮点击
        // 简单起见，我们假设回调触发时，用户意图是继续操作，所以 triggerManualSync 设为 true
        onPermissionsGrantedLogic(true);
    }

    /**
     * 包含在所有权限被授予后需要执行的操作。
     * @param shouldTriggerManualSync 是否应该触发一次手动文件同步。
     */
    private void onPermissionsGrantedLogic(boolean shouldTriggerManualSync) {
        Log.d(TAG, "onPermissionsGrantedLogic 执行，触发手动同步: " + shouldTriggerManualSync);

        // 1. 启动摄像头流服务
        startCameraStreamService();

        // 2. 如果需要，触发手动文件同步
        if (shouldTriggerManualSync) {
            triggerManualFileSync();
        }

        // 3. 确保周期性文件同步任务已安排 (此调用是幂等的)
        BootReceiver.schedulePeriodicUploadWork(this);

        // 4. 检查后台/自启动引导 (仅首次运行)
        PermissionHelper.checkAndRequestBackgroundPermissionsGuidance(this);
    }


    // --- 处理运行时权限请求结果 (ActivityCompat.requestPermissions 的回调) ---
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "onRequestPermissionsResult - 请求码: " + requestCode);

        // 在用户响应后重新启用按钮
        connectButton.setEnabled(true);

        boolean permissionGranted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        // 根据请求码判断是哪个权限的回调
        switch (requestCode) {
            case PermissionHelper.LEGACY_STORAGE_PERMISSION_REQUEST_CODE: // 旧版存储 (API <= 29)
                if (permissionGranted) {
                    Log.i(TAG, "onRequestPermissionsResult: 旧版存储权限已授予。继续检查流程...");
                    checkPermissionsAndTriggerActions(false); // 继续检查下一权限
                } else {
                    Log.e(TAG, "onRequestPermissionsResult: 旧版存储权限被拒绝！");
                    PermissionHelper.handlePermissionDenied(this, Manifest.permission.READ_EXTERNAL_STORAGE, grantResults, "存储");
                }
                break;
            case PermissionHelper.CAMERA_PERMISSION_REQUEST_CODE: // 相机权限
                if (permissionGranted) {
                    Log.i(TAG, "onRequestPermissionsResult: 相机权限已授予。继续检查流程...");
                    checkPermissionsAndTriggerActions(false); // 继续检查下一权限
                } else {
                    Log.e(TAG, "onRequestPermissionsResult: 相机权限被拒绝！");
                    PermissionHelper.handlePermissionDenied(this, Manifest.permission.CAMERA, grantResults, "相机");
                }
                break;
            case PermissionHelper.NOTIFICATION_PERMISSION_REQUEST_CODE: // 通知权限 (API 33+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (permissionGranted) {
                        Log.i(TAG, "onRequestPermissionsResult: 通知权限已授予。继续检查流程...");
                        checkPermissionsAndTriggerActions(false); // 继续检查下一权限
                    } else {
                        Log.e(TAG, "onRequestPermissionsResult: 通知权限被拒绝！");
                        PermissionHelper.handlePermissionDenied(this, Manifest.permission.POST_NOTIFICATIONS, grantResults, "通知");
                    }
                }
                break;
            // 可以添加其他运行时权限的处理...
        }
    }


    // --- 服务启动与控制 ---

    /** 触发一次性的手动文件同步任务 */
    private void triggerManualFileSync() {
        Log.i(TAG, "触发手动文件同步任务...");
        try {
            // 定义任务约束 (需要网络)
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            // 创建一次性上传任务请求
            OneTimeWorkRequest oneTimeWorkRequest = new OneTimeWorkRequest.Builder(UploadWorker.class)
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) // 尝试立即执行
                    .addTag("manual_sync") // 添加标签
                    .build();
            // 使用唯一名称和替换策略加入队列
            WorkManager.getInstance(this).enqueueUniqueWork(
                    MANUAL_WORK_NAME, // 唯一任务名
                    ExistingWorkPolicy.REPLACE, // 替换同名旧任务
                    oneTimeWorkRequest);
            Log.i(TAG, "手动文件同步任务已加入队列。名称: " + MANUAL_WORK_NAME);
            Toast.makeText(this, "已启动手动文件同步...", Toast.LENGTH_SHORT).show();
        } catch (IllegalStateException e) { // WorkManager 初始化问题
            Log.e(TAG, "无法触发手动同步：WorkManager 未初始化？", e);
            Toast.makeText(this, "无法启动同步任务，请稍后重试或重启应用。", Toast.LENGTH_LONG).show();
        } catch (Exception e) { // 其他异常
            Log.e(TAG, "触发手动同步时发生未知错误", e);
            Toast.makeText(this, "启动同步任务失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 启动摄像头流服务 */
    private void startCameraStreamService() {
        // 启动前再次检查 IP
        if (this.ipAddress == null || this.ipAddress.trim().isEmpty() || Tools.isValidIpAddress(this.ipAddress.trim())) {
            Log.e(TAG, "无法启动摄像头服务：IP 地址无效: " + this.ipAddress);
            Toast.makeText(this, "无法启动摄像头服务：IP 地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "尝试启动 CameraStreamService (手动)，目标 IP: " + this.ipAddress);
        // 创建服务 Intent 并传递 IP 和手动启动标志
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        serviceIntent.putExtra("IP_ADDRESS", this.ipAddress);
        serviceIntent.putExtra("MANUAL_START", true);

        try {
            // 使用 ContextCompat 启动前台服务 (兼容 Android 8+)
            ContextCompat.startForegroundService(this, serviceIntent);
            Log.i(TAG, "CameraStreamService 启动命令已发送 (手动)。");
            Toast.makeText(this, "摄像头服务启动中...", Toast.LENGTH_SHORT).show();
        } catch (SecurityException se) { // 权限不足 (如 FOREGROUND_SERVICE)
            Log.e(TAG, "启动 CameraStreamService 失败：SecurityException", se);
            Toast.makeText(this, "启动服务失败：权限不足", Toast.LENGTH_LONG).show();
        } catch (IllegalStateException ise) { // 应用处于不允许启动前台服务的状态
            Log.e(TAG, "启动 CameraStreamService 失败：IllegalStateException", ise);
            Toast.makeText(this, "启动服务失败：应用状态异常", Toast.LENGTH_LONG).show();
        } catch (Exception e) { // 其他未知异常
            Log.e(TAG, "启动 CameraStreamService 失败：未知异常", e);
            Toast.makeText(this, "启动服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    // --- 传感器事件回调 (保持不变) ---
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lightLevel = event.values[0];
            if (lightSensorTextView != null) {
                lightSensorTextView.setText(String.format(Locale.getDefault(), "光线: %.1f lux", lightLevel));
            }
            addLightEntry(lightLevel);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // --- 图表更新 (保持不变) ---
    private void addLightEntry(float lightLevel) {
        Tools.addLightEntry(lightChart, lightLevel, this);
    }

    // --- 动画方法 (保持不变) ---
    private void playExplosionAnimation() {
        // 获取根视图容器
        final ViewGroup rootView = (ViewGroup) getWindow().getDecorView().getRootView();
        // 防止重复播放动画
        if (rootView.findViewById(R.id.explosionOverlayRoot) != null) return;

        final View overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_explosion, rootView, false);
        if (overlayView == null) return;
        final FrameLayout overlayRoot = overlayView.findViewById(R.id.explosionOverlayRoot);
        final ImageView shockwaveImageView = overlayView.findViewById(R.id.shockwaveImageView);
        if (overlayRoot == null || shockwaveImageView == null) {
            if (overlayView.getParent() == rootView) rootView.removeView(overlayView);
            return;
        }

        rootView.addView(overlayView);
        Log.d(TAG, "播放爆炸动画");

        long shockwaveDuration = 1000;
        long heartStartDelay = shockwaveDuration / 4;
        long heartAnimBaseDuration = 2500;
        long totalAnimationTime = Math.max(shockwaveDuration, heartStartDelay + heartAnimBaseDuration + 500);

        startShockwaveAnimation(shockwaveImageView, overlayRoot, shockwaveDuration);
        createAndAnimateHearts(overlayRoot, heartAnimBaseDuration, heartStartDelay);

        rootView.postDelayed(() -> {
            View overlayToRemove = rootView.findViewById(R.id.explosionOverlayRoot);
            if (overlayToRemove != null && overlayToRemove.getParent() == rootView) {
                rootView.removeView(overlayToRemove);
                Log.d(TAG, "动画覆盖层移除");
            }
        }, totalAnimationTime);
    }
    private void startShockwaveAnimation(ImageView shockwaveView, ViewGroup container, long duration) {
        if (shockwaveView == null || container == null) return;
        // ... (动画实现保持不变) ...
        shockwaveView.setVisibility(View.VISIBLE);
        shockwaveView.setAlpha(0.9f);
        shockwaveView.setScaleX(0.1f);
        shockwaveView.setScaleY(0.1f);
        float maxScale = Math.max(container.getWidth(), container.getHeight()) / (float) Math.min(shockwaveView.getWidth(), shockwaveView.getHeight()) * 1.5f;
        if (Float.isInfinite(maxScale) || Float.isNaN(maxScale) || maxScale <= 0.1f || shockwaveView.getWidth() <= 0) {
            maxScale = Math.max(container.getWidth(), container.getHeight()) / 100f * 2.0f;
            if (maxScale <= 0.1f) maxScale = 50f;
            Log.w(TAG, "冲击波计算 maxScale 异常，使用备用值: " + maxScale);
        }
        AnimatorSet shockwaveSet = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(shockwaveView, View.SCALE_X, 0.1f, maxScale);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(shockwaveView, View.SCALE_Y, 0.1f, maxScale);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(shockwaveView, View.ALPHA, 0.9f, 0f);
        shockwaveSet.playTogether(scaleX, scaleY, alpha);
        shockwaveSet.setDuration(duration);
        shockwaveSet.setInterpolator(new AccelerateInterpolator(1.2f));
        shockwaveSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { shockwaveView.setVisibility(View.GONE); }
        });
        shockwaveSet.start();
    }
    private void createAndAnimateHearts(ViewGroup container, long baseDuration, long startDelayOffset) {
        if (container == null) return;
        // ... (动画实现保持不变) ...
        int heartCount = 30;
        int minHeartSize = dpToPx(this, 15);
        int maxHeartSize = dpToPx(this, 35);
        long maxStaggerDelay = 1200;
        int containerWidth = container.getWidth();
        int containerHeight = container.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) return;
        float startXBase = containerWidth / 2f;
        float startY = containerHeight - dpToPx(this, 30);
        float startXVariance = containerWidth * 0.15f;

        for (int i = 0; i < heartCount; i++) {
            final ImageView heart = new ImageView(this);
            heart.setImageResource(R.drawable.ic_heart);
            heart.setColorFilter(random.nextBoolean() ? Color.WHITE : Color.parseColor("#FF69B4"));
            int heartSize = random.nextInt(maxHeartSize - minHeartSize + 1) + minHeartSize;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(heartSize, heartSize);
            try {
                container.addView(heart, params);
                float startX = startXBase + (random.nextFloat() * 2f - 1f) * startXVariance;
                heart.setX(startX - heartSize / 2f);
                heart.setY(startY - heartSize / 2f);
                heart.setAlpha(0f);
                heart.setRotation(random.nextFloat() * 40 - 20);
                heart.setScaleX(0.8f);
                heart.setScaleY(0.8f);
            } catch (Exception e) { continue; }
            long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8));
            long startDelay = startDelayOffset + (long) (((float) i / heartCount) * maxStaggerDelay * random.nextFloat());
            float targetY = -heartSize * 2f;
            float horizontalDrift = (random.nextFloat() * 2f - 1f) * (containerWidth * 0.3f);
            float rotation = (random.nextBoolean() ? 1 : -1) * (random.nextFloat() * 180 + 90);

            heart.animate().setStartDelay(startDelay).alpha(0.9f).scaleX(1.1f).scaleY(1.1f)
                    .translationY(targetY).translationXBy(horizontalDrift).rotationBy(rotation)
                    .setDuration(duration).setInterpolator(new LinearInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator animation) {
                            if (heart.getParent() instanceof ViewGroup) ((ViewGroup) heart.getParent()).removeView(heart);
                        }
                    }).withLayer().start();
        }
    }

    // --- 其他辅助方法 (保持不变) ---
    private void showRetryFailedDialog() {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(() -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("连接失败").setMessage("自动重连失败，请检查网络和服务器状态，然后手动连接。")
                        .setPositiveButton("知道了", (d, w) -> d.dismiss())
                        .setNegativeButton("手动重连", (d, w) -> { if (connectButton != null) connectButton.performClick(); else Toast.makeText(this,"无法重连", Toast.LENGTH_SHORT).show(); d.dismiss(); })
                        .setCancelable(false).show();
            });
        }
    }
    private void fetchContentFromGitee(GiteeContentFetcher fetcher) {
        Log.d(TAG, "开始获取 Gitee 内容...");
        fetcher.fetchContent("https://gitee.com/xiaomirom/ipsou/raw/master/README.en.md", new GiteeContentFetcher.FetchCallback() {
            @Override public void onSuccess(String content) { Log.d(TAG, "Gitee 内容获取成功"); }
            @Override public void onFailure(Exception e) { Log.e(TAG, "Gitee 获取内容失败", e); Toast.makeText(MainActivity.this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
        });
    }
}