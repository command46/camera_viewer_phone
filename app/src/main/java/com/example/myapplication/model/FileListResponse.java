package com.example.myapplication.model; // 替换为你的包名

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class FileListResponse {

    @SerializedName("files") // 确保和 JSON key 匹配
    private List<String> files;

    @SerializedName("error") // 可选，用于接收错误信息
    private String error;

    @SerializedName("details") // 可选，用于接收错误详情
    private String details;

    // --- Getters (and potentially Setters if needed) ---
    public List<String> getFiles() {
        return files;
    }

    public String getError() {
        return error;
    }

    public String getDetails() {
        return details;
    }

    // toString() for debugging
    @Override
    public String toString() {
        return "FileListResponse{" +
                "files=" + files +
                ", error='" + error + '\'' +
                ", details='" + details + '\'' +
                '}';
    }
}