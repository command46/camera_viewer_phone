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
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
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
    private boolean StartPhotoService = false;
    private boolean StartVideoService = false;
    private boolean StartCameraStreamService = true;


    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private Button connectButton;
    private EditText ipAddressEditText;
    private String ipAddress;
    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE

    };

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private TextView lightSensorTextView;
    private SwitchMaterial CameraStreamServiceSwitch;
    private LineChart lightChart;
    private LineDataSet lightDataSet;
    private int dataSetSize = 100;

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

        // 初始化传感器
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorTextView = findViewById(R.id.lightSensorTextView);


        if (lightSensor == null) {
            lightSensorTextView.setText("Light Sensor: Not Available");
        }

        CameraStreamServiceSwitch = findViewById(R.id.CameraStreamServiceSwitch);
        connectButton = findViewById(R.id.connectButton);
        ipAddressEditText = findViewById(R.id.ipAddressEditText);
        connectButton.setOnClickListener(v -> {
            ipAddress = ipAddressEditText.getText().toString().trim();
            if (ipAddress.isEmpty()) {
                Toast.makeText(this, "请输入地址", Toast.LENGTH_SHORT).show();
                return;
            }
            checkCameraPermissionAndStartService();
        });
        SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
        String savedIpAddress = sharedPreferences.getString("IP_ADDRESS", "");
        ipAddressEditText.setText(savedIpAddress);

        lightChart = findViewById(R.id.lightChart);
        setupLightChart();

        CameraStreamServiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
               StartCameraStreamService = true;
               StartVideoService = false;
               StartPhotoService = false;
            } else {
                StartCameraStreamService = false;
                StartVideoService = true;
                StartPhotoService = true;
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
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
            lightSensorTextView.setText(String.format("Light Sensor: %.1f lux", lightLevel));

            addLightEntry(lightLevel);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理精度变化
    }

    private void checkCameraPermissionAndStartService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            startService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startService();
            } else {
                Toast.makeText(this, "摄像头权限被拒绝，应用即将退出", Toast.LENGTH_SHORT).show();
                finish(); // 拒绝权限则退出应用
            }
        } else if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startService();
            } else {
                Toast.makeText(this, "需要所有权限才能启动服务", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startService(){
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

    private void startCameraStreamService() {
        Log.d(TAG, "Starting CameraStreamService with IP: " + ipAddress);
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        serviceIntent.putExtra("IP_ADDRESS", ipAddress);
        startForegroundService(serviceIntent); // 使用 startForegroundService 以便在后台稳定运行
        Log.d(TAG, "CameraStreamService started");
    }
    private void startVideoService() {
        if (checkAndRequestPermissions()) {
            Log.d(TAG, "Starting VideoService with IP: " + ipAddress);
            Intent serviceIntent = new Intent(this, VideoService.class);
            serviceIntent.putExtra("IP_ADDRESS", ipAddress);
            startForegroundService(serviceIntent);
            Log.d(TAG, "VideoService started");
        }
    }
    private void startPhotoService() { // 启动 PhotoService
        Log.d(TAG, "Starting PhotoService with IP: " + ipAddress);
        Intent serviceIntent = new Intent(this, PhotoService.class); // 启动 PhotoService
        serviceIntent.putExtra("IP_ADDRESS", ipAddress);
        startForegroundService(serviceIntent); // 使用 startForegroundService
        Log.d(TAG, "PhotoService started");
    }

    /**
     * 检查并请求所需权限
     * 此方法用于检查应用是否已经获得了所有REQUIRED_PERMISSIONS中列出的权限
     * 如果有权限尚未授予，则将这些权限添加到permissionsNeeded列表中，并请求这些权限
     *
     * @return boolean 表示是否已经获得了所有所需的权限如果获得了所有权限，则返回true；否则，返回false
     */
    private boolean checkAndRequestPermissions() {
        // 初始化一个列表，用于存储需要请求的权限
        List<String> permissionsNeeded = new ArrayList<>();

        // 遍历所需的权限列表，检查每个权限是否已经授予
        for (String permission : REQUIRED_PERMISSIONS) {
            // 如果权限尚未授予，则将其添加到需要请求的权限列表中
            if (ActivityCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        // 如果有权限需要请求，则请求这些权限，并返回false表示权限尚未完全授予
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                permissionsNeeded.toArray(new String[0]),
                PERMISSION_REQUEST_CODE);
            return false;
        }
        // 如果所有权限都已经授予，返回true表示权限检查通过
        return true;
    }
    @Override
    protected void onStop() {
        super.onStop();
        // 保存当前输入的 IP 地址到 SharedPreferences
        SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("IP_ADDRESS", ipAddressEditText.getText().toString());
        editor.apply();
    }

    /**
     * 初始化光线图表的设置
     * 此方法配置图表的基本属性，如背景色、触摸和拖动功能等
     * 同时，它也设置了X轴和Y轴的属性，以适应光线数据的展示需求
     */
    private void setupLightChart() {
        // 禁用图表描述，以简化界面
        lightChart.getDescription().setEnabled(false);
        // 禁用触摸事件，因为光线数据不需要用户交互
        lightChart.setTouchEnabled(false);
        // 禁用拖动功能，保持图表稳定性
        lightChart.setDragEnabled(false);
        // 禁用缩放功能，确保图表按原始比例显示
        lightChart.setScaleEnabled(false);
        // 不绘制网格背景，以简化图表视觉效果
        lightChart.setDrawGridBackground(false);
        // 禁用双指缩放，保持图表稳定性
        lightChart.setPinchZoom(false);
        // 设置背景色为白色，以提高可读性
        lightChart.setBackgroundColor(Color.WHITE);
        // 设置最大高亮距离为300，限制用户交互范围
        lightChart.setMaxHighlightDistance(300);

        // 配置X轴属性
        XAxis x = lightChart.getXAxis();
        // 禁用X轴，因为光线数据的展示不需要X轴刻度
        x.setEnabled(false);

        // 配置左侧Y轴属性
        YAxis y = lightChart.getAxisLeft();
        // 设置Y轴标签数量为6，自动调整刻度间隔
        y.setLabelCount(6, false);
        // 设置Y轴标签颜色为黑色，以提高对比度和可读性
        y.setTextColor(Color.BLACK);
        // 将Y轴标签位置设置在图表内部，以节省空间
        y.setPosition(YAxis.YAxisLabelPosition.INSIDE_CHART);
        // 不绘制网格线，保持图表简洁
        y.setDrawGridLines(false);
        // 设置Y轴线条颜色为黑色，增强视觉效果
        y.setAxisLineColor(Color.BLACK);

        // 禁用右侧Y轴，因为光线数据只需要一个Y轴参考
        lightChart.getAxisRight().setEnabled(false);

        // 创建光线数据集对象，设置其属性
        lightDataSet = new LineDataSet(null, "Light");
        // 设置线条宽度为2浮点数，提高数据线的可见性
        lightDataSet.setLineWidth(2f);
        // 设置数据线颜色为蓝色，以区分光线数据
        lightDataSet.setColor(Color.BLUE);
        // 使用CUBIC_BEZIER模式绘制曲线，使数据线更加平滑
        lightDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        // 不绘制数据值，因为图表上不需要显示具体的数值
        lightDataSet.setDrawValues(false);
        // 不绘制数据点的圆圈，简化视觉效果
        lightDataSet.setDrawCircles(false);
        // 设置高亮颜色为蓝色，与数据线颜色保持一致
        lightDataSet.setHighLightColor(Color.BLUE);

        // 创建并设置LineData对象，将其添加到图表中
        LineData data = new LineData(lightDataSet);
        lightChart.setData(data);
    }

    /**
     * 添加光照强度条目到图表中
     * 此方法将给定的光照强度值作为新条目添加到光照强度图表的数据集中
     * 如果数据集不存在，则会先创建一个新的数据集，然后添加条目
     *
     * @param lightLevel 光照强度值，以浮点数表示
     */
    private void addLightEntry(float lightLevel) {
        // 获取光照强度图表的当前数据
        LineData data = lightChart.getData();

        // 检查数据对象是否存在
        if (data != null) {
            // 尝试获取第一个数据集
            ILineDataSet set = data.getDataSetByIndex(0);

            // 如果数据集不存在，则创建一个新的光照强度数据集并添加到数据对象中
            if (set == null) {
                set = createLightSet();
                data.addDataSet(set);
            }

            // 向数据集中添加新的条目，并通知数据对象更改
            data.addEntry(new Entry(set.getEntryCount(), lightLevel), 0);
            data.notifyDataChanged();

            // 通知图表数据已更改，以便更新显示
            lightChart.notifyDataSetChanged();
            // 设置图表的可见X轴范围最大值为数据集大小
            lightChart.setVisibleXRangeMaximum(dataSetSize);
            // 将图表移动到显示最新条目的位置
            lightChart.moveViewToX(data.getEntryCount());
        }
    }

    /**
     * 创建并返回一个表示光的LineDataSet对象
     * 该方法配置了数据集的基本属性，如线宽、颜色、模式以及是否绘制数值和圆点
     *
     * @return LineDataSet 配置好属性的LineDataSet对象，用于图表中表示光的数据序列
     */
    private LineDataSet createLightSet() {
        // 创建一个名为"Light"的LineDataSet对象，初始数据为null
        LineDataSet set = new LineDataSet(null, "Light");

        // 设置线宽为2浮点数单位
        set.setLineWidth(2f);

        // 设置线条颜色为蓝色
        set.setColor(Color.BLUE);

        // 设置线条模式为CUBIC_BEZIER，即使用贝塞尔曲线连接数据点
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        // 设置不绘制数据值
        set.setDrawValues(false);

        // 设置不绘制圆点
        set.setDrawCircles(false);

        // 设置高亮颜色为蓝色
        set.setHighLightColor(Color.BLUE);

        // 返回配置好的LineDataSet对象
        return set;
    }
}