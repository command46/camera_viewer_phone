package com.example.myapplication.ToolData; // 替换成你的应用包名

/**
 * 用于存储特定日期记录的数据结构
 */
public class RecordData {
    private int count;          // 次数
    private int pleasureLevel;  // 爽感等级

    // Gson 需要一个无参构造函数来进行反序列化
    public RecordData() {
    }

    public RecordData(int count, int pleasureLevel) {
        this.count = count;
        this.pleasureLevel = pleasureLevel;
    }

    // Getters (必须有，Gson 序列化/反序列化可能需要)
    public int getCount() {
        return count;
    }

    public int getPleasureLevel() {
        return pleasureLevel;
    }

    // Setters (可选，但有时方便)
    public void setCount(int count) {
        this.count = count;
    }

    public void setPleasureLevel(int pleasureLevel) {
        this.pleasureLevel = pleasureLevel;
    }

    @Override
    public String toString() {
        // 方便调试时查看内容
        return "RecordData{" +
                "count=" + count +
                ", pleasureLevel=" + pleasureLevel +
                '}';
    }
}