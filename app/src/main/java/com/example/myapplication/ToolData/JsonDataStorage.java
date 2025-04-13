package com.example.myapplication.ToolData;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 使用 JSON 格式在本地文件存储数据的工具类
 */
public class JsonDataStorage {

    private static final String TAG = "JsonDataStorage";
    private static final String FILE_NAME = "app_data_storage.json"; // 存储数据的文件名

    // 使用 Gson 进行 JSON 操作
    // 创建一个漂亮的打印格式，方便调试查看 JSON 文件
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // 定义存储结构对应的 Java 类
    private static class StorageContainer {
        Map<String, Integer> numbers = new HashMap<>();
        Map<String, Boolean> booleans = new HashMap<>();
        // Key: "年-月-日" (String), Value: RecordData 对象
        Map<String, RecordData> records = new HashMap<>();
    }

    // --- 内部辅助方法 ---

    /**
     * 从文件加载整个存储容器
     * @param context Context 对象，用于访问文件系统
     * @return 加载到的 StorageContainer 对象，如果文件不存在或出错则返回新的空对象
     */
    private static StorageContainer loadStorageContainer(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return new StorageContainer(); // 文件不存在，返回新的空容器
        }

        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isr)) {

            // 使用 Gson 将 JSON 字符串反序列化为 StorageContainer 对象
            StorageContainer container = gson.fromJson(reader, StorageContainer.class);
            // 如果文件是空的或者 JSON 无效，gson.fromJson 可能返回 null
            return (container != null) ? container : new StorageContainer();

        } catch (IOException e) {
            Log.e(TAG, "Error reading storage file: " + file.getAbsolutePath(), e);
            return new StorageContainer(); // 出错时返回空容器
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "Error parsing JSON from file: " + file.getAbsolutePath(), e);
            // 可以选择删除损坏的文件或返回空容器
            // deleteCorruptedFile(file);
            return new StorageContainer(); // JSON 格式错误，返回空容器
        }
    }

    /**
     * 将整个存储容器保存到文件
     * @param context Context 对象，用于访问文件系统
     * @param container 要保存的 StorageContainer 对象
     * @return true 如果保存成功，否则 false
     */
    private static boolean saveStorageContainer(Context context, StorageContainer container) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file); // 会覆盖旧文件
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {

            // 使用 Gson 将 StorageContainer 对象序列化为 JSON 字符串并写入文件
            gson.toJson(container, writer);
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Error writing storage file: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    // --- 公共 API 方法 ---

    // --- 数字 (Integer) 操作 ---

    /**
     * 保存一个整数值
     * @param context Context
     * @param key 键
     * @param value 值
     */
    public static void saveInt(Context context, String key, int value) {
        StorageContainer container = loadStorageContainer(context);
        if (container.numbers == null) { // 防御性编程，确保 map 存在
            container.numbers = new HashMap<>();
        }
        container.numbers.put(key, value);
        saveStorageContainer(context, container);
    }

    /**
     * 获取一个整数值
     * @param context Context
     * @param key 键
     * @param defaultValue 如果键不存在时返回的默认值
     * @return 存储的值或默认值
     */
    public static int getInt(Context context, String key, int defaultValue) {
        StorageContainer container = loadStorageContainer(context);
        if (container.numbers != null && container.numbers.containsKey(key)) {
            // Gson 会确保从 JSON 加载的是数字，但以防万一，检查 null
            Integer value = container.numbers.get(key);
            return (value != null) ? value : defaultValue;
        }
        return defaultValue;
    }

    /**
     * 移除一个整数值
     * @param context Context
     * @param key 键
     */
    public static void removeInt(Context context, String key) {
        StorageContainer container = loadStorageContainer(context);
        if (container.numbers != null) {
            container.numbers.remove(key);
            saveStorageContainer(context, container);
        }
    }

    // --- 布尔值 (Boolean) 操作 ---

    /**
     * 保存一个布尔值
     * @param context Context
     * @param key 键
     * @param value 值
     */
    public static void saveBoolean(Context context, String key, boolean value) {
        StorageContainer container = loadStorageContainer(context);
        if (container.booleans == null) {
            container.booleans = new HashMap<>();
        }
        container.booleans.put(key, value);
        saveStorageContainer(context, container);
    }

    /**
     * 获取一个布尔值
     * @param context Context
     * @param key 键
     * @param defaultValue 如果键不存在时返回的默认值
     * @return 存储的值或默认值
     */
    public static boolean getBoolean(Context context, String key, boolean defaultValue) {
        StorageContainer container = loadStorageContainer(context);
        if (container.booleans != null && container.booleans.containsKey(key)) {
            Boolean value = container.booleans.get(key);
            return (value != null) ? value : defaultValue;
        }
        return defaultValue;
    }

    /**
     * 移除一个布尔值
     * @param context Context
     * @param key 键
     */
    public static void removeBoolean(Context context, String key) {
        StorageContainer container = loadStorageContainer(context);
        if (container.booleans != null) {
            container.booleans.remove(key);
            saveStorageContainer(context, container);
        }
    }

    // --- 特定记录 (RecordData) 操作 ---

    /**
     * 保存一条特定日期的记录
     * @param context Context
     * @param dateKey "年-月-日" 格式的字符串键
     * @param count 次数
     * @param pleasureLevel 爽感等级
     */
    public static void saveRecord(Context context, String dateKey, int count, int pleasureLevel) {
        StorageContainer container = loadStorageContainer(context);
        if (container.records == null) {
            container.records = new HashMap<>();
        }
        RecordData record = new RecordData(count, pleasureLevel);
        container.records.put(dateKey, record);
        saveStorageContainer(context, container);
    }

    /**
     * 保存一条特定日期的记录 (使用 RecordData 对象)
     * @param context Context
     * @param dateKey "年-月-日" 格式的字符串键
     * @param record RecordData 对象
     */
    public static void saveRecord(Context context, String dateKey, RecordData record) {
        if (record == null) {
            Log.w(TAG, "Attempted to save a null record for key: " + dateKey);
            return;
        }
        StorageContainer container = loadStorageContainer(context);
        if (container.records == null) {
            container.records = new HashMap<>();
        }
        container.records.put(dateKey, record);
        saveStorageContainer(context, container);
    }


    /**
     * 获取一条特定日期的记录
     * @param context Context
     * @param dateKey "年-月-日" 格式的字符串键
     * @return 找到的 RecordData 对象，如果键不存在则返回 null
     */
    public static RecordData getRecord(Context context, String dateKey) {
        StorageContainer container = loadStorageContainer(context);
        if (container.records != null) {
            return container.records.get(dateKey); // .get() 会在 key 不存在时返回 null
        }
        return null;
    }

    /**
     * 获取所有存储的记录
     * @param context Context
     * @return 包含所有记录的 Map<String, RecordData>，可能为空 Map，但不会是 null。
     */
    public static Map<String, RecordData> getAllRecords(Context context) {
        StorageContainer container = loadStorageContainer(context);
        if (container.records == null) {
            return new HashMap<>(); // 返回空 Map
        }
        // 返回容器中 records map 的一个副本，防止外部修改影响内部状态
        return new HashMap<>(container.records);
    }

    /**
     * 移除一条特定日期的记录
     * @param context Context
     * @param key "年-月-日" 格式的字符串键
     */
    public static void removeRecord(Context context, String key) {
        StorageContainer container = loadStorageContainer(context);
        if (container.records != null) {
            container.records.remove(key);
            saveStorageContainer(context, container);
        }
    }

    // --- 清除操作 ---

    /**
     * 清除所有存储的数据 (删除文件)
     * @param context Context
     * @return true 如果文件成功删除或文件本就不存在，否则 false
     */
    public static boolean clearAllData(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) {
            if (file.delete()) {
                Log.i(TAG, "Storage file deleted successfully: " + file.getAbsolutePath());
                return true;
            } else {
                Log.e(TAG, "Failed to delete storage file: " + file.getAbsolutePath());
                return false;
            }
        }
        return true; // 文件本就不存在，也算清除成功
    }

    /**
     * (可选) 删除损坏的 JSON 文件
     * @param file 要删除的文件
     */
    private static void deleteCorruptedFile(File file) {
        if (file.exists()) {
            if (file.delete()) {
                Log.w(TAG, "Deleted corrupted storage file: " + file.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to delete corrupted storage file: " + file.getAbsolutePath());
            }
        }
    }
}
/*
  // 在你的 Activity 或 Service 中 (需要 Context)

  // 保存数据
  JsonDataStorage.saveInt(this, "userLevel", 10);
  JsonDataStorage.saveBoolean(this, "tutorialCompleted", true);
  JsonDataStorage.saveRecord(this, "2025-04-13", 5, 8); // 日期 "2025-04-13", 次数 5, 爽感 8

  // 或者创建 RecordData 对象保存
  RecordData todayRecord = new RecordData(3, 7);
  JsonDataStorage.saveRecord(this, "2025-04-14", todayRecord);


  // 读取数据
  int level = JsonDataStorage.getInt(this, "userLevel", 1); // 默认值 1
  boolean completed = JsonDataStorage.getBoolean(this, "tutorialCompleted", false); // 默认值 false
  RecordData record = JsonDataStorage.getRecord(this, "2025-04-13");

  if (record != null) {
      int count = record.getCount();
      int pleasure = record.getPleasureLevel();
      Log.d("MyApp", "Record for 2025-04-13: Count=" + count + ", Pleasure=" + pleasure);
  } else {
      Log.d("MyApp", "No record found for 2025-04-13");
  }

  // 获取所有记录
  Map<String, RecordData> allRecords = JsonDataStorage.getAllRecords(this);
  for (Map.Entry<String, RecordData> entry : allRecords.entrySet()) {
      Log.d("MyApp", "Date: " + entry.getKey() + ", Data: " + entry.getValue().toString());
  }

  // 移除数据
  // JsonDataStorage.removeInt(this, "userLevel");
  // JsonDataStorage.removeRecord(this, "2025-04-13");

  // 清除所有数据
  // JsonDataStorage.clearAllData(this);
 */