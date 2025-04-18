package com.example.myapplication; // 替换为您的包名

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.ToolData.RecordData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.RecordViewHolder> {

    private List<RecordItem> recordItems = new ArrayList<>();
    private Context context;
    private String[] pleasureLevels;

    public RecordAdapter(Context context) {
        this.context = context;
        this.pleasureLevels = context.getResources().getStringArray(R.array.pleasure_levels);
    }

    public void setData(Map<String, RecordData> recordDataMap) {
        // 使用TreeMap按日期排序
        TreeMap<String, RecordData> sortedMap = new TreeMap<>(recordDataMap);

        recordItems.clear();
        for (Map.Entry<String, RecordData> entry : sortedMap.entrySet()) {
            recordItems.add(new RecordItem(entry.getKey(), entry.getValue()));
        }
        notifyDataSetChanged();
    }

    public void setMonthData(Map<String, RecordData> allRecords, int year, int month) {
        recordItems.clear();

        // 月份从0开始，所以展示时要+1
        String monthPrefix = String.format("%04d-%02d-", year, month + 1);

        for (Map.Entry<String, RecordData> entry : allRecords.entrySet()) {
            if (entry.getKey().startsWith(monthPrefix)) {
                recordItems.add(new RecordItem(entry.getKey(), entry.getValue()));
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_record, parent, false);
        return new RecordViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position) {
        RecordItem item = recordItems.get(position);
        holder.bind(item, pleasureLevels);
    }

    @Override
    public int getItemCount() {
        return recordItems.size();
    }

    static class RecordViewHolder extends RecyclerView.ViewHolder {
        private TextView dateTextView;
        private TextView countTextView;
        private TextView pleasureLevelTextView;

        public RecordViewHolder(@NonNull View itemView) {
            super(itemView);
            dateTextView = itemView.findViewById(R.id.recordDateTextView);
            countTextView = itemView.findViewById(R.id.recordCountTextView);
            pleasureLevelTextView = itemView.findViewById(R.id.recordPleasureLevelTextView);
        }

        public void bind(RecordItem item, String[] pleasureLevels) {
            // 只显示日期的日部分
            String dayPart = item.getDate().substring(8); // 取出日期的后两位
            dateTextView.setText(dayPart + "日");
            countTextView.setText(item.getRecord().getCount() + "次");

            // 显示爽感文字描述而不是数字
            int pleasureLevel = item.getRecord().getPleasureLevel();
            if (pleasureLevel >= 1 && pleasureLevel <= pleasureLevels.length) {
                pleasureLevelTextView.setText(pleasureLevels[pleasureLevel - 1]);
            } else {
                pleasureLevelTextView.setText("未知");
            }
        }
    }

    public static class RecordItem {
        private String date;
        private RecordData record;

        public RecordItem(String date, RecordData record) {
            this.date = date;
            this.record = record;
        }

        public String getDate() {
            return date;
        }

        public RecordData getRecord() {
            return record;
        }
    }
}