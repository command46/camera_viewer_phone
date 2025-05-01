package com.example.myapplication.http;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 用于从 Gitee 获取文件内容的类。
 * 使用 OkHttp 库进行网络请求。
 */
public class GiteeContentFetcher {

    // OkHttpClient 实例，建议在应用中全局共享一个实例
    private final OkHttpClient client = new OkHttpClient();
    // 用于将结果回调到主线程
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 定义回调接口，用于处理获取结果
     */
    public interface FetchCallback {
        /**
         * 成功获取内容时调用
         * @param content 获取到的文件内容字符串
         */
        void onSuccess(String content);

        /**
         * 获取失败时调用
         * @param e 发生的异常
         */
        void onFailure(Exception e);
    }

    /**
     * 异步获取指定 URL 的内容
     *
     * @param urlString 要获取内容的 URL (注意：通常需要使用 'raw' 链接才能获取原始文件内容)
     * @param callback  结果回调
     */
    public void fetchContent(String urlString, final FetchCallback callback) {
        // 检查回调是否为空
        if (callback == null) {
            // 可以选择抛出异常或记录日志
            System.err.println("FetchCallback cannot be null.");
            return;
        }

        // 创建请求对象
        Request request = new Request.Builder()
                .url(urlString)
                .get() // GET 请求
                .build();

        // 使用 OkHttp 的异步执行方法 enqueue
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // 网络请求失败，通过 Handler 将错误回调到主线程
                mainHandler.post(() -> callback.onFailure(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                // 注意：OkHttp 的回调是在后台线程执行的

                // 使用 try-with-resources 确保 ResponseBody 被关闭
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        // 如果服务器返回非 2xx 状态码
                        final IOException exception = new IOException("Unexpected code " + response);
                        mainHandler.post(() -> callback.onFailure(exception));
                        return; // 提前返回
                    }

                    if (responseBody == null) {
                        // 如果响应体为空
                        final IOException exception = new IOException("Response body is null");
                        mainHandler.post(() -> callback.onFailure(exception));
                        return; // 提前返回
                    }

                    // 读取响应体内容为字符串
                    final String content = responseBody.string();

                    // 通过 Handler 将成功结果回调到主线程
                    mainHandler.post(() -> callback.onSuccess(content));

                } catch (IOException e) {
                    // 处理读取响应体时可能发生的 IOException
                    mainHandler.post(() -> callback.onFailure(e));
                }
            }
        });
    }
}