// Utils.java
package com.example.myapplication.utils;

import android.content.Context;
import android.graphics.Color;
import android.util.Patterns;

import java.util.Random;

public class Utils {

    private static final Random random = new Random();

    /**
     * 辅助方法：将 dp 转换为 px
     */
    public static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    /**
     * 获取随机的粒子颜色 (示例：白色、黄色、橙色系)
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
    * 验证 IP 地址格式是否有效 (注意：你的原始代码逻辑是反的，这里修正了)
    * @param ip IP 地址字符串
    * @return true 如果有效，false 如果无效
    */
    public static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        // 使用 Android 的 Patterns 类进行验证
        return Patterns.IP_ADDRESS.matcher(ip).matches();
    }
}
