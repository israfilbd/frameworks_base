/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: LicenseRef-InfinityX-Proprietary
 */

package com.android.systemui.qs.panels.ui.compose

import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.android.systemui.statusbar.util.MediaSessionTrackHelper
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MediaPanelShape = RoundedCornerShape(24.dp)
private val MediaTrackBackground = Color.White.copy(alpha = 0.14f)

private object InfinityMediaAppIconCache {
    private val icons = HashMap<String, Bitmap?>()

    fun get(context: Context, packageName: String): Bitmap? {
        synchronized(icons) {
            if (icons.containsKey(packageName)) {
                return icons[packageName]
            }
        }
        val loaded =
            try {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 48, height = 48)
            } catch (_: Exception) {
                null
            }
        synchronized(icons) {
            icons[packageName] = loaded
        }
        return loaded
    }
}

private data class MediaState(
    val title: String? = null,
    val artist: String? = null,
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val controller: MediaController? = null,
    val packageName: String? = null,
    val playbackPositionMs: Long = 0L,
    val playbackUpdateTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
)

@Composable
fun rememberInfinityMediaHasSession(): Boolean {
    val context = LocalContext.current
    DisposableEffect(context) {
        InfinitySharedMediaStore.ensureInitialized(context)
        onDispose {}
    }
    return InfinitySharedMediaStore.mediaState.controller != null
}

@Composable
fun InfinityMediaPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(context) {
        InfinitySharedMediaStore.ensureInitialized(context)
        InfinitySharedMediaStore.onPanelAttached()
        onDispose { InfinitySharedMediaStore.onPanelDetached() }
    }
    InfinityMediaPanelContent(
        mediaState = InfinitySharedMediaStore.mediaState,
        modifier = modifier,
    )
}

private object InfinitySharedMediaStore {
    var mediaState by mutableStateOf(MediaState())
        private set
    var nowElapsedRealtimeMs by mutableLongStateOf(SystemClock.elapsedRealtime())
        private set

    private var initialized = false
    private var trackHelper: MediaSessionTrackHelper? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private var activePanelCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
            if (tickerRunning) {
                handler.postDelayed(this, 500L)
            }
        }
    }

    private val mediaListener = object : MediaSessionTrackHelper.MediaMetadataListener {
        override fun onMediaMetadataChanged() {
            updateFromHelper()
        }

        override fun onPlaybackStateChanged() {
            updateFromHelper()
        }
    }

    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        trackHelper = MediaSessionTrackHelper.getInstance(context)
        trackHelper?.addMediaMetadataListener(mediaListener)
        updateFromHelper()
        syncTickerRunning()
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        handler.postDelayed(ticker, 500L)
    }

    private fun stopTicker() {
        if (!tickerRunning) return
        tickerRunning = false
        handler.removeCallbacks(ticker)
    }

    private fun syncTickerRunning() {
        val shouldRun = activePanelCount > 0 && mediaState.controller != null && mediaState.isPlaying
        if (shouldRun) {
            startTicker()
        } else {
            stopTicker()
        }
    }

    fun onPanelAttached() {
        activePanelCount += 1
        syncTickerRunning()
    }

    fun onPanelDetached() {
        activePanelCount = (activePanelCount - 1).coerceAtLeast(0)
        syncTickerRunning()
    }

    private fun updateFromHelper() {
        val helper = trackHelper ?: return
        val ctrl = helper.getCurrentMediaController()
        if (ctrl == null) {
            if (mediaState != MediaState()) {
                mediaState = MediaState()
            }
            syncTickerRunning()
            return
        }

        val meta = helper.getCurrentMediaMetadata()
        val rawTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (rawTitle.isNullOrBlank()) {
            if (mediaState != MediaState()) {
                mediaState = MediaState()
            }
            syncTickerRunning()
            return
        }

        val title = sanitizeMediaText(rawTitle) ?: rawTitle.trim()

        val playbackState = helper.getMediaControllerPlaybackState()
        val updated = MediaState(
            title = title,
            artist = sanitizeMediaText(
                meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ),
            albumArt = helper.getMediaBitmap(),
            isPlaying = helper.isMediaPlaying(),
            controller = ctrl,
            packageName = ctrl.packageName,
            playbackPositionMs = playbackState?.position ?: 0L,
            playbackUpdateTimeMs =
                playbackState?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            durationMs =
                meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L)
                    ?: 0L,
            playbackSpeed = playbackState?.playbackSpeed ?: 1f,
        )

        if (mediaState != updated) {
            mediaState = updated
        }
        nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        syncTickerRunning()
    }
}

