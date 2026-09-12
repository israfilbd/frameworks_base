/*
 * Copyright (C) 2026 The Android Open Source Project
 * Copyright (C) 2026 Project Infinity X
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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.view.DisplayCutout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.axdynamicbar.shared.sendWithBal
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.screenrecord.shared.model.ScreenRecordPopupModel

@Composable
private fun BlinkingRedDot(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "blinking_red_dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer(alpha = alpha)
            .background(Color.Red, CircleShape)
    )
}

/** Single centered status bar capsule styled like a compact dynamic island. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusBarDynamicIslandChip(
    viewModel: PopupChipModel.Shown,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    compactWidth: Int = 110,
) {
    val context = LocalContext.current
    val chipShape = RoundedCornerShape(50)
    val colors = viewModel.colors
    val chipBackgroundColor =
        colors.chipBackground(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipContentColor =
        colors.chipContent(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipOutline =
        colors.chipOutline(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isChipExpanding = !viewModel.isPopupShown

    val chipWidthSpec = if (isChipExpanding) ExpandWidthSpring else CollapseWidthSpring
    val chipAlphaSpec = if (isChipExpanding) ExpandAlphaSpring else CollapseAlphaSpring

    val targetWidth = when {
        viewModel.isPopupShown -> 0.dp
        isPressed -> (compactWidth - 20).dp
        else -> compactWidth.dp
    }
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = chipWidthSpec,
        label = "chip_width"
    )

    val targetAlpha = if (viewModel.isPopupShown) 0f else 1f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = chipAlphaSpec,
        label = "chip_alpha"
    )

    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = 32.dp)
                .width(animatedWidth)
                .graphicsLayer {
                    alpha = animatedAlpha
                }
                .clip(chipShape)
                .background(chipBackgroundColor)
                .border(width = 1.dp, color = chipOutline, shape = chipShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTap,
                    onLongClick = {
                        when (val popupContent = viewModel.popupContent) {
                            is PopupContentModel.Media -> {
                                popupContent.model.openApp?.invoke()
                            }
                            is PopupContentModel.PromotedOngoing -> {
                                try {
                                    popupContent.event.sbn.notification?.contentIntent?.sendWithBal(context)
                                } catch (e: Exception) {
                                    // log
                                }
                            }
                            is PopupContentModel.OngoingCall -> {
                                try {
                                    popupContent.event.sbn.notification?.contentIntent?.sendWithBal(context)
                                } catch (e: Exception) {
                                    // log
                                }
                            }
                            else -> Unit
                        }
                    }
                )
                .padding(
                    start = 8.dp,
                    end = 10.dp,
                    top = 5.dp,
                    bottom = 5.dp,
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left element
        when (viewModel.popupContent) {
            is PopupContentModel.ScreenRecord -> {
                Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BlinkingRedDot()
                }
            }
            is PopupContentModel.Media, is PopupContentModel.LiveScore -> {
                viewModel.icons.firstOrNull()?.let { chipIcon ->
                    Icon(
                        icon = chipIcon.icon,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        tint = Color.Unspecified,
                    )
                }
            }
            else -> {
                viewModel.icons.firstOrNull()?.let { chipIcon ->
                    Icon(
                        icon = chipIcon.icon,
                        modifier = Modifier.size(20.dp),
                        tint = chipContentColor,
                    )
                }
            }
        }

        // Right element (Animation, Duration, Status Text, or SwipeHint)
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media -> {
                if (popupContent.model.isPlaying) {
                    AudioReactiveBars(
                        isPlaying = true,
                        color = chipContentColor,
                    )
                } else if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                } else {
                    Box(modifier = Modifier.size(width = 16.dp, height = 12.dp))
                }
            }
            is PopupContentModel.ScreenRecord -> {
                val durationText = when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting -> "${model.secondsUntilStarted}s"
                    is ScreenRecordPopupModel.Recording ->
                        rememberElapsedDurationText(model.startElapsedRealtimeMs)
                }
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelMedium,
                    color = chipContentColor,
                    maxLines = 1,
                )
            }
            is PopupContentModel.Flashlight -> {
                Text(
                    text = "ON",
                    style = MaterialTheme.typography.labelLarge,
                    color = chipContentColor,
                    maxLines = 1,
                )
            }
            is PopupContentModel.LiveScore -> {
                Text(
                    text = viewModel.chipText.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = chipContentColor,
                    maxLines = 1,
                )
            }
            is PopupContentModel.PromotedOngoing -> {
                val progress = popupContent.event.progress
                if (progress in 0f..1f) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = chipContentColor,
                        maxLines = 1,
                    )
                } else {
                    Box(modifier = Modifier.size(width = 16.dp, height = 12.dp))
                }
            }
            is PopupContentModel.OngoingCall -> {
                Text(
                    text = viewModel.chipText.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = chipContentColor,
                    maxLines = 1,
                )
            }
            else -> {
                if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            }
        }
    }
}

@Composable
private fun SwipeHint(color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier = Modifier.size(width = 3.dp, height = 3.dp)
                    .background(color = color, shape = CircleShape)
            )
        }
    }
}

private val CompactMediaIslandWidth = 110.dp
private val DynamicIslandEmbeddedGapFallbackWidth = 38.dp
private val DynamicIslandEmbeddedGapMinWidth = 34.dp
private val DynamicIslandEmbeddedGapMaxWidth = 88.dp
private val DynamicIslandEmbeddedGapSidePadding = 10.dp

data class DynamicIslandCutoutSpec(
    val embeddedGapWidth: Dp,
    val horizontalOffset: Dp,
)

@Composable
fun rememberDynamicIslandCutoutSpec(): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    val view = LocalView.current
    val displayCutout = view.rootWindowInsets?.displayCutout ?: view.display?.cutout
    val topCutout = displayCutout?.topBoundingRectOrNull()
    val rootWidthPx =
        when {
            view.rootView.width > 0 -> view.rootView.width
            view.width > 0 -> view.width
            else -> view.resources.configuration.windowConfiguration.maxBounds.width()
        }

    return with(density) {
        if (topCutout == null || rootWidthPx <= 0) {
            DynamicIslandCutoutSpec(
                embeddedGapWidth = DynamicIslandEmbeddedGapFallbackWidth,
                horizontalOffset = 0.dp,
            )
        } else {
            val embeddedGapWidthDp =
                (topCutout.width().toDp() + (DynamicIslandEmbeddedGapSidePadding * 2))
                    .coerceIn(
                        DynamicIslandEmbeddedGapMinWidth,
                        DynamicIslandEmbeddedGapMaxWidth,
                    )
            val horizontalOffsetDp = (topCutout.exactCenterX() - (rootWidthPx / 2f)).toDp()
            DynamicIslandCutoutSpec(
                embeddedGapWidth = embeddedGapWidthDp,
                horizontalOffset = horizontalOffsetDp,
            )
        }
    }
}

private fun DisplayCutout.topBoundingRectOrNull() =
    getBoundingRectTop().takeUnless { it.isEmpty }
