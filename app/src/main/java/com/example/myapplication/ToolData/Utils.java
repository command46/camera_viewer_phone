package com.example.myapplication.utils;

import android.content.Context;
import android.graphics.Color;
import android.util.Patterns; // 导入 Android 的 Patterns 类

import java.util.Random;

/**
 * 包含各种辅助静态方法的工具类。
 */
public class Utils {

    // 用于生成随机数的实例
    private static final Random random = new Random();

    /**
     * 辅助方法：将 dp 单位的值转换为像素 (px) 值。
     * @param context 上下文，用于获取显示密度。
     * @param dp 需要转换的 dp 值。
     * @return 对应的像素值。
     */
    public static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density; // 获取屏幕密度
        return Math.round((float) dp * density); // 四舍五入计算像素值
    }

    /**
     * 获取随机的粒子颜色。
     * 提供了几种预设颜色供选择。
     * @return 一个随机选择的颜色整数值 (Color int)。
     */
    public static int getRandomParticleColor() {
        // 预设的颜色数组
        int[] colors = {
                Color.WHITE,        // 白色
                Color.YELLOW,       // 黄色
                0xFFFFA500, // 橙色 (Orange)
                0xFFFFE4B5  // 杏仁白 (Moccasin)
        };
        // 从数组中随机返回一个颜色
        return colors[random.nextInt(colors.length)];
    }

    /**
     * 验证给定的字符串是否是有效的 IPv4 地址格式。
     * @param ip 需要验证的 IP 地址字符串。
     * @return 如果 IP 地址格式有效则返回 true，否则返回 false。
     */
    public static boolean isValidIpAddress(String ip) {
        // IP 地址不能为空
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        // 使用 Android 内置的 Patterns 类进行 IP 地址格式匹配
        return Patterns.IP_ADDRESS.matcher(ip).matches();
    }
}
