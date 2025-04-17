package com.example.myapplication.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.myapplication.R; // 确保 R 文件被正确导入
import java.util.Random;

/**
 * 负责处理应用中复杂的 UI 动画效果。
 * 将动画逻辑从 Activity 中分离出来。
 */
public class AnimationHelper {

    private static final String TAG = "AnimationHelper"; // 日志标签
    private final Context context; // 上下文，用于加载资源、获取服务等
    private final Random random = new Random(); // 用于动画中的随机效果

    /**
     * 构造函数。
     * @param context 应用上下文。
     */
    public AnimationHelper(Context context) {
        this.context = context;
    }

    /**
     * 播放全屏爆炸动画、粒子、冲击波和爱心效果 (浮夸版)。
     * @param rootView 动画效果需要添加到的根视图容器 (通常是 Activity 的 DecorView 或根布局)。
     * @param anchorView (可选) 动画效果的触发源视图，例如用于确定粒子效果的起始位置。
     */
    public void playExplosionAnimation(ViewGroup rootView, View anchorView) {
        // 检查根视图是否为空
        if (rootView == null) {
            Log.e(TAG, "Root view cannot be null for animation");
            return;
        }

        // --- 避免重复播放动画 ---
        // 通过查找特定 ID 的视图来判断动画是否已在进行中
        if (rootView.findViewById(R.id.explosionOverlayRoot) != null) {
             Log.w(TAG, "动画已在进行中，忽略此次触发");
             return;
        }

        // 加载动画的覆盖层布局
        final View overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_explosion, rootView, false);
        // 获取覆盖层布局中的各个视图元素
        final FrameLayout overlayRoot = overlayView.findViewById(R.id.explosionOverlayRoot); // 动画根容器
        final TextView explosionText = overlayView.findViewById(R.id.explosionTextView);   // “爽”字 TextView
        final ImageView shockwaveImageView = overlayView.findViewById(R.id.shockwaveImageView); // 冲击波 ImageView

        // 将覆盖层添加到根视图
        rootView.addView(overlayView);

        // --- 定义动画参数 ---
        long textAnimDuration = 800;        // "爽"字动画时长
        long textAppearDelay = 100;         // "爽"字出现延迟
        long particleAnimBaseDuration = 1500; // 粒子动画基础时长
        long shockwaveDelay = textAnimDuration / 2 + textAppearDelay; // 冲击波延迟 (在"爽"字放大一半时开始)
        long shockwaveDuration = 1000;      // 冲击波动画时长
        long heartStartDelay = shockwaveDelay + shockwaveDuration / 3; // 爱心动画延迟 (冲击波扩散后开始)
        long heartAnimBaseDuration = 2500;   // 爱心动画基础时长 (更长)
        long maxParticleDuration = (long)(particleAnimBaseDuration * 1.3); // 最大粒子动画时长
        long maxHeartDuration = (long)(heartAnimBaseDuration * 1.3);     // 最大爱心动画时长
        // 计算覆盖层需要保持显示的总时长，确保所有动画都能播放完毕
        long overlayRemovalDelay = Math.max(shockwaveDelay + shockwaveDuration, heartStartDelay + maxHeartDuration) + 300;

        // (可选) 1. 背景层动画 - 当前版本主要依赖冲击波和爱心填充背景，可以简化或移除独立背景动画
        // AnimatorSet bgSet = new AnimatorSet();
        // ... (背景动画代码)

        // 2. "爽" 字动画 (放大、弹出效果)
        explosionText.animate()
             .setStartDelay(textAppearDelay) // 设置启动延迟
             .alpha(1f)                 // 淡入
             .scaleX(1.5f)              // X轴放大
             .scaleY(1.5f)              // Y轴放大
             .setDuration(textAnimDuration) // 设置动画时长
             .setInterpolator(new OvershootInterpolator(2f)) // 使用 Overshoot 插值器产生弹出效果
             .setListener(new AnimatorListenerAdapter() { // 设置动画结束监听器
                  @Override
                  public void onAnimationEnd(Animator animation) {
                      explosionText.setVisibility(View.GONE); // 动画结束后隐藏"爽"字
                      // 触发粒子效果，使用传入的 anchorView 或 "爽"字本身作为粒子起点
                      View particleSource = (anchorView != null) ? anchorView : explosionText;
                      createAndAnimateParticles(particleSource, overlayRoot, particleAnimBaseDuration);
                  }
             })
             .start(); // 启动动画

