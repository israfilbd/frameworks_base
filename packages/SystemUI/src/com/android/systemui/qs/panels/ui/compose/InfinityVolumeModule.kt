/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: LicenseRef-InfinityX-Proprietary
 */

package com.android.systemui.qs.panels.ui.compose

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

private const val VOLUME_RESTORE_PERCENTAGE_WHEN_MUTED = 0.1f
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

private fun readVolumeHapticsEnabled(cr: ContentResolver): Boolean = try {
    Settings.Secure.getIntForUser(
        cr,
        Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK,
        1,
        UserHandle.USER_CURRENT,
    ) != 0
} catch (_: Exception) {
    false
}

@Composable
private fun rememberVolumeSliderGradientEnabled(): Boolean {
    val contentResolver = LocalContext.current.contentResolver

    fun readEnabled(): Boolean = try {
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.VOLUME_SLIDER_GRADIENT,
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
            Settings.System.getUriFor(Settings.System.VOLUME_SLIDER_GRADIENT),
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
private fun rememberVolumeSliderGradientBrush(): Brush? {
    val enabled = rememberVolumeSliderGradientEnabled()
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
fun InfinityVolumeModule(
    modifier: Modifier = Modifier,
    capsuleStyle: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val cr = context.contentResolver

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    fun readVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        return if (max > 0f) (cur / max).coerceIn(0f, 1f) else 0f
    }

    var volumeFraction by remember { mutableFloatStateOf(readVolume()) }
    var hapticsEnabled by remember { mutableStateOf(readVolumeHapticsEnabled(cr)) }
    var isDragging by remember { mutableStateOf(false) }
    var holdOffsetPx by remember { mutableFloatStateOf(0f) }
    val volumeWriteChannel = remember { Channel<Int>(Channel.CONFLATED) }
    var lastWrittenVolumeLevel by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var lastHapticStep by remember { mutableIntStateOf(sliderHapticStep(volumeFraction)) }

    val edgeBouncePx = with(density) { EDGE_BOUNCE_DISTANCE.toPx() }
    val iconTouchHeightPx = with(density) { ICON_TOUCH_HEIGHT.toPx() }
    val iconTouchHalfWidthPx = with(density) { ICON_TOUCH_HALF_WIDTH.toPx() }

    val targetFraction = volumeFraction.coerceIn(0f, 1f)

    val animFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(if (isDragging) 0 else 150),
        label = "VolumeFraction"
    )
    val currentFraction = if (isDragging) targetFraction else animFraction
    val sliderOffsetY by animateFloatAsState(
        targetValue = if (isDragging) holdOffsetPx else 0f,
        animationSpec = tween(150),
        label = "VolumeSliderOffset"
    )

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != "android.media.VOLUME_CHANGED_ACTION" &&
                    action != "android.media.STREAM_DEVICES_CHANGED_ACTION" &&
                    action != "android.media.STREAM_MUTE_CHANGED_ACTION"
                ) return
                val changedStream = intent.getIntExtra(
                    AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                )
                if (!isDragging &&
                    (changedStream == AudioManager.STREAM_MUSIC ||
                        changedStream == AudioManager.USE_DEFAULT_STREAM_TYPE)
                ) {
                    volumeFraction = readVolume()
                    lastWrittenVolumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    lastHapticStep = sliderHapticStep(volumeFraction)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_DEVICES_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        context.registerReceiver(receiver, filter)

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                hapticsEnabled = readVolumeHapticsEnabled(cr)
            }
        }
        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose {
            context.unregisterReceiver(receiver)
            cr.unregisterContentObserver(observer)
        }
    }

    val trackBgColor = Color.White.copy(alpha = 0.18f)
    val fillColor = Color.White.copy(alpha = 0.9f)
    val fillGradient = rememberVolumeSliderGradientBrush()
    val iconTint = Color(0xFF2C2C2E)
    val sliderCornerRadius = if (capsuleStyle) SLIDER_CAPSULE_RADIUS else SLIDER_CORNER_RADIUS
    val iconRes = if (volumeFraction <= 0.0001f) {
        R.drawable.ic_volume_media_mute
    } else {
        R.drawable.ic_volume_media
    }

    DisposableEffect(Unit) {
        val writerJob = scope.launch(Dispatchers.IO) {
            for (level in volumeWriteChannel) {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                } catch (_: Exception) {}
            }
        }
        onDispose {
            volumeWriteChannel.close()
            writerJob.cancel()
        }
    }

    fun maxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun yToVolumeFraction(y: Float, heightPx: Int): Float {
        return 1f - (y / heightPx).coerceIn(0f, 1f)
    }

    fun writeVolume(level: Int) {
        volumeWriteChannel.trySend(level)
    }

    fun updateVolumeFromGesture(fraction: Float): Boolean {
        val safeFraction = fraction.coerceIn(0f, 1f)
        volumeFraction = safeFraction

        val maxVol = maxVolume()
        val targetVol = (safeFraction * maxVol).toInt().coerceIn(0, maxVol)
        if (targetVol != lastWrittenVolumeLevel) {
            lastWrittenVolumeLevel = targetVol
            writeVolume(targetVol)
        }

        if (hapticsEnabled) {
            val newStep = sliderHapticStep(safeFraction)
            if (newStep != lastHapticStep) {
                lastHapticStep = newStep
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
        }
        return false
    }

    fun toggleMuteByIcon() {
        val maxVol = maxVolume()
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val targetVol = if (currentVol > 0) {
            0
        } else {
            (maxVol * VOLUME_RESTORE_PERCENTAGE_WHEN_MUTED)
                .toInt()
                .coerceAtLeast(1)
                .coerceAtMost(maxVol)
        }
        volumeFraction = targetVol.toFloat() / maxVol.toFloat()
        if (targetVol != lastWrittenVolumeLevel) {
            lastWrittenVolumeLevel = targetVol
            writeVolume(targetVol)
        }
        lastHapticStep = sliderHapticStep(volumeFraction)
        if (hapticsEnabled) {
            view.performHapticFeedback(
                if (targetVol == 0) HapticFeedbackConstants.TOGGLE_OFF
                else HapticFeedbackConstants.TOGGLE_ON
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer { translationY = sliderOffsetY }
            .clip(RoundedCornerShape(sliderCornerRadius))
            .background(trackBgColor)
            .pointerInput(hapticsEnabled) {
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
                            toggleMuteByIcon()
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        return@awaitEachGesture
                    }

                    val downFraction = yToVolumeFraction(down.position.y, size.height)
                    val consumedByStep = updateVolumeFromGesture(downFraction)
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
                                val fraction = yToVolumeFraction(currentPointer.position.y, size.height)
                                updateVolumeFromGesture(fraction)

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
            contentDescription = "Volume",
            tint = iconTint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(22.dp)
        )
    }
}
