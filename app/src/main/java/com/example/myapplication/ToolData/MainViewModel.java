package com.example.myapplication.viewmodel;

import android.app.Application; // 使用 Application Context
import android.content.Context;
import android.content.Intent; // 保留 Intent 用于未来可能的 Service 操作
import android.content.SharedPreferences;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel; // 继承 AndroidViewModel 以安全地持有 Application Context
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.myapplication.CameraStreamService; // 保留 Service 类引用
import com.example.myapplication.ToolData.JsonDataStorage;
import com.example.myapplication.ToolData.RecordData;
import com.example.myapplication.utils.Utils; // 引入 Utils 工具类

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * MainActivity 的 ViewModel。
 * 负责管理 UI 相关的数据、处理业务逻辑（如数据存取、服务交互准备），
 * 并通过 LiveData 将状态暴露给 Activity。
 * 它能在配置更改（如屏幕旋转）中存活。
 */
public class MainViewModel extends AndroidViewModel {

    private static final String TAG = "MainViewModel"; // 日志标签

    // SharedPreferences 相关常量
    private static final String PREFS_NAME = "CameraServicePrefs";
    private static final String KEY_IP_ADDRESS = "last_ip_address";
    private static final String KEY_RESTART_SERVICE = "restart_service_flag";

    private final SharedPreferences sharedPreferences; // 用于存储设置
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()); // 日期格式化
    private String todayDateKey; // 今天日期的字符串键，用于存储每日记录

    // --- LiveData 用于承载 UI 状态 ---
    // 使用 MutableLiveData 对内修改，暴露 LiveData 对外观察

    // IP 地址状态
    private final MutableLiveData<String> _ipAddress = new MutableLiveData<>();
    public final LiveData<String> ipAddress = _ipAddress; // 对外暴露不可变的 LiveData

    // “服务失败时重启”开关的状态
    private final MutableLiveData<Boolean> _restartServiceEnabled = new MutableLiveData<>();
    public final LiveData<Boolean> restartServiceEnabled = _restartServiceEnabled;

    // 计数器的状态
    private final MutableLiveData<Integer> _counter = new MutableLiveData<>(0); // 初始值为 0
    public final LiveData<Integer> counter = _counter;

    // 爽感等级的状态 (1-5)
    private final MutableLiveData<Integer> _pleasureLevel = new MutableLiveData<>(1); // 默认等级为 1
    public final LiveData<Integer> pleasureLevel = _pleasureLevel;

    // 光照强度值的状态
    private final MutableLiveData<Float> _lightLevel = new MutableLiveData<>();
    public final LiveData<Float> lightLevel = _lightLevel; // 由 Activity/SensorHandler 更新

    // 光线传感器是否可用的状态
    private final MutableLiveData<Boolean> _isLightSensorAvailable = new MutableLiveData<>(true); // 默认为可用
    public final LiveData<Boolean> isLightSensorAvailable = _isLightSensorAvailable;

    // --- 事件 LiveData (用于处理一次性事件，如 Toast、导航、启动服务信号) ---
    // 使用 Event 包装类确保事件只被消费一次

    // 显示 Toast 消息的事件
    private final MutableLiveData<Event<String>> _toastMessage = new MutableLiveData<>();
    public final LiveData<Event<String>> toastMessage = _toastMessage;

    // 触发启动服务流程的事件 (携带 Boolean 值，但这里简单用 true 表示触发)
    private final MutableLiveData<Event<Boolean>> _startServiceEvent = new MutableLiveData<>();
    public final LiveData<Event<Boolean>> startServiceEvent = _startServiceEvent;

    // 触发播放动画的事件
    private final MutableLiveData<Event<Void>> _playAnimationEvent = new MutableLiveData<>();
    public final LiveData<Event<Void>> playAnimationEvent = _playAnimationEvent;


    /**
     * 构造函数。
     * @param application Application 实例，由 AndroidViewModel 提供。
     */
    public MainViewModel(Application application) {
        super(application);
        // 获取 SharedPreferences 实例
        sharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 获取今天的日期键
        todayDateKey = dateFormat.format(Calendar.getInstance().getTime());
        // 加载初始状态：上次保存的设置和今天的记录
        loadSavedPreferences();
        loadTodayData();
    }

    // --- SharedPreferences 处理 ---

    /**
     * 从 SharedPreferences 加载上次保存的 IP 地址和重启设置，并更新 LiveData。
     */
    private void loadSavedPreferences() {
        // 读取 IP 地址，默认为空字符串
        _ipAddress.setValue(sharedPreferences.getString(KEY_IP_ADDRESS, ""));
        // 读取重启开关状态，默认为 true
        _restartServiceEnabled.setValue(sharedPreferences.getBoolean(KEY_RESTART_SERVICE, true));
        Log.d(TAG,"已加载 SharedPreferences: IP=" + _ipAddress.getValue() + ", Restart=" + _restartServiceEnabled.getValue());
    }

    /**
     * 保存有效的 IP 地址到 SharedPreferences 并更新 LiveData。
     * @param ip 要保存的 IP 地址。
     */
    public void saveIpAddress(String ip) {
        // 使用 Utils 验证 IP 地址格式
        if (Utils.isValidIpAddress(ip)) {
             // 保存到 SharedPreferences
             sharedPreferences.edit().putString(KEY_IP_ADDRESS, ip).apply();
             // 更新 LiveData，触发 UI 更新
             _ipAddress.setValue(ip);
             Log.d(TAG,"已保存 IP 地址: " + ip);
        } else {
            // 如果 IP 无效，可以通过 Toast 事件通知 UI
             _toastMessage.setValue(new Event<>("保存失败：IP 地址无效"));
             Log.w(TAG,"尝试保存无效的 IP 地址: " + ip);
        }
    }

    /**
     * 保存“失败时重启”开关的状态到 SharedPreferences 并更新 LiveData。
     * @param enabled 开关是否启用。
     */
    public void saveRestartPreference(boolean enabled) {
        // 保存到 SharedPreferences
        sharedPreferences.edit().putBoolean(KEY_RESTART_SERVICE, enabled).apply();
        // 更新 LiveData
        _restartServiceEnabled.setValue(enabled);
        Log.d(TAG,"已保存重启设置: " + enabled);
    }

    // --- 计数器和爽感等级处理 ---

    /**
     * 从 JsonDataStorage 加载今天的记录（次数和爽感等级），并更新 LiveData。
     * 如果没有记录，则使用默认值。
     */
    private void loadTodayData() {
        // 从 JSON 文件读取今天的数据
        RecordData todayRecord = JsonDataStorage.getRecord(getApplication(), todayDateKey);
        if (todayRecord != null) {
            // 如果找到记录，更新 LiveData
            _counter.setValue(todayRecord.getCount());
            _pleasureLevel.setValue(todayRecord.getPleasureLevel());
            Log.d(TAG, "已加载今日记录: 次数=" + todayRecord.getCount() + ", 爽感=" + todayRecord.getPleasureLevel());
        } else {
            // 如果没有记录，设置为默认值
             _counter.setValue(0);
             _pleasureLevel.setValue(1); // 默认爽感等级为 1
            Log.d(TAG, "今日 (" + todayDateKey + ") 无记录，使用默认值。");
        }
    }

    /**
     * 更新计数器的值。
     * @param change 计数器的变化量 (+1 或 -1)。
     */
    public void updateCounter(int change) {
        // 获取当前计数值，如果 LiveData 为 null 则默认为 0
        int currentCount = _counter.getValue() != null ? _counter.getValue() : 0;
        // 计算新计数值，确保不小于 0
        int newCount = Math.max(0, currentCount + change);
        // 只有当计数值实际发生变化时才更新
         if (newCount != currentCount) {
            _counter.setValue(newCount); // 更新 LiveData
            // 如果是增加计数（且大于0），触发播放动画事件
            if (change > 0 && newCount > 0) {
                 _playAnimationEvent.setValue(new Event<>(null)); // 发送播放动画信号
            }
            saveCurrentState(); // 每次计数变化后保存当前状态
        }
    }

    /**
     * 设置爽感等级。
     * @param level 选择的爽感等级 (应为 1-5)。
     */
    public void setPleasureLevel(int level) {
        // 输入的 level 通常是 1-5，直接使用
        int newLevel = level;
        // 验证等级是否在有效范围内 (1-5)
        if (newLevel < 1 || newLevel > 5) newLevel = 1; // 无效则设为默认值 1

        // 只有当等级发生变化时才更新
        if (_pleasureLevel.getValue() == null || _pleasureLevel.getValue() != newLevel) {
             _pleasureLevel.setValue(newLevel); // 更新 LiveData
             saveCurrentState(); // 每次等级变化后保存当前状态
        }
    }

    /**
     * 将当前的计数和爽感等级保存到 JsonDataStorage。
     * 这个方法在计数或爽感等级发生变化时被调用。
     */
    private void saveCurrentState() {
        // 获取当前的计数值和爽感等级
        int count = _counter.getValue() != null ? _counter.getValue() : 0;
        int level = _pleasureLevel.getValue() != null ? _pleasureLevel.getValue() : 1;
        Log.d(TAG, "正在保存状态 - 日期: " + todayDateKey + ", 次数: " + count + ", 爽感: " + level);
        // 调用 JsonDataStorage 进行保存
        JsonDataStorage.saveRecord(getApplication(), todayDateKey, count, level);
    }

     // --- 月视图数据 ---

    /**
     * 获取所有已保存的记录。
     * 用于在月视图对话框中显示历史数据。
     * @return 包含所有日期记录的 Map。
     */
    public Map<String, RecordData> getAllRecords() {
        // 直接调用 JsonDataStorage 获取数据
        return JsonDataStorage.getAllRecords(getApplication());
    }


    // --- 传感器数据更新 ---

    /**
     * 由 Activity (通过 SensorHandler 的回调) 调用，用于更新光照强度 LiveData。
     * @param lux 当前的光照强度值。
     */
    public void updateLightLevel(float lux) {
        // 使用 postValue 因为这个方法可能从非 UI 线程（SensorEventListener回调）调用
        _lightLevel.postValue(lux);
    }

    /**
     * 由 Activity (通过 SensorHandler 的回调) 调用，用于更新光线传感器可用状态 LiveData。
     * @param available 传感器是否可用。
     */
    public void setLightSensorAvailable(boolean available) {
        // 使用 postValue 因为这个方法可能从非 UI 线程调用
        _isLightSensorAvailable.postValue(available);
    }

    // --- 服务交互 ---

    /**
     * 用户点击“连接”按钮时调用此方法。
     * 验证 IP，保存设置，并触发 Activity 开始服务启动流程（包括权限检查）。
     * @param currentIp 用户输入的 IP 地址。
     */
    public void attemptConnection(String currentIp) {
        // 再次验证 IP 地址
        if (!Utils.isValidIpAddress(currentIp)) {
            _toastMessage.setValue(new Event<>("请输入有效的 IP 地址"));
            return;
        }
         // 如果 IP 有效，先保存它 (会更新 LiveData)
         saveIpAddress(currentIp);
         // 同时保存当前的重启开关状态
         saveRestartPreference(_restartServiceEnabled.getValue() != null ? _restartServiceEnabled.getValue() : true);

        // 发送启动服务流程的信号给 Activity
        _startServiceEvent.setValue(new Event<>(true));
        Log.d(TAG,"尝试连接，已保存设置并发出启动服务事件。 IP: " + currentIp);
    }

    // --- LiveData 事件包装类 ---

    /**
     * 一个包装类，用于 LiveData 中表示一次性事件。
     * 事件（如 Toast 消息）应该只被消费一次，即使在配置更改后 LiveData 重新发送数据。
     * @param <T> 事件内容的类型。
     */
    public static class Event<T> {
        private T content; // 事件内容
        private boolean hasBeenHandled = false; // 标记事件是否已被处理

        public Event(T content) {
            this.content = content;
        }

        /**
         * 获取事件内容，但前提是该事件尚未被处理过。
         * 如果事件已被处理，则返回 null。此方法会将事件标记为已处理。
         * @return 事件内容（如果未处理），否则返回 null。
         */
        public T getContentIfNotHandled() {
            if (hasBeenHandled) {
                return null; // 已处理，返回 null
            } else {
                hasBeenHandled = true; // 标记为已处理
                return content; // 返回内容
            }
        }

        /**
         * 查看事件内容，但不将其标记为已处理。
         * 主要用于调试或特殊情况。
         * @return 事件内容。
         */
        public T peekContent() {
            return content;
        }
    }
}
