// MainActivity.java (Refactored)
package com.example.myapplication;

import android.Manifest;
// ... other imports ...
import androidx.lifecycle.ViewModelProvider; // Import ViewModelProvider

import com.example.myapplication.ToolData.RecordData; // Keep if needed for Dialog
import com.example.myapplication.databinding.ActivityMainBinding; // Import ViewBinding
import com.example.myapplication.utils.AnimationHelper;
import com.example.myapplication.utils.SensorHandler;
import com.example.myapplication.utils.Utils; // Import Utils
import com.example.myapplication.viewmodel.MainViewModel; // Import ViewModel

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements SensorHandler.LightSensorListener {

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // ViewBinding (Recommended way to access views)
    private ActivityMainBinding binding; // Replace findViewById

    // ViewModel
    private MainViewModel viewModel;

    // Helpers
    private SensorHandler sensorHandler;
    private AnimationHelper animationHelper;

    // Permissions
    private ActivityResultLauncher<String> requestCameraPermissionLauncher;
    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    private boolean pendingServiceStart = false; // Flag to start service after permissions granted

    // BroadcastReceiver
    private BroadcastReceiver retryFailureReceiver;
    private IntentFilter retryFailureIntentFilter;

    // Date Formatting (Keep if needed directly in Activity, e.g., for Dialog)
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        // Use ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
             Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
             v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
             return insets;
        });

        // Initialize ViewModel
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // Initialize Helpers
        sensorHandler = new SensorHandler(this, this);
        animationHelper = new AnimationHelper(this);

        // Initialize Permission Launchers
        setupPermissionLaunchers();

        // Setup UI Components and Listeners
        setupUI();
        setupListeners();

        // Observe ViewModel LiveData
        observeViewModel();

        // Initialize Chart (could be moved to a ChartHelper class too)
        setupLightChart();

        // Setup BroadcastReceiver
        setupRetryFailureReceiver();

         // Initial check for Notification permission (Camera permission checked on connect)
        checkNotificationPermission();
    }

    private void setupUI() {
        // Setup Spinner Adapter (remains similar)
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.pleasure_levels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.pleasureLevelSpinner.setAdapter(adapter); // Use binding
    }

    private void setupListeners() {
        // Connect Button
        binding.connectButton.setOnClickListener(v -> {
            String currentIp = binding.ipAddressEditText.getText().toString().trim();
            viewModel.attemptConnection(currentIp); // Delegate to ViewModel
        });

        // Restart Switch
        binding.CameraStreamServiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.saveRestartPreference(isChecked); // Delegate to ViewModel
        });

        // Counter Buttons
        binding.decrementButton.setOnClickListener(v -> viewModel.updateCounter(-1)); // Delegate
        binding.incrementButton.setOnClickListener(v -> viewModel.updateCounter(1));  // Delegate

        // Spinner Selection
        binding.pleasureLevelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                 // Position is 0-4, Level should be 1-5
                 viewModel.setPleasureLevel(position + 1); // Delegate level (1-based)
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Monthly View Button
        binding.viewMonthlyDataButton.setOnClickListener(v -> showMonthlyViewDialog());
    }

    private void observeViewModel() {
        viewModel.ipAddress.observe(this, ip -> binding.ipAddressEditText.setText(ip));
        viewModel.restartServiceEnabled.observe(this, enabled -> binding.CameraStreamServiceSwitch.setChecked(enabled));
        viewModel.counter.observe(this, count -> binding.countTextView.setText(String.valueOf(count)));
        viewModel.pleasureLevel.observe(this, level -> {
            int position = level - 1; // Level 1-5 to Position 0-4
             if (position >= 0 && position < binding.pleasureLevelSpinner.getAdapter().getCount()) {
                  // Avoid triggering listener during programmatic update
                 if (binding.pleasureLevelSpinner.getSelectedItemPosition() != position) {
                      binding.pleasureLevelSpinner.setSelection(position);
                 }
             }
        });

        viewModel.lightLevel.observe(this, lux -> {
            binding.lightSensorTextView.setText(getString(R.string.lux, String.format(Locale.getDefault(), "%.1f", lux)));
            addChartEntry(lux); // Update chart
        });

         viewModel.isLightSensorAvailable.observe(this, available -> {
             if (!available) {
                 binding.lightSensorTextView.setText(getString(R.string.lux_unavailable)); // Use a specific string
                 binding.lightChart.clear(); // Clear chart if sensor unavailable
                 binding.lightChart.invalidate();
             }
             // Can optionally disable chart interaction here
         });


        viewModel.toastMessage.observe(this, event -> {
            String message = event.getContentIfNotHandled();
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        });

         viewModel.playAnimationEvent.observe(this, event -> {
            if (event.getContentIfNotHandled() != null) {
                // Use counterTextView as the anchor for particle start position
                animationHelper.playExplosionAnimation((ViewGroup) getWindow().getDecorView(), binding.countTextView);
            }
         });

        viewModel.startServiceEvent.observe(this, event -> {
            Boolean start = event.getContentIfNotHandled();
             if (start != null && start) {
                 checkPermissionsAndStartService(); // Trigger permission check / service start
             }
        });
    }


    // --- SensorHandler.LightSensorListener Implementation ---
    @Override
    public void onLightLevelChanged(float lux) {
        viewModel.updateLightLevel(lux); // Pass data to ViewModel
    }

    @Override
    public void onLightSensorUnavailable() {
        viewModel.setLightSensorAvailable(false); // Notify ViewModel
    }

    // --- Permission Handling ---
    private void setupPermissionLaunchers() {
         requestCameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
             if (isGranted) {
                 Log.i(TAG, "Camera permission granted.");
                 if (pendingServiceStart) {
                     startCameraStreamService();
                 }
             } else {
                 Log.w(TAG, "Camera permission denied.");
                 Toast.makeText(this, "需要相机权限才能启动服务", Toast.LENGTH_SHORT).show();
                 pendingServiceStart = false;
             }
         });

        requestNotificationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                 Log.i(TAG, "Notification permission granted.");
                 // If service start was pending notification permission, proceed if camera also granted
                 if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                     startCameraStreamService();
                 }
             } else {
                 Log.w(TAG, "Notification permission denied.");
                 showNotificationPermissionRationale();
                 // Service can technically start, but warn user
                 Toast.makeText(this, "缺少通知权限，前台服务可能无法正常运行", Toast.LENGTH_LONG).show();
                  // If service start was pending, proceed if camera granted, but with warning
                 if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                     startCameraStreamService();
                 }
             }
        });
    }

    private void checkPermissionsAndStartService() {
        pendingServiceStart = true; // Mark that we intend to start the service

        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean notificationGranted = true; // Assume granted for below Android Tiramisu

         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             notificationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
         }

         if (cameraGranted && notificationGranted) {
             Log.d(TAG, "All required permissions already granted.");
             startCameraStreamService();
         } else if (!cameraGranted) {
             Log.d(TAG, "Requesting Camera permission.");
             requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
             // Notification check will happen after camera result or if camera already granted
         } else { // Camera granted, but Notification not (on Tiramisu+)
             Log.d(TAG, "Requesting Notification permission.");
             checkNotificationPermission(); // Will launch the notification request
         }
    }

     private void checkNotificationPermission() {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                 // Only request if permission not granted and start is pending or user just opened app
                  if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                     showNotificationPermissionRationale();
                 } else {
                     // Request permission - Launcher handles the result
                      Log.d(TAG,"Launching Notification permission request.");
                      requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                 }
             } else {
                  Log.d(TAG,"Notification permission already granted (Tiramisu+).");
                  // If start was pending just for this, proceed if camera also granted
                  if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                       startCameraStreamService();
                  }
             }
         } else {
              Log.d(TAG,"Notification permission not required before Tiramisu.");
              // If start was pending just for this (unlikely), proceed if camera granted
              if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                   startCameraStreamService();
              }
         }
     }

    private void showNotificationPermissionRationale() {
        new AlertDialog.Builder(this)
                .setTitle("需要通知权限")
                .setMessage("应用需要通知权限以在前台服务运行时显示状态。否则服务可能无法正常启动或被系统终止。")
                .setPositiveButton("好的", (dialog, which) -> {
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                         requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                      }
                 })
                .setNegativeButton("取消", (dialog, which) -> {
                     Toast.makeText(this, "未授予通知权限，服务可能无法正常显示", Toast.LENGTH_SHORT).show();
                     // If start was pending, proceed if camera granted, but with warning
                     if (pendingServiceStart && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                          startCameraStreamService();
                     }
                 })
                .show();
    }


    // --- Service Starting ---
    private void startCameraStreamService() {
         if (!pendingServiceStart) {
             Log.w(TAG,"startCameraStreamService called but pendingServiceStart is false.");
             return; // Avoid starting if the intent was cancelled (e.g., permission denied)
         }
         pendingServiceStart = false; // Reset the flag

         String ip = viewModel.ipAddress.getValue(); // Get IP from ViewModel
         Boolean restart = viewModel.restartServiceEnabled.getValue(); // Get switch state

         if (ip == null || !Utils.isValidIpAddress(ip)) { // Validate again
             Toast.makeText(this, "无法启动服务：IP 地址无效", Toast.LENGTH_SHORT).show();
             return;
         }
         if (restart == null) restart = true; // Default

        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        serviceIntent.putExtra("ip_address", ip);
        serviceIntent.putExtra("restart_on_failure", restart);
        // Remove retry count - Service should manage this internally
        // serviceIntent.putExtra("retry_count", 0); // Let service handle initial state

        try {
            ContextCompat.startForegroundService(this, serviceIntent);
            Log.i(TAG, "Attempting to start CameraStreamService with IP: " + ip + ", Restart: " + restart);
            Toast.makeText(this, "正在启动服务...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
             Toast.makeText(this, "启动服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
             // Handle specific exceptions like IllegalStateException if app is in background (less likely with foreground service start)
        }
    }


    // --- Broadcast Receiver ---
    private void setupRetryFailureReceiver() {
        retryFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG.equals(intent.getAction())) {
                    Log.w(TAG, "Received service retry failure broadcast.");
                    showRetryFailedDialog();
                }
            }
        };
        retryFailureIntentFilter = new IntentFilter(CameraStreamService.ACTION_SHOW_RETRY_FAILURE_DIALOG);
    }

    private void showRetryFailedDialog() {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("连接失败")
                    .setMessage("尝试自动重新连接服务失败。请检查网络连接和服务器状态，然后尝试手动连接。")
                    .setPositiveButton("知道了", (dialog, which) -> dialog.dismiss())
                    .setNegativeButton("尝试重连", (dialog, which) -> {
                         // Trigger ViewModel to attempt connection again
                        String currentIp = binding.ipAddressEditText.getText().toString().trim();
                         viewModel.attemptConnection(currentIp);
                         dialog.dismiss();
                    })
                    .setCancelable(false)
                    .show());
        }
    }

    // --- Chart Setup (Example - could be in a ChartHelper) ---
    private void setupLightChart() {
        // Basic setup
        binding.lightChart.getDescription().setEnabled(false);
        binding.lightChart.setTouchEnabled(true);
        binding.lightChart.setDragEnabled(true);
        binding.lightChart.setScaleEnabled(true);
        binding.lightChart.setPinchZoom(true);
        binding.lightChart.setBackgroundColor(Color.LTGRAY); // Example color

        // X Axis
        XAxis xAxis = binding.lightChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        // You might want a custom ValueFormatter for timestamps if adding entries over time

        // Y Axis
        YAxis leftAxis = binding.lightChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        // Set min/max if known, e.g., leftAxis.setAxisMinimum(0f);

        binding.lightChart.getAxisRight().setEnabled(false); // Disable right axis

        // Data
        LineData data = new LineData();
        binding.lightChart.setData(data);
    }

    private void addChartEntry(float luxValue) {
        LineData data = binding.lightChart.getData();

        if (data != null) {
            ILineDataSet set = data.getDataSetByIndex(0);
            // If set does not exist, create it
            if (set == null) {
                set = createSet();
                data.addDataSet(set);
            }

            // Add a new entry. Use timestamp or simple counter for X value
            // Using entry count for simplicity here
            data.addEntry(new Entry(set.getEntryCount(), luxValue), 0);

            data.notifyDataChanged();

            // Let the chart know its data has changed
            binding.lightChart.notifyDataSetChanged();

            // Limit the number of entries shown to avoid performance issues
            binding.lightChart.setVisibleXRangeMaximum(100); // Show last 100 entries

            // Move to the latest entry
            binding.lightChart.moveViewToX(data.getEntryCount());
        }
    }

    private LineDataSet createSet() {
        LineDataSet set = new LineDataSet(null, "光照强度 (lux)");
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setColor(Color.CYAN);
        set.setCircleColor(Color.WHITE);
        set.setLineWidth(2f);
        set.setCircleRadius(3f);
        set.setFillAlpha(65);
        set.setFillColor(Color.CYAN);
        set.setHighLightColor(Color.rgb(244, 117, 117));
        set.setValueTextColor(Color.WHITE);
        set.setValueTextSize(9f);
        set.setDrawValues(false); // Don't draw exact values on points
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER); // Smoothed line
        return set;
    }


     // --- Monthly View Dialog (Keep here or move to DialogManager) ---
    @SuppressLint("StringFormatMatches")
    private void showMonthlyViewDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        // Use ViewBinding for the dialog layout if you create one (e.g., DialogMonthlyViewBinding)
        View dialogView = inflater.inflate(R.layout.dialog_monthly_view, null);
        builder.setView(dialogView);
        builder.setTitle(R.string.monthly_view_title);

        CalendarView calendarView = dialogView.findViewById(R.id.calendarViewDialog);
        TextView detailsTextView = dialogView.findViewById(R.id.detailsTextViewDialog);

        // Fetch data using ViewModel or directly (ViewModel preferred for testability)
        final Map<String, RecordData> allRecords = viewModel.getAllRecords(); // Get data via ViewModel

        calendarView.setOnDateChangeListener((view, year, month, dayOfMonth) -> {
            Calendar selectedCalendar = Calendar.getInstance();
            selectedCalendar.set(year, month, dayOfMonth);
            String selectedDateKey = dateFormat.format(selectedCalendar.getTime());

            RecordData selectedRecord = allRecords.get(selectedDateKey);
            if (selectedRecord != null) {
                detailsTextView.setText(getString(R.string.record_details_format,
                        selectedRecord.getCount(), selectedRecord.getPleasureLevel()));
            } else {
                detailsTextView.setText(R.string.no_record_found);
            }
        });

        builder.setPositiveButton(R.string.close, (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.show();

        // Initialize with today's data
        long initialMillis = Calendar.getInstance().getTimeInMillis();
        calendarView.setDate(initialMillis, false, true);
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
    }

    // --- Lifecycle Methods ---
    @Override
    protected void onResume() {
        super.onResume();
        sensorHandler.registerListener(); // Register sensor listener

         // Register BroadcastReceiver
         if (retryFailureReceiver != null && retryFailureIntentFilter != null) {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                 registerReceiver(retryFailureReceiver, retryFailureIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
             } else {
                 registerReceiver(retryFailureReceiver, retryFailureIntentFilter);
             }
             Log.d(TAG,"Retry failure receiver registered.");
         }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorHandler.unregisterListener(); // Unregister sensor listener

         // Unregister BroadcastReceiver
         try {
             if (retryFailureReceiver != null) {
                 unregisterReceiver(retryFailureReceiver);
                 Log.d(TAG,"Retry failure receiver unregistered.");
             }
         } catch (IllegalArgumentException e) {
             Log.w(TAG, "Receiver already unregistered or never registered.", e);
         }
    }

     @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up ViewBinding reference
        binding = null;
    }

    // --- Deprecated onRequestPermissionsResult (kept for reference if not using Launchers fully) ---
    // @Override
    // public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    //     super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    //     // Handle results IF NOT using ActivityResultLaunchers exclusively
    // }
}