        // 3. 粉色冲击波动画 (延迟启动)
        overlayRoot.postDelayed(() -> { // 使用 postDelayed 实现延迟执行
             startShockwaveAnimation(shockwaveImageView, overlayRoot, shockwaveDuration);
             // if (!bgSet.isStarted()) bgSet.start(); // 如果需要独立背景动画，在此处启动
        }, shockwaveDelay); // 设置延迟时间

        // 4. 爱心喷泉动画 (更晚延迟启动)
         overlayRoot.postDelayed(() -> {
             createAndAnimateHearts(overlayRoot, heartAnimBaseDuration);
         }, heartStartDelay);


        // 5. 延迟移除整个动画覆盖层
        rootView.postDelayed(() -> {
             // 再次查找覆盖层视图，确保它仍然存在
             View overlayToRemove = rootView.findViewById(R.id.explosionOverlayRoot);
             if (overlayToRemove != null && overlayToRemove.getParent() instanceof ViewGroup) {
                 // 从父视图中移除覆盖层
                 ((ViewGroup)overlayToRemove.getParent()).removeView(overlayToRemove);
                 Log.d(TAG,"浮夸动画覆盖层已移除 (延迟)");
             } else {
                 Log.w(TAG,"尝试移除覆盖层，但未找到或已移除");
             }
        }, overlayRemovalDelay); // 设置移除的延迟时间

