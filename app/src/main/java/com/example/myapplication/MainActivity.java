package com.example.myapplication;

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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
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
import android.widget.CalendarView;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.ToolData.JsonDataStorage;
import com.example.myapplication.ToolData.RecordData;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

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

    private Spinner pleasureLevelSpinner; // 新增：爽感等级 Spinner
    private Button viewMonthlyDataButton; // 新增：查看月视图按钮

    // --- 新增：用于日期格式化 ---
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private String todayDateKey = ""; // 今天日期的键

    // 权限请求启动器
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    private boolean isNotificationPermissionGranted = false; // 跟踪通知权限状态

    //用于接收服务重试失败的广播
    private BroadcastReceiver retryFailureReceiver;
    private IntentFilter retryFailureIntentFilter;

    private final Random random = new Random(); // 用于生成随机效果

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
        // 获取今天的日期键
        todayDateKey = dateFormat.format(Calendar.getInstance().getTime());

        // 初始化 SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 初始化传感器
        initSensors();

        // 初始化视图控件
        initViews();

        // 加载保存的设置 (IP 地址和重启开关状态)
        loadSavedPreferences();

        // 设置监听器
        setupListeners();

        // 初始化图表
        setupLightChart();

        // 初始化通知权限请求
        initNotificationPermissionLauncher();

        // 检查通知权限
        checkNotificationPermission();

        // 初始化重试失败广播接收器
        setupRetryFailureReceiver();

        // --- 新增：加载今天的数据到计数器和 Spinner ---
        loadTodayData();
    }
    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorTextView = findViewById(R.id.lightSensorTextView);
        if (lightSensor == null) {
            lightSensorTextView.setText(getString(R.string.lux).replace("-- lux", "不可用"));
        }
    }

    private void initViews() {
        CameraStreamServiceSwitch = findViewById(R.id.CameraStreamServiceSwitch);
        connectButton = findViewById(R.id.connectButton);
        ipAddressEditText = findViewById(R.id.ipAddressEditText);
        lightChart = findViewById(R.id.lightChart);
        decrementButton = findViewById(R.id.decrementButton);
        incrementButton = findViewById(R.id.incrementButton);
        counterTextView = findViewById(R.id.countTextView);
        pleasureLevelSpinner = findViewById(R.id.pleasureLevelSpinner); // 初始化 Spinner
        viewMonthlyDataButton = findViewById(R.id.viewMonthlyDataButton); // 初始化按钮
    }

    private void setupListeners() {
        // 连接按钮
        connectButton.setOnClickListener(v -> {
            String currentIp = ipAddressEditText.getText().toString().trim();
            boolean restartEnabled = CameraStreamServiceSwitch.isChecked();
            if (isValidIpAddress(currentIp)) {
                Toast.makeText(this, "请输入有效的 IP 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            this.ipAddress = currentIp;
            savePreferences(currentIp, restartEnabled);
            checkPermissionsAndStartService();
        });

        // 重启开关
        CameraStreamServiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveRestartPreference(isChecked);
        });

        // 减号按钮
        decrementButton.setOnClickListener(v -> {
            updateCounter(-1); // 调用更新计数器方法
        });

        // 加号按钮
        incrementButton.setOnClickListener(v -> {
            updateCounter(1); // 调用更新计数器方法
        });

        // 设置 Spinner 适配器
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.pleasure_levels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pleasureLevelSpinner.setAdapter(adapter);

        // Spinner 选择监听器
        pleasureLevelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 当用户选择新的爽感等级时，保存当前状态
                saveCurrentState();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 通常不需要处理
            }
        });

        // 查看月视图按钮
        viewMonthlyDataButton.setOnClickListener(v -> showMonthlyViewDialog());
    }

    /**
     * 更新计数器并保存状态
     */
    private void updateCounter(int change) {
        int currentCount = 0;
        try {
            currentCount = Integer.parseInt(counterTextView.getText().toString());
        } catch (NumberFormatException e) {
            Log.e(TAG, "无法解析计数器文本: " + counterTextView.getText(), e);
            // 可以选择重置为 0 或其他处理
        }

        int newCount = currentCount + change;
        if (newCount < 0) { // 不允许小于 0
            newCount = 0;
        }
        counterTextView.setText(String.valueOf(newCount));
        // --- 新增：只有在增加计数时才播放动画 ---
        if (newCount > 0) { // 只有实际增加了才播放（防止从0到0播放）
            playExplosionAnimation();
        }
        saveCurrentState();
    }
    /**
     * 获取当前状态（次数、爽感）并保存到 JSON
     */
    private void saveCurrentState() {
        // 仅保存今天的数据
        int currentCount = 0;
        try {
            currentCount = Integer.parseInt(counterTextView.getText().toString());
        } catch (NumberFormatException e) {
            Log.e(TAG, "保存状态时无法解析计数器文本: " + counterTextView.getText(), e);
            // 可以根据需要决定是否继续保存，或者使用默认值 0
        }

        int selectedPleasureLevel = 1; // 默认值
        // Spinner position 是从 0 开始的，我们需要 1-5
        if (pleasureLevelSpinner.getSelectedItem() != null) {
            try {
                selectedPleasureLevel = Integer.parseInt(pleasureLevelSpinner.getSelectedItem().toString());
            } catch (NumberFormatException e) {
                Log.e(TAG, "保存状态时无法解析 Spinner 值: " + pleasureLevelSpinner.getSelectedItem(), e);
                selectedPleasureLevel = pleasureLevelSpinner.getSelectedItemPosition() + 1; // 尝试使用位置
                if(selectedPleasureLevel < 1 || selectedPleasureLevel > 5) selectedPleasureLevel = 1; // 再次检查
            }
        }

        Log.d(TAG, "正在保存状态 - 日期: " + todayDateKey + ", 次数: " + currentCount + ", 爽感: " + selectedPleasureLevel);
        JsonDataStorage.saveRecord(this, todayDateKey, currentCount, selectedPleasureLevel);
    }
    /**
     * 加载今天的数据（如果存在）并更新 UI
     */
    private void loadTodayData() {
        RecordData todayRecord = JsonDataStorage.getRecord(this, todayDateKey);
        if (todayRecord != null) {
            Log.d(TAG, "找到今天的数据: 次数=" + todayRecord.getCount() + ", 爽感=" + todayRecord.getPleasureLevel());
            counterTextView.setText(String.valueOf(todayRecord.getCount()));
            // 设置 Spinner 的选中项 (爽感是 1-5, Spinner 位置是 0-4)
            int spinnerPosition = todayRecord.getPleasureLevel() - 1;
            if (spinnerPosition >= 0 && spinnerPosition < pleasureLevelSpinner.getAdapter().getCount()) {
                pleasureLevelSpinner.setSelection(spinnerPosition);
            } else {
                pleasureLevelSpinner.setSelection(0); // 默认选第一个
            }
        } else {
            Log.d(TAG, "今天 ("+ todayDateKey +") 没有找到记录，使用默认值。");
            // 如果没有记录，保持 XML 中的默认值或显式设置为 0 和默认爽感
            counterTextView.setText("0");
            pleasureLevelSpinner.setSelection(0); // 默认选第一个 (即等级 1)
        }
    }
    /**
     * 显示月视图对话框
     */
    @SuppressLint("StringFormatMatches")
    private void showMonthlyViewDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_monthly_view, null);
        builder.setView(dialogView);
        builder.setTitle(R.string.monthly_view_title);

        CalendarView calendarView = dialogView.findViewById(R.id.calendarViewDialog);
        TextView detailsTextView = dialogView.findViewById(R.id.detailsTextViewDialog);
        RecyclerView monthRecordsRecyclerView = dialogView.findViewById(R.id.monthRecordsRecyclerView);

        // 获取所有记录用于查找
        final Map<String, RecordData> allRecords = JsonDataStorage.getAllRecords(this);

        // 获取爽感等级文字描述
        final String[] pleasureLevels = getResources().getStringArray(R.array.pleasure_levels);

        // 设置RecyclerView
        RecordAdapter adapter = new RecordAdapter(this);
        monthRecordsRecyclerView.setAdapter(adapter);
        monthRecordsRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 用于计算月度统计数据
        Calendar currentCalendar = Calendar.getInstance();
        updateMonthRecords(adapter, allRecords, currentCalendar.get(Calendar.YEAR),
                currentCalendar.get(Calendar.MONTH), detailsTextView);

        calendarView.setOnDateChangeListener((view, year, month, dayOfMonth) -> {
            // 更新月度记录列表
            if (year != currentCalendar.get(Calendar.YEAR) ||
                    month != currentCalendar.get(Calendar.MONTH)) {
                currentCalendar.set(Calendar.YEAR, year);
                currentCalendar.set(Calendar.MONTH, month);
                updateMonthRecords(adapter, allRecords, year, month, detailsTextView);
            }

            // 显示选中日期的详细信息
            Calendar selectedCalendar = Calendar.getInstance();
            selectedCalendar.set(year, month, dayOfMonth);
            String selectedDateKey = dateFormat.format(selectedCalendar.getTime());

            RecordData selectedRecord = allRecords.get(selectedDateKey);
            if (selectedRecord != null) {
                // 获取爽感的文字描述
                String pleasureLevelText = "未知";
                int pleasureLevel = selectedRecord.getPleasureLevel();
                if (pleasureLevel >= 1 && pleasureLevel <= pleasureLevels.length) {
                    pleasureLevelText = pleasureLevels[pleasureLevel - 1];
                }

                detailsTextView.setText(getString(R.string.record_details_format,
                        selectedRecord.getCount(), pleasureLevelText));
            } else {
                detailsTextView.setText(R.string.no_record_found);
            }
        });

        // 设置对话框关闭按钮
        builder.setPositiveButton(R.string.close, (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();

        // 初始化时显示当天的信息
        long initialMillis = Calendar.getInstance().getTimeInMillis();
        calendarView.setDate(initialMillis, false, true); // 设置日历到今天
        Calendar initialCalendar = Calendar.getInstance();
        initialCalendar.setTimeInMillis(initialMillis);
        String initialDateKey = dateFormat.format(initialCalendar.getTime());
        RecordData initialRecord = allRecords.get(initialDateKey);
        if (initialRecord != null) {
            // 获取爽感的文字描述
            String pleasureLevelText = "未知";
            int pleasureLevel = initialRecord.getPleasureLevel();
            if (pleasureLevel >= 1 && pleasureLevel <= pleasureLevels.length) {
                pleasureLevelText = pleasureLevels[pleasureLevel - 1];
            }

            detailsTextView.setText(getString(R.string.record_details_format,
                    initialRecord.getCount(), pleasureLevelText));
        } else {
            detailsTextView.setText(R.string.no_record_found);
        }
    }

    /**
     * 更新月记录列表并计算月度统计
     */
    private void updateMonthRecords(RecordAdapter adapter, Map<String, RecordData> allRecords,
                                    int year, int month, TextView summaryTextView) {
        adapter.setMonthData(allRecords, year, month);

        // 计算月度统计
        int totalCount = 0;
        int pleasureLevelSum = 0;
        int recordCount = 0;

        String monthPrefix = String.format("%04d-%02d-", year, month + 1);

        for (Map.Entry<String, RecordData> entry : allRecords.entrySet()) {
            if (entry.getKey().startsWith(monthPrefix)) {
                RecordData record = entry.getValue();
                totalCount += record.getCount();
                pleasureLevelSum += record.getPleasureLevel();
                recordCount++;
            }
        }

        // 显示月度统计信息
        if (recordCount > 0) {
            float avgPleasureLevel = (float) pleasureLevelSum / recordCount;
            summaryTextView.setText(getString(R.string.monthly_summary_format,
                    totalCount, avgPleasureLevel));
        } else {
            summaryTextView.setText(R.string.no_record_found);
        }
    }
    private void initNotificationPermissionLauncher() {
        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                isNotificationPermissionGranted = true;
                Log.i(TAG, "通知权限已授予。");
                checkPermissionsAndStartService();
            } else {
                isNotificationPermissionGranted = false;
                Log.w(TAG, "通知权限被拒绝。");
                showNotificationPermissionRationale();
                Toast.makeText(this,"缺少通知权限，前台服务可能无法正常启动或显示通知", Toast.LENGTH_LONG).show();
            }
        });
    }


    /**
     * 播放全屏爆炸动画、粒子、冲击波和爱心效果 (浮夸版)
     */
    private void playExplosionAnimation() {
        final ViewGroup rootView = (ViewGroup) getWindow().getDecorView();
        if (rootView.findViewById(R.id.explosionOverlayRoot) != null) {
            Log.w(TAG, "动画已在进行中，忽略此次触发");
            return;
        }

        final View overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_explosion, rootView, false);
        final FrameLayout overlayRoot = overlayView.findViewById(R.id.explosionOverlayRoot);
        final TextView explosionText = overlayView.findViewById(R.id.explosionTextView);
        final ImageView shockwaveImageView = overlayView.findViewById(R.id.shockwaveImageView); // 获取冲击波 ImageView

        rootView.addView(overlayView);

        long textAnimDuration = 800;       // "爽"字动画时长
        long textAppearDelay = 100;
        long particleAnimBaseDuration = 1500; // 粒子动画基础时长
        long shockwaveDelay = textAnimDuration / 2 + textAppearDelay; // 冲击波在"爽"字放大一半时开始
        long shockwaveDuration = 1000;     // 冲击波动画时长
        long heartStartDelay = shockwaveDelay + shockwaveDuration / 3; // 冲击波扩散后开始爱心
        long heartAnimBaseDuration = 2500; // 爱心动画基础时长 (更长)
        long maxParticleDuration = (long)(particleAnimBaseDuration * 1.3);
        long maxHeartDuration = (long)(heartAnimBaseDuration * 1.3);

        // --- 调整覆盖层移除延迟，需要覆盖所有动画 ---
        long overlayRemovalDelay = Math.max(shockwaveDelay + shockwaveDuration, heartStartDelay + maxHeartDuration) + 300;

        // 1. 背景层动画 (淡入，保持粉色更久)
        overlayRoot.setBackgroundColor(Color.TRANSPARENT); // 初始背景透明，靠冲击波上色
        ObjectAnimator bgFadeIn = ObjectAnimator.ofFloat(overlayRoot, "alpha", 0f, 0.6f); // 淡入到一定透明度
        bgFadeIn.setDuration(shockwaveDelay + shockwaveDuration); // 背景淡入持续到冲击波结束
        bgFadeIn.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator bgFadeOut = ObjectAnimator.ofFloat(overlayRoot, "alpha", 0.6f, 0f);
        bgFadeOut.setStartDelay(shockwaveDelay + shockwaveDuration); // 冲击波结束后开始淡出
        bgFadeOut.setDuration(maxHeartDuration); // 淡出持续时间覆盖爱心动画
        bgFadeOut.setInterpolator(new AccelerateInterpolator());

        AnimatorSet bgSet = new AnimatorSet();
        bgSet.playSequentially(bgFadeIn, bgFadeOut); // 顺序播放淡入淡出
        // bgSet.start(); // 背景动画由冲击波触发或独立启动

        // 2. "爽" 字动画 (结束后触发粒子)
        explosionText.animate()
                .setStartDelay(textAppearDelay)
                .alpha(1f)
                .scaleX(1.5f)
                .scaleY(1.5f)
                .setDuration(textAnimDuration)
                .setInterpolator(new OvershootInterpolator(2f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) { // 改为 onAnimationEnd
                        explosionText.setVisibility(View.GONE);
                        // 触发粒子效果
                        createAndAnimateParticles(explosionText, overlayRoot, particleAnimBaseDuration);
                    }
                })
                .start();

        // 3. 粉色冲击波动画 (延迟启动)
        overlayRoot.postDelayed(() -> {
            startShockwaveAnimation(shockwaveImageView, overlayRoot, shockwaveDuration);
            // 在冲击波开始时，也启动背景的动画，或者在此之前就启动
            if (!bgSet.isStarted()) bgSet.start();
        }, shockwaveDelay);


        // 4. 爱心喷泉动画 (更晚延迟启动)
        overlayRoot.postDelayed(() -> {
            createAndAnimateHearts(overlayRoot, heartAnimBaseDuration);
        }, heartStartDelay);


        // 5. 延迟移除整个覆盖层
        rootView.postDelayed(() -> {
            View overlayToRemove = rootView.findViewById(R.id.explosionOverlayRoot);
            if (overlayToRemove != null && overlayToRemove.getParent() instanceof ViewGroup) {
                ((ViewGroup)overlayToRemove.getParent()).removeView(overlayToRemove);
                Log.d(TAG,"浮夸动画覆盖层已移除 (延迟)");
            } else {
                Log.w(TAG,"尝试移除覆盖层，但未找到或已移除");
            }
        }, overlayRemovalDelay);

        Log.d(TAG,"开始播放浮夸版爆炸动画 (粒子+冲击波+爱心)");
    }

    /**
     * 启动粉色冲击波动画
     */
    private void startShockwaveAnimation(ImageView shockwaveView, ViewGroup container, long duration) {
        if (shockwaveView == null) return;

        shockwaveView.setVisibility(View.VISIBLE);
        shockwaveView.setAlpha(0.8f); // 设置初始透明度 (不需要全不透明)
        shockwaveView.setScaleX(0.1f);
        shockwaveView.setScaleY(0.1f);

        // 获取容器大小，冲击波放大到能覆盖容器
        float maxScale = Math.max(container.getWidth(), container.getHeight()) * 1.5f; // 放大到比容器还大一点

        AnimatorSet shockwaveSet = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(shockwaveView, "scaleX", 0.1f, maxScale);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(shockwaveView, "scaleY", 0.1f, maxScale);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(shockwaveView, "alpha", 0.8f, 0f); // 放大同时淡出

        shockwaveSet.playTogether(scaleX, scaleY, alpha);
        shockwaveSet.setDuration(duration);
        shockwaveSet.setInterpolator(new AccelerateInterpolator(1.5f)); // 加速扩散
        shockwaveSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                shockwaveView.setVisibility(View.GONE); // 动画结束隐藏
                // 可以同时给 overlayRoot 上个色
                // container.setBackgroundColor(Color.parseColor("#33FFC0CB")); // 设置一个淡淡的粉色背景
            }
        });
        shockwaveSet.start();
        Log.d(TAG, "冲击波动画开始");
    }

    /**
     * 创建并动画化爱心喷泉效果
     */
    private void createAndAnimateHearts(ViewGroup container, long baseDuration) {
        if (container == null) return;

        int heartCount = 100; // 爱心数量
        int minHeartSize = dpToPx(15);
        int maxHeartSize = dpToPx(35);
        long maxStaggerDelay = 800; // 爱心出现的时间更分散

        // 获取容器尺寸
        int containerWidth = container.getWidth();
        int containerHeight = container.getHeight();
        // 爱心起始位置在底部中心区域
        float startXBase = containerWidth / 2f;
        float startY = containerHeight - dpToPx(20); // 稍微离底部一点
        float startXVariance = containerWidth * 0.1f; // X轴起始位置的随机范围

        Log.d(TAG, "创建爱心喷泉");

        for (int i = 0; i < heartCount; i++) {
            // 创建爱心 ImageView
            ImageView heart = new ImageView(this);
            heart.setImageResource(R.drawable.ic_heart); // 设置爱心图片
            // --- 随机设置爱心颜色 (粉色或白色) ---
            heart.setColorFilter(random.nextBoolean() ? Color.WHITE : Color.parseColor("#FF69B4"));

            int heartSize = random.nextInt(maxHeartSize - minHeartSize + 1) + minHeartSize;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(heartSize, heartSize);
            container.addView(heart, params);

            // 设置随机起始位置
            float startX = startXBase + (random.nextFloat() * 2f - 1f) * startXVariance;
            heart.setX(startX - heartSize / 2f);
            heart.setY(startY - heartSize / 2f); // Y轴起始固定在底部
            heart.setAlpha(0f); // 初始透明
            heart.setRotation(random.nextFloat() * 60 - 30); // 初始随机倾斜

            // --- 动画参数 ---
            long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8)); // 随机时长
            long startDelay = (long) (((float)i / heartCount) * maxStaggerDelay); // 分散启动
            float targetY = -heartSize; // 最终飘出屏幕顶部
            float horizontalDrift = (random.nextFloat() * 2f - 1f) * (containerWidth * 0.6f); // 水平漂移距离

            // --- 执行爱心动画 ---
            // 使用 ObjectAnimator 组合更复杂的动画路径或效果
            // 这里简化处理，用 ViewPropertyAnimator
            heart.animate()
                    .setStartDelay(startDelay)
                    .alpha(1f) // 先淡入
                    .translationY(targetY) // 向上移动
                    .translationXBy(horizontalDrift) // 水平漂移
                    .rotationBy(random.nextFloat() * 360 - 180) // 向上时旋转
                    .setDuration(duration)
                    .setInterpolator(new LinearInterpolator()) // 匀速或非常慢的减速漂浮
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            // (可选) 可以在开始时加一个小的缩放脉冲
                            heart.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).withEndAction(()->{
                                heart.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                            }).start();
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // --- 快要结束时淡出 --- (或者直接在向上移动的同时淡出)
                            heart.animate()
                                    .alpha(0f)
                                    .setDuration(duration / 4) // 最后1/4时间淡出
                                    .setInterpolator(new AccelerateInterpolator())
                                    .setListener(new AnimatorListenerAdapter() {
                                        @Override
                                        public void onAnimationEnd(Animator animation) {
                                            if (heart.getParent() != null) {
                                                container.removeView(heart);
                                            }
                                        }
                                    })
                                    .start();
                        }
                    })
                    .withLayer()
                    .start();
        }
    }
    /**
     * 在指定容器内，从一个源 View 的位置创建并动画化粒子效果 (修复卡顿和时长版)
     * @param sourceView 动画起源的 View (用于获取位置)
     * @param container 容纳粒子的 ViewGroup
     * @param baseDuration 粒子动画的基础持续时间 (会在此基础上随机化)
     */
    private void createAndAnimateParticles(View sourceView, ViewGroup container, long baseDuration) {
        if (sourceView == null || container == null) return;

        int particleCount = 40; // 可以稍微减少粒子数，如果50仍然卡顿
        int minParticleSize = dpToPx(3);
        int maxParticleSize = dpToPx(8);

        // 获取起点坐标 (同前)
        int[] sourcePos = new int[2];
        sourceView.getLocationOnScreen(sourcePos);
        float startX = sourcePos[0] + sourceView.getWidth() / 2f;
        float startY = sourcePos[1] + sourceView.getHeight() / 2f;

        // 获取屏幕尺寸和距离 (同前)
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;
        float maxDistance = Math.max(screenWidth, screenHeight) * 0.7f;
        float minDistance = maxDistance * 0.3f;

        // --- 新增：计算每个粒子的最大延迟时间 ---
        // 让所有粒子的启动分散在大约 150ms 内完成
        long maxStaggerDelay = 450;


        Log.d(TAG, "创建粒子 (增强版)，起点: (" + startX + ", " + startY + ")");

        for (int i = 0; i < particleCount; i++) {
            // 创建粒子 View (同前)
            View particle = new View(this);
            particle.setBackgroundColor(getRandomParticleColor());
            int particleSize = random.nextInt(maxParticleSize - minParticleSize + 1) + minParticleSize;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(particleSize, particleSize);
            // --- 延迟添加 View 可能帮助不大，主要瓶颈在启动动画 ---
            container.addView(particle, params);

            // 设置初始位置和状态 (同前)
            particle.setX(startX - particleSize / 2f);
            particle.setY(startY - particleSize / 2f);
            particle.setAlpha(1f);
            particle.setScaleX(1.2f);
            particle.setScaleY(1.2f);

            // 计算随机目标 (同前)
            double angle = random.nextDouble() * 2 * Math.PI;
            float distance = random.nextFloat() * (maxDistance - minDistance) + minDistance;
            float translationX = (float) (distance * Math.cos(angle));
            float translationY = (float) (distance * Math.sin(angle));
            float rotation = random.nextFloat() * 1080 - 540;

            // --- 调整随机化动画时长，使其整体更长 ---
            // 时长范围改为 80% 到 130% 的基础时长
            long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8));

            // --- 新增：计算每个粒子的启动延迟 ---
            long startDelay = (long) (((float)i / particleCount) * maxStaggerDelay); // 根据索引计算延迟

            // 执行粒子动画
            particle.animate()
                    .setStartDelay(startDelay) // *** 应用启动延迟 ***
                    .translationXBy(translationX)
                    .translationYBy(translationY)
                    .alpha(0f)
                    .rotationBy(rotation)
                    .scaleX(0.7f)
                    .scaleY(0.7f)
                    .setDuration(duration) // 使用更长且随机化的时长
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (particle.getParent() != null) {
                                container.removeView(particle);
                            }
                        }
                    })
                    .withLayer() // *** 尝试使用硬件层加速 ***
                    .start();
        }
    }

    /**
     * 获取随机的粒子颜色 (示例：白色、黄色、橙色系)
     * @return Color int
     */
    private int getRandomParticleColor() {
        int[] colors = {
                Color.WHITE,
                Color.YELLOW,
                0xFFFFA500, // Orange
                0xFFFFE4B5  // Moccasin (淡黄)
        };
        return colors[random.nextInt(colors.length)];
    }

    /**
     * 辅助方法：将 dp 转换为 px
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
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
    @Override
    protected void onStop() {
        super.onStop();
    }


    // --- 图表相关方法 ---
    /**
     * 设置图表的基本样式和配置
     * 优化后的图表设计更加美观和专业
     */
    private void setupLightChart() {
        // 基础图表配置
        lightChart.getDescription().setEnabled(false);
        lightChart.setTouchEnabled(true);        // 启用触摸以提供更好的用户体验
        lightChart.setDragEnabled(true);         // 允许用户拖动图表
        lightChart.setScaleEnabled(true);        // 允许缩放以查看详细数据
        lightChart.setDrawGridBackground(false);
        lightChart.setPinchZoom(true);           // 启用双指缩放
        lightChart.setBackgroundColor(Color.WHITE);
        lightChart.setExtraOffsets(10f, 10f, 10f, 10f);  // 添加边距，使图表更美观
        lightChart.getLegend().setTextSize(12f);  // 设置图例文字大小
        lightChart.getLegend().setForm(Legend.LegendForm.LINE);  // 设置图例样式为线形
        lightChart.getLegend().setFormSize(15f);  // 设置图例形状大小
        lightChart.setMaxHighlightDistance(300);

        // 设置动画效果
        lightChart.animateX(1000);  // 添加1秒的X轴动画，使数据更新更流畅

        // 配置 X 轴
        XAxis x = lightChart.getXAxis();
        x.setEnabled(true);  // 显示X轴以提高可读性
        x.setPosition(XAxis.XAxisPosition.BOTTOM);  // 将X轴放在底部
        x.setDrawGridLines(false);  // 不显示网格线，保持整洁
        x.setTextColor(Color.DKGRAY);  // 深灰色文字，不那么突兀
        x.setTextSize(10f);
        x.setAxisLineColor(Color.DKGRAY);
        x.setAxisLineWidth(1f);
        x.setLabelRotationAngle(0);  // 保持标签水平

        // 配置左 Y 轴
        YAxis y = lightChart.getAxisLeft();
        y.setLabelCount(6, true);  // 强制使用固定数量的标签，更好看
        y.setTextColor(Color.DKGRAY);
        y.setTextSize(10f);
        y.setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART);  // 标签放在外部更清晰
        y.setDrawGridLines(true);  // 显示水平网格线帮助读数
        y.setGridColor(Color.LTGRAY);  // 浅灰色网格线不会太显眼
        y.setGridLineWidth(0.5f);  // 细网格线
        y.setAxisLineColor(Color.DKGRAY);
        y.setAxisLineWidth(1f);
        y.setAxisMinimum(0f);  // 设置Y轴最小值为0，更符合光照强度的实际情况

        // 禁用右 Y 轴
        lightChart.getAxisRight().setEnabled(false);

        // 创建数据集并添加到图表
        LineDataSet lightDataSet = createLightSet();
        LineData data = new LineData(lightDataSet);
        data.setValueTextSize(10f);  // 设置数值文字大小
        lightChart.setData(data);
        lightChart.invalidate();
    }

    /**
     * 向图表添加新的光线数据点
     * 优化了数据点管理和滚动效果
     * @param lightLevel 光照强度值
     */
    private void addLightEntry(float lightLevel) {
        LineData data = lightChart.getData();
        if (data == null) {
            // 如果没有数据，创建新数据集
            LineDataSet set = createLightSet();
            data = new LineData(set);
            lightChart.setData(data);
        }

        ILineDataSet set = data.getDataSetByIndex(0);
        if (set == null) {
            set = createLightSet();
            data.addDataSet(set);
        }

        // 控制数据点数量和滚动效果
        final int MAX_VISIBLE_POINTS = 100;  // 最大可见点数常量，便于维护
        final int MAX_TOTAL_POINTS = 1000;   // 总保留点数上限，防止内存占用过大

        // 添加新数据点
        data.addEntry(new Entry(set.getEntryCount(), lightLevel), 0);

        // 移除过多的点以优化性能
        if (set.getEntryCount() > MAX_TOTAL_POINTS) {
            set.removeEntry(0);
        }

        // 通知数据更新
        data.notifyDataChanged();
        lightChart.notifyDataSetChanged();

        // 优化滚动效果
        lightChart.setVisibleXRangeMaximum(MAX_VISIBLE_POINTS);
        lightChart.moveViewToX(data.getEntryCount() - 1);

        // 可选：添加边界检查，确保图表显示范围在合理区间
        if (lightLevel > lightChart.getAxisLeft().getAxisMaximum()) {
            // 如果新数据超出当前Y轴范围，适当调整
            lightChart.getAxisLeft().setAxisMaximum(lightLevel + lightLevel * 0.1f);
            lightChart.invalidate();
        }
    }

    /**
     * 创建光线图表的数据集样式
     * 优化后的数据集更加美观，提供渐变色填充效果
     * @return 配置好的LineDataSet对象
     */
    private LineDataSet createLightSet() {
        LineDataSet set = new LineDataSet(null, "光照强度 (lux)");

        // 线条样式
        set.setLineWidth(2.5f);  // 稍微加粗线条
        set.setColor(Color.rgb(33, 150, 243));  // 使用Material Design蓝色
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);  // 平滑曲线

        // 数据点样式
        set.setDrawCircles(true);  // 显示数据点，增强可读性
        set.setCircleRadius(3f);   // 适当的圆点大小
        set.setCircleColor(Color.rgb(33, 150, 243));  // 与线条颜色一致
        set.setCircleHoleRadius(1.5f);  // 设置圆点空心部分大小
        set.setCircleHoleColor(Color.WHITE);  // 圆点中心为白色

        // 数值显示
        set.setDrawValues(false);  // 默认不显示数值

        // 高亮效果
        set.setHighlightEnabled(true);  // 启用高亮
        set.setHighLightColor(Color.rgb(244, 67, 54));  // 高亮颜色使用红色
        set.setHighlightLineWidth(1.5f);  // 高亮线宽度

        // 填充效果
        set.setDrawFilled(true);

        // 使用渐变填充效果
        // 填充渐变 - 从蓝色过渡到透明
        Drawable gradientDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {
                        Color.argb(150, 33, 150, 243),  // 半透明蓝色
                        Color.argb(50, 33, 150, 243),   // 更透明的蓝色
                        Color.argb(20, 33, 150, 243)    // 几乎透明的蓝色
                }
        );
        set.setFillDrawable(gradientDrawable);

        return set;
    }

}