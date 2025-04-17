// MainViewModel.java
package com.example.myapplication.viewmodel;

import android.app.Application; // 使用 Application Context
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel; // 继承 AndroidViewModel 以获取 Application Context
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.myapplication.CameraStreamService;
import com.example.myapplication.ToolData.JsonDataStorage;
import com.example.myapplication.ToolData.RecordData;
import com.example.myapplication.utils.Utils; // 引入 Utils

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

public class MainViewModel extends AndroidViewModel {

    private static final String TAG = "MainViewModel";
    private static final String PREFS_NAME = "CameraServicePrefs";
    private static final String KEY_IP_ADDRESS = "last_ip_address";
    private static final String KEY_RESTART_SERVICE = "restart_service_flag";

    private final SharedPreferences sharedPreferences;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private String todayDateKey;

    // --- LiveData for UI State ---
    private final MutableLiveData<String> _ipAddress = new MutableLiveData<>();
    public final LiveData<String> ipAddress = _ipAddress;

    private final MutableLiveData<Boolean> _restartServiceEnabled = new MutableLiveData<>();
    public final LiveData<Boolean> restartServiceEnabled = _restartServiceEnabled;

    private final MutableLiveData<Integer> _counter = new MutableLiveData<>(0);
    public final LiveData<Integer> counter = _counter;

    private final MutableLiveData<Integer> _pleasureLevel = new MutableLiveData<>(1); // Default to 1
    public final LiveData<Integer> pleasureLevel = _pleasureLevel;

    private final MutableLiveData<Float> _lightLevel = new MutableLiveData<>();
    public final LiveData<Float> lightLevel = _lightLevel; // Activity/SensorHandler will update this

     private final MutableLiveData<Boolean> _isLightSensorAvailable = new MutableLiveData<>(true);
    public final LiveData<Boolean> isLightSensorAvailable = _isLightSensorAvailable;

    // Event LiveData (for single-time events like showing toasts or starting services)
    private final MutableLiveData<Event<String>> _toastMessage = new MutableLiveData<>();
    public final LiveData<Event<String>> toastMessage = _toastMessage;

    private final MutableLiveData<Event<Boolean>> _startServiceEvent = new MutableLiveData<>();
    public final LiveData<Event<Boolean>> startServiceEvent = _startServiceEvent; // True to start, false to stop (if needed)

     private final MutableLiveData<Event<Void>> _playAnimationEvent = new MutableLiveData<>();
    public final LiveData<Event<Void>> playAnimationEvent = _playAnimationEvent;


    public MainViewModel(Application application) {
        super(application);
        sharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        todayDateKey = dateFormat.format(Calendar.getInstance().getTime());
        loadSavedPreferences();
        loadTodayData();
    }

    // --- Preference Handling ---
    private void loadSavedPreferences() {
        _ipAddress.setValue(sharedPreferences.getString(KEY_IP_ADDRESS, ""));
        _restartServiceEnabled.setValue(sharedPreferences.getBoolean(KEY_RESTART_SERVICE, true)); // Default true
    }

    public void saveIpAddress(String ip) {
        if (Utils.isValidIpAddress(ip)) { // Use Utils for validation
             sharedPreferences.edit().putString(KEY_IP_ADDRESS, ip).apply();
             _ipAddress.setValue(ip); // Update LiveData
        } else {
            // Optionally notify UI about invalid IP
             _toastMessage.setValue(new Event<>("请输入有效的 IP 地址"));
        }
    }

    public void saveRestartPreference(boolean enabled) {
        sharedPreferences.edit().putBoolean(KEY_RESTART_SERVICE, enabled).apply();
        _restartServiceEnabled.setValue(enabled); // Update LiveData
    }

    // --- Counter and Pleasure Level ---
    private void loadTodayData() {
        RecordData todayRecord = JsonDataStorage.getRecord(getApplication(), todayDateKey);
        if (todayRecord != null) {
            _counter.setValue(todayRecord.getCount());
            _pleasureLevel.setValue(todayRecord.getPleasureLevel());
            Log.d(TAG, "Loaded today's data: Count=" + todayRecord.getCount() + ", Pleasure=" + todayRecord.getPleasureLevel());
        } else {
             _counter.setValue(0);
             _pleasureLevel.setValue(1); // Default pleasure level
            Log.d(TAG, "No data found for today, using defaults.");
        }
    }

    public void updateCounter(int change) {
        int currentCount = _counter.getValue() != null ? _counter.getValue() : 0;
        int newCount = Math.max(0, currentCount + change); // Ensure count doesn't go below 0
         if (newCount != currentCount) { // Only update if value changed
            _counter.setValue(newCount);
            if (change > 0 && newCount > 0) { // Play animation only on increment
                 _playAnimationEvent.setValue(new Event<>(null)); // Trigger animation event
            }
            saveCurrentState(); // Save whenever counter changes
        }
    }

    public void setPleasureLevel(int level) {
        // Spinner position is 0-4, level is 1-5
        int newLevel = level;
        if (newLevel < 1 || newLevel > 5) newLevel = 1; // Validate level

        if (_pleasureLevel.getValue() == null || _pleasureLevel.getValue() != newLevel) {
             _pleasureLevel.setValue(newLevel);
             saveCurrentState(); // Save whenever level changes
        }
    }

    private void saveCurrentState() {
        int count = _counter.getValue() != null ? _counter.getValue() : 0;
        int level = _pleasureLevel.getValue() != null ? _pleasureLevel.getValue() : 1;
        Log.d(TAG, "Saving state - Date: " + todayDateKey + ", Count: " + count + ", Pleasure: " + level);
        JsonDataStorage.saveRecord(getApplication(), todayDateKey, count, level);
    }

     // --- Monthly Data ---
    public Map<String, RecordData> getAllRecords() {
        return JsonDataStorage.getAllRecords(getApplication());
    }


    // --- Sensor Data Update ---
    public void updateLightLevel(float lux) {
        _lightLevel.postValue(lux); // Use postValue if called from background thread (SensorEventListener)
    }

    public void setLightSensorAvailable(boolean available) {
        _isLightSensorAvailable.postValue(available);
    }

    // --- Service Interaction ---
    public void attemptConnection(String currentIp) {
        if (!Utils.isValidIpAddress(currentIp)) {
            _toastMessage.setValue(new Event<>("请输入有效的 IP 地址"));
            return;
        }
         // Save valid IP first
         saveIpAddress(currentIp); // This updates LiveData too
         saveRestartPreference(_restartServiceEnabled.getValue() != null ? _restartServiceEnabled.getValue() : true); // Save current switch state

        // Signal Activity to check permissions and start service
        _startServiceEvent.setValue(new Event<>(true));
    }

    // --- Helper for LiveData Events ---
    // Used to ensure an event (like a toast) is only handled once.
    public static class Event<T> {
        private T content;
        private boolean hasBeenHandled = false;

        public Event(T content) {
            this.content = content;
        }

        public T getContentIfNotHandled() {
            if (hasBeenHandled) {
                return null;
            } else {
                hasBeenHandled = true;
                return content;
            }
        }

        public T peekContent() {
            return content;
        }
    }
}
