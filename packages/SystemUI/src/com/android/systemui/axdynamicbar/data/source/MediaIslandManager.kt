package com.android.systemui.axdynamicbar.data.source

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon as DrawableIcon
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.statusbar.util.MediaSessionTrackHelper
import com.android.systemui.util.concurrency.RepeatableExecutor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class MediaIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    @Main private val mainExecutor: RepeatableExecutor,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
) {
    companion object {
        private const val TAG = "MediaIslandManager"
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L
    }

    private val _mediaEvent = MutableStateFlow<IslandEvent.Media?>(null)
    val mediaEvent: StateFlow<IslandEvent.Media?> = _mediaEvent.asStateFlow()

    var activeMediaPackage: String? = null
        private set

    var onMediaSessionLost: (() -> Unit)? = null

    @Volatile private var listening = false
    @Volatile private var sessionMediaColor: Int = 0
    @Volatile private var sessionAlbumArt: Drawable? = null
    @Volatile private var sessionAppIcon: Drawable? = null

    private val trackHelper: MediaSessionTrackHelper by lazy {
        MediaSessionTrackHelper.getInstance(context)
    }

    private val trackHelperListener =
        object : MediaSessionTrackHelper.MediaMetadataListener {
            override fun onMediaMetadataChanged() {
                updateFromHelper()
            }

            override fun onPlaybackStateChanged() {
                updateFromHelper()
            }
        }

    private val mediaSessionListener = object : MediaSessionManager.MediaDataListener {
        override fun onMediaColorsChanged(color: Int) {
            sessionMediaColor = color
            val current = _mediaEvent.value ?: return
            _mediaEvent.value = current.copy(mediaColor = color)
        }

        override fun onAlbumArtChanged(drawable: Drawable?) {
            sessionAlbumArt = drawable
            val current = _mediaEvent.value ?: return
            _mediaEvent.value = current.copy(albumArt = drawable)
        }

        override fun onAppIconChanged(drawable: Drawable?) {
            sessionAppIcon = drawable
            val current = _mediaEvent.value ?: return
            _mediaEvent.value = current.copy(appIcon = drawable)
        }

        override fun onMetadataChanged(track: String, artist: String) {
            val current = _mediaEvent.value ?: return
            if (current.track == track && current.artist == artist) return
            _mediaEvent.value = current.copy(track = track, artist = artist)
        }
    }

    private fun updateFromHelper() {
        val controller = trackHelper.getCurrentMediaController()
        if (controller == null) {
            val current = _mediaEvent.value
            if (current != null) {
                _mediaEvent.value = null
                activeMediaPackage = null
                onMediaSessionLost?.invoke()
            }
            return
        }

        val metadata = trackHelper.getCurrentMediaMetadata()
        val ps = trackHelper.getMediaControllerPlaybackState()
        val isPlaying = trackHelper.isMediaPlaying()

        val track =
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val durationRaw = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val duration = if (durationRaw > 0L) durationRaw else 0L

        val albumArt = trackHelper.getMediaBitmap()?.let { BitmapDrawable(context.resources, it) }
            ?: sessionAlbumArt

        val existingPos = _mediaEvent.value?.position ?: 0L
        val posMs = if (ps != null) computeAccuratePosition(ps) else existingPos
        val progress =
            if (duration > 0L) (posMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            else 0f
        val outputDevice = getOutputDeviceName()
        val pkg = controller.packageName
        val customActions =
            ps?.customActions?.take(2)?.mapNotNull { ca ->
                val lbl =
                    ca.name?.toString()?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                val act = ca.action?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val icon = try {
                    if (ca.icon != 0) {
                        DrawableIcon.createWithResource(pkg, ca.icon)
                            .loadDrawable(context)
                    } else null
                } catch (_: Exception) { null }
                IslandEvent.MediaCustomAction(label = lbl, action = act, icon = icon)
            } ?: emptyList()
        val appIcon = sessionAppIcon ?: try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) { null }

        val speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        val updateTime = ps?.lastPositionUpdateTime ?: 0L

        if (isPlaying) {
            activeMediaPackage = pkg
            _mediaEvent.value =
                IslandEvent.Media(
                    track = track,
                    artist = artist,
                    isPlaying = true,
                    albumArt = albumArt,
                    progress = progress,
                    duration = duration,
                    position = posMs,
                    playbackSpeed = speed,
                    positionUpdateTime = updateTime,
                    outputDeviceName = outputDevice,
                    customActions = customActions,
                    appIcon = appIcon,
                    packageName = pkg,
                    mediaColor = sessionMediaColor,
                )
        } else {
            val current = _mediaEvent.value
            if (current != null) {
                _mediaEvent.value =
                    current.copy(
                        track = track,
                        artist = artist,
                        isPlaying = false,
                        albumArt = albumArt ?: current.albumArt,
                        progress = progress,
                        duration = duration,
                        position = posMs,
                        playbackSpeed = speed,
                        positionUpdateTime = updateTime,
                        customActions = customActions,
                        appIcon = appIcon ?: current.appIcon,
                        packageName = pkg,
                    )
            }
        }
    }

    private fun isInMotion(state: PlaybackState): Boolean =
        state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_FAST_FORWARDING ||
            state.state == PlaybackState.STATE_REWINDING

    private fun computeAccuratePosition(state: PlaybackState): Long {
        val basePos = state.position.coerceAtLeast(0L)
        if (!isInMotion(state)) return basePos
        val updateTime = state.lastPositionUpdateTime
        if (updateTime <= 0) return basePos
        val elapsed = SystemClock.elapsedRealtime() - updateTime
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        val rawDuration = _mediaEvent.value?.duration ?: Long.MAX_VALUE
        val safeDuration = if (rawDuration > 0L) rawDuration else Long.MAX_VALUE

        return (basePos + (elapsed * speed).toLong())
            .coerceIn(0L, safeDuration)
    }

    private var cancelProgressPolling: Runnable? = null

    private fun tickProgress() {
        val ev = _mediaEvent.value ?: return
        if (!ev.isPlaying || ev.duration <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - ev.positionUpdateTime
        val pos = (ev.position + (elapsed * ev.playbackSpeed).toLong()).coerceIn(0L, ev.duration)
        val prog = (pos.toFloat() / ev.duration).coerceIn(0f, 1f)
        _mediaEvent.value = ev.copy(progress = prog, position = pos, positionUpdateTime = now)
    }

    fun startProgressPolling() {
        if (cancelProgressPolling != null) return
        cancelProgressPolling = mainExecutor.executeRepeatedly(
            ::tickProgress, 0L, POSITION_UPDATE_INTERVAL_MS,
        )
    }

    fun stopProgressPolling() {
        cancelProgressPolling?.run()
        cancelProgressPolling = null
    }

    private fun getActiveController(): MediaController? =
        trackHelper.getCurrentMediaController()

    fun startListening() {
        if (listening) return
        listening = true
        trackHelper.addMediaMetadataListener(trackHelperListener)
        MediaSessionManager.get().addListener(mediaSessionListener)
        updateFromHelper()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        stopProgressPolling()
        trackHelper.removeMediaMetadataListener(trackHelperListener)
        MediaSessionManager.get().removeListener(mediaSessionListener)
        _mediaEvent.value = null
        activeMediaPackage = null
        sessionMediaColor = 0
        sessionAlbumArt = null
        sessionAppIcon = null
    }

    fun clear() {
        stopProgressPolling()
        _mediaEvent.value = null
    }

    fun togglePlayPause() {
        val c = getActiveController() ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
        val current = _mediaEvent.value ?: return
        _mediaEvent.value = current.copy(isPlaying = !playing)
    }

    fun skipNext() {
        getActiveController()?.transportControls?.skipToNext()
    }

    fun skipPrev() {
        getActiveController()?.transportControls?.skipToPrevious()
    }

    fun seekTo(position: Long) {
        getActiveController()?.transportControls?.seekTo(position)
    }

    fun sendCustomAction(action: String) {
        getActiveController()?.transportControls?.sendCustomAction(action, null)
    }

    fun openMediaApp() {
        val pkg = getActiveController()?.packageName ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open media app: $pkg", e)
        }
    }

    private fun getOutputDeviceName(): String =
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            val primary =
                outputs.firstOrNull {
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                        it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                } ?: outputs.firstOrNull()
            when (primary?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    primary.productName?.toString()?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                null -> "Speaker"
                else -> primary.productName?.toString()?.takeIf { it.isNotEmpty() } ?: "Speaker"
            }
        } catch (_: Exception) {
            "Speaker"
        }

    fun openMediaOutputSwitcher() {
        val pkg = getActiveController()?.packageName ?: return
        mainHandler.post {
            try {
                mediaOutputDialogManager.createAndShow(packageName = pkg, aboveStatusBar = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open media output switcher", e)
            }
        }
    }
}