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

package com.android.systemui.statusbar.featurepods.media.domain.interactor

import android.app.PendingIntent
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.remedia.data.model.MediaDataModel
import com.android.systemui.media.remedia.data.repository.MediaRepositoryImpl
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.MEDIA_CONTROLS
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.SHOW_LYRICS
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

private data class LyricsCacheKey(val artist: String, val title: String)
private sealed interface LyricsLookupResult {
    data class Found(val plainLyrics: String?, val syncedLyrics: String?) : LyricsLookupResult
    data class NotFound(val timestamp: Long) : LyricsLookupResult
}
private val lyricsCache = android.util.LruCache<LyricsCacheKey, LyricsLookupResult>(50)

private const val PLAYBACK_POSITION_POLL_INTERVAL_MS = 1000L

/**
 * Interactor for managing the state of the media control chip in the status bar.
 *
 * Provides a [StateFlow] of [MediaControlChipModel] representing the current state of the media
 * control chip. Emits a new [MediaControlChipModel] when there is an active media session and the
 * corresponding user preference is found, otherwise emits null.
 */
@SysUISingleton
class MediaControlChipInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Background private val backgroundScope: CoroutineScope,
    private val mediaRepository: MediaRepositoryImpl,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
    private val axDynamicBarSettings: AxDynamicBarSettings,
) {
    private val isEnabled = MutableStateFlow(false)
    private val isDynamicIslandEnabled = MutableStateFlow(false)
    private var isInitialized = false
    private val currentLyrics = MutableStateFlow<String?>(null)
    private val currentSyncedLyrics = MutableStateFlow<String?>(null)
    private var lastFetchedSong: String? = null
    private var lastFetchedArtist: String? = null
    private val dynamicIslandObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateDynamicIslandState()
            }
        }

    private val mediaControlChipModelForScene: Flow<MediaControlState> = snapshotFlow {
        mediaRepository.currentMedia.firstOrNull { it.isActive }?.toMediaControlState(
            context = context,
            activityStarter = activityStarter,
            activityIntentHelper = activityIntentHelper,
            lockscreenUserManager = lockscreenUserManager,
            keyguardStateController = keyguardStateController,
        )
            ?: MediaControlState.Hidden
    }

    /**
     * A flow of [MediaControlChipModel] representing the current state of the media controls chip.
     * This flow emits null when no active media is playing or when playback information is
     * unavailable. This flow is only active when [MediaControlsInComposeFlag] is disabled.
     */
    private val mediaControlChipModelLegacy = MutableStateFlow(MediaControlState.Hidden)

    fun updateMediaControlChipModelLegacy(mediaData: MediaData?) {
        if (!MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelLegacy.value =
                mediaData?.toMediaControlState(
                    context = context,
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                ) ?: MediaControlState.Hidden
        }
    }

    private val mediaControlState: Flow<MediaControlState> =
        if (MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelForScene
        } else {
            mediaControlChipModelLegacy
        }

    private val livePlaybackInfo: Flow<PlaybackInfo?> =
        mediaControlState
            .flatMapLatest { state -> state.token.playbackInfoFlow(context) }
            .distinctUntilChanged()

    private val baseMediaControlChipModel: Flow<MediaControlChipModel?> =
        run {
            val isAxMediaEnabledFlow = combine(
                axDynamicBarSettings.isEnabled,
                axDynamicBarSettings.disabledEventTypes,
                axDynamicBarSettings.isLockscreenMediaEnabled,
                axDynamicBarSettings.isLockscreenMediaLyricsEnabled,
            ) { isEnabled, disabledEvents, isLockscreenMediaEnabled, isLockscreenMediaLyricsEnabled ->
                (isEnabled && "media" !in disabledEvents) || isLockscreenMediaEnabled || isLockscreenMediaLyricsEnabled
            }

            val isMediaFeatureActiveFlow = combine(
                isEnabled,
                isDynamicIslandEnabled,
                observeDynamicIslandFeatureEnabled(context, MEDIA_CONTROLS),
                isAxMediaEnabledFlow,
            ) { isEnabled, isDynamicIslandEnabled, mediaControlsEnabled, isAxMediaEnabled ->
                isEnabled && ((isDynamicIslandEnabled && mediaControlsEnabled) || isAxMediaEnabled)
            }.distinctUntilChanged()

            isMediaFeatureActiveFlow.flatMapLatest { active ->
                if (!active) {
                    flowOf(null)
                } else {
                    combine(
                        mediaControlState,
                        livePlaybackInfo,
                    ) { state, playbackInfo ->
                        state.model?.withPlaybackInfo(playbackInfo)
                    }
                }
            }
        }

    /** The currently active [MediaControlChipModel] */
    val mediaControlChipModel: StateFlow<MediaControlChipModel?> =
        combine(
            baseMediaControlChipModel,
            currentLyrics,
            currentSyncedLyrics,
            observeDynamicIslandFeatureEnabled(context, SHOW_LYRICS, false),
        ) { baseModel, lyrics, syncedLyrics, isLyricsEnabled ->
            baseModel?.copy(
                lyrics = lyrics,
                syncedLyrics = syncedLyrics,
                isDynamicIslandLyricsEnabled = isLyricsEnabled
            )
        }
        .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), null)

    /** Initializes setting observation. This must be called from a CoreStartable. */
    fun initialize() {
        if (isInitialized) {
            return
        }
        isInitialized = true
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(
                Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND),
            false,
            dynamicIslandObserver,
            UserHandle.USER_ALL
        )
        updateDynamicIslandState()
        isEnabled.value = true
        setupLyricsWorker()
    }

    private fun updateDynamicIslandState() {
        isDynamicIslandEnabled.value =
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
                0,
                UserHandle.USER_CURRENT
            ) != 0
    }

    private var lyricsFetchJob: Job? = null

    private fun setupLyricsWorker() {
        backgroundScope.launch {
            val isAxLyricsEnabledFlow = combine(
                axDynamicBarSettings.isEnabled,
                axDynamicBarSettings.disabledEventTypes,
                axDynamicBarSettings.isLockscreenMediaLyricsEnabled,
                axDynamicBarSettings.isLockscreenMediaEnabled,
            ) { isEnabled, disabledEvents, isLockscreenLyricsEnabled, isLockscreenMediaEnabled ->
                val isDynamicBarLyricsEnabled = isEnabled && "media" !in disabledEvents && "lyrics" !in disabledEvents
                val isLockscreenLyricsActive = isLockscreenLyricsEnabled
                isDynamicBarLyricsEnabled || isLockscreenLyricsActive
            }

            combine(
                mediaControlState,
                observeDynamicIslandFeatureEnabled(context, SHOW_LYRICS, false),
                isAxLyricsEnabledFlow
            ) { state, lyricsEnabled, axLyricsEnabled ->
                Pair(state.model, lyricsEnabled || axLyricsEnabled)
            }.collect { (model, lyricsEnabled) ->
                if (model != null && lyricsEnabled) {
                    val song = model.songName?.toString()
                    val artist = model.artistName?.toString()
                    val durationMs = model.durationMs

                    if (!song.isNullOrBlank() && !artist.isNullOrBlank()) {
                        if (song != lastFetchedSong || artist != lastFetchedArtist) {
                            lyricsFetchJob?.cancel() // Cancel previous search only when track actually changes!
                            lastFetchedSong = song
                            lastFetchedArtist = artist
                            currentLyrics.value = null
                            currentSyncedLyrics.value = null
                            lyricsFetchJob = backgroundScope.launch {
                                fetchLyrics(artist, song, durationMs)
                            }
                        }
                    } else {
                        lyricsFetchJob?.cancel()
                        lastFetchedSong = null
                        lastFetchedArtist = null
                        currentLyrics.value = null
                        currentSyncedLyrics.value = null
                    }
                } else {
                    lyricsFetchJob?.cancel()
                    if (model == null) {
                        lastFetchedSong = null
                        lastFetchedArtist = null
                    }
                    currentLyrics.value = null
                    currentSyncedLyrics.value = null
                }
            }
        }
    }

    private fun cleanSongTitle(title: String): String {
        if (title.isBlank()) return title
        var cleaned = title
        val separatorRegex = Regex("\\s+[-–—|•]\\s+")
        cleaned = cleaned.split(separatorRegex)[0]
        val parentheticalRegex = Regex("(?i)\\s*[\\(\\[]([^\\)\\]]*(?:feat|featuring|ft\\.?|with|remaster|live|video|version|edit|acoustic|single|studio|mono|stereo|re-recorded|lyric|mv|hd|hq|audio|visualizer|official)[^\\)\\]]*)[\\]\\)]")
        cleaned = cleaned.replace(parentheticalRegex, "")
        val metaTermsRegex = Regex("(?i)\\s*\\b(official\\s+video|official\\s+audio|lyric\\s+video|official\\s+music\\s+video|music\\s+video|lyric\\s+card|lyric|lyrics|video|mv|hd|hq|audio|visualizer|uncensored|clean\\s+version|extended\\s+mix)\\b")
        cleaned = cleaned.replace(metaTermsRegex, "")
        val featRegex = Regex("(?i)\\s+\\b(feat\\.?|featuring|ft\\.?|with)\\b.*")
        cleaned = cleaned.replace(featRegex, "")
        cleaned = cleaned.replace(Regex("[\"']"), "")
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        return cleaned.trim()
    }

    private fun cleanSongTitleModerate(title: String): String {
        if (title.isBlank()) return title
        var cleaned = title
        val parentheticalRegex = Regex("(?i)\\s*[\\(\\[]([^\\)\\]]*(?:feat|featuring|ft\\.?|with|remaster|live|video|version|edit|acoustic|single|studio|mono|stereo|re-recorded|lyric|mv|hd|hq|audio|visualizer|official)[^\\)\\]]*)[\\]\\)]")
        cleaned = cleaned.replace(parentheticalRegex, "")
        val metaTermsRegex = Regex("(?i)\\s*\\b(official\\s+video|official\\s+audio|lyric\\s+video|official\\s+music\\s+video|music\\s+video|lyric\\s+card|lyric|lyrics|video|mv|hd|hq|audio|visualizer|uncensored|clean\\s+version|extended\\s+mix)\\b")
        cleaned = cleaned.replace(metaTermsRegex, "")
        val featRegex = Regex("(?i)\\s+\\b(feat\\.?|featuring|ft\\.?|with)\\b.*")
        cleaned = cleaned.replace(featRegex, "")
        cleaned = cleaned.replace(Regex("[\"']"), "")
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        return cleaned.trim()
    }

    private fun cleanArtistName(artist: String): String {
        if (artist.isBlank()) return artist
        val splitRegex = Regex("(?i)\\s*[,/;]\\s*|\\s+\\b(feat\\.?|featuring|ft\\.?|and|&)\\b\\s+")
        return artist.split(splitRegex)[0].trim()
    }

    private fun generateSearchCandidates(artist: String, song: String): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()
        val cleanArtist = cleanArtistName(artist)
        
        // 1. Moderate-clean artist/title (Trustworthy, preserves connectors like "-")
        val moderateSong = cleanSongTitleModerate(song)
        if (cleanArtist.isNotBlank() && moderateSong.isNotBlank()) {
            candidates.add(Pair(cleanArtist, moderateSong))
        }

        // 2. Original artist/title (Raw metadata)
        if (artist.isNotBlank() && song.isNotBlank() && Pair(artist, song) !in candidates) {
            candidates.add(Pair(artist, song))
        }

        // 3. Aggressive cleanup fallback (splits on connectors)
        val cleanSong = cleanSongTitle(song)
        if (cleanArtist.isNotBlank() && cleanSong.isNotBlank() && Pair(cleanArtist, cleanSong) !in candidates) {
            candidates.add(Pair(cleanArtist, cleanSong))
        }

        return candidates
    }

    private fun normalize(value: String): String {
        return value.lowercase(java.util.Locale.US)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateMatchScore(
        queryArtist: String,
        queryTitle: String,
        resultArtist: String,
        resultTitle: String
    ): Int {
        val normQueryArtist = normalize(queryArtist)
        val normQueryTitle = normalize(queryTitle)
        val normResultArtist = normalize(resultArtist)
        val normResultTitle = normalize(resultTitle)

        return when {
            normResultArtist == normQueryArtist && normResultTitle == normQueryTitle -> 100
            normResultTitle == normQueryTitle && normResultArtist.contains(normQueryArtist) -> 90
            normResultArtist == normQueryArtist && normResultTitle.contains(normQueryTitle) -> 80
            normResultArtist.contains(normQueryArtist) && normResultTitle.contains(normQueryTitle) -> 60
            else -> 0
        }
    }

    private suspend fun fetchLyricsWithCandidate(
        artist: String,
        song: String,
        durationMs: Long,
        totalRequests: AtomicInteger
    ): LyricsLookupResult? {
        val cacheKey = LyricsCacheKey(artist, song)
        val cached = lyricsCache.get(cacheKey)
        if (cached != null) {
            when (cached) {
                is LyricsLookupResult.Found -> return cached
                is LyricsLookupResult.NotFound -> {
                    val ttl = 12 * 60 * 60 * 1000L // 12 hours negative cache TTL
                    if (SystemClock.elapsedRealtime() - cached.timestamp < ttl) {
                        return cached
                    } else {
                        lyricsCache.remove(cacheKey)
                    }
                }
            }
        }

        var attempt = 0
        val maxAttempts = 2
        var delayMs = 1500L
        var connection: java.net.HttpURLConnection? = null

        while (attempt < maxAttempts && coroutineContext.isActive) {
            try {
                // Enforce hard request ceiling budget
                if (totalRequests.get() >= 4) {
                    return null
                }
                totalRequests.incrementAndGet() // Increment right before opening connection

                val query = java.net.URLEncoder.encode("$artist $song", "UTF-8")
                val url = java.net.URL("https://lrclib.net/api/search?q=$query")
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "SystemUI-DynamicIslandLyrics/2.0 (https://lineageos.org)")

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(response)

                    var bestScore = -1
                    var bestLyricsResult: LyricsLookupResult.Found? = null

                    val searchLen = jsonArray.length().coerceAtMost(10)
                    for (i in 0 until searchLen) {
                        val item = jsonArray.getJSONObject(i)
                        val resArtist = item.optString("artistName", "")
                        val resTitle = item.optString("trackName", "")
                        val resDurationSeconds = item.optInt("duration", 0)
                        val resDurationMs = resDurationSeconds * 1000L
                        val plain = item.optString("plainLyrics", "")
                        val synced = item.optString("syncedLyrics", "")

                        if (plain.isNotBlank() || synced.isNotBlank()) {
                            val metaScore = calculateMatchScore(artist, song, resArtist, resTitle)
                            
                            val hasComparableDuration = durationMs > 0L && resDurationMs > 0L
                            val durationDifference = if (hasComparableDuration) Math.abs(durationMs - resDurationMs) else Long.MAX_VALUE
                            
                            // Combined recording score scoring algorithm
                            val durationModifier = when {
                                !hasComparableDuration -> 0
                                durationDifference <= 2000L -> 30
                                durationDifference <= 5000L -> 20
                                durationDifference <= 10000L -> 5
                                else -> -50
                            }
                            
                            val combinedScore = metaScore + durationModifier
                            
                            val canUsePlain = metaScore >= 80 && plain.isNotBlank()
                            val canUseSynced = synced.isNotBlank() && metaScore >= 80 && (
                                (hasComparableDuration && durationDifference <= 5000L) ||
                                (!hasComparableDuration && metaScore >= 90)
                            )

                            if (canUsePlain || canUseSynced) {
                                if (combinedScore > bestScore) {
                                    bestScore = combinedScore
                                    
                                    bestLyricsResult = LyricsLookupResult.Found(
                                        plainLyrics = plain.takeIf { canUsePlain },
                                        syncedLyrics = synced.takeIf { canUseSynced }
                                    )
                                }
                            }
                        }
                    }

                    if (bestLyricsResult != null) {
                        lyricsCache.put(cacheKey, bestLyricsResult)
                        return bestLyricsResult
                    } else {
                        return LyricsLookupResult.NotFound(SystemClock.elapsedRealtime())
                    }
                } else if (responseCode == 404) {
                    return LyricsLookupResult.NotFound(SystemClock.elapsedRealtime())
                } else if (responseCode == 429) {
                    val retryAfterSeconds = connection.getHeaderField("Retry-After")?.toIntOrNull()
                    if (retryAfterSeconds != null && retryAfterSeconds > 15) {
                        return null // Abandon immediately if Retry-After is too long (>15s)
                    }
                    val delaySec = retryAfterSeconds?.coerceIn(1, 15) ?: 5
                    attempt++
                    delay(delaySec * 1000L)
                } else if (responseCode in listOf(408, 500, 502, 503, 504)) {
                    attempt++
                    delay(delayMs)
                    delayMs *= 2
                } else {
                    return null // Permanent HTTP failure
                }
            } catch (e: java.io.IOException) {
                android.util.Log.e("MediaControlChipInteractor", "Network exception fetching lyrics, attempt $attempt", e)
                attempt++
                delay(delayMs)
                delayMs *= 2
            } catch (e: Exception) {
                android.util.Log.e("MediaControlChipInteractor", "Non-transient exception fetching lyrics", e)
                return null
            } finally {
                connection?.disconnect()
                connection = null
            }
        }
        return null
    }

    private suspend fun fetchLyrics(artist: String, song: String, durationMs: Long) {
        val originalCacheKey = LyricsCacheKey(normalize(artist), normalize(song))
        val originalCached = lyricsCache.get(originalCacheKey)
        if (originalCached != null) {
            when (originalCached) {
                is LyricsLookupResult.Found -> {
                    currentLyrics.value = originalCached.plainLyrics
                    currentSyncedLyrics.value = originalCached.syncedLyrics
                    return
                }
                is LyricsLookupResult.NotFound -> {
                    val ttl = 12 * 60 * 60 * 1000L // 12 hours negative cache TTL
                    if (SystemClock.elapsedRealtime() - originalCached.timestamp < ttl) {
                        currentLyrics.value = null
                        currentSyncedLyrics.value = null
                        return
                    } else {
                        lyricsCache.remove(originalCacheKey)
                    }
                }
            }
        }

        val candidates = generateSearchCandidates(artist, song)
        val totalRequests = AtomicInteger(0)
        
        var anyCandidateHadTransientFailure = false
        var anyCandidateMatched = false

        for (candidate in candidates) {
            if (totalRequests.get() >= 4) {
                anyCandidateHadTransientFailure = true
                break
            }

            val result = fetchLyricsWithCandidate(
                candidate.first,
                candidate.second,
                durationMs,
                totalRequests
            )

            when (result) {
                is LyricsLookupResult.Found -> {
                    currentLyrics.value = result.plainLyrics
                    currentSyncedLyrics.value = result.syncedLyrics
                    lyricsCache.put(originalCacheKey, result)
                    anyCandidateMatched = true
                    return
                }
                is LyricsLookupResult.NotFound -> {
                    // Search completed but no acceptable match found for this candidate
                }
                null -> {
                    // Transient error occurred (e.g. timeout, 5xx, or out-of-budget)
                    anyCandidateHadTransientFailure = true
                }
            }
        }

        // Only write NotFound to the cache at the original song level if we have exhaustively
        // searched all candidates and none of them returned a transient failure!
        if (!anyCandidateMatched && !anyCandidateHadTransientFailure) {
            val finalNotFound = LyricsLookupResult.NotFound(SystemClock.elapsedRealtime())
            lyricsCache.put(originalCacheKey, finalNotFound)
        }

        currentLyrics.value = null
        currentSyncedLyrics.value = null
    }
}

