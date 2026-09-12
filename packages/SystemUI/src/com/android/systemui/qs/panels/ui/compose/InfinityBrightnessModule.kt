/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: LicenseRef-InfinityX-Proprietary
 */

package com.android.systemui.qs.panels.ui.compose

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

private const val GAMMA = 2.2f
private const val SLIDER_HAPTIC_STEPS = 24
private const val EDGE_TOUCH_FRACTION = 0.005f
private val SLIDER_CORNER_RADIUS = 20.dp
private val SLIDER_CAPSULE_RADIUS = 100.dp
private val EDGE_BOUNCE_DISTANCE = 6.dp
private val ICON_TOUCH_HEIGHT = 44.dp
private val ICON_TOUCH_HALF_WIDTH = 20.dp

private fun sliderHapticStep(fraction: Float): Int {
    return (fraction.coerceIn(0f, 1f) * SLIDER_HAPTIC_STEPS)
        .toInt()
        .coerceIn(0, SLIDER_HAPTIC_STEPS)
}

private fun brightnessToFraction(brightness: Float, min: Float = 1f, max: Float = 255f): Float {
    val normalized = ((brightness - min) / (max - min)).coerceIn(0f, 1f)
    return normalized.pow(1f / GAMMA)
}

private fun fractionToBrightness(fraction: Float, min: Float = 1f, max: Float = 255f): Float {
    val normalized = fraction.coerceIn(0f, 1f).pow(GAMMA)
    return (min + normalized * (max - min)).coerceIn(min, max)
}

private fun readBrightnessHapticsEnabled(cr: ContentResolver): Boolean = try {
    Settings.System.getIntForUser(
        cr,
        Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC,
        1,
        UserHandle.USER_CURRENT,
    ) != 0
} catch (_: Exception) {
    false
}

