/*
 * Copyright (C) 2023 The risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.charging;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.PorterDuff;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.ProgressBar;

import androidx.core.content.ContextCompat;

public class BatteryProgressBar extends ProgressBar {

    private int batteryLevel;
    private int maxBatteryLevel = 100;

    public BatteryProgressBar(Context context) {
        super(context);
        init();
    }

    public BatteryProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        batteryLevel = getBatteryLevel();
        setProgress(batteryLevel);
        setProgressTint(getColorForBatteryLevel(batteryLevel));
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                final int currentLevel = getBatteryLevel();
                if (batteryLevel != currentLevel) {
                    batteryLevel = currentLevel;
                    setProgress(currentLevel);
                    animateColorChange();
                }
                handler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private void animateColorChange() {
        int startColor = getColorForBatteryLevel(batteryLevel - 1);
        int endColor = getColorForBatteryLevel(batteryLevel);

        ValueAnimator colorAnimation = ValueAnimator.ofArgb(startColor, endColor);
        colorAnimation.setDuration(1000);
        colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                int animatedValue = (int) animator.getAnimatedValue();
                setProgressTint(animatedValue);
            }
        });
        colorAnimation.start();
    }

    private void setProgressTint(int color) {
        setProgressTintList(ColorStateList.valueOf(color));
    }

    private int getColorForBatteryLevel(int level) {
        float fraction = (float) level / maxBatteryLevel;
        int startColor = Color.rgb(255, 0, 0);
        int endColor = Color.rgb(0, 255, 0);

        int red = (int) (Color.red(startColor) * (1 - fraction) + Color.red(endColor) * fraction);
        int green = (int) (Color.green(startColor) * (1 - fraction) + Color.green(endColor) * fraction);
        int blue = (int) (Color.blue(startColor) * (1 - fraction) + Color.blue(endColor) * fraction);

        return Color.rgb(red, green, blue);
    }

    private int getBatteryLevel() {
        BatteryManager batteryManager = (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager != null) {
            return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } else {
            return 0;
        }
    }
}