private fun MediaDataModel.toMediaControlState(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): MediaControlState {
    return MediaControlState(
        model =
            MediaControlChipModel(
                packageName = packageName,
                appIcon = appIcon,
                artworkIcon = background ?: appIcon,
                appName = appName,
                artistName = subtitle,
                songName = title,
                playOrPause = playbackStateActions?.getActionById(R.id.actionPlayPause),
                nextAction = playbackStateActions?.getActionById(R.id.actionNext),
                previousAction = playbackStateActions?.getActionById(R.id.actionPrev),
                openApp =
                    clickIntent.toAction(
                        activityStarter = activityStarter,
                        activityIntentHelper = activityIntentHelper,
                        lockscreenUserManager = lockscreenUserManager,
                        keyguardStateController = keyguardStateController,
                    ),
                seekTo = token.toSeekAction(context),
                durationMs = durationMs,
                positionMs = positionMs,
                canBeScrubbed = canBeScrubbed,
                isPlaying = state is MediaSessionState.Playing || state is MediaSessionState.Buffering,
            ),
        token = token,
    )
}

private fun MediaData.toMediaControlState(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): MediaControlState {
    val contentDescription = app?.let { ContentDescription.Loaded(it) }
    return MediaControlState(
        model =
            MediaControlChipModel(
                packageName = packageName,
                appIcon = appIcon.loadUiIcon(context, contentDescription),
                artworkIcon = artwork.loadUiIcon(context, contentDescription),
                appName = app,
                artistName = artist,
                songName = song,
                playOrPause = semanticActions?.getActionById(R.id.actionPlayPause),
                nextAction = semanticActions?.getActionById(R.id.actionNext),
                previousAction = semanticActions?.getActionById(R.id.actionPrev),
                openApp =
                    clickIntent.toAction(
                        activityStarter = activityStarter,
                        activityIntentHelper = activityIntentHelper,
                        lockscreenUserManager = lockscreenUserManager,
                        keyguardStateController = keyguardStateController,
                    ),
                seekTo = token.toSeekAction(context),
                durationMs = 0L,
                positionMs = 0L,
                canBeScrubbed = false,
                isPlaying = isPlaying ?: playOrPauseLooksPlaying(),
            ),
        token = token,
    )
}