@Composable
private fun rememberBrightnessSliderGradientEnabled(): Boolean {
    val contentResolver = LocalContext.current.contentResolver

    fun readEnabled(): Boolean = try {
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.QS_BRIGHTNESS_SLIDER_GRADIENT,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    } catch (_: Throwable) {
        false
    }

    var enabled by remember { mutableStateOf(readEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                enabled = readEnabled()
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_BRIGHTNESS_SLIDER_GRADIENT),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return enabled
}

@Composable
private fun rememberGradientColorMode(enabled: Boolean): Int =
    com.android.systemui.util.GradientColorUtils.rememberGradientColorMode(enabled)

@Composable
private fun rememberGradientCustomColors(enabled: Boolean): Pair<Color, Color> =
    com.android.systemui.util.GradientColorUtils.rememberGradientCustomColors(
        enabled,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary
    )

@Composable
private fun rememberBrightnessSliderGradientBrush(): Brush? {
    val enabled = rememberBrightnessSliderGradientEnabled()
    if (!enabled) return null

    val mode = rememberGradientColorMode(enabled)
    val (start, end) = rememberGradientCustomColors(enabled)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val colors = remember(mode, start, end, primaryColor, secondaryColor) {
        if (mode == 1) {
            listOf(end, start)
        } else {
            listOf(secondaryColor, primaryColor)
        }
    }

    return remember(colors) { Brush.verticalGradient(colors = colors) }
}

@Composable
fun InfinityBrightnessModule(
    modifier: Modifier = Modifier,
    capsuleStyle: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val cr: ContentResolver = context.contentResolver

    @Suppress("UNUSED_VARIABLE")
    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    val brightnessMin = 1f
    val brightnessMax = 255f

    fun readBrightness(): Float = try {
        Settings.System.getIntForUser(
            cr, Settings.System.SCREEN_BRIGHTNESS, 128, UserHandle.USER_CURRENT
        ).toFloat().coerceIn(brightnessMin, brightnessMax)
    } catch (_: Exception) { 128f }

    fun readAutoMode(): Boolean = try {
        Settings.System.getIntForUser(
            cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, UserHandle.USER_CURRENT
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) { false }

    var brightness by remember { mutableFloatStateOf(readBrightness()) }
    var autoMode   by remember { mutableStateOf(readAutoMode()) }
    var hapticsEnabled by remember { mutableStateOf(readBrightnessHapticsEnabled(cr)) }
    var isDragging by remember { mutableStateOf(false) }
    var holdOffsetPx by remember { mutableFloatStateOf(0f) }
    var lastWrittenBrightnessInt by remember { mutableIntStateOf(brightness.toInt()) }
    val brightnessWriteChannel = remember { Channel<Int>(Channel.CONFLATED) }
    var lastHapticStep by remember {
        mutableIntStateOf(sliderHapticStep(brightnessToFraction(brightness, brightnessMin, brightnessMax)))
    }

    val edgeBouncePx = with(density) { EDGE_BOUNCE_DISTANCE.toPx() }
    val iconTouchHeightPx = with(density) { ICON_TOUCH_HEIGHT.toPx() }
    val iconTouchHalfWidthPx = with(density) { ICON_TOUCH_HALF_WIDTH.toPx() }

    val targetFraction = brightnessToFraction(brightness, brightnessMin, brightnessMax)

    val animFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(if (isDragging) 0 else 150),
        label = "BrightnessFraction"
    )
    val currentFraction = if (isDragging) targetFraction else animFraction
    val sliderOffsetY by animateFloatAsState(
        targetValue = if (isDragging) holdOffsetPx else 0f,
        animationSpec = tween(150),
        label = "BrightnessSliderOffset"
    )
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                hapticsEnabled = readBrightnessHapticsEnabled(cr)
                if (!isDragging) {
                    brightness = readBrightness()
                    autoMode   = readAutoMode()
                    lastWrittenBrightnessInt = brightness.toInt()
                    lastHapticStep = sliderHapticStep(
                        brightnessToFraction(brightness, brightnessMin, brightnessMax)
                    )
                }
            }
        }
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false, observer, UserHandle.USER_ALL
        )
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false, observer, UserHandle.USER_ALL
        )
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { cr.unregisterContentObserver(observer) }
    }

    val trackBgColor = Color.White.copy(alpha = 0.18f)
    val fillColor = Color.White.copy(alpha = 0.9f)
    val fillGradient = rememberBrightnessSliderGradientBrush()
    val iconTint = Color(0xFF2C2C2E)
    val sliderCornerRadius = if (capsuleStyle) SLIDER_CAPSULE_RADIUS else SLIDER_CORNER_RADIUS
    val iconRes = if (autoMode) R.drawable.ic_qs_brightness_auto_on
                  else          R.drawable.ic_qs_brightness_auto_off

    DisposableEffect(Unit) {
        val writerJob = scope.launch(Dispatchers.IO) {
            for (value in brightnessWriteChannel) {
                try {
                    Settings.System.putIntForUser(
                        cr,
                        Settings.System.SCREEN_BRIGHTNESS,
                        value,
                        UserHandle.USER_CURRENT,
                    )
                } catch (_: Exception) {}
            }
        }
        onDispose {
            brightnessWriteChannel.close()
            writerJob.cancel()
        }
    }

    fun yToBrightness(y: Float, heightPx: Int): Float {
        val fraction = 1f - (y / heightPx).coerceIn(0f, 1f)
        return fractionToBrightness(fraction, brightnessMin, brightnessMax)
    }

    fun writeBrightness(value: Int) {
        brightnessWriteChannel.trySend(value)
    }

    fun setAutoBrightnessMode(enabled: Boolean) {
        autoMode = enabled
        val mode = if (enabled) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        scope.launch(Dispatchers.IO) {
            try {
                Settings.System.putIntForUser(
                    cr,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mode,
                    UserHandle.USER_CURRENT,
                )
            } catch (_: Exception) {}
        }
    }

    fun toggleAutoBrightnessMode() {
        val newMode = !autoMode
        setAutoBrightnessMode(newMode)
        if (hapticsEnabled) {
            view.performHapticFeedback(
                if (newMode) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            )
        }
    }

    fun updateBrightnessFromGesture(newBrightness: Float): Boolean {
        val clipped = newBrightness.coerceIn(brightnessMin, brightnessMax)
        val clippedFraction = brightnessToFraction(clipped, brightnessMin, brightnessMax)
        val newStep = sliderHapticStep(clippedFraction)
        val delta = clipped - brightness
        if (abs(delta) < 0.01f) {
            if (hapticsEnabled && newStep != lastHapticStep) {
                lastHapticStep = newStep
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
            return false
        }

        brightness = clipped
        val clippedInt = clipped.toInt()
        if (clippedInt != lastWrittenBrightnessInt) {
            lastWrittenBrightnessInt = clippedInt
            writeBrightness(clippedInt)
        }

        if (hapticsEnabled) {
            if (newStep != lastHapticStep) {
                lastHapticStep = newStep
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
        }
        return false
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer { translationY = sliderOffsetY }
            .clip(RoundedCornerShape(sliderCornerRadius))
            .background(trackBgColor)
            .pointerInput(hapticsEnabled, autoMode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    view.parent?.requestDisallowInterceptTouchEvent(true)

                    val inIconHitArea =
                        down.position.y >= (size.height - iconTouchHeightPx) &&
                            abs(down.position.x - (size.width / 2f)) <= iconTouchHalfWidthPx

                    if (inIconHitArea) {
                        var isTap = true
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val currentPointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!currentPointer.pressed) break
                            if (isTap) {
                                val dx = abs(currentPointer.position.x - down.position.x)
                                val dy = abs(currentPointer.position.y - down.position.y)
                                if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                                    isTap = false
                                }
                            }
                        }
                        if (isTap) {
                            toggleAutoBrightnessMode()
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        return@awaitEachGesture
                    }

                    val downBrightness = yToBrightness(down.position.y, size.height)
                    val consumedByStep = updateBrightnessFromGesture(downBrightness)
                    if (hapticsEnabled && !consumedByStep) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    var dragging = false
                    var edgeDirection = 0

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val currentPointer = event.changes.firstOrNull { it.id == down.id }
                                ?: break

                            if (!currentPointer.pressed) {
                                break
                            }

                            val dragAmount = currentPointer.position.y - down.position.y

                            if (!dragging && abs(dragAmount) > viewConfiguration.touchSlop) {
                                dragging = true
                                isDragging = true
                            }

                            if (dragging) {
                                currentPointer.consume()
                                val v = yToBrightness(currentPointer.position.y, size.height)
                                updateBrightnessFromGesture(v)

                                val fraction = brightnessToFraction(v, brightnessMin, brightnessMax)
                                when {
                                    fraction >= (1f - EDGE_TOUCH_FRACTION) -> {
                                        if (edgeDirection != 1) {
                                            edgeDirection = 1
                                            holdOffsetPx = -edgeBouncePx
                                            if (hapticsEnabled) {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            }
                                        }
                                    }
                                    fraction <= EDGE_TOUCH_FRACTION -> {
                                        if (edgeDirection != -1) {
                                            edgeDirection = -1
                                            holdOffsetPx = edgeBouncePx
                                            if (hapticsEnabled) {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            }
                                        }
                                    }
                                    else -> {
                                        edgeDirection = 0
                                    }
                                }
                            }
                        }
                    } finally {
                        isDragging = false
                        holdOffsetPx = 0f
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
    ) {
        val fillShape = RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = sliderCornerRadius,
            bottomEnd = sliderCornerRadius,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(currentFraction)
                .align(Alignment.BottomCenter)
                .let {
                    if (fillGradient != null) {
                        it.background(fillGradient, fillShape)
                    } else {
                        it.background(fillColor, fillShape)
                    }
                }
        )

        Icon(
            painter = painterResource(iconRes),
            contentDescription = "Brightness",
            tint = iconTint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(22.dp)
        )
    }
}
