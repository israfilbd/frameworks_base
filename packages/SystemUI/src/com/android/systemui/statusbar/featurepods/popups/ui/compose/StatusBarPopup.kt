/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.view.ViewTreeObserver
import com.android.systemui.media.MediaSessionManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.media.ui.compose.LyricsCard
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.ui.compose.PrimaryCard
import com.android.systemui.axdynamicbar.ui.compose.MediaCard
import com.android.systemui.axdynamicbar.ui.compose.SportsExpanded
import com.android.systemui.axdynamicbar.ui.compose.TorchExpanded
import com.android.systemui.axdynamicbar.ui.compose.PromotedOngoingExpanded
import com.android.systemui.axdynamicbar.ui.compose.CallExpanded
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.featurepods.av.ui.compose.AvControlsChipPopup
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.featurepods.sharescreen.ui.compose.ShareScreenPrivacyIndicatorPopup

/**
 * Displays a popup in the status bar area. The offset is calculated to draw the popup below the
 * status bar.
 */
@Composable
fun StatusBarPopup(
    viewModel: PopupChipModel.Shown,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
) {
    val context = LocalContext.current
    val density = Density(context)
    Popup(
        alignment = Alignment.TopCenter,
        properties =
            PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        offset =
            IntOffset(
                x = 0,
                y = with(density) { dimensionResource(R.dimen.status_bar_height).roundToPx() },
            ),
        onDismissRequest = { viewModel.hidePopup() },
    ) {
        val popupView = LocalView.current
        var mediaColor by remember { mutableStateOf(0) }
        DisposableEffect(viewModel.popupContent) {
            val listener = object : MediaSessionManager.MediaDataListener {
                override fun onMediaColorsChanged(color: Int) {
                    mediaColor = color
                }
            }
            MediaSessionManager.get().addListener(listener)
            onDispose {
                MediaSessionManager.get().removeListener(listener)
            }
        }
        var popupBoundsInScreen by remember { mutableStateOf<Rect?>(null) }

        val transformOrigin by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f) {
                    TransformOrigin(0.5f, 0f)
                } else {
                    val pivotX =
                        ((chip.center.x - popup.left) / popup.width).coerceIn(0.05f, 0.95f)
                    val pivotY =
                        if (popup.height > 0f) {
                            ((chip.center.y - popup.top) / popup.height).coerceIn(0f, 0.3f)
                        } else {
                            0f
                        }
                    TransformOrigin(pivotX, pivotY)
                }
            }
        }

        val initialScaleFromChip by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f || popup.height <= 0f) {
                    Offset(0.4f, 0.4f)
                } else {
                    Offset(
                        x = (chip.width / popup.width).coerceIn(0.2f, 1f),
                        y = (chip.height / popup.height).coerceIn(0.15f, 1f),
                    )
                }
            }
        }

        val scaleX = remember { Animatable(initialScaleFromChip.x) }
        val scaleY = remember { Animatable(initialScaleFromChip.y) }
        val alpha = remember { Animatable(0f) }
        val translationY = remember { Animatable(-24f) }

        LaunchedEffect(isVisible, popupBoundsInScreen != null) {
            if (isVisible && popupBoundsInScreen != null) {
                scaleX.snapTo(initialScaleFromChip.x)
                scaleY.snapTo(initialScaleFromChip.y)
                alpha.snapTo(0f)
                translationY.snapTo(-24f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.6f,
                                    stiffness = Spring.StiffnessLow,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.65f,
                                    stiffness = Spring.StiffnessLow,
                                ),
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = 0f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.7f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch { alpha.animateTo(1f, animationSpec = tween(180)) }
                }
            } else if (!isVisible) {
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = initialScaleFromChip.x,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = initialScaleFromChip.y,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch {
                        translationY.animateTo(-16f, animationSpec = spring(stiffness = Spring.StiffnessMedium))
                    }
                    launch { alpha.animateTo(0f, animationSpec = tween(160)) }
                }
            }
        }

        val islandActions = remember(viewModel.popupContent) {
            object : IslandActions {
                override fun collapseIsland() {
                    viewModel.hidePopup()
                }
                override fun dismissEvent(event: IslandEvent) {
                    if (event is IslandEvent.Torch) {
                        val popupContent = viewModel.popupContent
                        if (popupContent is PopupContentModel.Flashlight) {
                            popupContent.model.turnOff.invoke()
                        }
                    }
                    viewModel.hidePopup()
                }
                override fun togglePlayPause() {
                    val popupContent = viewModel.popupContent
                    if (popupContent is PopupContentModel.Media) {
                        popupContent.model.playOrPause?.action?.run()
                    }
                }
                override fun skipNext() {
                    val popupContent = viewModel.popupContent
                    if (popupContent is PopupContentModel.Media) {
                        popupContent.model.nextAction?.action?.run()
                    }
                }
                override fun skipPrev() {
                    val popupContent = viewModel.popupContent
                    if (popupContent is PopupContentModel.Media) {
                        popupContent.model.previousAction?.action?.run()
                    }
                }
                override fun seekTo(position: Long) {
                    val popupContent = viewModel.popupContent
                    if (popupContent is PopupContentModel.Media) {
                        popupContent.model.seekTo?.invoke(position)
                    }
                }
                override fun sendCustomAction(action: String) {}
                override fun openMediaOutputSwitcher() {}
                override fun openMediaApp() {
                    val popupContent = viewModel.popupContent
                    if (popupContent is PopupContentModel.Media) {
                        popupContent.model.openApp?.invoke()
                    }
                }
                override fun toggleTorch() {
                    val popupContent = viewModel.popupContent
                    if (popupContent is PopupContentModel.Flashlight) {
                        popupContent.model.turnOff.invoke()
                    }
                }
                override fun disconnectBluetooth(address: String) {}
                override fun setRingerMode(mode: Int) {}
                override fun setTorchLevel(level: Int) {}
                override fun setTorchLevelTemporary(level: Int) {}
                override fun copyToClipboard(text: String) {}
                override fun copyUriToClipboard(uri: android.net.Uri) {}
                override fun openUrl(url: String) {}
                override fun removeClipboardItem(id: Long) {}
                override fun switchToApp(taskId: Int) {}
                override fun killApp(taskId: Int) {}
                override fun onNotificationInteraction(eventId: String) {}
                override fun onNotificationInteractionEnd(eventId: String) {}
                override fun onNotificationAlertInteractionStart() {}
                override fun onNotificationAlertInteractionEnd() {}
                override fun launchNotificationDismissingKeyguard(event: IslandEvent.Notification) {}
            }
        }

        val dummyHapticsFactory = remember {
            object : com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel.Factory {
                override fun create(
                    interactionSource: androidx.compose.foundation.interaction.InteractionSource,
                    sliderRange: ClosedFloatingPointRange<Float>,
                    orientation: androidx.compose.foundation.gestures.Orientation,
                    sliderHapticFeedbackConfig: com.android.systemui.haptics.slider.SliderHapticFeedbackConfig,
                    sliderTrackerConfig: com.android.systemui.haptics.slider.SeekableSliderTrackerConfig,
                ): com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel {
                    throw UnsupportedOperationException()
                }
            }
        }

        DisposableEffect(popupView) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (!hasFocus) {
                    viewModel.hidePopup()
                }
            }
            popupView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose {
                popupView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            }
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(60)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            Box(
                modifier =
                    Modifier.padding(8.dp)
                        .wrapContentSize()
                        .onGloballyPositioned { coordinates ->
                            popupBoundsInScreen = coordinates.boundsInScreen(popupView)
                        }
                        .graphicsLayer {
                            this.scaleX = scaleX.value
                            this.scaleY = scaleY.value
                            this.alpha = alpha.value
                            this.translationY = translationY.value
                            this.transformOrigin = transformOrigin
                        }
            ) {
                when (val popupContent = viewModel.popupContent) {
                    is PopupContentModel.Media -> {
                        val model = popupContent.model
                        val eventMedia = remember(model, mediaColor) {
                            val helper = com.android.systemui.statusbar.util.MediaSessionTrackHelper.getInstance(context)
                            val albumArtDrawable = (model.artworkIcon as? Icon.Loaded)?.drawable
                                ?: helper.getMediaBitmap()?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
                            val appIconDrawable = (model.appIcon as? Icon.Loaded)?.drawable

                            IslandEvent.Media(
                                track = model.songName?.toString().orEmpty(),
                                artist = model.artistName?.toString().orEmpty(),
                                isPlaying = model.isPlaying,
                                albumArt = albumArtDrawable,
                                progress = if (model.durationMs > 0L) model.positionMs.toFloat() / model.durationMs else 0f,
                                duration = model.durationMs,
                                position = model.positionMs,
                                packageName = model.packageName.orEmpty(),
                                appIcon = appIconDrawable,
                                mediaColor = mediaColor,
                            )
                        }
                        val hasLyrics = model.isDynamicIslandLyricsEnabled && (!model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank())
                        if (hasLyrics) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                                    MediaCard(event = eventMedia, interactor = islandActions)
                                }
                                LyricsCard(model = model)
                            }
                        } else {
                            Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                                MediaCard(event = eventMedia, interactor = islandActions)
                            }
                        }
                    }
                    is PopupContentModel.ScreenRecord -> ScreenRecordPopup(model = popupContent.model)
                    is PopupContentModel.LiveScore -> {
                        val model = popupContent.model
                        val eventSports = remember(model) {
                            val titleParts = model.title?.split(Regex("\\s*vs\\s*|\\s*-\\s*|\\s*@\\s*"), limit = 2) ?: emptyList()
                            val team1Name = titleParts.getOrNull(0) ?: model.title ?: model.appName
                            val team2Name = titleParts.getOrNull(1) ?: ""

                            val scoreParts = model.score.split(Regex("\\s*-\\s*|\\s*:\\s*"), limit = 2)
                            val score1 = scoreParts.getOrNull(0) ?: model.score
                            val score2 = scoreParts.getOrNull(1) ?: ""

                            IslandEvent.Sports(
                                team1Name = team1Name,
                                team2Name = team2Name,
                                score1 = score1,
                                score2 = score2,
                                team1Icon = (model.icon as? Icon.Loaded)?.drawable,
                                statusDetail = model.subtitle.orEmpty(),
                                league = model.appName,
                                key = model.key,
                            )
                        }
                        Box(modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)) {
                            PrimaryCard {
                                SportsExpanded(event = eventSports, interactor = islandActions)
                            }
                        }
                    }
                    is PopupContentModel.Flashlight -> {
                        val model = popupContent.model
                        val eventTorch = remember(model) {
                            IslandEvent.Torch(
                                level = model.levelPercent ?: -1,
                                maxLevel = 100
                            )
                        }
                        Box(modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)) {
                            PrimaryCard {
                                TorchExpanded(
                                    event = eventTorch,
                                    interactor = islandActions,
                                    hapticsViewModelFactory = dummyHapticsFactory
                                )
                            }
                        }
                    }
                    is PopupContentModel.PromotedOngoing -> {
                        val event = popupContent.event
                        Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                            PrimaryCard {
                                PromotedOngoingExpanded(event = event, interactor = islandActions)
                            }
                        }
                    }
                    is PopupContentModel.OngoingCall -> {
                        val event = popupContent.event
                        Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                            PrimaryCard {
                                CallExpanded(event = event, interactor = islandActions)
                            }
                        }
                    }
                    PopupContentModel.None ->
                        when (viewModel.chipId) {
                            is PopupChipId.AvControlsIndicator -> AvControlsChipPopup()
                            is PopupChipId.ShareScreenPrivacyIndicator ->
                                ShareScreenPrivacyIndicatorPopup()
                            else -> Unit
                        }
                }
            }
        }
    }
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