private fun MediaData.playOrPauseLooksPlaying(): Boolean {
    val description = semanticActions?.getActionById(R.id.actionPlayPause)?.contentDescription
    return description?.toString()?.lowercase()?.contains("pause") == true
}

private fun android.graphics.drawable.Icon?.loadUiIcon(
    context: Context,
    contentDescription: ContentDescription?,
): UiIcon? {
    return this?.loadDrawable(context)?.let { UiIcon.Loaded(it, contentDescription) }
}

private fun PendingIntent?.toAction(
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): (() -> Unit)? {
    return this?.let { pendingIntent ->
        {
            val showOverLockscreen =
                keyguardStateController.isShowing &&
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        pendingIntent,
                        lockscreenUserManager.currentUserId,
                    )

            if (showOverLockscreen) {
                activityStarter.startPendingIntentMaybeDismissingKeyguard(
                    pendingIntent,
                    null,
                    null,
                )
            } else {
                activityStarter.postStartActivityDismissingKeyguard(pendingIntent, null)
            }
        }
    }
}

private fun MediaSession.Token?.toSeekAction(context: Context): ((Long) -> Unit)? {
    return this?.let { token ->
        { targetPositionMs ->
            runCatching {
                MediaController(context, token)
                    .transportControls
                    .seekTo(targetPositionMs.coerceAtLeast(0L))
            }
        }
    }
}

