package com.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // SharedPreferences 相关常量
    private static final String PREFS_NAME = "CameraServicePrefs";
    private static final String KEY_IP_ADDRESS = "last_ip_address";
    private static final String KEY_RESTART_SERVICE = "restart_service_flag";

    // KEY_RETRY_COUNT 由 Service 内部管理，Activity 不需要知道

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 1001; // 用于其他权限组（如果需要）

    private final boolean StartCameraStreamService = true; // 默认启动 CameraStreamService

    private Button connectButton;
    private EditText ipAddressEditText;
    private String ipAddress; // 用于存储当前 Activity 中验证通过并用于启动服务的 IP 地址
    private SharedPreferences sharedPreferences;
    private SwitchMaterial CameraStreamServiceSwitch; // 重启开关

    // 传感器相关
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private TextView lightSensorTextView;

    // 图表相关
    private LineChart lightChart;

    //计数器
    private ImageButton decrementButton;
    private ImageButton incrementButton;
    private TextView counterTextView;


    // 权限请求启动器
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    private boolean isNotificationPermissionGranted = false; // 跟踪通知权限状态

    //用于接收服务重试失败的广播
    private BroadcastReceiver retryFailureReceiver;
    private IntentFilter retryFailureIntentFilter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 初始化 SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 初始化传感器
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorTextView = findViewById(R.id.lightSensorTextView);
        if (lightSensor == null) {
            lightSensorTextView.setText("光线传感器: 不可用");
        }

        // 初始化视图控件
        CameraStreamServiceSwitch = findViewById(R.id.CameraStreamServiceSwitch);
        connectButton = findViewById(R.id.connectButton);
        ipAddressEditText = findViewById(R.id.ipAddressEditText);

        // 加载保存的设置 (IP 地址和重启开关状态)
        loadSavedPreferences();

        // 设置连接按钮点击事件
        connectButton.setOnClickListener(v -> {
            // 1. 获取当前输入
            String currentIp = ipAddressEditText.getText().toString().trim();
            boolean restartEnabled = CameraStreamServiceSwitch.isChecked();

            // 2. 验证 IP 地址
            if (isValidIpAddress(currentIp)) { // 使用 IP 验证函数
                Toast.makeText(this, "请输入有效的 IP 地址", Toast.LENGTH_SHORT).show();
                return;
            }

            // 3. 保存有效的 IP 和重启设置到 SharedPreferences
            this.ipAddress = currentIp; // 更新成员变量，供 startService 使用
            savePreferences(currentIp, restartEnabled);

            // 4. 检查权限并尝试启动服务
            checkPermissionsAndStartService();
        });

        // 设置重启开关状态变化监听器
        CameraStreamServiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 当开关状态改变时，立即保存新的状态
            saveRestartPreference(isChecked);
        });
        //计数器
        decrementButton = findViewById(R.id.decrementButton);
        incrementButton = findViewById(R.id.incrementButton);
        counterTextView = findViewById(R.id.countTextView);
        decrementButton.setOnClickListener(v -> {
            int currentCount = Integer.parseInt(counterTextView.getText().toString());
            if (currentCount > 0) {
                counterTextView.setText(String.valueOf(currentCount - 1));
            }
        });
        incrementButton.setOnClickListener(v -> {
            int currentCount = Integer.parseInt(counterTextView.getText().toString());
            counterTextView.setText(String.valueOf(currentCount + 1));
        });
        counterTextView.setText("0");
        //为计数器添加监听器有变化就写入本地数据
        counterTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });



        // 初始化图表
        lightChart = findViewById(R.id.lightChart);
        setupLightChart();

        // 初始化通知权限请求启动器
        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                isNotificationPermissionGranted = true;
                Log.i(TAG, "通知权限已授予。");
                // 权限授予后，可以再次尝试启动服务（如果是因为缺少此权限而中断的话）
                checkPermissionsAndStartService();
            } else {
                isNotificationPermissionGranted = false;
                Log.w(TAG, "通知权限被拒绝。");
                // 权限被拒绝，可以显示提示或解释
                showNotificationPermissionRationale(); // 再次解释原因或提示功能受限
                Toast.makeText(this,"缺少通知权限，前台服务可能无法正常启动或显示通知", Toast.LENGTH_LONG).show();
            }
        });

        // 检查通知权限 (如果需要，其他权限检查在点击连接时进行)
        checkNotificationPermission();

        // --- 新增：初始化重试失败广播接收器 ---
        setupRetryFailureReceiver();
        // --- 结束新增 ---
    }

    /**
     * 设置用于接收服务重试失败通知的广播接收器。
     */
    private void setupRetryFailureReceiver() {
        retryFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 检查收到的广播 Action 是否是我们期望的那个
                if (CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG.equals(intent.getAction())) {
                    Log.w(TAG, "接收到服务重试失败的广播通知");
                    // 调用显示弹窗的方法
                    showRetryFailedDialog();
                }
            }
        };
        // 创建 IntentFilter，只接收指定 Action 的广播
        // 使用 Service 中定义的完整 Action 名称
        retryFailureIntentFilter = new IntentFilter(CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG);
    }

    /**
     * 显示自动重连失败的对话框。
     * 确保在 UI 线程执行。
     */
    private void showRetryFailedDialog() {
        // 确保 Activity 仍然处于活动状态，避免在已销毁的 Activity 上显示弹窗
        if (!isFinishing() && !isDestroyed()) {
            // 使用 runOnUiThread 确保弹窗在主线程显示
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("连接失败")
                    .setMessage("尝试自动重新连接服务失败。请检查网络连接和服务器状态，然后尝试手动连接。")
                    .setPositiveButton("知道了", (dialog, which) -> dialog.dismiss()) // 关闭对话框
                    .setNegativeButton("尝试重连", (dialog, which) -> {
                        // 用户点击“尝试重连”，模拟点击界面上的连接按钮
                        if (connectButton != null) {
                            connectButton.performClick();
                        }
                        dialog.dismiss();
                    })
                    .setCancelable(false) // 不允许点击对话框外部区域取消
                    .show());
        } else {
            Log.w(TAG,"尝试显示重试失败对话框，但 Activity 已结束。");
        }
    }

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "WrongConstant"}) // 添加注解抑制警告
    @Override
    protected void onResume() {
        super.onResume();
        // 注册光线传感器监听器
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        // 可以在 onResume 时再次检查通知权限，以防用户在设置中更改了它
        checkNotificationPermission();

        // --- 新增：注册广播接收器 ---
        if (retryFailureReceiver != null && retryFailureIntentFilter != null) {
            // 注册广播接收器以接收来自 Service 的失败通知
            // Android Tiramisu (API 33) 及以上版本需要明确指定导出行为
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 对于应用内广播，使用 RECEIVER_NOT_EXPORTED
                registerReceiver(retryFailureReceiver, retryFailureIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                // 或者 ContextCompat.registerReceiver(this, retryFailureReceiver, retryFailureIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                Log.d(TAG,"已注册重试失败广播接收器 (Android 13+, NOT_EXPORTED)");
            } else {
                // 对于 Android 13 以下版本，不需要指定导出标志
                registerReceiver(retryFailureReceiver, retryFailureIntentFilter);
                Log.d(TAG,"已注册重试失败广播接收器 (Android 13 以下)");
                // 注意：如果担心安全问题或只想应用内通信，可以考虑使用 LocalBroadcastManager
                // LocalBroadcastManager.getInstance(this).registerReceiver(retryFailureReceiver, retryFailureIntentFilter);
            }
        }
        // --- 结束新增 ---
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册光线传感器监听器
        if (lightSensor != null) {
            sensorManager.unregisterListener(this);
        }

        // --- 新增：取消注册广播接收器 ---
        if (retryFailureReceiver != null) {
            try {
                // 取消注册广播接收器，避免内存泄漏
                unregisterReceiver(retryFailureReceiver);
                // LocalBroadcastManager.getInstance(this).unregisterReceiver(retryFailureReceiver);
                Log.d(TAG,"已取消注册重试失败广播接收器");
            } catch (IllegalArgumentException e) {
                // 如果接收器之前没有成功注册，取消注册时会抛出此异常，可以安全地忽略
                Log.w(TAG,"取消注册重试失败广播接收器时出错（可能未注册）: " + e.getMessage());
            }
        }
        // --- 结束新增 ---
    }

    /**
     * 从 SharedPreferences 加载保存的 IP 地址和重启设置
     */
    private void loadSavedPreferences() {
        String savedIpAddress = sharedPreferences.getString(KEY_IP_ADDRESS, ""); // 默认空字符串
        boolean savedRestartPref = sharedPreferences.getBoolean(KEY_RESTART_SERVICE, true); // 默认开启重启

        ipAddressEditText.setText(savedIpAddress);
        CameraStreamServiceSwitch.setChecked(savedRestartPref);

        Log.d(TAG, "已加载保存的设置: IP=" + savedIpAddress + ", Restart=" + savedRestartPref);
    }

    /**
     * 保存 IP 地址和重启设置到 SharedPreferences
     */
    private void savePreferences(String ip, boolean restartEnabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_IP_ADDRESS, ip);
        editor.putBoolean(KEY_RESTART_SERVICE, restartEnabled);
        editor.apply(); // 异步保存
        Log.i(TAG, "已保存设置: IP=" + ip + ", Restart=" + restartEnabled);
    }

    /**
     * 单独保存重启设置到 SharedPreferences (当开关状态改变时调用)
     */
    private void saveRestartPreference(boolean restartEnabled) {
        sharedPreferences.edit().putBoolean(KEY_RESTART_SERVICE, restartEnabled).apply();
        Log.i(TAG, "已更新重启设置: " + restartEnabled);
    }

    /**
     * 检查 IP 地址格式是否有效
     * @param ip 要检查的 IP 地址字符串
     * @return 如果格式有效则返回 true，否则返回 false
     */
    private boolean isValidIpAddress(String ip) {
        // 检查非空且符合 IP 地址的格式
        return TextUtils.isEmpty(ip) || !Patterns.IP_ADDRESS.matcher(ip).matches();
    }


    /**
     * 检查 Android 13+ 的通知权限状态。
     */
    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33) 或更高
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                // 已经有权限
                isNotificationPermissionGranted = true;
                // Log.d(TAG,"通知权限已授予 (Android 13+)。"); // 日志可以按需开启
            } else {
                // 没有权限
                isNotificationPermissionGranted = false;
                Log.d(TAG,"通知权限未授予 (Android 13+)，将在需要时请求。");
            }
        } else {
            // Android 13 以下，不需要此运行时权限
            isNotificationPermissionGranted = true;
            // Log.d(TAG,"低于 Android 13，无需运行时通知权限。");
        }
    }

    /**
     * 显示请求通知权限的理由对话框 (如果需要)。
     */
    private void showNotificationPermissionRationale() {
        // 仅在 Android 13+ 且系统建议显示理由时才显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要通知权限")
                    .setMessage("应用需要在后台运行时显示通知，以确保服务持续运行并告知您状态。请授予通知权限。")
                    .setPositiveButton("去授权", (dialog, which) -> {
                        // 用户同意，再次请求权限
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                        // 用户拒绝，权限状态不变
                        isNotificationPermissionGranted = false;
                        Toast.makeText(this, "未授予通知权限，服务可能无法正常运行", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted) {
            // 用户可能选择了 "不再询问"，或者首次请求就被拒绝（不显示理由）
            // 提示用户去系统设置中手动开启
            Toast.makeText(this, "请在应用设置中手动开启通知权限以确保服务正常运行", Toast.LENGTH_LONG).show();
            // 可以选择性地引导用户去设置界面：
             Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
             intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
             startActivity(intent);
        }
    }

    // 光线传感器数值变化回调
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lightLevel = event.values[0];
            // 更新 TextView 显示
            lightSensorTextView.setText(String.format("光线传感器: %.1f lux", lightLevel));
            // 更新图表
            addLightEntry(lightLevel);
        }
    }

    // 传感器精度变化回调（通常不用处理）
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * 统一的权限检查和启动服务入口。
     * 按顺序检查权限：通知 -> 相机 -> (其他)。
     */
    private void checkPermissionsAndStartService() {
        // 1. 检查通知权限 (Android 13+)
        //    必须先获得通知权限，才能成功调用 startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted) {
            Log.d(TAG, "启动服务前检查：通知权限未授予，请求权限...");
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            // 等待权限结果回调，在回调中会再次调用此方法
            return;
        }

        // 2. 通知权限已满足 (或不需要)，检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "启动服务前检查：相机权限未授予，请求权限...");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            // 等待权限结果回调
            return;
        }

        // --- 所有必要权限都已授予 ---
        Log.i(TAG, "所有必要权限已授予，准备启动服务...");
        // 调用启动服务的方法
        startSelectedServices();
    }


    // 处理权限请求结果的回调
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = true;
        if (grantResults.length == 0) {
            allGranted = false; // 没有结果，认为失败
        } else {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
        }

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allGranted) {
                Log.i(TAG, "相机权限已授予。");
                // 相机权限OK，再次调用检查流程，它会检查通知权限（理论上已通过或无需检查）并启动服务
                checkPermissionsAndStartService();
            } else {
                Log.e(TAG, "相机权限被拒绝！");
                Toast.makeText(this, "必须授予相机权限才能使用此功能", Toast.LENGTH_SHORT).show();
                // 可以选择退出或禁用相关功能
                // finish();
            }
        } else if (requestCode == PERMISSION_REQUEST_CODE) { // 处理其他权限组（如果使用）
            // 如果使用了 checkAndRequestPermissions() 处理一组权限
            if (allGranted) {
                Log.i(TAG, "其他权限组已授予。");
                checkPermissionsAndStartService(); // 同样，重新检查并启动
            } else {
                Log.e(TAG, "其他权限组被拒绝！");
                Toast.makeText(this, "需要所有请求的权限才能启动服务", Toast.LENGTH_SHORT).show();
            }
        }
        // 通知权限的结果由 ActivityResultLauncher 处理
    }

    /**
     * 启动选中的服务。
     */
    private void startSelectedServices(){
        // 再次确认 ipAddress 是最新的且有效
        // 使用成员变量 this.ipAddress，它应该在点击按钮时已验证并更新
        if (isValidIpAddress(this.ipAddress)){
            Toast.makeText(this, "启动服务前发现无效 IP 地址", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG,"准备启动服务，使用 IP: " + this.ipAddress);

        // 根据标志启动不同的服务 (当前只关注 CameraStreamService)
        if (StartCameraStreamService){
            startCameraStreamService();
        }
    }

    /**
     * 启动 CameraStreamService。
     */
    private void startCameraStreamService() {
        Log.d(TAG, "启动 CameraStreamService (手动)..."); // 标识为手动启动
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        // 将验证过的 IP 地址传递给服务
        serviceIntent.putExtra("IP_ADDRESS", this.ipAddress);
        // --- 新增：添加手动启动标志 ---
        serviceIntent.putExtra("MANUAL_START", true);
        try {
            startForegroundService(serviceIntent);
            Log.i(TAG, "CameraStreamService 启动命令已发送 (手动)。");
            Toast.makeText(this,"相机流服务已启动", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "启动 CameraStreamService 失败", e);
            Toast.makeText(this,"启动相机流服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Activity 停止时调用
    @Override
    protected void onStop() {
        super.onStop();
        // 可以在这里保存一些临时状态，但 IP 地址的保存应该在明确点击“连接”时进行。
        // SharedPreferences.Editor editor = sharedPreferences.edit();
        // editor.putString("last_edited_ip", ipAddressEditText.getText().toString()); // 可以用不同的键名保存
        // editor.apply();
    }


    // --- 图表相关方法 ---
    // 设置图表的基本样式
    private void setupLightChart() {
        lightChart.getDescription().setEnabled(false); // 禁用描述文本
        lightChart.setTouchEnabled(false); // 禁用触摸交互
        lightChart.setDragEnabled(false); // 禁用拖拽
        lightChart.setScaleEnabled(false); // 禁用缩放
        lightChart.setDrawGridBackground(false); // 不绘制网格背景
        lightChart.setPinchZoom(false); // 禁用双指缩放
        lightChart.setBackgroundColor(Color.WHITE); // 设置背景色
        lightChart.setMaxHighlightDistance(300); // 高亮距离

        // 配置 X 轴
        XAxis x = lightChart.getXAxis();
        x.setEnabled(false); // 不显示 X 轴

        // 配置左 Y 轴
        YAxis y = lightChart.getAxisLeft();
        y.setLabelCount(6, false); // 设置标签数量
        y.setTextColor(Color.BLACK); // 设置文字颜色
        y.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART); // 标签位置在图表内部
        y.setDrawGridLines(false); // 不绘制 Y 轴网格线
        y.setAxisLineColor(Color.BLACK); // 设置 Y 轴线颜色

        // 禁用右 Y 轴
        lightChart.getAxisRight().setEnabled(false);

        // 创建数据集并添加到图表
        LineDataSet lightDataSet = createLightSet();
        LineData data = new LineData(lightDataSet);
        lightChart.setData(data);
        lightChart.invalidate(); // 初始绘制
    }

    // 向图表添加新的光线数据点
    private void addLightEntry(float lightLevel) {
        LineData data = lightChart.getData();
        if (data != null) {
            ILineDataSet set = data.getDataSetByIndex(0);
            // 如果数据集不存在，则创建
            if (set == null) {
                set = createLightSet();
                data.addDataSet(set);
            }

            // 控制数据点数量，移除旧数据
            // 图表显示的数据点数量
            int dataSetSize = 100;
            if (set.getEntryCount() >= dataSetSize) {
                // 移除第一个点 (最旧的点)
                set.removeFirst();
                // 为了让图表看起来是滚动的，需要重新设置剩余点的 x 轴索引
                // 效率较低，但对于少量数据点可以接受
                for (int i = 0; i < set.getEntryCount(); i++) {
                    Entry entry = set.getEntryForIndex(i);
                    if (entry != null) {
                        entry.setX(i); // 将 x 值设置为其新的索引
                    }
                }
            }

            // 添加新点，x 值为当前点的数量 (确保 x 值递增)
            data.addEntry(new Entry(set.getEntryCount(), lightLevel), 0);
            // 通知数据已改变
            data.notifyDataChanged();
            lightChart.notifyDataSetChanged();

            // 设置 X 轴显示范围，让最新的点始终可见
            lightChart.setVisibleXRangeMaximum(dataSetSize);
            // 移动视图，将最新的点显示在图表右侧
            lightChart.moveViewToX(data.getEntryCount());
        }
    }

    // 创建光线图表的数据集样式
    private LineDataSet createLightSet() {
        LineDataSet set = new LineDataSet(null, "光照强度 (lux)"); // 数据集标签
        set.setLineWidth(2f); // 线宽
        set.setColor(Color.rgb(135, 206, 250)); // 设置线条颜色 (淡蓝色)
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); // 设置为平滑曲线
        set.setDrawValues(false); // 不在点上绘制数值
        set.setDrawCircles(false); // 不绘制数据点圆圈
        set.setHighLightColor(Color.rgb(0, 191, 255)); // 设置高亮线颜色 (深天蓝)
        // 启用填充绘制
        set.setDrawFilled(true);
        set.setFillColor(Color.rgb(135, 206, 250)); // 填充颜色
        set.setFillAlpha(100); // 填充透明度 (0-255)
        return set;
    }
}
// --- END OF FILE MainActivity.java (包含失败弹窗逻辑) ---