private fun launchMusicApp(context: Context, packageName: String?) {
    if (packageName.isNullOrBlank()) return
    try {
        (context.getSystemService(Context.STATUS_BAR_SERVICE) as? StatusBarManager)?.collapsePanels()
    } catch (_: Exception) {}
    try {
        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun currentPlaybackPositionMs(mediaState: MediaState, nowElapsedRealtimeMs: Long): Long {
    val duration = mediaState.durationMs.coerceAtLeast(0L)
    if (duration == 0L) return 0L

    val base = mediaState.playbackPositionMs.coerceAtLeast(0L)
    if (!mediaState.isPlaying) {
        return base.coerceAtMost(duration)
    }

    val elapsed = (nowElapsedRealtimeMs - mediaState.playbackUpdateTimeMs).coerceAtLeast(0L)
    val advanced = base + (elapsed * mediaState.playbackSpeed).toLong()
    return advanced.coerceIn(0L, duration)
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun sanitizeMediaText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null

    val parenIndex = raw.indexOf('(').let { if (it >= 0) it else Int.MAX_VALUE }
    val pipeIndex = raw.indexOf('|').let { if (it >= 0) it else Int.MAX_VALUE }
    val cutIndex = minOf(parenIndex, pipeIndex)
    val sanitized = if (cutIndex == Int.MAX_VALUE) raw else raw.substring(0, cutIndex)

    return sanitized.trim().takeIf { it.isNotEmpty() }
}

@Composable
private fun MediaProgressSection(
    mediaState: MediaState,
    durationMs: Long,
) {
    var isSeeking by remember(mediaState.controller) { mutableStateOf(false) }
    var seekFraction by remember(mediaState.controller) { mutableFloatStateOf(0f) }
    val nowElapsedRealtimeMs = InfinitySharedMediaStore.nowElapsedRealtimeMs

    val livePositionMs by remember(mediaState, nowElapsedRealtimeMs) {
        derivedStateOf { currentPlaybackPositionMs(mediaState, nowElapsedRealtimeMs) }
    }
    val liveFraction by remember(livePositionMs, durationMs) {
        derivedStateOf {
            if (durationMs > 0L) {
                (livePositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val sliderFraction by remember(isSeeking, seekFraction, liveFraction) {
        derivedStateOf { if (isSeeking) seekFraction.coerceIn(0f, 1f) else liveFraction }
    }
    val displayedPositionMs by remember(durationMs, isSeeking, seekFraction, livePositionMs) {
        derivedStateOf {
            if (durationMs > 0L) {
                if (isSeeking) {
                    (seekFraction.coerceIn(0f, 1f) * durationMs.toFloat()).toLong()
                } else {
                    livePositionMs
                }
            } else {
                0L
            }
        }
    }

    Row(
        modifier = Modifier.width(136.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDurationMs(displayedPositionMs),
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 8.sp,
            modifier = Modifier.offset(x = 4.dp),
        )
        Text(
            text = formatDurationMs(durationMs),
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 8.sp,
            modifier = Modifier.offset(x = (-4).dp),
        )
    }

    Slider(
        value = sliderFraction,
        onValueChange = {
            isSeeking = true
            seekFraction = it.coerceIn(0f, 1f)
        },
        onValueChangeFinished = {
            val seekTo = (seekFraction.coerceIn(0f, 1f) * durationMs.toFloat()).toLong()
            mediaState.controller?.transportControls?.seekTo(seekTo)
            isSeeking = false
        },
        valueRange = 0f..1f,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White.copy(alpha = 0.92f),
            inactiveTrackColor = Color.White.copy(alpha = 0.28f),
        ),
        modifier = Modifier
            .width(136.dp)
            .height(14.dp),
    )
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun InfinityMediaPanelContent(
    mediaState: MediaState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlayBrush =
        remember {
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.6f))
            )
        }

    var tapFlashTarget by remember { mutableFloatStateOf(0f) }
    val tapFlashAlpha by animateFloatAsState(
        targetValue = tapFlashTarget,
        animationSpec = tween(120),
        label = "media_tap_flash"
    )
    val interactionSource = remember { MutableInteractionSource() }

    val appIconBitmap = remember(mediaState.packageName) {
        val pkg = mediaState.packageName ?: return@remember null
        InfinityMediaAppIconCache.get(context, pkg)
    }

    val durationMs = mediaState.durationMs.coerceAtLeast(0L)
    val showInteractiveLayout = mediaState.controller != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(MediaPanelShape)
            .background(MediaTrackBackground)
            .clickable(
                enabled = mediaState.packageName != null,
                interactionSource = interactionSource,
                indication = null,
            ) {
                tapFlashTarget = 0.12f
                scope.launch {
                    delay(100)
                    tapFlashTarget = 0f
                }
                launchMusicApp(context, mediaState.packageName)
            }
    ) {
        mediaState.albumArt?.let { bmp ->
            Image(
                painter = BitmapPainter(bmp.asImageBitmap()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MediaPanelShape),
                alpha = 0.52f,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlayBrush)
        )

        if (tapFlashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = tapFlashAlpha))
            )
        }

        if (showInteractiveLayout) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaState.title ?: "Not Playing",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = Color.White,
                                fontFamily = FontFamily(Font(DeviceFontFamilyName("variable-title-medium-emphasized"))),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!mediaState.artist.isNullOrBlank()) {
                            Text(
                                text = mediaState.artist,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.82f),
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (appIconBitmap != null) {
                        Image(
                            painter = BitmapPainter(appIconBitmap.asImageBitmap()),
                            contentDescription = "App Icon",
                            modifier = Modifier.size(26.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = "App Icon",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (durationMs > 0L) {
                        MediaProgressSection(
                            mediaState = mediaState,
                            durationMs = durationMs,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { mediaState.controller?.transportControls?.skipToPrevious() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White.copy(alpha = if (mediaState.controller != null) 1f else 0.5f),
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        IconButton(
                            onClick = {
                                val ctrl = mediaState.controller
                                if (ctrl != null) {
                                    if (mediaState.isPlaying) {
                                        ctrl.transportControls.pause()
                                    } else {
                                        ctrl.transportControls.play()
                                    }
                                }
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Crossfade(
                                targetState = mediaState.isPlaying,
                                animationSpec = tween(200),
                                label = "play_pause_crossfade",
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color.White.copy(alpha = if (mediaState.controller != null) 1f else 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        IconButton(
                            onClick = { mediaState.controller?.transportControls?.skipToNext() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White.copy(alpha = if (mediaState.controller != null) 1f else 0.5f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = mediaState.title ?: "Not Playing",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color.White,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp),
                    )
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}