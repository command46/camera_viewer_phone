package com.example.myapplication.ToolData;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CalendarView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.RecordAdapter;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

public class Tools {
    private static final String TAG = "Tools";
    // 日期格式化器，用于生成记录键（YYYY-MM-DD）
    private static final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());


    /**
     * 获取随机的粒子颜色 (示例：白色、黄色、橙色系)
     *
     * @return Color int
     */
    public static int getRandomParticleColor() {
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
    public static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    /**
     * 加载今天的数据
     */
    public static int[] loadTodayData(Context context) {
        RecordData todayRecord = JsonDataStorage.getRecord(context, getTodayDateKey());
        if (todayRecord != null) {
            Log.d(TAG, "找到今天的数据: 次数=" + todayRecord.getCount() + ", 爽感=" + todayRecord.getPleasureLevel());
            return new int[]{todayRecord.getCount(), todayRecord.getPleasureLevel()};
        } else {
            Log.d(TAG, "没有找到今天的数据");
            return new int[]{0, 0};
        }

    }

    public static String getTodayDateKey() {
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1; // 月份从 0 开始，所以需要加 1
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        return String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month, day);
    }

    /**
     * 检查 IP 地址格式是否有效
     *
     * @param ip 要检查的 IP 地址字符串
     * @return 如果格式有效则返回 true，否则返回 false
     */
    public static boolean isValidIpAddress(String ip) {
        // 检查非空且符合 IP 地址的格式
        return TextUtils.isEmpty(ip) || !Patterns.IP_ADDRESS.matcher(ip).matches();
    }

    public static void setupLightChart(LineChart lightChart, Context context) {
        lightChart.getDescription().setEnabled(false);
        lightChart.setTouchEnabled(true);
        lightChart.setDragEnabled(true);
        lightChart.setScaleEnabled(true);
        lightChart.setDrawGridBackground(false);
        lightChart.setPinchZoom(true);
        lightChart.setBackgroundColor(Color.WHITE);
        lightChart.setExtraOffsets(10f, 10f, 10f, 10f);
        lightChart.getLegend().setTextSize(12f);
        lightChart.getLegend().setForm(Legend.LegendForm.LINE);
        lightChart.getLegend().setFormSize(15f);
        lightChart.setMaxHighlightDistance(300);

        lightChart.animateX(1000);

        XAxis x = lightChart.getXAxis();
        x.setEnabled(true);
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(Color.DKGRAY);
        x.setTextSize(10f);
        x.setAxisLineColor(Color.DKGRAY);
        x.setAxisLineWidth(1f);
        x.setLabelRotationAngle(0);

        YAxis y = lightChart.getAxisLeft();
        y.setLabelCount(6, true);
        y.setTextColor(Color.DKGRAY);
        y.setTextSize(10f);
        y.setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART);
        y.setDrawGridLines(true);
        y.setGridColor(Color.LTGRAY);
        y.setGridLineWidth(0.5f);
        y.setAxisLineColor(Color.DKGRAY);
        y.setAxisLineWidth(1f);
        y.setAxisMinimum(0f);

        lightChart.getAxisRight().setEnabled(false);

        LineDataSet lightDataSet = createLightSet(context);
        LineData data = new LineData(lightDataSet);
        data.setValueTextSize(10f);
        lightChart.setData(data);
        lightChart.invalidate();
    }

    public static void addLightEntry(LineChart lightChart, float lightLevel, Context context) {
        LineData data = lightChart.getData();
        if (data == null) {
            LineDataSet set = createLightSet(context);
            data = new LineData(set);
            lightChart.setData(data);
        }

        ILineDataSet set = data.getDataSetByIndex(0);
        if (set == null) {
            set = createLightSet(context);
            data.addDataSet(set);
        }

        final int MAX_VISIBLE_POINTS = 100;
        final int MAX_TOTAL_POINTS = 1000;

        data.addEntry(new Entry(set.getEntryCount(), lightLevel), 0);

        if (set.getEntryCount() > MAX_TOTAL_POINTS) {
            set.removeEntry(0);
        }

        data.notifyDataChanged();
        lightChart.notifyDataSetChanged();

        lightChart.setVisibleXRangeMaximum(MAX_VISIBLE_POINTS);
        lightChart.moveViewToX(data.getEntryCount() - 1);

        if (lightLevel > lightChart.getAxisLeft().getAxisMaximum()) {
            lightChart.getAxisLeft().setAxisMaximum(lightLevel + lightLevel * 0.1f);
            lightChart.invalidate();
        }
    }

    public static LineDataSet createLightSet(Context context) {
        LineDataSet set = new LineDataSet(null, "光照强度 (lux)");
        set.setLineWidth(2.5f);
        set.setColor(Color.rgb(33, 150, 243));
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        set.setDrawCircles(true);
        set.setCircleRadius(3f);
        set.setCircleColor(Color.rgb(33, 150, 243));
        set.setCircleHoleRadius(1.5f);
        set.setCircleHoleColor(Color.WHITE);
        set.setDrawValues(false);
        set.setHighlightEnabled(true);
        set.setHighLightColor(Color.rgb(244, 67, 54));
        set.setHighlightLineWidth(1.5f);
        set.setDrawFilled(true);

        Drawable gradientDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.argb(150, 33, 150, 243),
                        Color.argb(50, 33, 150, 243),
                        Color.argb(20, 33, 150, 243)
                }
        );
        set.setFillDrawable(gradientDrawable);

        return set;
    }

    /**
     * 显示月视图对话框
     *
     * @param activity 调用该方法的 Activity
     */
    @SuppressLint("StringFormatMatches")
    public static void showMonthlyViewDialog(final Activity activity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LayoutInflater inflater = activity.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_monthly_view, null);
        builder.setView(dialogView);
        builder.setTitle(R.string.monthly_view_title);

        CalendarView calendarView = dialogView.findViewById(R.id.calendarViewDialog);
        TextView detailsTextView = dialogView.findViewById(R.id.detailsTextViewDialog);
        RecyclerView monthRecordsRecyclerView = dialogView.findViewById(R.id.monthRecordsRecyclerView);

        // 获取所有记录
        final Map<String, RecordData> allRecords = JsonDataStorage.getAllRecords(activity);
        final String[] pleasureLevels = activity.getResources().getStringArray(R.array.pleasure_levels);

        // 设置 RecyclerView
        RecordAdapter adapter = new RecordAdapter(activity);
        monthRecordsRecyclerView.setAdapter(adapter);
        monthRecordsRecyclerView.setLayoutManager(new LinearLayoutManager(activity));

        // 初始化统计并绑定日历监听
        Calendar currentCalendar = Calendar.getInstance();
        updateMonthRecords(adapter, allRecords,
                currentCalendar.get(Calendar.YEAR), currentCalendar.get(Calendar.MONTH),
                detailsTextView, activity);

        calendarView.setOnDateChangeListener((view, year, month, dayOfMonth) -> {
            // 切换月份时更新统计
            if (year != currentCalendar.get(Calendar.YEAR)
                    || month != currentCalendar.get(Calendar.MONTH)) {
                currentCalendar.set(Calendar.YEAR, year);
                currentCalendar.set(Calendar.MONTH, month);
                updateMonthRecords(adapter, allRecords, year, month, detailsTextView, activity);
            }

            // 点击日期时显示当日详情
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, dayOfMonth);
            String key = dateFormat.format(selected.getTime());
            RecordData record = allRecords.get(key);
            if (record != null) {
                String levelText = "未知";
                int level = record.getPleasureLevel();
                if (level >= 1 && level <= pleasureLevels.length) {
                    levelText = pleasureLevels[level - 1];
                }
                detailsTextView.setText(activity.getString(
                        R.string.record_details_format,
                        record.getCount(), levelText));
            } else {
                detailsTextView.setText(R.string.no_record_found);
            }
        });

        builder.setPositiveButton(R.string.close, (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.show();

        // 初始时定位到今天并显示详情
        long now = Calendar.getInstance().getTimeInMillis();
        calendarView.setDate(now, false, true);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        String todayKey = dateFormat.format(today.getTime());
        RecordData todayRecord = allRecords.get(todayKey);
        if (todayRecord != null) {
            String levelText = "未知";
            int level = todayRecord.getPleasureLevel();
            if (level >= 1 && level <= pleasureLevels.length) {
                levelText = pleasureLevels[level - 1];
            }
            detailsTextView.setText(activity.getString(
                    R.string.record_details_format,
                    todayRecord.getCount(), levelText));
        } else {
            detailsTextView.setText(R.string.no_record_found);
        }
    }

    /**
     * 更新月记录列表并计算月度统计
     */
    private static void updateMonthRecords(RecordAdapter adapter,
                                           Map<String, RecordData> allRecords,
                                           int year, int month,
                                           TextView summaryTextView,
                                           Activity activity) {
        adapter.setMonthData(allRecords, year, month);

        int totalCount = 0;
        int pleasureLevelSum = 0;
        int recordCount = 0;
        String prefix = String.format("%04d-%02d-", year, month + 1);
        for (Map.Entry<String, RecordData> e : allRecords.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                RecordData r = e.getValue();
                totalCount += r.getCount();
                pleasureLevelSum += r.getPleasureLevel();
                recordCount++;
            }
        }

        if (recordCount > 0) {
            float avg = (float) pleasureLevelSum / recordCount;
            summaryTextView.setText(activity.getString(
                    R.string.monthly_summary_format, totalCount, avg));
        } else {
            summaryTextView.setText(R.string.no_record_found);
        }
    }
}
