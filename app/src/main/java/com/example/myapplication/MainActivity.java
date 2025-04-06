package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils; // 引入 TextUtils 用于检查空字符串
import android.util.Log;
import android.util.Patterns; // 引入 Patterns 用于 IP 地址校验
import android.widget.Button;
import android.widget.EditText;
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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    // 不再需要这些静态变量
    // public static String IP_ADDRESS_ = "IP_ADDRESS";
    // public static boolean RestartService = true;

    // SharedPreferences 相关常量 (与 Service 和 Receiver 保持一致)
    private static final String PREFS_NAME = "CameraServicePrefs";
    private static final String KEY_IP_ADDRESS = "last_ip_address";
    private static final String KEY_RESTART_SERVICE = "restart_service_flag";

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 1001; // 用于其他权限组（如果需要）

    // 服务启动标志 (根据需要保留或移除)
    private boolean StartPhotoService = false;
    private boolean StartVideoService = false;
    private boolean StartCameraStreamService = true; // 默认启动 CameraStreamService

    private Button connectButton;
    private EditText ipAddressEditText;
    private String ipAddress; // 用于存储当前 Activity 中使用的 IP 地址
    private SharedPreferences sharedPreferences; // SharedPreferences 实例
    private SwitchMaterial CameraStreamServiceSwitch; // 重启开关

    // 需要的权限列表
    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            // Manifest.permission.RECORD_AUDIO, // 如果 VideoService 需要录音
            // Manifest.permission.WRITE_EXTERNAL_STORAGE, // 如果需要写文件（Android 10 以下）
            // Android 13+ 通知权限是动态添加的
    };

    // 传感器相关
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private TextView lightSensorTextView;

    // 图表相关
    private LineChart lightChart;
    private LineDataSet lightDataSet;
    private int dataSetSize = 100; // 图表显示的数据点数量

    // 权限请求启动器
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    private boolean isNotificationPermissionGranted = false; // 跟踪通知权限状态

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

        // 加载保存的设置
        loadSavedPreferences();

        // 设置连接按钮点击事件
        connectButton.setOnClickListener(v -> {
            // 1. 获取当前输入
            String currentIp = ipAddressEditText.getText().toString().trim();
            boolean restartEnabled = CameraStreamServiceSwitch.isChecked();

            // 2. 验证 IP 地址
            if (!isValidIpAddress(currentIp)) { // 使用 IP 验证函数
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

        // 初始化图表
        lightChart = findViewById(R.id.lightChart);
        setupLightChart();

        // 初始化通知权限请求启动器
        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                isNotificationPermissionGranted = true;
                Log.i(TAG, "通知权限已授予。");
                // 权限授予后，可以再次尝试启动服务（如果是因为缺少此权限而中断的话）
                // 这里可以调用 checkPermissionsAndStartService()，它会检查其他权限
                checkPermissionsAndStartService();
            } else {
                isNotificationPermissionGranted = false;
                Log.w(TAG, "通知权限被拒绝。");
                // 权限被拒绝，可以显示提示
                showNotificationPermissionRationale(); // 再次解释原因或提示功能受限
                Toast.makeText(this,"缺少通知权限，前台服务可能无法正常启动或显示通知", Toast.LENGTH_LONG).show();
            }
        });

        // 检查通知权限 (如果需要，其他权限检查在点击连接时进行)
        checkNotificationPermission();
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
     * 单独保存重启设置到 SharedPreferences
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
        return !TextUtils.isEmpty(ip) && Patterns.IP_ADDRESS.matcher(ip).matches();
    }


    /**
     * 检查 Android 13+ 的通知权限
     */
    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                // 已经有权限
                isNotificationPermissionGranted = true;
                Log.d(TAG,"通知权限已授予 (Android 13+)。");
            } else {
                // 没有权限，需要请求 (在需要时请求，例如点击连接按钮时)
                isNotificationPermissionGranted = false;
                Log.d(TAG,"通知权限未授予 (Android 13+)，将在需要时请求。");
                // 可以在这里请求，或者在点击连接时请求
                // requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            // Android 13 以下，不需要此运行时权限
            isNotificationPermissionGranted = true;
            Log.d(TAG,"低于 Android 13，无需运行时通知权限。");
        }
    }

    /**
     * 显示请求通知权限的理由对话框
     */
    private void showNotificationPermissionRationale() {
        // 只有在 Android 13+ 且应该显示理由时才显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要通知权限")
                    .setMessage("应用需要在后台运行时显示通知，以确保服务持续运行。请授予通知权限。")
                    .setPositiveButton("去授权", (dialog, which) -> {
                        // 再次请求权限
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                        isNotificationPermissionGranted = false;
                        Toast.makeText(this, "未授予通知权限，服务可能无法正常运行", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted) {
            // 用户可能选择了 "不再询问"，这里可以提示用户去设置中开启
            Toast.makeText(this, "请在应用设置中手动开启通知权限", Toast.LENGTH_LONG).show();
            // 可以引导用户去设置界面：
            // Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            // intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            // startActivity(intent);
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        // 可以在 onResume 时再次检查通知权限，以防用户在设置中更改了它
        checkNotificationPermission();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (lightSensor != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lightLevel = event.values[0];
            lightSensorTextView.setText(String.format("光线传感器: %.1f lux", lightLevel));
            addLightEntry(lightLevel);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 通常不需要处理
    }

    /**
     * 统一的权限检查和启动服务入口
     */
    private void checkPermissionsAndStartService() {
        // 1. 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted) {
            Log.d(TAG, "请求通知权限...");
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            // 等待权限结果回调，在回调中再检查相机权限
            return;
        }

        // 2. 通知权限已满足 (或不需要)，检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "请求相机权限...");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            // 等待权限结果回调
            return;
        }

        // 3. 如果需要其他权限 (如录音、存储)，在这里检查或在 checkAndRequestPermissions() 中统一检查
        // if (!checkAndRequestPermissions()) { // 如果使用了 REQUIRED_PERMISSIONS
        //    return; // 等待权限结果
        // }

        // --- 所有必要权限都已授予 ---
        Log.i(TAG, "所有必要权限已授予，准备启动服务...");
        startSelectedServices(); // 调用启动服务的方法
    }


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
                // 相机权限OK，再次调用检查流程，它会检查通知权限（理论上已通过）并启动服务
                checkPermissionsAndStartService();
            } else {
                Log.e(TAG, "相机权限被拒绝！");
                Toast.makeText(this, "必须授予相机权限才能使用此功能", Toast.LENGTH_SHORT).show();
                // 可以选择退出或禁用相关功能
                // finish();
            }
        } else if (requestCode == PERMISSION_REQUEST_CODE) { // 处理其他权限组（如果使用）
            if (allGranted) {
                Log.i(TAG, "其他权限组已授予。");
                checkPermissionsAndStartService(); // 同样，重新检查并启动
            } else {
                Log.e(TAG, "其他权限组被拒绝！");
                Toast.makeText(this, "需要所有请求的权限才能启动服务", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 启动选中的服务
     */
    private void startSelectedServices(){
        // 确保 ipAddress 是最新的
        this.ipAddress = ipAddressEditText.getText().toString().trim();
        if (!isValidIpAddress(this.ipAddress)){
            Toast.makeText(this, "启动服务前发现无效 IP 地址", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG,"准备启动服务，使用 IP: " + this.ipAddress);

        // 根据标志启动不同的服务
        if (StartVideoService){
            startVideoService();
        }
        if (StartPhotoService){
            startPhotoService();
        }
        if (StartCameraStreamService){
            startCameraStreamService();
        }
    }

    /**
     * 启动 CameraStreamService
     */
    private void startCameraStreamService() {
        Log.d(TAG, "启动 CameraStreamService...");
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        serviceIntent.putExtra("IP_ADDRESS", this.ipAddress); // 使用成员变量 ipAddress
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "CameraStreamService 启动命令已发送。");
            Toast.makeText(this,"相机流服务已启动", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "启动 CameraStreamService 失败", e);
            Toast.makeText(this,"启动相机流服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 启动 VideoService (示例，如果需要)
     */
    private void startVideoService() {
        // if (checkAndRequestPermissions()) { // 确保 VideoService 需要的权限已授予
        Log.d(TAG, "启动 VideoService...");
        Intent serviceIntent = new Intent(this, VideoService.class); // 假设存在 VideoService
        serviceIntent.putExtra("IP_ADDRESS", this.ipAddress);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "VideoService 启动命令已发送。");
        } catch (Exception e) {
            Log.e(TAG, "启动 VideoService 失败", e);
            Toast.makeText(this,"启动视频服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        // }
    }

    /**
     * 启动 PhotoService (示例，如果需要)
     */
    private void startPhotoService() {
        Log.d(TAG, "启动 PhotoService...");
        Intent serviceIntent = new Intent(this, PhotoService.class); // 假设存在 PhotoService
        serviceIntent.putExtra("IP_ADDRESS", this.ipAddress);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "PhotoService 启动命令已发送。");
        } catch (Exception e) {
            Log.e(TAG, "启动 PhotoService 失败", e);
            Toast.makeText(this,"启动照片服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 检查并请求一组权限 (如果使用了 REQUIRED_PERMISSIONS)
     * @return boolean 是否所有权限都已授予
     */
    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        List<String> finalPermissions = new ArrayList<>(List.of(REQUIRED_PERMISSIONS));

        // 动态添加通知权限 (如果目标 SDK >= 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            finalPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        for (String permission : finalPermissions) {
            // 过滤掉空字符串（用于兼容旧版通知权限写法）
            if (!TextUtils.isEmpty(permission) && ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            Log.d(TAG, "请求权限组: " + permissionsNeeded);
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false; // 需要等待结果
        }
        Log.d(TAG, "所有权限组权限已满足。");
        return true; // 所有权限已满足
    }

    @Override
    protected void onStop() {
        super.onStop();
        // onStop 时可以保存当前 EditText 的内容，但不应覆盖由“连接”按钮确认的 IP
        // SharedPreferences.Editor editor = sharedPreferences.edit();
        // editor.putString("last_edited_ip", ipAddressEditText.getText().toString()); // 可以用不同的键名保存
        // editor.apply();
        // 这里选择不保存，让用户明确点击连接时才保存最终使用的 IP
    }


    // --- 图表相关方法 (setupLightChart, addLightEntry, createLightSet) ---
    // 这些方法保持不变，因为它们与 IP/服务逻辑无关
    private void setupLightChart() {
        lightChart.getDescription().setEnabled(false);
        lightChart.setTouchEnabled(false);
        lightChart.setDragEnabled(false);
        lightChart.setScaleEnabled(false);
        lightChart.setDrawGridBackground(false);
        lightChart.setPinchZoom(false);
        lightChart.setBackgroundColor(Color.WHITE);
        lightChart.setMaxHighlightDistance(300);

        XAxis x = lightChart.getXAxis();
        x.setEnabled(false);

        YAxis y = lightChart.getAxisLeft();
        y.setLabelCount(6, false);
        y.setTextColor(Color.BLACK);
        y.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);
        y.setDrawGridLines(false);
        y.setAxisLineColor(Color.BLACK);

        lightChart.getAxisRight().setEnabled(false);

        lightDataSet = createLightSet(); // 使用 createLightSet 方法
        LineData data = new LineData(lightDataSet);
        lightChart.setData(data);
        lightChart.invalidate(); // 初始绘制
    }

    private void addLightEntry(float lightLevel) {
        LineData data = lightChart.getData();
        if (data != null) {
            ILineDataSet set = data.getDataSetByIndex(0);
            if (set == null) {
                set = createLightSet();
                data.addDataSet(set);
            }

            // 控制数据点数量，移除旧数据
            if (set.getEntryCount() >= dataSetSize) {
                // 移除第一个点 (最旧的点)
                set.removeFirst();
                // 可能需要调整剩余点的 x 值，但这会比较复杂且影响性能
                // 更简单的做法是让 X 轴自动滚动，或者不严格控制数量，只限制显示范围
            }

            // 添加新点，x 值为当前点的数量 (会一直增加)
            data.addEntry(new Entry(set.getEntryCount(), lightLevel), 0);
            data.notifyDataChanged();

            lightChart.notifyDataSetChanged();
            // 设置 X 轴显示范围，让最新的点始终可见
            lightChart.setVisibleXRangeMaximum(dataSetSize);
            // 移动视图，将最新的点显示在图表右侧
            lightChart.moveViewToX(data.getEntryCount());
        }
    }

    private LineDataSet createLightSet() {
        LineDataSet set = new LineDataSet(null, "光照强度 (lux)"); // 修改标签
        set.setLineWidth(2f);
        set.setColor(Color.rgb(135, 206, 250)); // 淡蓝色
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); // 平滑曲线
        set.setDrawValues(false);
        set.setDrawCircles(false); // 不绘制数据点圆圈
        set.setHighLightColor(Color.rgb(0, 191, 255)); // 高亮颜色 (深天蓝)
        // 启用填充绘制
        set.setDrawFilled(true);
        set.setFillColor(Color.rgb(135, 206, 250)); // 填充颜色
        set.setFillAlpha(100); // 填充透明度
        return set;
    }
}