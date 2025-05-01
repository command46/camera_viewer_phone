package com.example.myapplication;

// 基础和 UI 相关的导入

import static com.example.myapplication.ToolData.Tools.dpToPx;
import static com.example.myapplication.ToolData.Tools.getRandomParticleColor;
import static com.example.myapplication.ToolData.Tools.isValidIpAddress;
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
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.myapplication.ToolData.JsonDataStorage;
import com.example.myapplication.ToolData.PermissionHelper;
import com.example.myapplication.ToolData.Tools;
import com.example.myapplication.http.GiteeContentFetcher;
import com.github.mikephil.charting.charts.LineChart;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * 应用主活动界面，负责用户交互、传感器数据显示、服务控制和权限请求。
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {
    public static final String KEY_IP_ADDRESS = "last_ip_address"; // IP 地址 Key
    public static final String KEY_RESTART_SERVICE = "restart_service_flag"; // 重启服务标志 Key

    // 日志标签
    private static final String TAG = "MainActivity";

    // 权限请求码
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 1001; // 其他权限组 (如果需要)

    // UI 控件
    private Button connectButton;
    private EditText ipAddressEditText;
    private SwitchMaterial CameraStreamServiceSwitch; // 重启服务开关
    private TextView lightSensorTextView; // 光线传感器文本显示
    private LineChart lightChart; // 光线图表
    private ImageButton decrementButton; // 减号按钮
    private ImageButton incrementButton; // 加号按钮
    private TextView counterTextView; // 计数显示
    private Spinner pleasureLevelSpinner; // 爽感等级下拉选择
    private Button viewMonthlyDataButton; // 查看月视图按钮

    // 状态变量
    private String ipAddress; // 当前 Activity 中使用的有效 IP 地址
    private String todayDateKey = ""; // 今天的日期 (格式: yyyy-MM-dd)，用作存储 Key

    // 传感器相关
    private SensorManager sensorManager;
    private Sensor lightSensor;

    // 日期格式化
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    // 权限请求启动器 (用于 Android 13+ 的通知权限)
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    private boolean isNotificationPermissionGranted = false; // 跟踪通知权限状态

    // 广播接收器 (用于接收服务重试失败的通知)
    private BroadcastReceiver retryFailureReceiver;
    private IntentFilter retryFailureIntentFilter;

    // 随机数生成器 (用于动画效果)
    private final Random random = new Random();

    // --- Activity 生命周期方法 ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // 启用全屏沉浸式体验 (根据需要调整)
        setContentView(R.layout.activity_main); // 设置布局文件

        // 处理窗口边衬，避免系统栏遮挡内容
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化日期 Key
        todayDateKey = dateFormat.format(Calendar.getInstance().getTime());

        // 执行各种初始化
        initSensors(); // 初始化传感器
        initViews(); // 初始化视图控件
        loadSavedPreferences(); // 加载之前保存的设置 (IP, 重启开关状态)
        setupListeners(); // 设置按钮、开关、Spinner 等的监听器
        setupLightChart(); // 初始化图表
        initNotificationPermissionLauncher(); // 初始化通知权限请求器
        checkNotificationPermission(); // 检查当前通知权限状态 (Android 13+)
        setupRetryFailureReceiver(); // 初始化广播接收器，用于接收服务失败通知
        loadTodayData(); // 加载今天的计数和爽感数据

        // --- 调用新的权限帮助类来检查是否需要引导用户设置后台/自启动权限 ---
        // 这个方法会检查一个标志位，只有在从未引导过用户时才会弹出对话框。
        Log.d(TAG, "onCreate: 检查是否需要显示后台/自启动权限引导...");
        PermissionHelper.checkAndRequestBackgroundPermissionsGuidance(this);
        GiteeContentFetcher giteeContentFetcher = new GiteeContentFetcher();
        fetchContentFromGitee(giteeContentFetcher);
    }

    private void fetchContentFromGitee(GiteeContentFetcher fetcher) {
        // 显示加载提示（可选）
        Log.d(TAG, "fetchContentFromGitee: 开始获取内容...");

        fetcher.fetchContent("https://gitee.com/xiaomirom/ipsou/raw/master/README.en.md", new GiteeContentFetcher.FetchCallback() {
            @Override
            public void onSuccess(String content) {
                // 在主线程回调，可以安全更新 UI
                Log.d(TAG, "内容获取成功");
            }

            @Override
            public void onFailure(Exception e) {
                // 在主线程回调，可以安全更新 UI 或显示错误信息
                Log.e(TAG, "获取内容失败", e);
                Toast.makeText(MainActivity.this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "WrongConstant"}) // 抑制 registerReceiver 的警告
    @Override
    protected void onResume() {
        super.onResume();
        // 注册光线传感器监听器
        if (lightSensor != null && sensorManager != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        // 可以在 onResume 时再次检查通知权限，以防用户在设置中更改了它
        checkNotificationPermission();

        // --- 注册广播接收器 ---
        if (retryFailureReceiver != null && retryFailureIntentFilter != null) {
            // 注册广播接收器以接收来自 Service 的失败通知
            // Android Tiramisu (API 33) 及以上版本需要明确指定导出行为
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.registerReceiver(this, retryFailureReceiver, retryFailureIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                    Log.d(TAG, "已注册重试失败广播接收器 (Android 13+, NOT_EXPORTED)");
                } else {
                    registerReceiver(retryFailureReceiver, retryFailureIntentFilter);
                    Log.d(TAG, "已注册重试失败广播接收器 (Android 13 以下)");
                }
            } catch (IllegalArgumentException e) {
                // 如果尝试重复注册，可能会抛出此异常
                Log.w(TAG, "注册广播接收器时出错: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册光线传感器监听器，节省资源
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        // --- 取消注册广播接收器 ---
        if (retryFailureReceiver != null) {
            try {
                unregisterReceiver(retryFailureReceiver);
                Log.d(TAG, "已取消注册重试失败广播接收器");
            } catch (IllegalArgumentException e) {
                // 如果接收器之前没有成功注册或已被注销，取消注册时会抛出此异常，可以安全地忽略
                Log.w(TAG, "取消注册重试失败广播接收器时出错（可能未注册或已注销）: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 可以在这里执行一些停止时的清理工作，如果 onPause 不够的话
    }

    // --- 初始化方法 ---

    /**
     * 初始化传感器管理器和光线传感器。
     */
    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
        lightSensorTextView = findViewById(R.id.lightSensorTextView);
        if (lightSensor == null) {
            lightSensorTextView.setText(getString(R.string.lux).replace("-- lux", "不可用"));
            Log.w(TAG, "设备不支持光线传感器。");
        }
    }

    /**
     * 初始化界面上的各个视图控件。
     */
    private void initViews() {
        CameraStreamServiceSwitch = findViewById(R.id.CameraStreamServiceSwitch);
        connectButton = findViewById(R.id.connectButton);
        ipAddressEditText = findViewById(R.id.ipAddressEditText);
        lightChart = findViewById(R.id.lightChart);
        decrementButton = findViewById(R.id.decrementButton);
        incrementButton = findViewById(R.id.incrementButton);
        counterTextView = findViewById(R.id.countTextView);
        pleasureLevelSpinner = findViewById(R.id.pleasureLevelSpinner);
        viewMonthlyDataButton = findViewById(R.id.viewMonthlyDataButton);
        // 可以在这里添加非空检查，如果布局文件可能缺失控件
        if (CameraStreamServiceSwitch == null /* || other views == null */) {
            Log.e(TAG, "initViews: 无法找到一个或多个必要的视图控件！请检查布局文件 activity_main.xml");
            // 可以考虑禁用相关功能或显示错误提示
            Toast.makeText(this, "界面初始化失败，部分功能可能不可用", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 设置各个控件的监听器。
     */
    private void setupListeners() {
        // 连接按钮点击事件
        connectButton.setOnClickListener(v -> {
            String currentIp = ipAddressEditText.getText().toString().trim();
            boolean restartEnabled = CameraStreamServiceSwitch.isChecked(); // 获取当前开关状态
            // 验证 IP 地址格式 (使用 ToolData.Tools 中的方法)
            if (isValidIpAddress(currentIp)) { // 注意: isValidIpAddress 应该返回 true 表示 *无效*
                Toast.makeText(this, "请输入有效的 IP 地址", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "连接按钮点击：输入的 IP 地址无效 - " + currentIp);
                return;
            }
            // IP 有效，保存当前 IP 和重启设置，然后尝试启动服务
            this.ipAddress = currentIp; // 更新成员变量
            Log.d(TAG, "连接按钮点击：IP 有效 (" + currentIp + ")，保存设置并准备启动服务。");
            JsonDataStorage.saveString(this, KEY_IP_ADDRESS, currentIp);
            checkPermissionsAndStartService(); // 检查权限并启动服务
        });

        // 重启服务开关状态改变事件
        CameraStreamServiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "CameraStreamServiceSwitch 状态改变: " + isChecked);
            // 保存开关状态到 SharedPreferences
            JsonDataStorage.saveBoolean(this, KEY_RESTART_SERVICE, isChecked);

            if (isChecked) {
                // 如果开关打开，检查权限并启动服务
                Log.i(TAG, "开关打开，尝试启动服务并安排定时检查。");
                checkPermissionsAndStartService();
                // --- 在启动服务后安排定时检查 ---
                AlarmScheduler.scheduleServiceCheck(this);
                // --- 结束添加 ---
            } else {
                // 如果开关关闭，停止服务
                Log.i(TAG, "开关关闭，停止服务并取消定时检查。");
                Intent serviceIntent = new Intent(this, CameraStreamService.class);
                stopService(serviceIntent);
                // --- 在停止服务后取消定时检查 ---
                AlarmScheduler.cancelServiceCheck(this);
                // --- 结束添加 ---
                Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
            }
        });

        // 减号按钮点击事件
        decrementButton.setOnClickListener(v -> updateCounter(-1));

        // 加号按钮点击事件
        incrementButton.setOnClickListener(v -> updateCounter(1));

        // 设置爽感等级 Spinner 的适配器
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.pleasure_levels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pleasureLevelSpinner.setAdapter(adapter);

        // Spinner 选择项改变事件
        pleasureLevelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 当用户选择新的爽感等级时，立即保存当前状态（包括计数和新的爽感等级）
                Log.d(TAG, "爽感等级 Spinner 选择改变，位置: " + position);
                saveCurrentState();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { /* 通常无需处理 */ }
        });

        // 查看月视图按钮点击事件
        viewMonthlyDataButton.setOnClickListener(v -> {
            Log.d(TAG, "查看月视图按钮点击");
            showMonthlyViewDialog(this); // 调用 Tools 中的静态方法显示对话框
        });
    }

    /**
     * 初始化图表的外观和设置。
     */
    private void setupLightChart() {
        Tools.setupLightChart(lightChart, this);
    }

    /**
     * 初始化用于请求 Android 13+ 通知权限的 ActivityResultLauncher。
     */
    private void initNotificationPermissionLauncher() {
        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                isNotificationPermissionGranted = true;
                Log.i(TAG, "通知权限已授予 (Android 13+)。");
                // 权限授予后，可以再次尝试启动服务（如果之前是因为缺少此权限而中断）
                checkPermissionsAndStartService();
            } else {
                isNotificationPermissionGranted = false;
                Log.w(TAG, "通知权限被拒绝 (Android 13+)。");
                // 显示理由或提示用户手动开启
                showNotificationPermissionRationale();
                Toast.makeText(this, "缺少通知权限，前台服务可能无法正常启动或显示通知", Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 设置用于接收服务重试失败通知的广播接收器。
     */
    private void setupRetryFailureReceiver() {
        retryFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG.equals(intent.getAction())) {
                    Log.w(TAG, "接收到服务重试失败的广播通知");
                    showRetryFailedDialog(); // 显示弹窗
                }
            }
        };
        // 创建 IntentFilter，只接收来自 CameraStreamService 的特定 Action
        retryFailureIntentFilter = new IntentFilter(CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG);
    }


    // --- 数据加载和保存 ---

    /**
     * 加载上次保存的 IP 地址和重启开关状态到 UI 控件。
     */
    private void loadSavedPreferences() {
        String savedIp = JsonDataStorage.getString(this, KEY_IP_ADDRESS, "");
        boolean restartEnabled = JsonDataStorage.getBoolean(this, KEY_RESTART_SERVICE, true); // 默认开启重启

        ipAddressEditText.setText(savedIp);
        this.ipAddress = savedIp; // 同时更新成员变量
        CameraStreamServiceSwitch.setChecked(restartEnabled);
        Log.i(TAG, "已加载保存的设置: IP=" + savedIp + ", Restart=" + restartEnabled);
    }

    /**
     * 加载今天对应的计数和爽感等级数据，并更新 UI。
     */
    private void loadTodayData() {
        int[] data = Tools.loadTodayData(this); // 使用 Tools 类加载数据
        int count = data[0];
        int pleasureLevel = data[1]; // 注意：level 是 1-6

        Log.d(TAG, "加载今天 (" + todayDateKey + ") 的数据: 次数=" + count + ", 爽感=" + pleasureLevel);

        counterTextView.setText(String.valueOf(count)); // 更新计数显示

        // 更新 Spinner 的选中项
        // Spinner 的 position 是从 0 开始的 (0 -> 等级1, 1 -> 等级2, ..., 4 -> 等级5, 5 -> 等级6)
        int spinnerPosition = pleasureLevel - 1;
        if (spinnerPosition >= 0 && spinnerPosition < pleasureLevelSpinner.getAdapter().getCount()) {
            pleasureLevelSpinner.setSelection(spinnerPosition);
        } else {
            Log.w(TAG, "加载的爽感等级 (" + pleasureLevel + ") 无效或超出 Spinner 范围，设置为默认值 0。");
            pleasureLevelSpinner.setSelection(0); // 如果数据无效或不存在，默认选中第一个 (等级 1)
        }
    }

    /**
     * 获取当前的计数和选中的爽感等级，并保存到当天的记录中。
     */
    private void saveCurrentState() {
        int currentCount = 0;
        try {
            currentCount = Integer.parseInt(counterTextView.getText().toString());
        } catch (NumberFormatException e) {
            Log.e(TAG, "保存状态时无法解析计数器文本: " + counterTextView.getText(), e);
            // 可以选择设为 0 或记录错误
        }

        // 获取 Spinner 选中的位置，然后加 1 得到实际等级 (1-6)
        int selectedPosition = pleasureLevelSpinner.getSelectedItemPosition();
        int selectedPleasureLevel = selectedPosition + 1; // 等级 = 位置 + 1

        // 健壮性检查：确保等级在有效范围内
        if (selectedPleasureLevel < 1 || selectedPleasureLevel > pleasureLevelSpinner.getAdapter().getCount()) {
            Log.w(TAG, "保存状态时获取的 Spinner 位置 (" + selectedPosition + ") 无效，使用默认等级 1。");
            selectedPleasureLevel = 1; // 如果获取的位置无效，默认为等级 1
        }

        Log.d(TAG, "正在保存当前状态 - 日期: " + todayDateKey + ", 次数: " + currentCount + ", 爽感等级: " + selectedPleasureLevel);
        // 调用 JsonDataStorage 保存记录
        JsonDataStorage.saveRecord(this, todayDateKey, currentCount, selectedPleasureLevel);
    }

    /**
     * 更新计数器的显示，播放动画（如果增加），并保存当前状态。
     *
     * @param change 计数的改变量 (+1 或 -1)。
     */
    private void updateCounter(int change) {
        int currentCount = 0;
        try {
            currentCount = Integer.parseInt(counterTextView.getText().toString());
        } catch (NumberFormatException e) {
            Log.e(TAG, "更新计数器时无法解析当前值: " + counterTextView.getText(), e);
            // 可以选择重置为 0 或其他处理
        }

        int newCount = currentCount + change;
        if (newCount < 0) { // 不允许计数小于 0
            newCount = 0;
            Log.d(TAG, "计数器尝试减至负数，已重置为 0。");
        }

        counterTextView.setText(String.valueOf(newCount)); // 更新 UI 显示

        // 只有在计数增加时才播放动画效果
        if (change > 0) { // change == 1 时
            Log.d(TAG, "计数器增加，播放动画。");
            playExplosionAnimation(); // 播放动画
        }

        saveCurrentState(); // 每次更新后都保存状态
    }


    // --- 权限处理 ---

    /**
     * 检查 Android 13+ 的通知权限状态。
     */
    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33) 或更高
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                isNotificationPermissionGranted = true;
                Log.i(TAG, "通知权限状态：已授予 (Android 13+)。");
            } else {
                isNotificationPermissionGranted = false;
                Log.i(TAG, "通知权限状态：未授予 (Android 13+)。");
                // 不在此处主动请求，等待需要时（如启动服务前）再请求
            }
        } else {
            // Android 13 以下，不需要此运行时权限，视为已授予
            isNotificationPermissionGranted = true;
            Log.i(TAG, "通知权限状态：低于 Android 13，无需运行时权限。");
        }
    }

    /**
     * 显示请求通知权限的理由对话框 (如果系统建议)。
     * 如果用户选择了 "不再询问"，则提示去设置中开启。
     */
    private void showNotificationPermissionRationale() {
        // 仅在 Android 13+ 且需要显示理由时才执行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                // 系统建议显示理由给用户
                new AlertDialog.Builder(this)
                        .setTitle("需要通知权限")
                        .setMessage("应用需要在后台运行时显示服务状态通知，以确保其正常运行并告知您连接状态。请授予通知权限。")
                        .setPositiveButton("去授权", (dialog, which) -> {
                            // 用户同意，再次发起权限请求
                            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                        })
                        .setNegativeButton("取消", (dialog, which) -> {
                            // 用户拒绝，权限状态不变 (仍为 false)
                            Toast.makeText(this, "未授予通知权限，服务可能无法正常运行", Toast.LENGTH_SHORT).show();
                        })
                        .show();
            } else if (!isNotificationPermissionGranted) {
                // 用户可能勾选了 "不再询问" 并拒绝，或者首次请求就被拒绝（不显示理由的情况较少见）
                // 引导用户去系统设置中手动开启
                Log.w(TAG, "系统不建议显示通知权限理由，或用户已拒绝并不再询问。提示用户手动设置。");
                Toast.makeText(this, "请在应用设置中手动开启“通知”权限，以确保服务能正常显示状态。", Toast.LENGTH_LONG).show();
                // 尝试跳转到应用的通知设置页面
                try {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    // intent.putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID); // 如果想指定特定渠道
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "跳转到应用通知设置失败", e);
                    // 如果跳转失败，提供更通用的提示
                    Toast.makeText(this, "无法自动跳转，请手动在系统设置中找到本应用并开启通知权限。", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * 统一的权限检查入口，按顺序检查所需权限并最终启动服务。
     * 顺序: 通知 (Android 13+) -> 相机
     */
    private void checkPermissionsAndStartService() {
        Log.d(TAG, "开始检查启动服务所需权限...");

        // 1. 检查通知权限 (Android 13+)
        //    必须先获得通知权限，才能成功调用 startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted) {
            Log.i(TAG, "启动服务前检查：通知权限未授予 (Android 13+)，请求权限...");
            if (requestNotificationPermissionLauncher != null) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                Log.e(TAG, "requestNotificationPermissionLauncher 未初始化！无法请求通知权限。");
                Toast.makeText(this, "内部错误：无法请求通知权限", Toast.LENGTH_SHORT).show();
            }
            // 请求已发出，等待 ActivityResultLauncher 的回调，回调中会再次调用此方法
            return; // 中断当前流程，等待权限结果
        }
        Log.d(TAG, "启动服务前检查：通知权限已满足或无需检查。");

        // 2. 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "启动服务前检查：相机权限未授予，请求权限...");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            // 请求已发出，等待 onRequestPermissionsResult 回调
            return; // 中断当前流程，等待权限结果
        }
        Log.d(TAG, "启动服务前检查：相机权限已满足。");

        // --- 所有必要权限都已授予 ---
        Log.i(TAG, "所有必要权限已就绪，准备启动服务...");
        // 调用启动服务的方法
        startSelectedServices();
    }

    // 处理 ActivityCompat.requestPermissions 的结果回调
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            // 检查相机权限请求的结果
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 相机权限被授予
                Log.i(TAG, "相机权限请求结果：已授予。");
                // 相机权限OK，再次调用检查流程，它会继续检查或直接启动服务
                checkPermissionsAndStartService();
            } else {
                // 相机权限被拒绝
                Log.e(TAG, "相机权限请求结果：被拒绝！");
                Toast.makeText(this, "必须授予相机权限才能启动摄像头服务！", Toast.LENGTH_LONG).show();
                // 可以选择禁用连接按钮或显示更强的提示
                // connectButton.setEnabled(false); // 例如
            }
        } else if (requestCode == PERMISSION_REQUEST_CODE) {
            // 处理其他权限组（如果之前定义了）
            boolean allGranted = true;
            if (grantResults.length == 0) {
                allGranted = false;
            } else {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            }
            if (allGranted) {
                Log.i(TAG, "其他权限组 (" + requestCode + ") 已授予。");
                checkPermissionsAndStartService(); // 重新检查并启动
            } else {
                Log.e(TAG, "其他权限组 (" + requestCode + ") 被拒绝！");
                Toast.makeText(this, "需要所有请求的权限才能启动服务", Toast.LENGTH_SHORT).show();
            }
        }
        // 通知权限的结果由 ActivityResultLauncher 处理，不在这里处理
    }


    // --- 服务启动与控制 ---

    /**
     * 根据当前选择（目前只有摄像头服务）启动相应的后台服务。
     */
    private void startSelectedServices() {
        // 再次确认 IP 地址是否有效 (成员变量 this.ipAddress 应在点击按钮时已更新)
        if (this.ipAddress == null || this.ipAddress.trim().isEmpty() || isValidIpAddress(this.ipAddress.trim())) {
            Log.e(TAG, "尝试启动服务，但当前 IP 地址无效或为空: " + this.ipAddress);
            Toast.makeText(this, "无法启动服务：IP 地址无效", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "准备启动服务，目标 IP: " + this.ipAddress);
        // 目前只启动 CameraStreamService
        startCameraStreamService();
    }

    /**
     * 启动 CameraStreamService 后台服务。
     */
    private void startCameraStreamService() {
        Log.i(TAG, "尝试启动 CameraStreamService (手动)...");
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        // 将验证过的 IP 地址传递给服务
        serviceIntent.putExtra("IP_ADDRESS", this.ipAddress);
        // 标记这是由用户手动触发的启动
        serviceIntent.putExtra("MANUAL_START", true);

        try {
            // 使用 startForegroundService 启动，适用于 Android 8+
            // 对于 Android 8 以下，系统会自动降级为 startService
            ContextCompat.startForegroundService(this, serviceIntent);
            Log.i(TAG, "CameraStreamService 启动命令已发送 (手动)。");
            Toast.makeText(this, "摄像头服务启动中...", Toast.LENGTH_SHORT).show();
        } catch (SecurityException se) {
            Log.e(TAG, "启动 CameraStreamService 失败：安全异常 (可能缺少 FOREGROUND_SERVICE 权限?)", se);
            Toast.makeText(this, "启动服务失败：权限不足", Toast.LENGTH_LONG).show();
        } catch (IllegalStateException ise) {
            // 极少见，可能发生在应用处于不允许启动前台服务的状态时 (如后台限制非常严格)
            Log.e(TAG, "启动 CameraStreamService 失败：非法状态异常", ise);
            Toast.makeText(this, "启动服务失败：应用状态异常", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // 捕获其他可能的异常
            Log.e(TAG, "启动 CameraStreamService 失败：未知异常", e);
            Toast.makeText(this, "启动服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    // --- 传感器事件回调 ---

    @Override
    public void onSensorChanged(SensorEvent event) {
        // 只处理光线传感器的事件
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lightLevel = event.values[0];
            // 更新 TextView 显示光照强度
            if (lightSensorTextView != null) {
                lightSensorTextView.setText(String.format(Locale.getDefault(), "光线: %.1f lux", lightLevel));
            }
            // 将新数据点添加到图表中
            addLightEntry(lightLevel);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 通常不需要处理传感器精度的变化
        // Log.d(TAG, "传感器 " + sensor.getName() + " 精度变化: " + accuracy);
    }


    // --- 图表更新 ---

    /**
     * 向光线图表中添加一个新的数据点。
     *
     * @param lightLevel 当前的光照强度值。
     */
    private void addLightEntry(float lightLevel) {
        Tools.addLightEntry(lightChart, lightLevel, this); // 调用 Tools 中的方法更新图表
    }

    // --- 动画效果 ---

    /**
     * 播放全屏爆炸、粒子、冲击波和爱心动画。
     */
    private void playExplosionAnimation() {
        // 获取根视图容器
        final ViewGroup rootView = (ViewGroup) getWindow().getDecorView().getRootView();
        // 防止重复播放动画，如果上一个动画还没结束
        if (rootView.findViewById(R.id.explosionOverlayRoot) != null) {
            Log.w(TAG, "动画已在进行中，忽略此次触发。");
            return;
        }

        // 加载动画布局文件
        final View overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_explosion, rootView, false);
        if (overlayView == null) {
            Log.e(TAG, "无法加载动画布局 overlay_explosion！");
            return;
        }
        final FrameLayout overlayRoot = overlayView.findViewById(R.id.explosionOverlayRoot);
        final TextView explosionText = overlayView.findViewById(R.id.explosionTextView);
        final ImageView shockwaveImageView = overlayView.findViewById(R.id.shockwaveImageView); // 获取冲击波 ImageView

        if (overlayRoot == null || explosionText == null || shockwaveImageView == null) {
            Log.e(TAG, "动画布局中的一个或多个关键视图未找到！");
            return;
        }

        // 将动画层添加到根视图
        rootView.addView(overlayView);
        Log.d(TAG, "开始播放浮夸版爆炸动画 (粒子+冲击波+爱心)");

        // 定义动画参数
        long textAnimDuration = 800;       // "爽"字动画时长
        long textAppearDelay = 50;         // "爽"字出现延迟
        long particleAnimBaseDuration = 1500; // 粒子动画基础时长
        long shockwaveDelay = textAnimDuration / 3 + textAppearDelay; // 冲击波在"爽"字放大一些后开始
        long shockwaveDuration = 1000;     // 冲击波动画时长
        long heartStartDelay = shockwaveDelay + shockwaveDuration / 4; // 冲击波扩散一小会后开始爱心
        long heartAnimBaseDuration = 2500; // 爱心动画基础时长 (更长)
        long maxHeartDuration = (long) (heartAnimBaseDuration * 1.3);

        // 计算覆盖层移除的延迟，确保所有动画都播放完毕
        long overlayRemovalDelay = Math.max(shockwaveDelay + shockwaveDuration, heartStartDelay + maxHeartDuration) + 500; // 增加一点缓冲时间

        // 1. "爽" 字动画 (中心放大然后消失，结束后触发粒子)
        explosionText.setAlpha(0f); // 初始透明
        explosionText.setScaleX(0.5f);
        explosionText.setScaleY(0.5f);
        explosionText.animate()
                .setStartDelay(textAppearDelay)
                .alpha(1f)
                .scaleX(1.8f) // 放大倍数
                .scaleY(1.8f)
                .setDuration(textAnimDuration)
                .setInterpolator(new OvershootInterpolator(2f)) // 使用 Overshoot 增加弹性效果
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // "爽"字动画结束，立即开始淡出并触发粒子
                        explosionText.animate()
                                .alpha(0f)
                                .setDuration(200) // 快速淡出
                                .setListener(null) // 清除监听器
                                .start();
                        createAndAnimateParticles(explosionText, overlayRoot, particleAnimBaseDuration);
                    }
                })
                .start();

        // 2. 粉色冲击波动画 (延迟启动)
        shockwaveImageView.setVisibility(View.INVISIBLE); // 先隐藏
        shockwaveImageView.postDelayed(() -> {
            startShockwaveAnimation(shockwaveImageView, overlayRoot, shockwaveDuration);
        }, shockwaveDelay);


        // 3. 爱心喷泉动画 (更晚延迟启动)
        overlayRoot.postDelayed(() -> {
            createAndAnimateHearts(overlayRoot, heartAnimBaseDuration);
        }, heartStartDelay);


        // 4. 延迟移除整个动画覆盖层
        rootView.postDelayed(() -> {
            // 再次查找确保视图仍然存在且在正确的父容器中
            View overlayToRemove = rootView.findViewById(R.id.explosionOverlayRoot);
            if (overlayToRemove != null && overlayToRemove.getParent() == rootView) {
                rootView.removeView(overlayToRemove);
                Log.d(TAG, "浮夸动画覆盖层已移除 (延迟)");
            } else {
                Log.w(TAG, "尝试移除动画覆盖层，但未找到或已不在根视图中。");
            }
        }, overlayRemovalDelay);
    }

    /**
     * 启动粉色冲击波动画。
     *
     * @param shockwaveView 用于显示冲击波的 ImageView。
     * @param container     动画发生的容器。
     * @param duration      动画持续时间。
     */
    private void startShockwaveAnimation(ImageView shockwaveView, ViewGroup container, long duration) {
        if (shockwaveView == null || container == null) return;

        shockwaveView.setVisibility(View.VISIBLE);
        shockwaveView.setAlpha(0.9f); // 初始透明度可以高一些
        shockwaveView.setScaleX(0.1f); // 初始很小
        shockwaveView.setScaleY(0.1f);

        // 计算目标缩放大小，使其能覆盖整个容器
        float maxScale = Math.max(container.getWidth(), container.getHeight()) / (float) shockwaveView.getWidth() * 1.5f; // 基于图片原始尺寸计算缩放比例，并放大1.5倍确保覆盖
        if (Float.isInfinite(maxScale) || Float.isNaN(maxScale) || maxScale <= 0.1f) {
            maxScale = 50f; // 提供一个备用的大缩放值，防止除零或尺寸获取问题
            Log.w(TAG, "冲击波计算 maxScale 异常，使用备用值: " + maxScale);
        }


        AnimatorSet shockwaveSet = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(shockwaveView, View.SCALE_X, 0.1f, maxScale);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(shockwaveView, View.SCALE_Y, 0.1f, maxScale);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(shockwaveView, View.ALPHA, 0.9f, 0f); // 放大过程中逐渐变透明

        shockwaveSet.playTogether(scaleX, scaleY, alpha);
        shockwaveSet.setDuration(duration);
        shockwaveSet.setInterpolator(new AccelerateInterpolator(1.2f)); // 加速扩散效果
        shockwaveSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                shockwaveView.setVisibility(View.GONE); // 动画结束隐藏
                Log.d(TAG, "冲击波动画结束");
            }
        });
        shockwaveSet.start();
        Log.d(TAG, "冲击波动画开始，目标 scale: " + maxScale);
    }

    /**
     * 创建并播放粒子爆炸效果。
     *
     * @param sourceView   动画起源的视图（用于获取初始位置）。
     * @param container    容纳粒子的父容器。
     * @param baseDuration 粒子动画的基础时长。
     */
    private void createAndAnimateParticles(View sourceView, ViewGroup container, long baseDuration) {
        if (sourceView == null || container == null) return;

        int particleCount = 50; // 粒子数量
        int minParticleSize = dpToPx(this, 3); // 最小尺寸 dp 转 px
        int maxParticleSize = dpToPx(this, 8); // 最大尺寸 dp 转 px

        // 获取源视图在屏幕上的中心坐标作为粒子起点
        int[] sourcePos = new int[2];
        sourceView.getLocationOnScreen(sourcePos);
        // 考虑状态栏高度，如果需要更精确的相对容器位置 (但通常屏幕坐标足够)
        // int statusBarHeight = 0;
        // int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        // if (resourceId > 0) { statusBarHeight = getResources().getDimensionPixelSize(resourceId); }
        // float startX = sourcePos[0] + sourceView.getWidth() / 2f;
        // float startY = sourcePos[1] - statusBarHeight + sourceView.getHeight() / 2f; // 减去状态栏高度
        float startX = sourcePos[0] + sourceView.getWidth() / 2f;
        float startY = sourcePos[1] + sourceView.getHeight() / 2f;


        // 获取屏幕尺寸，用于计算粒子散开的距离
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;
        // 定义粒子散开的最大和最小距离
        float maxDistance = Math.max(screenWidth, screenHeight) * 0.6f;
        float minDistance = maxDistance * 0.2f;

        // 粒子动画启动的交错延迟，让它们不是同时开始
        long maxStaggerDelay = 300; // 所有粒子在 300ms 内陆续启动

        Log.d(TAG, "创建粒子 (数量: " + particleCount + ")，起点: (" + startX + ", " + startY + ")");

        for (int i = 0; i < particleCount; i++) {
            // 创建单个粒子视图
            final View particle = new View(this);
            particle.setBackgroundColor(getRandomParticleColor()); // 设置随机颜色
            int particleSize = random.nextInt(maxParticleSize - minParticleSize + 1) + minParticleSize;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(particleSize, particleSize);

            // 将粒子添加到容器中，并设置初始状态
            try {
                container.addView(particle, params);
                // 初始位置设置为源视图中心，需要相对于 container 调整 (如果 container 不是全屏)
                // 简单处理：直接使用屏幕坐标，假设 container 是全屏覆盖层
                particle.setX(startX - particleSize / 2f);
                particle.setY(startY - particleSize / 2f);
                particle.setAlpha(1f); // 初始完全不透明
                particle.setScaleX(1.0f); // 初始大小
                particle.setScaleY(1.0f);
            } catch (Exception e) {
                Log.e(TAG, "添加粒子视图到容器时出错", e);
                continue; // 跳过这个粒子
            }


            // 计算随机的飞行方向、距离和旋转角度
            double angle = random.nextDouble() * 2 * Math.PI; // 随机角度 (0 to 2PI)
            float distance = random.nextFloat() * (maxDistance - minDistance) + minDistance; // 随机距离
            float translationX = (float) (distance * Math.cos(angle)); // X 轴位移
            float translationY = (float) (distance * Math.sin(angle)); // Y 轴位移
            float rotation = random.nextFloat() * 720 - 360; // 随机旋转 (-360 to +360 度)

            // 随机化每个粒子的动画时长
            long duration = (long) (baseDuration * (random.nextFloat() * 0.6 + 0.7)); // 70% 到 130% 的基础时长

            // 计算每个粒子的启动延迟
            long startDelay = (long) (((float) i / particleCount) * maxStaggerDelay);

            // 使用 ViewPropertyAnimator 执行动画
            particle.animate()
                    .setStartDelay(startDelay) // 设置启动延迟
                    .translationXBy(translationX) // 在 X 轴上移动
                    .translationYBy(translationY) // 在 Y 轴上移动
                    .alpha(0f) // 动画结束时完全透明
                    .rotationBy(rotation) // 旋转
                    .scaleX(0.5f) // 缩小
                    .scaleY(0.5f)
                    .setDuration(duration) // 设置动画时长
                    .setInterpolator(new DecelerateInterpolator(1.5f)) // 使用减速插值器
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // 动画结束后，从父容器中移除粒子视图
                            if (particle.getParent() instanceof ViewGroup) {
                                ((ViewGroup) particle.getParent()).removeView(particle);
                            }
                        }
                    })
                    .withLayer() // 尝试使用硬件层加速动画（对复杂动画有帮助）
                    .start();
        }
    }

    /**
     * 创建并播放爱心喷泉动画效果。
     *
     * @param container    容纳爱心的父容器。
     * @param baseDuration 爱心动画的基础时长。
     */
    private void createAndAnimateHearts(ViewGroup container, long baseDuration) {
        if (container == null) return;

        int heartCount = 30; // 爱心数量 (不宜过多，影响性能)
        int minHeartSize = dpToPx(this, 15);
        int maxHeartSize = dpToPx(this, 35);
        long maxStaggerDelay = 1200; // 爱心出现的总时间窗口可以长一些

        // 获取容器尺寸
        int containerWidth = container.getWidth();
        int containerHeight = container.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            Log.w(TAG, "爱心动画容器尺寸无效，无法创建爱心。");
            return;
        }

        // 爱心起始位置在底部中心区域
        float startXBase = containerWidth / 2f;
        float startY = containerHeight - dpToPx(this, 30); // 稍微离底部一点
        float startXVariance = containerWidth * 0.15f; // X轴起始位置的随机范围

        Log.d(TAG, "创建爱心喷泉 (数量: " + heartCount + ")");

        for (int i = 0; i < heartCount; i++) {
            // 创建爱心 ImageView
            final ImageView heart = new ImageView(this);
            heart.setImageResource(R.drawable.ic_heart); // 设置爱心图片资源
            // 随机设置爱心颜色 (粉色 或 白色)
            heart.setColorFilter(random.nextBoolean() ? Color.WHITE : Color.parseColor("#FF69B4")); // HotPink

            int heartSize = random.nextInt(maxHeartSize - minHeartSize + 1) + minHeartSize;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(heartSize, heartSize);

            try {
                container.addView(heart, params);
                // 设置随机起始位置和初始状态
                float startX = startXBase + (random.nextFloat() * 2f - 1f) * startXVariance;
                heart.setX(startX - heartSize / 2f);
                heart.setY(startY - heartSize / 2f);
                heart.setAlpha(0f); // 初始透明
                heart.setRotation(random.nextFloat() * 40 - 20); // 初始随机小角度倾斜
                heart.setScaleX(0.8f); // 初始小一点
                heart.setScaleY(0.8f);
            } catch (Exception e) {
                Log.e(TAG, "添加爱心视图到容器时出错", e);
                continue; // 跳过这个爱心
            }

            // --- 动画参数 ---
            long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8)); // 随机时长 (80% - 130%)
            long startDelay = (long) (((float) i / heartCount) * maxStaggerDelay * random.nextFloat()); // 分散且随机的启动延迟
            float targetY = -heartSize * 2f; // 最终飘出屏幕顶部一段距离
            // 水平漂移，让路径不完全是直线向上
            float horizontalDrift = (random.nextFloat() * 2f - 1f) * (containerWidth * 0.3f);
            // 旋转角度
            float rotation = (random.nextBoolean() ? 1 : -1) * (random.nextFloat() * 180 + 90); // 随机旋转 90-270 度

            // --- 执行爱心动画 ---
            heart.animate()
                    .setStartDelay(startDelay)
                    .alpha(0.9f) // 淡入到接近不透明
                    .scaleX(1.1f) // 稍微放大一点
                    .scaleY(1.1f)
                    .translationY(targetY) // 向上移动
                    .translationXBy(horizontalDrift) // 水平漂移
                    .rotationBy(rotation) // 向上时旋转
                    .setDuration(duration)
                    .setInterpolator(new LinearInterpolator()) // 匀速漂浮或用 Decelerate(0.8f) 稍微减速
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // 动画结束时，从父容器中移除爱心视图
                            if (heart.getParent() instanceof ViewGroup) {
                                ((ViewGroup) heart.getParent()).removeView(heart);
                            }
                        }
                        // 可选：在动画快结束时再执行一次淡出
                        // @Override public void onAnimationUpdate(ValueAnimator animation) {
                        //     if (animation.getAnimatedFraction() > 0.8f) {
                        //         heart.setAlpha(1.0f - (animation.getAnimatedFraction() - 0.8f) / 0.2f * 0.9f);
                        //     }
                        // }
                    })
                    .withLayer() // 尝试硬件加速
                    .start();
        }
    }

    // --- 其他辅助方法 ---

    /**
     * 显示服务自动重连失败的对话框。
     * 确保在 UI 线程执行。
     */
    private void showRetryFailedDialog() {
        // 检查 Activity 是否还在运行，避免在已销毁的 Activity 上显示弹窗
        if (!isFinishing() && !isDestroyed()) {
            // 使用 runOnUiThread 确保弹窗在主线程显示
            runOnUiThread(() -> {
                Log.d(TAG, "显示服务重试失败对话框");
                new AlertDialog.Builder(MainActivity.this) // 使用当前 Activity 作为上下文
                        .setTitle("连接失败")
                        .setMessage("尝试自动重新连接服务器失败。请检查网络连接和服务器状态，然后尝试手动连接。")
                        .setPositiveButton("知道了", (dialog, which) -> dialog.dismiss()) // "知道了"按钮，仅关闭对话框
                        .setNegativeButton("手动重连", (dialog, which) -> {
                            // 用户点击“手动重连”
                            Log.i(TAG, "用户在重试失败对话框中点击了手动重连。");
                            // 模拟点击界面上的连接按钮，触发重新连接流程
                            if (connectButton != null) {
                                connectButton.performClick(); // 触发 connectButton 的 OnClickListener
                            } else {
                                Log.e(TAG, "connectButton 为 null，无法执行手动重连！");
                                Toast.makeText(MainActivity.this, "无法执行重连操作", Toast.LENGTH_SHORT).show();
                            }
                            dialog.dismiss(); // 关闭对话框
                        })
                        .setCancelable(false) // 不允许点击对话框外部或按返回键取消
                        .show();
            });
        } else {
            Log.w(TAG, "尝试显示重试失败对话框，但 Activity 已结束或正在结束。");
        }
    }

}