private fun MediaSession.Token?.playbackInfoFlow(context: Context): Flow<PlaybackInfo?> {
    if (this == null) {
        return flowOf(null)
    }
    return callbackFlow {
        val controller =
            runCatching { MediaController(context, this@playbackInfoFlow) }.getOrNull()
                ?: run {
                    trySend(null)
                    close()
                    return@callbackFlow
                }
        var poller: Job? = null

        fun updatePolling(playbackInfo: PlaybackInfo) {
            if (playbackInfo.isPlaying) {
                if (poller?.isActive == true) {
                    return
                }
                poller =
                    launch {
                        while (isActive) {
                            delay(PLAYBACK_POSITION_POLL_INTERVAL_MS)
                            trySend(controller.resolvePlaybackInfo(context))
                        }
                    }
            } else {
                poller?.cancel()
                poller = null
            }
        }

        fun dispatchPlaybackInfo() {
            val playbackInfo = controller.resolvePlaybackInfo(context)
            trySend(playbackInfo)
            updatePolling(playbackInfo)
        }

        val callback =
            object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    dispatchPlaybackInfo()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    dispatchPlaybackInfo()
                }

                override fun onSessionDestroyed() {
                    trySend(null)
                    close()
                }
            }

        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        dispatchPlaybackInfo()

        awaitClose {
            poller?.cancel()
            runCatching { controller.unregisterCallback(callback) }
        }
    }
}

