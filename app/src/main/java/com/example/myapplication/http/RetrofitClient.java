package com.example.myapplication.http; // 替换为你的包名

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    // !! 将 "YOUR_SERVER_IP" 替换为你的 Python 服务器 IP 地址和端口 !!
    // 模拟器访问宿主机: "http://10.0.2.2:5000/"
    // 同一局域网内的设备: "http://<你的电脑IP>:5000/"
    private static final String BASE_URL = "http://YOUR_SERVER_IP:5000/";

    private static Retrofit retrofit = null;
    private static ApiService apiService = null;

    private static Retrofit getClient() {
        if (retrofit == null) {
            // 添加日志拦截器 (可选, 用于调试)
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client) // 使用带拦截器的 OkHttpClient
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit;
    }

    public static ApiService getApiService() {
        if (apiService == null) {
            apiService = getClient().create(ApiService.class);
        }
        return apiService;
    }
}