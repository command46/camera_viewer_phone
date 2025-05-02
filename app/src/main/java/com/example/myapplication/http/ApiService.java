package com.example.myapplication.http; // 替换为你的包名

import com.example.myapplication.model.FileListResponse;

import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface ApiService {

    @GET("/list_files")
    Call<FileListResponse> listCloudFiles(); // 返回 Call<T> 用于同步或异步执行

    @Multipart
    @POST("/upload")
    Call<ResponseBody> uploadFile(
            @Part MultipartBody.Part file // 文件部分
            // 如果需要，可以添加其他部分，例如 @Part("description") RequestBody description
    );
}