private fun MediaController.resolvePlaybackInfo(context: Context): PlaybackInfo {
    val metadata = metadata
    val playbackState = playbackState
    val durationMs =
        metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
    return PlaybackInfo(
        durationMs = durationMs,
        positionMs = playbackState?.computeActualPosition(durationMs) ?: 0L,
        canSeek =
            playbackState?.actions?.and(PlaybackState.ACTION_SEEK_TO) != 0L && durationMs > 0L,
        isPlaying = playbackState?.isActivePlaybackState() == true,
        playOrPause = playbackState?.toPlayPauseAction(context = context, controller = this),
    )
}

private fun PlaybackState.computeActualPosition(durationMs: Long): Long {
    var currentPosition = position.coerceAtLeast(0L)
    if (NotificationMediaManager.isPlayingState(state) && lastPositionUpdateTime > 0L) {
        currentPosition =
            ((playbackSpeed * (SystemClock.elapsedRealtime() - lastPositionUpdateTime)).toLong() +
                    position)
                .coerceAtLeast(0L)
    }
    return if (durationMs > 0L) currentPosition.coerceAtMost(durationMs) else currentPosition
}

private fun PlaybackState.toPlayPauseAction(
    context: Context,
    controller: MediaController,
): MediaAction? {
    val transportControls = controller.transportControls
    return when {
        NotificationMediaManager.isPlayingState(state) && supportsPlaybackAction(PlaybackState.ACTION_PAUSE) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_pause_button),
                action = Runnable { transportControls.pause() },
                contentDescription = context.getString(R.string.controls_media_button_pause),
                background = context.getDrawable(R.drawable.ic_media_pause_button_container),
            )
        supportsPlaybackAction(PlaybackState.ACTION_PLAY) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_play_button),
                action = Runnable { transportControls.play() },
                contentDescription = context.getString(R.string.controls_media_button_play),
                background = context.getDrawable(R.drawable.ic_media_play_button_container),
            )
        else -> null
    }
}

