/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.util

import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.android.internal.R as internalR

object GradientColorUtils {

    @JvmStatic
    fun isGradientEnabled(context: Context, settingKey: String): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver, settingKey, 0,
            UserHandle.USER_CURRENT
        ) != 0
    }

    @JvmStatic
    fun getGradientColors(context: Context): Pair<Int, Int> {
        val resolver = context.contentResolver

        val mode = Settings.System.getIntForUser(
            resolver, Settings.System.CUSTOM_GRADIENT_COLOR_MODE, 0,
            UserHandle.USER_CURRENT
        )

        val primary = context.getColor(internalR.color.materialColorPrimary)
        val secondary = context.getColor(internalR.color.materialColorSecondary)

        if (mode == 1) {
            val start = Settings.System.getIntForUser(
                resolver, Settings.System.CUSTOM_GRADIENT_START_COLOR, 0,
                UserHandle.USER_CURRENT
            )
            val end = Settings.System.getIntForUser(
                resolver, Settings.System.CUSTOM_GRADIENT_END_COLOR, 0,
                UserHandle.USER_CURRENT
            )

            val startColor = if (start != 0) start else primary
            val endColor = if (end != 0) end else secondary

            return Pair(startColor, endColor)
        }

        return Pair(primary, secondary)
    }

    @Composable
    @JvmStatic
    fun rememberGradientColorMode(enabled: Boolean): Int {
        if (!enabled) return 0
        val contentResolver = LocalContext.current.contentResolver

        fun readMode(): Int = try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.CUSTOM_GRADIENT_COLOR_MODE, 0,
                UserHandle.USER_CURRENT
            )
        } catch (_: Throwable) { 0 }

        var mode by remember(enabled) { mutableIntStateOf(readMode()) }

        DisposableEffect(contentResolver, enabled) {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { mode = readMode() }
            }
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_COLOR_MODE),
                false, observer, UserHandle.USER_ALL
            )
            onDispose { contentResolver.unregisterContentObserver(observer) }
        }

        return mode
    }

    @Composable
    @JvmStatic
    fun rememberGradientCustomColors(
        enabled: Boolean,
        fallbackStart: Color,
        fallbackEnd: Color
    ): Pair<Color, Color> {
        if (!enabled) return Pair(fallbackStart, fallbackEnd)
        val contentResolver = LocalContext.current.contentResolver

        fun readStart(): Int = try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.CUSTOM_GRADIENT_START_COLOR, 0,
                UserHandle.USER_CURRENT
            )
        } catch (_: Throwable) { 0 }

        fun readEnd(): Int = try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.CUSTOM_GRADIENT_END_COLOR, 0,
                UserHandle.USER_CURRENT
            )
        } catch (_: Throwable) { 0 }

        var startInt by remember(enabled) { mutableIntStateOf(readStart()) }
        var endInt by remember(enabled) { mutableIntStateOf(readEnd()) }

        DisposableEffect(contentResolver, enabled) {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    startInt = readStart()
                    endInt = readEnd()
                }
            }
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_START_COLOR),
                false, observer, UserHandle.USER_ALL
            )
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_END_COLOR),
                false, observer, UserHandle.USER_ALL
            )
            onDispose { contentResolver.unregisterContentObserver(observer) }
        }

        val start = if (startInt != 0) Color(startInt) else fallbackStart
        val end = if (endInt != 0) Color(endInt) else fallbackEnd
        return Pair(start, end)
    }
}
