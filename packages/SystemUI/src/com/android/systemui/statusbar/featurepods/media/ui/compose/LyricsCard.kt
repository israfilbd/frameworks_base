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

package com.android.systemui.statusbar.featurepods.media.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel

private val PopupShape = RoundedCornerShape(34.dp)
private val timestampRegex = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")

data class LyricLine(val timestampMs: Long, val text: String)

@Composable
fun LyricsCard(
    model: MediaControlChipModel,
    modifier: Modifier = Modifier,
) {
    val syncedLyrics = model.syncedLyrics
    val plainLyrics = model.lyrics

    val lyricLines = remember(syncedLyrics) {
        if (syncedLyrics.isNullOrBlank()) emptyList() else parseLrc(syncedLyrics)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = PopupShape,
        shadowElevation = 12.dp,
        modifier = modifier.widthIn(min = 320.dp, max = 400.dp).height(200.dp),
    ) {
        if (lyricLines.isNotEmpty()) {
            var currentPosition by remember { mutableLongStateOf(model.positionMs) }
            
            LaunchedEffect(model.positionMs, model.isPlaying) {
                if (model.isPlaying) {
                    val baseRealtime = android.os.SystemClock.elapsedRealtime()
                    val basePos = model.positionMs
                    while (isActive) {
                        val elapsed = android.os.SystemClock.elapsedRealtime() - baseRealtime
                        currentPosition = basePos + elapsed
                        delay(200)
                    }
                } else {
                    currentPosition = model.positionMs
                }
            }

            val activeIndex = remember(lyricLines, currentPosition) {
                lyricLines.indexOfLast { currentPosition >= it.timestampMs }
            }

            val lazyListState = rememberLazyListState()

            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0 && activeIndex < lyricLines.size) {
                    lazyListState.animateScrollToItem(activeIndex)
                }
            }

            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(vertical = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                itemsIndexed(lyricLines) { index, line ->
                    val isActive = index == activeIndex
                    val alpha by animateFloatAsState(
                        targetValue = if (isActive) 1f else 0.4f,
                        animationSpec = tween(250),
                        label = "lyric_alpha",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isActive) 1.04f else 0.96f,
                        animationSpec = tween(250),
                        label = "lyric_scale",
                    )
                    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                    Text(
                        text = line.text,
                        color = LocalContentColor.current,
                        fontSize = 16.sp,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .graphicsLayer {
                                this.alpha = alpha
                                scaleX = scale
                                scaleY = scale
                            }
                    )
                }
            }
        } else if (!plainLyrics.isNullOrBlank()) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                item {
                    Text(
                        text = plainLyrics,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun LockscreenLyricsView(
    model: MediaControlChipModel,
    modifier: Modifier = Modifier,
) {
    val syncedLyrics = model.syncedLyrics ?: return

    val lyricLines = remember(syncedLyrics) {
        if (syncedLyrics.isBlank()) emptyList() else parseLrc(syncedLyrics)
    }

    if (lyricLines.isEmpty()) return

    var currentPosition by remember { mutableLongStateOf(model.positionMs) }

    LaunchedEffect(model.positionMs, model.isPlaying) {
        if (model.isPlaying) {
            val baseRealtime = android.os.SystemClock.elapsedRealtime()
            val basePos = model.positionMs
            while (isActive) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - baseRealtime
                currentPosition = basePos + elapsed
                delay(100)
            }
        } else {
            currentPosition = model.positionMs
        }
    }

    val activeIndex = remember(lyricLines, currentPosition) {
        lyricLines.indexOfLast { currentPosition >= it.timestampMs }
    }

    if (activeIndex < 0) return

    val prevLine = lyricLines.getOrNull(activeIndex - 1)?.text.orEmpty()
    val currentLine = lyricLines.getOrNull(activeIndex)?.text.orEmpty()
    val nextLine = lyricLines.getOrNull(activeIndex + 1)?.text.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Line 1
        AnimatedLyricsLine(
            text = prevLine,
            fontSize = 13.sp,
            alpha = 0.45f,
            scale = 0.9f,
            fontWeight = FontWeight.Normal,
        )

        // Line 2
        AnimatedLyricsLine(
            text = currentLine,
            fontSize = 16.sp,
            alpha = 1.0f,
            scale = 1.05f,
            fontWeight = FontWeight.SemiBold,
        )

        // Line 3
        AnimatedLyricsLine(
            text = nextLine,
            fontSize = 13.sp,
            alpha = 0.45f,
            scale = 0.9f,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun AnimatedLyricsLine(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    alpha: Float,
    scale: Float,
    fontWeight: FontWeight,
) {
    val animAlpha by animateFloatAsState(
        targetValue = if (text.isEmpty()) 0f else alpha,
        animationSpec = tween(350),
        label = "lyric_line_alpha",
    )
    val animScale by animateFloatAsState(
        targetValue = if (text.isEmpty()) 0.8f else scale,
        animationSpec = tween(350),
        label = "lyric_line_scale",
    )

    Text(
        text = text,
        color = Color.White,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = animAlpha
                scaleX = animScale
                scaleY = animScale
            }
    )
}

private fun parseLrc(lrcText: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    
    lrcText.split("\n").forEach { lineStr ->
        val trimmedLine = lineStr.trim()
        if (trimmedLine.isBlank()) return@forEach
        
        val timestamps = mutableListOf<Long>()
        var currentIndex = 0
        while (currentIndex < trimmedLine.length) {
            val match = timestampRegex.find(trimmedLine, currentIndex)
            if (match == null || match.range.first != currentIndex) {
                break
            }
            
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val fraction = match.groupValues[3]
            val fracMs = when (fraction.length) {
                1 -> fraction.toLong() * 100L
                2 -> fraction.toLong() * 10L
                3 -> fraction.toLong()
                else -> 0L
            }
            val timestampMs = (min * 60 + sec) * 1000L + fracMs
            timestamps.add(timestampMs)
            
            currentIndex = match.range.last + 1
        }
        
        val text = trimmedLine.substring(currentIndex).trim()
        if (text.isNotEmpty() || lines.isNotEmpty()) {
            timestamps.forEach { ts ->
                lines.add(LyricLine(ts, text))
            }
        }
    }
    return lines.sortedBy { it.timestampMs }
}