private fun PlaybackState.supportsPlaybackAction(@PlaybackState.Actions action: Long): Boolean {
    if (
        (action == PlaybackState.ACTION_PLAY || action == PlaybackState.ACTION_PAUSE) &&
            (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
    ) {
        return true
    }
    return (actions and action) != 0L
}

private fun PlaybackState.isActivePlaybackState(): Boolean {
    return state == PlaybackState.STATE_PLAYING ||
        state == PlaybackState.STATE_BUFFERING ||
        state == PlaybackState.STATE_FAST_FORWARDING ||
        state == PlaybackState.STATE_REWINDING ||
        state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
        state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
}

private data class PlaybackInfo(
    val durationMs: Long,
    val positionMs: Long,
    val canSeek: Boolean,
    val isPlaying: Boolean,
    val playOrPause: MediaAction?,
)

private data class MediaControlState(
    val model: MediaControlChipModel?,
    val token: MediaSession.Token?,
) {
    companion object {
        val Hidden = MediaControlState(model = null, token = null)
    }
}

private fun MediaControlChipModel.withPlaybackInfo(playbackInfo: PlaybackInfo?): MediaControlChipModel {
    if (playbackInfo == null) {
        return this
    }
    return copy(
        playOrPause = playbackInfo.playOrPause ?: playOrPause,
        durationMs = durationMs.takeIf { it > 0L } ?: playbackInfo.durationMs,
        positionMs = playbackInfo.positionMs,
        canBeScrubbed = canBeScrubbed || playbackInfo.canSeek,
        isPlaying = playbackInfo.isPlaying,
    )
}
