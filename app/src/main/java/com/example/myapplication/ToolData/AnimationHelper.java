// AnimationHelper.java
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

public class AnimationHelper {

    private static final String TAG = "AnimationHelper";
    private final Context context;
    private final Random random = new Random();

    public AnimationHelper(Context context) {
        this.context = context;
    }

    /**
     * 播放全屏爆炸动画、粒子、冲击波和爱心效果 (浮夸版)
     */
    public void playExplosionAnimation(ViewGroup rootView, View anchorView) {
        // rootView: 动画添加到的根视图 (通常是 DecorView 或 Activity 的根布局)
        // anchorView: (可选) 动画的触发源，例如用于粒子效果的起始位置

        if (rootView == null) {
            Log.e(TAG, "Root view cannot be null for animation");
            return;
        }

        // --- 避免重复动画 ---
        if (rootView.findViewById(R.id.explosionOverlayRoot) != null) {
             Log.w(TAG, "动画已在进行中，忽略此次触发");
             return;
        }

        final View overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_explosion, rootView, false);
        final FrameLayout overlayRoot = overlayView.findViewById(R.id.explosionOverlayRoot);
        final TextView explosionText = overlayView.findViewById(R.id.explosionTextView);
        final ImageView shockwaveImageView = overlayView.findViewById(R.id.shockwaveImageView);

        rootView.addView(overlayView);

        long textAnimDuration = 800;
        long textAppearDelay = 100;
        long particleAnimBaseDuration = 1500;
        long shockwaveDelay = textAnimDuration / 2 + textAppearDelay;
        long shockwaveDuration = 1000;
        long heartStartDelay = shockwaveDelay + shockwaveDuration / 3;
        long heartAnimBaseDuration = 2500;
        long maxParticleDuration = (long)(particleAnimBaseDuration * 1.3);
        long maxHeartDuration = (long)(heartAnimBaseDuration * 1.3);
        long overlayRemovalDelay = Math.max(shockwaveDelay + shockwaveDuration, heartStartDelay + maxHeartDuration) + 300;

        // 1. 背景层动画 (现在由冲击波和爱心填充，本身可以不用动画或简单淡入淡出)
        AnimatorSet bgSet = new AnimatorSet();
        // ... (保持或简化背景动画逻辑)

        // 2. "爽" 字动画
        explosionText.animate()
             .setStartDelay(textAppearDelay)
             .alpha(1f)
             .scaleX(1.5f)
             .scaleY(1.5f)
             .setDuration(textAnimDuration)
             .setInterpolator(new OvershootInterpolator(2f))
             .setListener(new AnimatorListenerAdapter() {
                  @Override
                  public void onAnimationEnd(Animator animation) {
                      explosionText.setVisibility(View.GONE);
                      // 使用 anchorView (例如 counterTextView) 作为粒子起点
                      View particleSource = (anchorView != null) ? anchorView : explosionText;
                      createAndAnimateParticles(particleSource, overlayRoot, particleAnimBaseDuration);
                  }
             })
             .start();

        // 3. 粉色冲击波动画
        overlayRoot.postDelayed(() -> {
             startShockwaveAnimation(shockwaveImageView, overlayRoot, shockwaveDuration);
             // if (!bgSet.isStarted()) bgSet.start(); // 如果需要背景动画
        }, shockwaveDelay);

        // 4. 爱心喷泉动画
         overlayRoot.postDelayed(() -> {
             createAndAnimateHearts(overlayRoot, heartAnimBaseDuration);
         }, heartStartDelay);


        // 5. 延迟移除整个覆盖层
        rootView.postDelayed(() -> {
             View overlayToRemove = rootView.findViewById(R.id.explosionOverlayRoot);
             if (overlayToRemove != null && overlayToRemove.getParent() instanceof ViewGroup) {
                 ((ViewGroup)overlayToRemove.getParent()).removeView(overlayToRemove);
                 Log.d(TAG,"浮夸动画覆盖层已移除 (延迟)");
             } else {
                 Log.w(TAG,"尝试移除覆盖层，但未找到或已移除");
             }
        }, overlayRemovalDelay);

