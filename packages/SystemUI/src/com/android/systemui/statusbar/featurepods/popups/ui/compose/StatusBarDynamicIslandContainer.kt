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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Phone-only centered dynamic island that pages through active popup chips. */
@Composable
fun StatusBarDynamicIslandContainer(
    chips: List<PopupChipModel.Shown>,
    onMediaControlPopupVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cutoutSpec = rememberDynamicIslandCutoutSpec()
    var selectedChipId by remember { mutableStateOf<PopupChipId?>(null) }
    var popupAnchorChip by remember { mutableStateOf<PopupChipModel.Shown?>(null) }
    var popupVisible by remember { mutableStateOf(false) }
    var knownChipIds by remember { mutableStateOf<List<PopupChipId>>(emptyList()) }

    LaunchedEffect(chips) {
        val currentChipIds = chips.map { it.chipId }
        val newestChipId =
            if (knownChipIds.isEmpty()) {
                null
            } else {
                currentChipIds.lastOrNull { it !in knownChipIds }
            }
        selectedChipId =
            when {
                newestChipId != null -> newestChipId
                chips.any { it.chipId == selectedChipId } -> selectedChipId
                else -> chips.firstOrNull()?.chipId
            }
        knownChipIds = currentChipIds
    }

    val selectedIndex = chips.indexOfFirst { it.chipId == selectedChipId }.coerceAtLeast(0)
    val selectedChip = chips.getOrNull(selectedIndex)
    val shownChip = chips.firstOrNull { it.isPopupShown }

    val context = LocalContext.current
    var customCompactWidth by remember { androidx.compose.runtime.mutableIntStateOf(110) }

    LaunchedEffect(context) {
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings
            .observeDynamicIslandWidth(context)
            .collect { width ->
                customCompactWidth = width
            }
    }

    // Preserve the last active chip during the exit collapse animation
    var activeChipForAnimation by remember { mutableStateOf<PopupChipModel.Shown?>(null) }
    LaunchedEffect(selectedChip) {
        if (selectedChip != null) {
            activeChipForAnimation = selectedChip
        }
    }

    val isIslandVisible = chips.isNotEmpty()
    val isIslandExpanding = isIslandVisible && shownChip == null

    val overallWidthAnimationSpec = if (isIslandExpanding) {
        ExpandWidthSpring
    } else {
        CollapseWidthSpring
    }

    val overallAlphaAnimationSpec = if (isIslandExpanding) {
        ExpandAlphaSpring
    } else {
        CollapseAlphaSpring
    }

    val overallWidth by animateDpAsState(
        targetValue = if (isIslandExpanding) customCompactWidth.dp else 0.dp,
        animationSpec = overallWidthAnimationSpec,
        label = "overall_island_width"
    )

    val overallAlpha by animateFloatAsState(
        targetValue = if (isIslandExpanding) 1f else 0f,
        animationSpec = overallAlphaAnimationSpec,
        label = "overall_island_alpha"
    )

    LaunchedEffect(shownChip) {
        if (shownChip != null) {
            selectedChipId = shownChip.chipId
            popupAnchorChip = shownChip
            delay(100)
            popupVisible = true
        } else if (popupAnchorChip != null) {
            popupVisible = false
            delay(220)
            popupAnchorChip = null
        }
    }

    LaunchedEffect(chips) {
        onMediaControlPopupVisibilityChanged(
            chips.any { it.chipId == PopupChipId.MediaControl && it.isPopupShown }
        )
    }

    // Only return early when the exit animation has completed (width is under 1.dp)
    if (!isIslandVisible && overallWidth < 1.dp) {
        return
    }

    val chipToRender = selectedChip ?: activeChipForAnimation ?: return

    fun selectRelative(direction: Int) {
        if (chips.size <= 1) return
        val newIndex = (selectedIndex + direction).mod(chips.size)
        val newChip = chips[newIndex]
        selectedChipId = newChip.chipId
        if (popupVisible) {
            newChip.showPopup()
        }
    }

    Box(
        modifier =
            modifier
                .padding(horizontal = 8.dp)
                .offset(x = cutoutSpec.horizontalOffset)
                .width(overallWidth)
                .graphicsLayer {
                    alpha = overallAlpha
                }
                .clip(RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = chipToRender.chipId,
            transitionSpec = {
                if (targetState == initialState) {
                    fadeIn(animationSpec = tween(150)) togetherWith
                        fadeOut(animationSpec = tween(150))
                } else {
                    val slideDirection =
                        if (
                            chips.indexOfFirst { it.chipId == targetState } >
                                chips.indexOfFirst { it.chipId == initialState }
                        ) {
                            1
                        } else {
                            -1
                        }
                    (slideInHorizontally(
                        animationSpec = tween(220),
                        initialOffsetX = { fullWidth -> slideDirection * fullWidth / 2 },
                    ) + fadeIn(animationSpec = tween(180))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(200),
                            targetOffsetX = { fullWidth -> -slideDirection * fullWidth / 3 },
                        ) + fadeOut(animationSpec = tween(140)))
                }
            },
            label = "dynamic_island_chip",
        ) { chipId ->
            val chip = (if (isIslandVisible) chips else listOf(chipToRender)).firstOrNull { it.chipId == chipId } ?: return@AnimatedContent
            var horizontalDragPx by remember(chipId, chips.size) { mutableFloatStateOf(0f) }
            val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }

            StatusBarDynamicIslandChip(
                viewModel = chip,
                pageCount = chips.size,
                cutoutSpec = cutoutSpec,
                compactWidth = customCompactWidth,
                modifier =
                    Modifier.pointerInput(chips.size, chip.chipId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    horizontalDragPx <= -thresholdPx -> selectRelative(1)
                                    horizontalDragPx >= thresholdPx -> selectRelative(-1)
                                }
                                horizontalDragPx = 0f
                            },
                            onDragCancel = { horizontalDragPx = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                horizontalDragPx += dragAmount
                                if (chips.size > 1 && abs(horizontalDragPx) > 8f) {
                                    change.consume()
                                }
                            },
                        )
                    },
                onTap = {
                    if (chip.isPopupShown) chip.hidePopup() else chip.showPopup()
                },
            )
        }

        popupAnchorChip?.let { anchoredChip ->
            StatusBarPopup(
                viewModel = anchoredChip,
                isVisible = popupVisible,
            )
        }
    }
}

internal val CollapseWidthSpring = spring<Dp>(
    dampingRatio = 0.8f,
    stiffness = Spring.StiffnessMediumLow
)

internal val CollapseAlphaSpring = spring<Float>(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessMediumLow
)

internal val ExpandWidthSpring = spring<Dp>(
    dampingRatio = 0.85f,
    stiffness = 220f
)

internal val ExpandAlphaSpring = spring<Float>(
    dampingRatio = 0.92f,
    stiffness = 220f
)
