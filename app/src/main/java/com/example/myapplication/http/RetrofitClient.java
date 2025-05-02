package com.example.myapplication.http; // 替换为你的包名

import static com.example.myapplication.MainActivity.KEY_IP_ADDRESS;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.myapplication.ToolData.JsonDataStorage;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    private static final String TAG = "RetrofitClient";
    private static final String DEFAULT_IP_PLACEHOLDER = "NOT_SET"; // 用于内部标记未设置状态

    private static volatile Retrofit retrofit = null; // volatile 保证多线程可见性
    private static volatile ApiService apiService = null;
    private static String currentBaseUrl = DEFAULT_IP_PLACEHOLDER; // 存储当前使用的 Base URL

    // 私有构造函数防止外部实例化
    private RetrofitClient() {}

    /**
     * 获取 ApiService 实例。
     * 第一次调用时会进行初始化，需要传入有效的 Context。
     * 后续调用（如果 Base URL 未改变）将直接返回缓存的实例。
     *
     * @param context 用于读取配置（IP地址）的 Context。推荐使用 Application Context。
     * @return ApiService 实例
     * @throws IllegalStateException 如果无法获取有效的 Base URL
     */
    public static ApiService getApiService(Context context) {
        // 双重检查锁定 (Double-Checked Locking) 优化性能
        if (apiService == null) {
            synchronized (RetrofitClient.class) {
                if (apiService == null) {
                    // --- 初始化逻辑 ---
                    // 从存储中获取 IP 地址，并拼接端口号 (确保端口在这里处理)
                    String savedIp = JsonDataStorage.getString(context.getApplicationContext(), KEY_IP_ADDRESS, null);
                    if (TextUtils.isEmpty(savedIp) || savedIp.equalsIgnoreCase("null")) { // 添加对 "null" 字符串的检查
                        Log.e(TAG, "无法获取有效的 IP 地址，请先在设置中配置。 Key: " + KEY_IP_ADDRESS);
                        throw new IllegalStateException("未配置有效的服务器 IP 地址。请在应用设置中配置。");
                    }

                    String ipWithPort = savedIp + ":5000"; // 拼接端口
                    Log.d(TAG, "读取存储的 IP 地址并拼接端口: " + ipWithPort);

                    // 构建 Base URL (确保格式正确，例如以 / 结尾)
                    String newBaseUrl = formatBaseUrl(ipWithPort);
                    Log.i(TAG, "构建 Base URL: " + newBaseUrl);

                    // 创建 Retrofit 实例
                    retrofit = buildRetrofitInstance(newBaseUrl);
                    apiService = retrofit.create(ApiService.class);
                    currentBaseUrl = newBaseUrl; // 记录当前使用的 URL
                    Log.i(TAG, "Retrofit 和 ApiService 初始化完成。 Base URL: " + currentBaseUrl);
                }
            }
        } else {
            // Log.v(TAG, "当前使用的 Base URL: " + currentBaseUrl); // 减少 V 级日志
            Log.d(TAG, "返回已缓存的 ApiService 实例。Base URL: " + currentBaseUrl);
        }
        return apiService;
    }

    /**
     * 构建 Retrofit 实例的具体逻辑
     * @param baseUrl 服务器的基础 URL
     * @return Retrofit 实例
     */
    private static Retrofit buildRetrofitInstance(String baseUrl) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        // --- 关键修改：将日志级别从 BODY 改为 HEADERS ---
        // 设置为 Level.BODY 会在上传大文件时读取整个文件到内存导致 OOM
        // Level.HEADERS 只记录请求/响应行和头部，足够调试且安全
        logging.setLevel(HttpLoggingInterceptor.Level.HEADERS); // <-- 修改此处

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging) // 添加日志拦截器
                .connectTimeout(30, TimeUnit.SECONDS) // 连接超时
                .readTimeout(60, TimeUnit.SECONDS)    // 读取超时 (上传大文件可能需要更长时间)
                .writeTimeout(60, TimeUnit.SECONDS)   // 写入超时 (上传大文件可能需要更长时间)
                .build();

        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client) // 使用配置好的 OkHttpClient
                .addConverterFactory(GsonConverterFactory.create()) // 使用 Gson 解析 JSON
                .build();
    }

    /**
     * 格式化 Base URL，确保以 "http://" 开头，并以 "/" 结尾。
     * @param ipOrUrl 输入的 IP 地址或 IP:端口
     * @return 格式化的 Base URL (例如 "http://192.168.1.100:5000/")
     */
    private static String formatBaseUrl(String ipOrUrl) {
        if (TextUtils.isEmpty(ipOrUrl)) {
            Log.e(TAG, "formatBaseUrl 接收到空的 ipOrUrl");
            return ""; // 或者抛出异常
        }
        String url = ipOrUrl.trim();
        // 检查是否已包含协议头，如果没有，则添加 http://
        if (!url.matches("^(http|https)://.*")) {
            url = "http://" + url;
        }
        // 确保以 "/" 结尾
        if (!url.endsWith("/")) {
            url += "/";
        }
        return url;
    }

    /**
     * (可选) 如果需要支持动态更新 IP 地址，可以添加此方法。
     * 调用此方法会清除缓存的实例，下次调用 getApiService 时会重新初始化。
     *
     * @param context 用于读取新 IP 的 Context
     * @param newIp 新的 IP 地址 (不含端口)
     */
    public static synchronized void updateBaseUrl(Context context, String newIp) {
        if (TextUtils.isEmpty(newIp) || newIp.equalsIgnoreCase("null")){
            Log.w(TAG, "尝试更新 Base URL，但新 IP 无效或为 'null'。");
            return;
        }
        String newIpWithPort = newIp + ":5000"; // 拼接端口
        String newBaseUrl = formatBaseUrl(newIpWithPort);

        if (!newBaseUrl.equals(currentBaseUrl)) {
            Log.i(TAG, "Base URL 发生变化，清除旧实例。旧: " + currentBaseUrl + ", 新: " + newBaseUrl);
            retrofit = null;
            apiService = null;
            currentBaseUrl = DEFAULT_IP_PLACEHOLDER; // 重置状态，强制下次重新初始化
            // 注意：更新配置后，需要调用 getApiService(context) 才能使新配置生效
        } else {
            Log.d(TAG, "尝试更新 Base URL，但与当前 URL 相同 ("+ currentBaseUrl +")，无需操作。");
        }
    }

    /**
     * (可选) 获取当前配置的 Base URL (主要用于调试或显示)
     * @return 当前使用的 Base URL，如果未初始化则返回 null
     */
    public static String getCurrentBaseUrl() {
        return (currentBaseUrl.equals(DEFAULT_IP_PLACEHOLDER)) ? null : currentBaseUrl;
    }
}