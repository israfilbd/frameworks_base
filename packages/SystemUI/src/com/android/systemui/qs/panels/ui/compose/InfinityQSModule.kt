/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: LicenseRef-InfinityX-Proprietary
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun InfinityQSModule(
    style: Int,
    quickQsMode: Boolean = false,
    animatedVisibility: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val sanitizedStyle = sanitizeInfinityQsStyle(style)
    if (sanitizedStyle == INFINITY_QS_STYLE_STOCK) {
        return
    }
    if (!animatedVisibility) {
        Box(modifier = modifier) {
            InfinityQSModuleContent(
                style = sanitizedStyle,
                quickQsMode = quickQsMode,
            )
        }
    } else {
        AnimatedVisibility(
            visible = true,
            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(250)),
            modifier = modifier,
        ) {
            InfinityQSModuleContent(
                style = sanitizedStyle,
                quickQsMode = quickQsMode,
            )
        }
    }
}

private enum class OxygenRightPaneMode {
    MEDIA,
    SLIDERS,
}

private object InfinityOxygenRightPaneState {
    var mode by mutableStateOf(OxygenRightPaneMode.SLIDERS)
    var mediaAvailable by mutableStateOf(false)
}

@Composable
private fun InfinityQSModuleContent(style: Int, quickQsMode: Boolean) {
    val contentHeight = if (quickQsMode) 144.dp else 160.dp
    val connectivityHeight = if (quickQsMode) 76.dp else 84.dp
    val oxygenRightPaneHeight = (connectivityHeight * 2) + 12.dp
    val showConnectivity = style != INFINITY_QS_STYLE_INFINITY_X
    val oxygenStyle = style == INFINITY_QS_STYLE_OXYGENOS
    val detectedMediaAvailable = if (oxygenStyle) rememberInfinityMediaHasSession() else false

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showConnectivity && !oxygenStyle) {
            InfinityConnectivityModule(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(connectivityHeight),
            )
        }

        if (oxygenStyle) {
            LaunchedEffect(detectedMediaAvailable) {
                InfinityOxygenRightPaneState.mediaAvailable = detectedMediaAvailable
                InfinityOxygenRightPaneState.mode =
                    if (detectedMediaAvailable) OxygenRightPaneMode.MEDIA
                    else OxygenRightPaneMode.SLIDERS
            }
            var horizontalDragAccumulation by remember { mutableFloatStateOf(0f) }
            val swipeThresholdPx = 80f
            val rightPaneMode = InfinityOxygenRightPaneState.mode
            val mediaAvailable = InfinityOxygenRightPaneState.mediaAvailable

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(oxygenRightPaneHeight),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfinityConnectivityModule(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    stackMobileBelowWifi = true,
                    capsuleStyle = true,
                )

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(mediaAvailable, rightPaneMode) {
                                if (!mediaAvailable) return@pointerInput

                                detectHorizontalDragGestures(
                                    onDragStart = { horizontalDragAccumulation = 0f },
                                    onHorizontalDrag = { _, dragAmount ->
                                        horizontalDragAccumulation += dragAmount
                                    },
                                    onDragEnd = {
                                        if (abs(horizontalDragAccumulation) >= swipeThresholdPx) {
                                            InfinityOxygenRightPaneState.mode =
                                                if (rightPaneMode == OxygenRightPaneMode.MEDIA) {
                                                    OxygenRightPaneMode.SLIDERS
                                                } else {
                                                    OxygenRightPaneMode.MEDIA
                                                }
                                        }
                                        horizontalDragAccumulation = 0f
                                    },
                                    onDragCancel = {
                                        horizontalDragAccumulation = 0f
                                    },
                                )
                            }
                ) {
                    if (mediaAvailable && rightPaneMode == OxygenRightPaneMode.MEDIA) {
                        InfinityMediaPanel(modifier = Modifier.fillMaxWidth().fillMaxHeight())
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            InfinityBrightnessModule(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                capsuleStyle = true,
                            )
                            InfinityVolumeModule(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                capsuleStyle = true,
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeight),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    InfinityMediaPanel(modifier = Modifier.fillMaxWidth().fillMaxHeight())
                }

                Row(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfinityBrightnessModule(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )

                    InfinityVolumeModule(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}
