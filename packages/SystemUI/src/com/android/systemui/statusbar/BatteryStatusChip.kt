/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar

import android.annotation.IntRange
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.res.R
import com.android.systemui.statusbar.events.BackgroundAnimatableView

class BatteryStatusChip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), BackgroundAnimatableView {

    private val roundedContainer: LinearLayout
    private val batteryMeterView: BatteryMeterView
    override val contentView: View
        get() = batteryMeterView

    init {
        inflate(context, R.layout.battery_status_chip, this)
        roundedContainer = requireViewById(R.id.rounded_container)
        batteryMeterView = requireViewById(R.id.battery_meter_view)
        updateResources()
    }

    /**
     * When animating as a chip in the status bar, we want to animate the width for the rounded
     * container. We have to subtract our own top and left offset because the bounds come to us as
     * absolute on-screen bounds.
     */
    override fun setBoundsForAnimation(l: Int, t: Int, r: Int, b: Int) {
        roundedContainer.setLeftTopRightBottom(l - left, t - top, r - left, b - top)
    }

    fun setBatteryLevel(@IntRange(from = 0, to = 100) batteryLevel: Int) {
        batteryMeterView.setForceShowPercent(true)
        batteryMeterView.onBatteryLevelChanged(batteryLevel, true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun updateResources() {
        roundedContainer.background = mContext.getDrawable(R.drawable.statusbar_chip_bg)
        updateBatteryMeterViewColor()
    }

    private fun calculateLuminance(color: Int): Double {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0

        val r = if (red <= 0.03928) red / 12.92 else Math.pow((red + 0.055) / 1.055, 2.4)
        val g = if (green <= 0.03928) green / 12.92 else Math.pow((green + 0.055) / 1.055, 2.4)
        val b = if (blue <= 0.03928) blue / 12.92 else Math.pow((blue + 0.055) / 1.055, 2.4)

        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun updateBatteryMeterViewColor() {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
        val colorAccent = typedValue.data
        val white = Color.WHITE
        val black = Color.BLACK

        val accentLuminance = calculateLuminance(colorAccent)
        val contrastWithWhite = ColorUtils.calculateContrast(colorAccent, white)
        val contrastWithBlack = ColorUtils.calculateContrast(colorAccent, black)

        val chosenTintColor = when {
            accentLuminance < 0.5 -> {
                if (contrastWithWhite > contrastWithBlack) white else black
            }
            else -> {
                if (contrastWithBlack > contrastWithWhite) black else white
            }
        }
        batteryMeterView.updateColors(chosenTintColor, chosenTintColor, chosenTintColor)
    }
}