        Log.d(TAG,"开始播放浮夸版爆炸动画 (粒子+冲击波+爱心)");
    }


    private void startShockwaveAnimation(ImageView shockwaveView, ViewGroup container, long duration) {
       // ... (冲击波动画代码，使用 context 获取资源)
       if (shockwaveView == null || container == null) return;

        shockwaveView.setVisibility(View.VISIBLE);
        shockwaveView.setAlpha(0.8f);
        shockwaveView.setScaleX(0.1f);
        shockwaveView.setScaleY(0.1f);

        float maxScale = Math.max(container.getWidth(), container.getHeight()) * 1.5f / shockwaveView.getDrawable().getIntrinsicWidth(); // 基于图片大小计算缩放

        AnimatorSet shockwaveSet = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(shockwaveView, "scaleX", 0.1f, maxScale);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(shockwaveView, "scaleY", 0.1f, maxScale);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(shockwaveView, "alpha", 0.8f, 0f);

        shockwaveSet.playTogether(scaleX, scaleY, alpha);
        shockwaveSet.setDuration(duration);
        shockwaveSet.setInterpolator(new AccelerateInterpolator(1.5f));
        shockwaveSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                shockwaveView.setVisibility(View.GONE);
            }
        });
        shockwaveSet.start();
        Log.d(TAG, "冲击波动画开始");
    }

    private void createAndAnimateHearts(ViewGroup container, long baseDuration) {
        // ... (爱心动画代码，使用 context 获取资源和 Utils.dpToPx)
        if (container == null) return;

        int heartCount = 100;
        int minHeartSize = Utils.dpToPx(context, 15); // 使用 Utils
        int maxHeartSize = Utils.dpToPx(context, 35); // 使用 Utils
        long maxStaggerDelay = 800;

        int containerWidth = container.getWidth();
        int containerHeight = container.getHeight();
        float startXBase = containerWidth / 2f;
        float startY = containerHeight - Utils.dpToPx(context, 20); // 使用 Utils
        float startXVariance = containerWidth * 0.1f;

        Log.d(TAG, "创建爱心喷泉");

        for (int i = 0; i < heartCount; i++) {
            ImageView heart = new ImageView(context);
            heart.setImageResource(R.drawable.ic_heart);
            heart.setColorFilter(random.nextBoolean() ? Color.WHITE : Color.parseColor("#FF69B4"));

            int heartSize = random.nextInt(maxHeartSize - minHeartSize + 1) + minHeartSize;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(heartSize, heartSize);
            container.addView(heart, params);

            float startX = startXBase + (random.nextFloat() * 2f - 1f) * startXVariance;
            heart.setX(startX - heartSize / 2f);
            heart.setY(startY - heartSize / 2f);
            heart.setAlpha(0f);
            heart.setRotation(random.nextFloat() * 60 - 30);

            long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8));
            long startDelay = (long) (((float)i / heartCount) * maxStaggerDelay);
            float targetY = -heartSize;
            float horizontalDrift = (random.nextFloat() * 2f - 1f) * (containerWidth * 0.6f);

            heart.animate()
                .setStartDelay(startDelay)
                .alpha(1f)
                .translationY(targetY)
                .translationXBy(horizontalDrift)
                .rotationBy(random.nextFloat() * 360 - 180)
                .setDuration(duration)
                .setInterpolator(new LinearInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                     // ... (onAnimationStart, onAnimationEnd - 移除 view)
                    @Override
                    public void onAnimationEnd(Animator animation) {
                         heart.animate()
                             .alpha(0f)
                             .setDuration(duration / 4)
                             .setInterpolator(new AccelerateInterpolator())
                             .setListener(new AnimatorListenerAdapter() {
                                 @Override
                                 public void onAnimationEnd(Animator animation) {
                                     if (heart.getParent() != null) {
                                         container.removeView(heart);
                                     }
                                 }
                             })
                             .start();
                    }
                })
                .withLayer()
                .start();
        }
    }

    private void createAndAnimateParticles(View sourceView, ViewGroup container, long baseDuration) {
        // ... (粒子动画代码，使用 context 获取资源和 Utils.dpToPx, Utils.getRandomParticleColor)
        if (sourceView == null || container == null) return;

        int particleCount = 40;
        int minParticleSize = Utils.dpToPx(context, 3); // 使用 Utils
        int maxParticleSize = Utils.dpToPx(context, 8); // 使用 Utils

        int[] sourcePos = new int[2];
        sourceView.getLocationInWindow(sourcePos); // Use getLocationInWindow for overlay
        float startX = sourcePos[0] + sourceView.getWidth() / 2f;
        float startY = sourcePos[1] + sourceView.getHeight() / 2f;


        // --- 修复获取屏幕尺寸的方法 ---
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;
        // --- 结束修复 ---

        float maxDistance = Math.max(screenWidth, screenHeight) * 0.7f;
        float minDistance = maxDistance * 0.3f;
        long maxStaggerDelay = 450;

        Log.d(TAG, "创建粒子 (增强版)，起点: (" + startX + ", " + startY + ")");

        for (int i = 0; i < particleCount; i++) {
             View particle = new View(context);
             particle.setBackgroundColor(Utils.getRandomParticleColor()); // 使用 Utils
             int particleSize = random.nextInt(maxParticleSize - minParticleSize + 1) + minParticleSize;
             FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(particleSize, particleSize);
             container.addView(particle, params);

             particle.setX(startX - particleSize / 2f);
             particle.setY(startY - particleSize / 2f);
             particle.setAlpha(1f);
             particle.setScaleX(1.2f);
             particle.setScaleY(1.2f);

             double angle = random.nextDouble() * 2 * Math.PI;
             float distance = random.nextFloat() * (maxDistance - minDistance) + minDistance;
             float translationX = (float) (distance * Math.cos(angle));
             float translationY = (float) (distance * Math.sin(angle));
             float rotation = random.nextFloat() * 1080 - 540;
             long duration = (long) (baseDuration * (random.nextFloat() * 0.5 + 0.8));
             long startDelay = (long) (((float)i / particleCount) * maxStaggerDelay);

             particle.animate()
                 .setStartDelay(startDelay)
                 .translationXBy(translationX)
                 .translationYBy(translationY)
                 .alpha(0f)
                 .rotationBy(rotation)
                 .scaleX(0.7f)
                 .scaleY(0.7f)
                 .setDuration(duration)
                 .setInterpolator(new DecelerateInterpolator(1.5f))
                 .setListener(new AnimatorListenerAdapter() {
                     @Override
                     public void onAnimationEnd(Animator animation) {
                         if (particle.getParent() != null) {
                             container.removeView(particle);
                         }
                     }
                 })
                 .withLayer()
                 .start();
        }
    }
}