        Log.d(TAG,"开始播放浮夸版爆炸动画 (粒子+冲击波+爱心)");
    }


    /**
     * 启动粉色冲击波动画效果。
     * @param shockwaveView 用于显示冲击波效果的 ImageView。
     * @param container 冲击波动画所在的父容器。
     * @param duration 冲击波动画的持续时间。
     */
    private void startShockwaveAnimation(ImageView shockwaveView, ViewGroup container, long duration) {
       // 检查视图和容器是否有效
       if (shockwaveView == null || container == null || shockwaveView.getDrawable() == null) return;

        // 设置冲击波视图可见，并设置初始状态（透明度、缩放）
        shockwaveView.setVisibility(View.VISIBLE);
        shockwaveView.setAlpha(0.8f); // 初始透明度
        shockwaveView.setScaleX(0.1f); // 初始缩放比例
        shockwaveView.setScaleY(0.1f);

        // 计算冲击波需要放大的最终比例，使其能覆盖整个容器
        // 基于容器尺寸和冲击波图片自身尺寸计算
        float maxScale = Math.max(
                (float)container.getWidth() / shockwaveView.getDrawable().getIntrinsicWidth(),
                (float)container.getHeight() / shockwaveView.getDrawable().getIntrinsicHeight()
        ) * 1.1f; // 乘以一个系数确保完全覆盖，并稍微超出边界


        // 创建 AnimatorSet 来组合缩放和透明度动画
        AnimatorSet shockwaveSet = new AnimatorSet();
        // X轴缩放动画：从 0.1 放大到 maxScale
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(shockwaveView, View.SCALE_X, 0.1f, maxScale);
        // Y轴缩放动画：从 0.1 放大到 maxScale
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(shockwaveView, View.SCALE_Y, 0.1f, maxScale);
        // 透明度动画：从 0.8 淡出到 0
        ObjectAnimator alpha = ObjectAnimator.ofFloat(shockwaveView, View.ALPHA, 0.8f, 0f);

        // 同时播放缩放和透明度动画
        shockwaveSet.playTogether(scaleX, scaleY, alpha);
        shockwaveSet.setDuration(duration); // 设置动画总时长
        shockwaveSet.setInterpolator(new AccelerateInterpolator(1.5f)); // 使用加速插值器，使扩散效果更明显
        shockwaveSet.addListener(new AnimatorListenerAdapter() { // 添加动画结束监听
            @Override
            public void onAnimationEnd(Animator animation) {
                shockwaveView.setVisibility(View.GONE); // 动画结束后隐藏冲击波视图
            }
        });
        shockwaveSet.start(); // 启动动画
        Log.d(TAG, "冲击波动画开始");
    }

    /**
     * 创建并播放爱心喷泉动画效果。
     * @param container 爱心动画所在的父容器。
     * @param baseDuration 爱心动画的基础持续时间（每个爱心会有随机变化）。
     */
    private void createAndAnimateHearts(ViewGroup container, long baseDuration) {
        // 检查容器是否有效
        if (container == null) return;

        // --- 爱心动画参数 ---
        int heartCount = 100; // 生成的爱心数量
        int minHeartSize = Utils.dpToPx(context, 15); // 爱心最小尺寸 (dp转px)
        int maxHeartSize = Utils.dpToPx(context, 35); // 爱心最大尺寸 (dp转px)
        long maxStaggerDelay = 800; // 最大启动延迟，使爱心出现时间更分散

        // 获取容器尺寸
        int containerWidth = container.getWidth();
        int containerHeight = container.getHeight();
        // 设置爱心起始位置在容器底部中心区域
        float startXBase = containerWidth / 2f; // X轴基准位置
        float startY = containerHeight - Utils.dpToPx(context, 20); // Y轴位置 (离底部一段距离)
        float startXVariance = containerWidth * 0.1f; // X轴起始位置的随机范围

        Log.d(TAG, "创建爱心喷泉");

        // 循环创建并启动每个爱心的动画
        for (int i = 0; i < heartCount; i++) {
            // 创建 ImageView 用于显示爱心
            ImageView heart = new ImageView(context);
            heart.setImageResource(R.drawable.ic_heart); // 设置爱心图片资源
            // 随机设置爱心颜色 (粉色或白色)
            heart.setColorFilter(random.nextBoolean() ? Color.WHITE : Color.parseColor("#FF69B4")); // HotPink

            // 随机设置爱心尺寸
            int heartSize = random.nextInt(maxHeartSize - minHeartSize + 1) + minHeartSize;
            // 创建布局参数
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(heartSize, heartSize);
            // 将爱心添加到容器中
            container.addView(heart, params);

            // 设置爱心的随机起始位置 (基于底部中心区域) 和初始状态
            float startX = startXBase + (random.nextFloat() * 2f - 1f) * startXVariance; // 随机 X 位置
            heart.setX(startX - heartSize / 2f); // 设置 X 坐标 (中心对齐)
            heart.setY(startY - heartSize / 2f); // 设置 Y 坐标 (中心对齐)
            heart.setAlpha(0f); // 初始状态完全透明
            heart.setRotation(random.nextFloat() * 60 - 30); // 初始随机倾斜角度

            // --- 单个爱心动画参数 ---
            // 随机化动画时长 (基础时长的 80% 到 130%)
            long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8));
            // 计算启动延迟，使爱心按顺序、分散地出现
            long startDelay = (long) (((float)i / heartCount) * maxStaggerDelay);
            // 设置动画目标 Y 坐标 (飘出屏幕顶部)
            float targetY = -heartSize;
            // 设置水平漂移距离 (随机左右漂移)
            float horizontalDrift = (random.nextFloat() * 2f - 1f) * (containerWidth * 0.6f);

            // --- 使用 ViewPropertyAnimator 启动爱心动画 ---
            heart.animate()
                .setStartDelay(startDelay) // 设置启动延迟
                .alpha(1f)                 // 淡入
                .translationY(targetY)     // 向上移动到目标位置
                .translationXBy(horizontalDrift) // 同时进行水平漂移
                .rotationBy(random.nextFloat() * 360 - 180) // 向上移动时随机旋转
                .setDuration(duration)     // 设置动画时长
                .setInterpolator(new LinearInterpolator()) // 使用线性插值器 (匀速或慢速减速漂浮感)
                .setListener(new AnimatorListenerAdapter() { // 添加动画监听器
                    // (可选) 可以在动画开始时添加效果，例如小的缩放脉冲
                    // @Override
                    // public void onAnimationStart(Animator animation) { ... }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // 当主要的向上移动动画结束时，启动一个快速淡出动画
                        heart.animate()
                             .alpha(0f) // 淡出至完全透明
                             .setDuration(duration / 4) // 淡出时间为总时长的 1/4
                             .setInterpolator(new AccelerateInterpolator()) // 加速淡出
                             .setListener(new AnimatorListenerAdapter() { // 添加淡出动画结束监听
                                 @Override
                                 public void onAnimationEnd(Animator animation) {
                                     // 最终动画结束后，从父容器中移除爱心视图，避免内存泄漏
                                     if (heart.getParent() != null) {
                                         container.removeView(heart);
                                     }
                                 }
                             })
                             .start(); // 启动淡出动画
                    }
                })
                .withLayer() // (可选) 尝试使用硬件层加速动画，可能提高性能但会增加内存消耗
                .start(); // 启动主要的爱心动画
        }
    }

    /**
     * 创建并播放粒子爆炸动画效果。
     * @param sourceView 动画效果的触发源视图，用于确定粒子效果的起始位置。
     * @param container 粒子动画所在的父容器。
     * @param baseDuration 粒子动画的基础持续时间（每个粒子会有随机变化）。
     */
    private void createAndAnimateParticles(View sourceView, ViewGroup container, long baseDuration) {
        // 检查源视图和容器是否有效
        if (sourceView == null || container == null) return;

        // --- 粒子动画参数 ---
        int particleCount = 40; // 生成的粒子数量 (可调整以优化性能)
        int minParticleSize = Utils.dpToPx(context, 3); // 粒子最小尺寸
        int maxParticleSize = Utils.dpToPx(context, 8); // 粒子最大尺寸

        // 获取粒子动画的起始坐标 (源视图的中心)
        int[] sourcePos = new int[2];
        // 使用 getLocationInWindow 而不是 getLocationOnScreen，因为动画是添加到 overlay 层
        sourceView.getLocationInWindow(sourcePos);
        float startX = sourcePos[0] + sourceView.getWidth() / 2f;
        float startY = sourcePos[1] + sourceView.getHeight() / 2f;

        // 获取屏幕尺寸，用于计算粒子的运动范围
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;
        // 计算粒子运动的最大和最小距离
        float maxDistance = Math.max(screenWidth, screenHeight) * 0.7f;
        float minDistance = maxDistance * 0.3f;
        // 最大启动延迟，使粒子分散出现
        long maxStaggerDelay = 450;

        Log.d(TAG, "创建粒子 (增强版)，起点: (" + startX + ", " + startY + ")");

        // 循环创建并启动每个粒子的动画
        for (int i = 0; i < particleCount; i++) {
             // 创建一个简单的 View 作为粒子
             View particle = new View(context);
             particle.setBackgroundColor(Utils.getRandomParticleColor()); // 设置随机背景色
             // 随机设置粒子尺寸
             int particleSize = random.nextInt(maxParticleSize - minParticleSize + 1) + minParticleSize;
             // 创建布局参数
             FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(particleSize, particleSize);
             // 将粒子添加到容器中
             container.addView(particle, params);

             // 设置粒子的初始位置 (起点) 和状态
             particle.setX(startX - particleSize / 2f); // 中心对齐 X
             particle.setY(startY - particleSize / 2f); // 中心对齐 Y
             particle.setAlpha(1f); // 初始不透明
             // 初始稍微放大一点，增加视觉效果
             particle.setScaleX(1.2f);
             particle.setScaleY(1.2f);

             // --- 计算单个粒子的动画目标 ---
             // 随机生成运动角度和距离
             double angle = random.nextDouble() * 2 * Math.PI; // 随机角度 (0 到 2PI)
             float distance = random.nextFloat() * (maxDistance - minDistance) + minDistance; // 随机距离
             // 计算 X 和 Y 方向的位移量
             float translationX = (float) (distance * Math.cos(angle));
             float translationY = (float) (distance * Math.sin(angle));
             // 随机生成旋转角度
             float rotation = random.nextFloat() * 1080 - 540; // -540 到 +540 度

             // --- 单个粒子动画参数 ---
             // 随机化动画时长 (基础时长的 80% 到 130%)
             long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8));
             // 计算启动延迟
             long startDelay = (long) (((float)i / particleCount) * maxStaggerDelay);

             // --- 使用 ViewPropertyAnimator 启动粒子动画 ---
             particle.animate()
                 .setStartDelay(startDelay) // 设置启动延迟
                 .translationXBy(translationX) // X 方向位移
                 .translationYBy(translationY) // Y 方向位移
                 .alpha(0f)                 // 动画过程中淡出
                 .rotationBy(rotation)       // 同时旋转
                 .scaleX(0.7f)              // 同时缩小 X
                 .scaleY(0.7f)              // 同时缩小 Y
                 .setDuration(duration)     // 设置动画时长
                 .setInterpolator(new DecelerateInterpolator(1.5f)) // 使用减速插值器
                 .setListener(new AnimatorListenerAdapter() { // 添加动画结束监听
                     @Override
                     public void onAnimationEnd(Animator animation) {
                         // 动画结束后，从父容器中移除粒子视图
                         if (particle.getParent() != null) {
                             container.removeView(particle);
                         }
                     }
                 })
                 .withLayer() // (可选) 尝试使用硬件层加速
                 .start(); // 启动动画
        }
    }
}
