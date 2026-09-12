/*
 * Copyright (C) 2024 the risingOS Android Project
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

package com.android.systemui.lockscreen

import android.annotation.NonNull
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionLegacyHelper
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.KeyEvent
import android.provider.MediaStore
import android.provider.Settings
import android.util.AttributeSet
import android.os.UserHandle
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.Toast
import android.view.View
import android.view.ViewGroup

import androidx.annotation.Nullable
import androidx.annotation.StringRes

import com.android.settingslib.net.DataUsageController
import com.android.settingslib.Utils

import com.android.systemui.res.R
import com.android.systemui.Dependency
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.animation.view.LaunchableFAB
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsContentViewModel
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.IconState
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.connectivity.MobileDataIndicators
import com.android.systemui.statusbar.connectivity.WifiIndicators
import com.android.systemui.statusbar.util.MediaSessionTrackHelper
import com.android.internal.util.android.VibrationUtils
import com.android.internal.util.infinity.OmniJawsClient

class LockScreenWidgetsController(private val mView: View) :
    OmniJawsClient.OmniJawsObserver,
    MediaSessionTrackHelper.MediaMetadataListener {

    companion object {
        private const val LOCKSCREEN_WIDGETS_ENABLED = "lockscreen_widgets_enabled"
        private const val LOCKSCREEN_WIDGETS = "lockscreen_widgets"
        private const val LOCKSCREEN_WIDGETS_EXTRAS = "lockscreen_widgets_extras"
        private const val LOCKSCREEN_WIDGETS_STYLE = "lockscreen_widgets_style"
        private const val LOCKSCREEN_WIDGETS_TRANSPARENCY = "lockscreen_widgets_transparency"

        private val MAIN_WIDGETS_VIEW_IDS = intArrayOf(
            R.id.main_kg_item_placeholder1,
            R.id.main_kg_item_placeholder2
        )

        private val WIDGETS_VIEW_IDS = intArrayOf(
            R.id.kg_item_placeholder1,
            R.id.kg_item_placeholder2,
            R.id.kg_item_placeholder3,
            R.id.kg_item_placeholder4
        )

        val BT_ACTIVE = R.drawable.qs_bluetooth_icon_on
        val BT_INACTIVE = R.drawable.qs_bluetooth_icon_off
        val DATA_ACTIVE = R.drawable.ic_signal_cellular_alt_24
        val DATA_INACTIVE = R.drawable.ic_mobiledata_off_24
        val RINGER_ACTIVE = R.drawable.ic_vibration_24
        val RINGER_INACTIVE = R.drawable.ic_ring_volume_24
        val TORCH_RES_ACTIVE = R.drawable.ic_flashlight_on
        val TORCH_RES_INACTIVE = R.drawable.ic_flashlight_off
        val WIFI_ACTIVE = R.drawable.ic_wifi_24
        val WIFI_INACTIVE = R.drawable.ic_wifi_off_24
        val HOTSPOT_ACTIVE = R.drawable.qs_hotspot_icon_on
        val HOTSPOT_INACTIVE = R.drawable.qs_hotspot_icon_off

        val BT_LABEL_INACTIVE = R.string.quick_settings_bluetooth_label
        val DATA_LABEL_INACTIVE = R.string.quick_settings_data_label
        val RINGER_LABEL_INACTIVE = R.string.quick_settings_ringer_label
        val TORCH_LABEL_ACTIVE = R.string.torch_active
        val TORCH_LABEL_INACTIVE = R.string.quick_settings_flashlight_label
        val WIFI_LABEL_INACTIVE = R.string.quick_settings_wifi_label
        val HOTSPOT_LABEL = R.string.accessibility_status_bar_hotspot

        private fun removeDoubleQuotes(string: String?): String? {
            if (string == null) return null
            val length = string.length
            return if (length > 1 && string[0] == '"' && string[length - 1] == '"') {
                string.substring(1, length - 1)
            } else {
                string
            }
        }
    }

    private val mContext: Context = mView.context
    
    private val mAccessPointController: AccessPointController by lazy { Dependency.get(AccessPointController::class.java) }
    private val mBluetoothDetailsContentViewModel: BluetoothDetailsContentViewModel by lazy { Dependency.get(BluetoothDetailsContentViewModel::class.java) }
    private val mConfigurationController: ConfigurationController by lazy { Dependency.get(ConfigurationController::class.java) }
    private val mFlashlightController: FlashlightController by lazy { Dependency.get(FlashlightController::class.java) }
    private val mInternetDialogManager: InternetDialogManager by lazy { Dependency.get(InternetDialogManager::class.java) }
    private val mStatusBarStateController: StatusBarStateController by lazy { Dependency.get(StatusBarStateController::class.java) }
    private val mBluetoothController: BluetoothController by lazy { Dependency.get(BluetoothController::class.java) }
    private val mNetworkController: NetworkController by lazy { Dependency.get(NetworkController::class.java) }
    private val mDataController by lazy { mNetworkController.mobileDataController }
    private val mHotspotController: HotspotController by lazy { Dependency.get(HotspotController::class.java) }
    private val mMediaSessionTrackHelper: MediaSessionTrackHelper by lazy { MediaSessionTrackHelper.getInstance(mContext) }

    private val mActivityLauncherUtils = ActivityLauncherUtils(mContext)
    private val mLockscreenWidgetsObserver = LockscreenWidgetsObserver()

    private val mAudioManager: AudioManager? = mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mCameraManager: CameraManager? = mContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val mWeatherClient: OmniJawsClient by lazy { OmniJawsClient() }
    private var mWeatherInfo: OmniJawsClient.WeatherInfo? = null

    private var mWidget1: LaunchableImageView? = null
    private var mWidget2: LaunchableImageView? = null
    private var mWidget3: LaunchableImageView? = null
    private var mWidget4: LaunchableImageView? = null
    private var mediaButton: LaunchableImageView? = null
    private var torchButton: LaunchableImageView? = null
    private var weatherButton: LaunchableImageView? = null

    private var mediaButtonFab: LaunchableFAB? = null
    private var torchButtonFab: LaunchableFAB? = null
    private var weatherButtonFab: LaunchableFAB? = null
    private var hotspotButtonFab: LaunchableFAB? = null
    private var wifiButtonFab: LaunchableFAB? = null
    private var dataButtonFab: LaunchableFAB? = null
    private var ringerButtonFab: LaunchableFAB? = null
    private var btButtonFab: LaunchableFAB? = null

    private var wifiButton: LaunchableImageView? = null
    private var dataButton: LaunchableImageView? = null
    private var ringerButton: LaunchableImageView? = null
    private var btButton: LaunchableImageView? = null
    private var hotspotButton: LaunchableImageView? = null

    private var mDarkColor = 0
    private var mDarkColorActive = 0
    private var mLightColor = 0
    private var mLightColorActive = 0

    private var isFlashOn = false

    private var mMainLockscreenWidgetsList: String? = null
    private var mSecondaryLockscreenWidgetsList: String? = null
    private var mMainWidgetViews: Array<LaunchableFAB?>? = null
    private var mSecondaryWidgetViews: Array<LaunchableImageView?>? = null
    private var mMainWidgetsList: List<String> = ArrayList()
    private var mSecondaryWidgetsList: List<String> = ArrayList()

    private var mLastTrackTitle: String? = null
    private var mDozing = false
    private var mIsInflated = false
    private var mIsLongPress = false

    private var mLockscreenWidgetsEnabled = false
    private var mThemeStyle = 0
    private var mTransparency = 0.3f
    private var mIsWeatherObserving = false

    private val mButtonStateCache = mutableMapOf<View, ButtonState>()

    private data class ButtonState(
        val active: Boolean,
        val imageResource: Int,
        val text: String?
    )

    private val mConfigurationListener = object : ConfigurationListener {
        override fun onUiModeChanged() {
            updateWidgetViews()
        }
        override fun onThemeChanged() {
            updateWidgetViews()
        }
    }

    private val mHandler = Handler(mContext.mainLooper)
    private val mBgHandler by lazy { Handler(Dependency.get(Dependency.BG_LOOPER)) }

    init {
        initResources()
    }

    private val mStatusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {}
        override fun onDozingChanged(dozing: Boolean) {
            if (mDozing == dozing) return
            mDozing = dozing
            updateContainerVisibility()
        }
    }

    private val mFlashlightCallback = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            isFlashOn = enabled
            updateTorchButtonState()
        }
        override fun onFlashlightError() {}
        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            isFlashOn = mFlashlightController.isEnabled && available
            updateTorchButtonState()
        }
        override fun onFlashlightStrengthChanged(level: Int) {
            updateTorchButtonState()
        }
    }

    private fun initResources() {
        mDarkColor = mContext.resources.getColor(R.color.lockscreen_widget_background_color_dark, null)
        mLightColor = mContext.resources.getColor(R.color.lockscreen_widget_background_color_light, null)
        mDarkColorActive = mContext.resources.getColor(R.color.lockscreen_widget_active_color_dark, null)
        mLightColorActive = mContext.resources.getColor(R.color.lockscreen_widget_active_color_light, null)
    }

    private var mIsHotspotCallbackRegistered = false
    private var mIsWifiCallbackRegistered = false
    private var mIsCellCallbackRegistered = false
    private var mIsBtCallbackRegistered = false
    private var mIsFlashlightCallbackRegistered = false
    private var mIsMediaCallbackRegistered = false

    private fun updateCallbackRegistrations() {
        if (!mLockscreenWidgetsEnabled) {
            unregisterActiveCallbacks()
            return
        }

        val hotspotNeeded = isWidgetEnabled("hotspot")
        if (hotspotNeeded != mIsHotspotCallbackRegistered) {
            if (hotspotNeeded) {
                mHotspotController.addCallback(mHotspotCallback)
            } else {
                mHotspotController.removeCallback(mHotspotCallback)
            }
            mIsHotspotCallbackRegistered = hotspotNeeded
        }

        val wifiNeeded = isWidgetEnabled("wifi")
        if (wifiNeeded != mIsWifiCallbackRegistered) {
            if (wifiNeeded) {
                mNetworkController.addCallback(mWifiSignalCallback)
            } else {
                mNetworkController.removeCallback(mWifiSignalCallback)
            }
            mIsWifiCallbackRegistered = wifiNeeded
        }

        val cellNeeded = isWidgetEnabled("data")
        if (cellNeeded != mIsCellCallbackRegistered) {
            if (cellNeeded) {
                mNetworkController.addCallback(mCellSignalCallback)
            } else {
                mNetworkController.removeCallback(mCellSignalCallback)
            }
            mIsCellCallbackRegistered = cellNeeded
        }

        val btNeeded = isWidgetEnabled("bt")
        if (btNeeded != mIsBtCallbackRegistered) {
            if (btNeeded) {
                mBluetoothController.addCallback(mBtCallback)
            } else {
                mBluetoothController.removeCallback(mBtCallback)
            }
            mIsBtCallbackRegistered = btNeeded
        }

        val flashlightNeeded = isWidgetEnabled("torch")
        if (flashlightNeeded != mIsFlashlightCallbackRegistered) {
            if (flashlightNeeded) {
                mFlashlightController.addCallback(mFlashlightCallback)
            } else {
                mFlashlightController.removeCallback(mFlashlightCallback)
            }
            mIsFlashlightCallbackRegistered = flashlightNeeded
        }

        val mediaNeeded = isWidgetEnabled("media")
        if (mediaNeeded != mIsMediaCallbackRegistered) {
            if (mediaNeeded) {
                mMediaSessionTrackHelper.addMediaMetadataListener(this)
                updateMediaPlaybackState()
            } else {
                mMediaSessionTrackHelper.removeMediaMetadataListener(this)
            }
            mIsMediaCallbackRegistered = mediaNeeded
        }

        if (!isWidgetEnabled("weather")) {
            disableWeatherUpdates()
        }
    }

    private var mObserverRegistered = false
    private var mAreActiveCallbacksRegistered = false

    private fun registerActiveCallbacks() {
        if (!mLockscreenWidgetsEnabled || mAreActiveCallbacksRegistered) return
        mAreActiveCallbacksRegistered = true

        try {
            val ringerFilter = IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)
            mContext.registerReceiver(mRingerModeReceiver, ringerFilter)
        } catch (e: Exception) {
        }

        mConfigurationController.addCallback(mConfigurationListener)
        mStatusBarStateController.addCallback(mStatusBarStateListener)
        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing)

        updateCallbackRegistrations()
        if (mIsMediaCallbackRegistered) {
            updateMediaPlaybackState()
        }
    }

    private fun unregisterActiveCallbacks() {
        if (!mAreActiveCallbacksRegistered) return
        mAreActiveCallbacksRegistered = false

        try {
            mContext.unregisterReceiver(mRingerModeReceiver)
        } catch (e: Exception) {
        }

        if (mIsHotspotCallbackRegistered) {
            mHotspotController.removeCallback(mHotspotCallback)
            mIsHotspotCallbackRegistered = false
        }
        if (mIsWifiCallbackRegistered) {
            mNetworkController.removeCallback(mWifiSignalCallback)
            mIsWifiCallbackRegistered = false
        }
        if (mIsCellCallbackRegistered) {
            mNetworkController.removeCallback(mCellSignalCallback)
            mIsCellCallbackRegistered = false
        }
        if (mIsBtCallbackRegistered) {
            mBluetoothController.removeCallback(mBtCallback)
            mIsBtCallbackRegistered = false
        }
        if (mIsFlashlightCallbackRegistered) {
            mFlashlightController.removeCallback(mFlashlightCallback)
            mIsFlashlightCallbackRegistered = false
        }
        if (mIsMediaCallbackRegistered) {
            mMediaSessionTrackHelper.removeMediaMetadataListener(this)
            mIsMediaCallbackRegistered = false
        }

        disableWeatherUpdates()

        mConfigurationController.removeCallback(mConfigurationListener)
        mStatusBarStateController.removeCallback(mStatusBarStateListener)

        mButtonStateCache.clear()
    }

    fun registerCallbacks() {
        if (!mObserverRegistered) {
            mLockscreenWidgetsObserver.observe()
            mObserverRegistered = true
        }
    }

    fun unregisterCallbacks() {
        if (mObserverRegistered) {
            mLockscreenWidgetsObserver.unobserve()
            mObserverRegistered = false
        }
        unregisterActiveCallbacks()
        mHandler.removeCallbacksAndMessages(null)
        mBgHandler.removeCallbacksAndMessages(null)
    }

    fun initViews() {
        mMainWidgetViews = Array(MAIN_WIDGETS_VIEW_IDS.size) { i ->
            mView.findViewById(MAIN_WIDGETS_VIEW_IDS[i])
        }
        mSecondaryWidgetViews = Array(WIDGETS_VIEW_IDS.size) { i ->
            mView.findViewById(WIDGETS_VIEW_IDS[i])
        }
        mIsInflated = true
        updateWidgetViews()
    }

    fun updateWidgetViews() {
        if (!mIsInflated) return

        mButtonStateCache.clear()

        mediaButton = null
        torchButton = null
        weatherButton = null
        mediaButtonFab = null
        torchButtonFab = null
        weatherButtonFab = null
        hotspotButtonFab = null
        wifiButtonFab = null
        dataButtonFab = null
        ringerButtonFab = null
        btButtonFab = null
        wifiButton = null
        dataButton = null
        ringerButton = null
        btButton = null
        hotspotButton = null

        if (!mLockscreenWidgetsEnabled) {
            mMainWidgetViews?.forEach { it?.visibility = View.GONE }
            mSecondaryWidgetViews?.forEach { it?.visibility = View.GONE }
            mView.findViewById<View>(R.id.main_widgets_container)?.visibility = View.GONE
            mView.findViewById<View>(R.id.secondary_widgets_container)?.visibility = View.GONE
            mView.visibility = View.GONE
            return
        }

        mMainWidgetViews?.let { views ->
            for (i in views.indices) {
                views[i]?.visibility = if (i < mMainWidgetsList.size) View.VISIBLE else View.GONE
            }
            for (i in 0 until Math.min(mMainWidgetsList.size, views.size)) {
                val widgetType = mMainWidgetsList[i]
                if (widgetType != null && views[i] != null) {
                    setUpWidgetViews(null, views[i], widgetType)
                    updateMainWidgetResources(views[i], false)
                }
            }
        }

        mSecondaryWidgetViews?.let { views ->
            for (i in views.indices) {
                views[i]?.visibility = if (i < mSecondaryWidgetsList.size) View.VISIBLE else View.GONE
            }
            for (i in 0 until Math.min(mSecondaryWidgetsList.size, views.size)) {
                val widgetType = mSecondaryWidgetsList[i]
                if (widgetType != null && views[i] != null) {
                    setUpWidgetViews(views[i], null, widgetType)
                    updateWidgetsResources(views[i])
                }
            }
        }

        updateContainerVisibility()
    }

    private fun updateMainWidgetResources(efab: LaunchableFAB?, active: Boolean) {
        if (efab == null) return
        efab.elevation = 0f
        setButtonActiveState(null, efab, false)
        val visibleWidgetCount = mMainWidgetsList.filter { it != "none" }.size
        val params = efab.layoutParams
        if (params is LinearLayout.LayoutParams) {
            if (efab.visibility == View.VISIBLE && visibleWidgetCount == 1) {
                params.width = mContext.resources.getDimensionPixelSize(R.dimen.kg_widget_main_width)
                params.height = mContext.resources.getDimensionPixelSize(R.dimen.kg_widget_main_height)
            } else {
                params.width = 0
                params.weight = 1f
            }
            efab.layoutParams = params
        }
    }

    private fun updateContainerVisibility() {
        if (!mLockscreenWidgetsEnabled) {
            mView.findViewById<View>(R.id.main_widgets_container)?.visibility = View.GONE
            mView.findViewById<View>(R.id.secondary_widgets_container)?.visibility = View.GONE
            mView.visibility = View.GONE
            return
        }

        val isMainWidgetsEmpty = mMainLockscreenWidgetsList.isNullOrEmpty()
        val isSecondaryWidgetsEmpty = mSecondaryLockscreenWidgetsList.isNullOrEmpty()
        val isEmpty = isMainWidgetsEmpty && isSecondaryWidgetsEmpty

        mView.findViewById<View>(R.id.main_widgets_container)?.visibility =
            if (isMainWidgetsEmpty) View.GONE else View.VISIBLE

        mView.findViewById<View>(R.id.secondary_widgets_container)?.visibility =
            if (isSecondaryWidgetsEmpty) View.GONE else View.VISIBLE

        val shouldHideContainer = isEmpty || mDozing || !mLockscreenWidgetsEnabled
        mView.visibility = if (shouldHideContainer) View.GONE else View.VISIBLE
    }

    private fun updateWidgetsResources(iv: LaunchableImageView?) {
        if (iv == null) return
        val bgRes = when (mThemeStyle) {
            1, 2 -> R.drawable.lockscreen_widget_background_square
            else -> R.drawable.lockscreen_widget_background_circle
        }
        iv.setBackgroundResource(bgRes)
        setButtonActiveState(iv, null, false)
    }

    private val isNightMode: Boolean
        get() {
            val config = mContext.resources.configuration
            return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

    private val showWeatherIcon: Boolean
        get() = mSecondaryWidgetsList.contains("weather") && !mMainWidgetsList.contains("weather")

    private fun setUpWidgetViews(iv: LaunchableImageView?, efab: LaunchableFAB?, type: String) {
        var clickListener: View.OnClickListener? = null
        var longClickListener: View.OnLongClickListener? = null
        var drawableRes = 0
        var stringRes = 0

        when (type) {
            "none" -> {
                iv?.visibility = View.GONE
                efab?.visibility = View.GONE
                return
            }
            "wifi" -> {
                clickListener = View.OnClickListener { toggleWiFi() }
                longClickListener = View.OnLongClickListener { v ->
                    showInternetDialog(v)
                    true
                }
                drawableRes = WIFI_INACTIVE
                stringRes = R.string.quick_settings_wifi_label
                if (iv != null) wifiButton = iv
                if (efab != null) wifiButtonFab = efab
            }
            "data" -> {
                clickListener = View.OnClickListener { toggleMobileData() }
                longClickListener = View.OnLongClickListener { v ->
                    showInternetDialog(v)
                    true
                }
                drawableRes = DATA_INACTIVE
                stringRes = DATA_LABEL_INACTIVE
                if (iv != null) dataButton = iv
                if (efab != null) dataButtonFab = efab
            }
            "ringer" -> {
                clickListener = View.OnClickListener { toggleRingerMode() }
                drawableRes = RINGER_INACTIVE
                stringRes = RINGER_LABEL_INACTIVE
                if (iv != null) ringerButton = iv
                if (efab != null) ringerButtonFab = efab
            }
            "bt" -> {
                clickListener = View.OnClickListener { toggleBluetoothState() }
                longClickListener = View.OnLongClickListener { v ->
                    showBluetoothDialog(v)
                    true
                }
                drawableRes = BT_INACTIVE
                stringRes = BT_LABEL_INACTIVE
                if (iv != null) btButton = iv
                if (efab != null) btButtonFab = efab
            }
            "torch" -> {
                clickListener = View.OnClickListener { toggleFlashlight() }
                drawableRes = TORCH_RES_INACTIVE
                stringRes = TORCH_LABEL_INACTIVE
                if (iv != null) torchButton = iv
                if (efab != null) torchButtonFab = efab
            }
            "timer" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchTimer() }
                drawableRes = R.drawable.ic_alarm
                stringRes = R.string.clock_timer
            }
            "calculator" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchCalculator() }
                drawableRes = R.drawable.ic_calculator
                stringRes = R.string.calculator
            }
            "media" -> {
                clickListener = View.OnClickListener { toggleMediaPlaybackState() }
                longClickListener = View.OnLongClickListener { v ->
                    showMediaDialog(v)
                    true
                }
                drawableRes = R.drawable.ic_media_play
                stringRes = R.string.controls_media_button_play
                if (iv != null) mediaButton = iv
                if (efab != null) mediaButtonFab = efab
            }
            "weather" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchWeatherApp() }
                drawableRes = if (showWeatherIcon) R.drawable.ic_weather else 0
                stringRes = R.string.weather_data_unavailable
                if (iv != null) weatherButton = iv
                if (efab != null) weatherButtonFab = efab
                enableWeatherUpdates()
            }
            "hotspot" -> {
                clickListener = View.OnClickListener { toggleHotspot() }
                longClickListener = View.OnLongClickListener { v ->
                    showInternetDialog(v)
                    true
                }
                drawableRes = HOTSPOT_INACTIVE
                stringRes = HOTSPOT_LABEL
                if (iv != null) hotspotButton = iv
                if (efab != null) hotspotButtonFab = efab
            }
            "wallet" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchWalletApp() }
                drawableRes = R.drawable.ic_wallet_lockscreen
                stringRes = R.string.google_wallet
            }
            "qrscanner" -> {
                clickListener = View.OnClickListener { mActivityLauncherUtils.launchQrScanner() }
                drawableRes = R.drawable.ic_qr_code_scanner
                stringRes = R.string.qr_code_scanner_title
            }
            else -> return
        }

        efab?.let {
            it.setOnClickListener(clickListener)
            if (drawableRes != 0) {
                it.setIcon(mContext.getDrawable(drawableRes))
            } else {
                it.setIcon(null)
            }
            it.text = mContext.resources.getString(stringRes)
            if (longClickListener != null) it.setOnLongClickListener(longClickListener)
            if (mediaButtonFab == it) attachSwipeGesture(it)
        }
        iv?.let {
            it.setOnClickListener(clickListener)
            if (longClickListener != null) it.setOnLongClickListener(longClickListener)
            if (drawableRes != 0) {
                it.setImageResource(drawableRes)
            } else {
                it.setImageDrawable(null)
            }
        }
    }

    private fun attachSwipeGesture(efab: LaunchableFAB) {
        val gestureDetector = GestureDetector(mContext, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        VibrationUtils.triggerVibration(mContext, 2)
                    } else {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT)
                        VibrationUtils.triggerVibration(mContext, 2)
                    }
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                super.onLongPress(e)
                mIsLongPress = true
                showMediaDialog(efab)
                mHandler.postDelayed({ mIsLongPress = false }, 2500)
            }
        })

        efab.setOnTouchListener { v, event ->
            val isClick = gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !isClick && !mIsLongPress) {
                v.performClick()
            }
            true
        }
    }

    private fun setButtonActiveState(iv: LaunchableImageView?, efab: LaunchableFAB?, active: Boolean) {
        val bgTint: Int
        val tintColor: Int
        if (mThemeStyle == 2 || mThemeStyle == 3) {
            if (active) {
                bgTint = Utils.applyAlpha(mTransparency, mDarkColorActive)
                tintColor = mDarkColorActive
            } else {
                bgTint = Utils.applyAlpha(mTransparency, Color.WHITE)
                tintColor = Color.WHITE
            }
        } else {
            if (active) {
                bgTint = if (isNightMode) mDarkColorActive else mLightColorActive
                tintColor = if (isNightMode) mDarkColor else mLightColor
            } else {
                bgTint = if (isNightMode) mDarkColor else mLightColor
                tintColor = if (isNightMode) mLightColor else mDarkColor
            }
        }

        iv?.let {
            it.backgroundTintList = ColorStateList.valueOf(bgTint)
            if (it != weatherButton) {
                it.imageTintList = ColorStateList.valueOf(tintColor)
            } else {
                it.imageTintList = null
            }
        }

        efab?.let {
            it.backgroundTintList = ColorStateList.valueOf(bgTint)
            if (it != weatherButtonFab) {
                it.setIconTint(ColorStateList.valueOf(tintColor))
            } else {
                it.setIconTint(null)
            }
            it.setTextColor(tintColor)
        }
    }

    private fun toggleMediaPlaybackState() {
        if (mMediaSessionTrackHelper.isMediaPlaying()) {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    private fun showMediaDialog(view: View) {
        val lastMediaPkg = lastUsedMedia
        if (lastMediaPkg.isNullOrEmpty()) return
        mHandler.post {
            (mView as? LockScreenWidgets)?.showMediaDialog(view, lastMediaPkg)
            VibrationUtils.triggerVibration(mContext, 2)
        }
    }

    private val lastUsedMedia: String?
        get() = Settings.System.getString(
            mContext.contentResolver,
            "media_session_last_package_name"
        )

    private fun dispatchMediaKeyWithWakeLockToMediaSession(keycode: Int) {
        val helper = MediaSessionLegacyHelper.getHelper(mContext) ?: return
        var event = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            keycode,
            0
        )
        helper.sendMediaButtonEvent(event, true)
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP)
        helper.sendMediaButtonEvent(event, true)
        mHandler.postDelayed({
            updateMediaPlaybackState()
        }, 250)
    }

    private fun updateMediaPlaybackState() {
        if (!isWidgetEnabled("media")) return
        val isPlaying = mMediaSessionTrackHelper.isMediaPlaying()
        val stateIcon = if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play

        mediaButton?.let {
            it.setImageResource(stateIcon)
            setButtonActiveState(it, null, isPlaying)
        }

        mediaButtonFab?.let { fab ->
            val mediaMetadata = mMediaSessionTrackHelper.getCurrentMediaMetadata()
            val trackTitle = mediaMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            if (trackTitle.isNotEmpty() && mLastTrackTitle != trackTitle) {
                mLastTrackTitle = trackTitle
            }
            val canShowTrackTitle = isPlaying || !mLastTrackTitle.isNullOrEmpty()
            fab.setIcon(mContext.getDrawable(if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play))
            fab.text = if (canShowTrackTitle) mLastTrackTitle else mContext.resources.getString(R.string.controls_media_button_play)
            setButtonActiveState(null, fab, isPlaying)
        }
    }

    private fun toggleFlashlight() {
        if (torchButton == null && torchButtonFab == null) return
        try {
            val newState = !isFlashOn
            mFlashlightController.setFlashlight(newState)
        } catch (e: Exception) {
        }
    }

    private fun toggleWiFi() {
        val cbi = mWifiSignalCallback.mInfo
        mNetworkController.setWifiEnabled(!cbi.enabled)
        updateWiFiButtonState(!cbi.enabled)
        mHandler.postDelayed({
            updateWiFiButtonState(cbi.enabled)
        }, 250)
    }

    private fun isMobileDataEnabled(): Boolean {
        return mDataController.isMobileDataEnabled
    }

    private fun toggleMobileData() {
        val nextState = !isMobileDataEnabled()
        mDataController.isMobileDataEnabled = nextState
        updateMobileDataState(nextState)
        mHandler.postDelayed({
            updateMobileDataState(isMobileDataEnabled())
        }, 250)
    }

    private fun showInternetDialog(view: View) {
        mHandler.post {
            mInternetDialogManager.create(
                true,
                mAccessPointController.canConfigMobileData(),
                mAccessPointController.canConfigWifi(),
                Expandable.fromView(view)
            )
        }
        VibrationUtils.triggerVibration(mContext, 2)
    }

    private fun toggleRingerMode() {
        mAudioManager?.let {
            val mode = it.ringerMode
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                it.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            } else {
                it.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            updateRingerButtonState()
        }
    }

    private fun updateTileButtonState(
        iv: LaunchableImageView?,
        efab: LaunchableFAB?,
        active: Boolean,
        activeResource: Int,
        inactiveResource: Int,
        activeString: String,
        inactiveString: String
    ) {
        val targetRes = if (active) activeResource else inactiveResource
        val targetString = if (active) activeString else inactiveString

        mHandler.post {
            if (iv != null) {
                val cached = mButtonStateCache[iv]
                if (cached == null || cached.active != active || cached.imageResource != targetRes) {
                    iv.setImageResource(targetRes)
                    setButtonActiveState(iv, null, active)
                    mButtonStateCache[iv] = ButtonState(active, targetRes, null)
                }
            }
            if (efab != null) {
                val cached = mButtonStateCache[efab]
                if (cached == null || cached.active != active || cached.imageResource != targetRes || cached.text != targetString) {
                    efab.setIcon(mContext.getDrawable(targetRes))
                    efab.text = targetString
                    setButtonActiveState(null, efab, active)
                    mButtonStateCache[efab] = ButtonState(active, targetRes, targetString)
                }
            }
        }
    }

    fun updateTorchButtonState() {
        if (!isWidgetEnabled("torch")) return
        val activeString = mContext.resources.getString(TORCH_LABEL_ACTIVE)
        val inactiveString = mContext.resources.getString(TORCH_LABEL_INACTIVE)
        updateTileButtonState(
            torchButton, torchButtonFab, isFlashOn,
            TORCH_RES_ACTIVE, TORCH_RES_INACTIVE, activeString, inactiveString
        )
    }

    private val mRingerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateRingerButtonState()
        }
    }

    private val mBtCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) {
            updateBtState()
        }
        override fun onBluetoothDevicesChanged() {
            updateBtState()
        }
    }

    private fun updateWiFiButtonState(enabled: Boolean) {
        if (!isWidgetEnabled("wifi")) return
        if (wifiButton == null && wifiButtonFab == null) return
        val cbi = mWifiSignalCallback.mInfo
        val inactiveString = mContext.resources.getString(WIFI_LABEL_INACTIVE)
        updateTileButtonState(
            wifiButton, wifiButtonFab, enabled,
            WIFI_ACTIVE, WIFI_INACTIVE,
            if (cbi.ssid != null) removeDoubleQuotes(cbi.ssid) ?: inactiveString else inactiveString,
            inactiveString
        )
    }

    private fun updateRingerButtonState() {
        if (!isWidgetEnabled("ringer")) return
        if (ringerButton == null && ringerButtonFab == null) return
        mAudioManager?.let {
            val isVibrateActive = it.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            val inactiveString = mContext.resources.getString(RINGER_LABEL_INACTIVE)
            updateTileButtonState(
                ringerButton, ringerButtonFab, isVibrateActive,
                RINGER_ACTIVE, RINGER_INACTIVE, inactiveString, inactiveString
            )
        }
    }

    private fun updateMobileDataState(enabled: Boolean) {
        if (!isWidgetEnabled("data")) return
        if (dataButton == null && dataButtonFab == null) return
        val networkName = mNetworkController.mobileDataNetworkName ?: ""
        val hasNetwork = networkName.isNotEmpty() && mNetworkController.hasMobileDataFeature()
        val inactiveString = mContext.resources.getString(DATA_LABEL_INACTIVE)
        updateTileButtonState(
            dataButton, dataButtonFab, enabled,
            DATA_ACTIVE, DATA_INACTIVE,
            if (hasNetwork && enabled) networkName else inactiveString,
            inactiveString
        )
    }

    private fun toggleBluetoothState() {
        val enable = !mBluetoothController.isBluetoothEnabled
        updateBtState(enable, null)
        mBgHandler.post { mBluetoothController.isBluetoothEnabled = enable }
    }

    private fun showBluetoothDialog(view: View) {
        mHandler.post {
            mBluetoothDetailsContentViewModel.showDialog(Expandable.fromView(view))
        }
        VibrationUtils.triggerVibration(mContext, 2)
    }

    private fun updateBtState() {
        if (!isWidgetEnabled("bt")) return
        if (btButton == null && btButtonFab == null) return
        val enabled = mBluetoothController.isBluetoothEnabled
        if (!enabled) {
            updateBtState(false, null)
            return
        }

        mBgHandler.post {
            val deviceName = mBluetoothController.connectedDeviceName
            mHandler.post { updateBtState(enabled, deviceName) }
        }
    }

    private fun updateBtState(enabled: Boolean, deviceName: String?) {
        val inactiveString = mContext.resources.getString(BT_LABEL_INACTIVE)
        val isConnected = !deviceName.isNullOrEmpty()
        updateTileButtonState(
            btButton,
            btButtonFab,
            enabled,
            BT_ACTIVE,
            BT_INACTIVE,
            if (isConnected) deviceName!! else inactiveString,
            inactiveString
        )
    }

    class WifiCallbackInfo {
        var enabled = false
        var ssid: String? = null
    }

    private val mWifiSignalCallback = object : SignalCallback {
        val mInfo = WifiCallbackInfo()
        override fun setWifiIndicators(indicators: WifiIndicators) {
            if (indicators.qsIcon == null) {
                updateWiFiButtonState(false)
                return
            }
            mInfo.enabled = indicators.enabled
            mInfo.ssid = indicators.description
            updateWiFiButtonState(mInfo.enabled)
        }
    }

    private val mCellSignalCallback = object : SignalCallback {
        override fun setMobileDataIndicators(indicators: MobileDataIndicators) {
            if (indicators.qsIcon == null) {
                updateMobileDataState(false)
                return
            }
            updateMobileDataState(isMobileDataEnabled())
        }
        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            updateMobileDataState(simDetected && isMobileDataEnabled())
        }
        override fun setIsAirplaneMode(icon: IconState) {
            updateMobileDataState(!icon.visible && isMobileDataEnabled())
        }
    }

    fun enableWeatherUpdates() {
        if (!mIsWeatherObserving) {
            mWeatherClient.addObserver(mContext, this)
            mIsWeatherObserving = true
            queryAndUpdateWeather()
        }
    }

    fun disableWeatherUpdates() {
        if (mIsWeatherObserving) {
            mWeatherClient.removeObserver(mContext, this)
            mIsWeatherObserving = false
        }
    }

    override fun weatherError(errorReason: Int) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null
        }
    }

    override fun weatherUpdated() {
        queryAndUpdateWeather()
    }

    override fun updateSettings() {
        queryAndUpdateWeather()
    }

    private fun queryAndUpdateWeather() {
        if (!isWidgetEnabled("weather")) return
        try {
            val weatherClient = mWeatherClient
            if (!weatherClient.isOmniJawsEnabled(mContext)) return
            weatherClient.queryWeather(mContext)
            mWeatherInfo = weatherClient.weatherInfo
            mWeatherInfo?.let { info ->
                var formattedCondition = info.condition ?: ""
                val lowerCondition = formattedCondition.lowercase()

                formattedCondition = when {
                    lowerCondition.contains("clouds") -> mContext.resources.getString(R.string.weather_condition_clouds)
                    lowerCondition.contains("rain") -> mContext.resources.getString(R.string.weather_condition_rain)
                    lowerCondition.contains("clear") -> mContext.resources.getString(R.string.weather_condition_clear)
                    lowerCondition.contains("storm") -> mContext.resources.getString(R.string.weather_condition_storm)
                    lowerCondition.contains("snow") -> mContext.resources.getString(R.string.weather_condition_snow)
                    lowerCondition.contains("wind") -> mContext.resources.getString(R.string.weather_condition_wind)
                    lowerCondition.contains("mist") -> mContext.resources.getString(R.string.weather_condition_mist)
                    lowerCondition.contains("_") -> {
                        val words = formattedCondition.split("_")
                        words.joinToString(" ") { word ->
                            if (word.isNotEmpty()) {
                                word.substring(0, 1).uppercase() + word.substring(1)
                            } else ""
                        }.trim()
                    }
                    else -> formattedCondition
                }

                val d = if (showWeatherIcon) weatherClient.getWeatherConditionImage(mContext, info.conditionCode) else null

                weatherButtonFab?.let { fab ->
                    fab.setIcon(d)
                    fab.text = "${info.temp}${info.tempUnits} \u2022 $formattedCondition"
                    fab.setIconTint(null)
                }

                weatherButton?.let { button ->
                    button.setImageDrawable(d)
                    button.imageTintList = null
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun isWidgetEnabled(widget: String): Boolean {
        return mMainWidgetsList.contains(widget) || mSecondaryWidgetsList.contains(widget)
    }

    override fun onMediaMetadataChanged() {
        if (mIsMediaCallbackRegistered) {
            updateMediaPlaybackState()
        }
    }

    override fun onPlaybackStateChanged() {
        if (mIsMediaCallbackRegistered) {
            updateMediaPlaybackState()
        }
    }

    private inner class LockscreenWidgetsObserver : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            updateSettings()
        }

        fun observe() {
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN_WIDGETS_ENABLED),
                false,
                this
            )
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN_WIDGETS),
                false,
                this
            )
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN_WIDGETS_EXTRAS),
                false,
                this
            )
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN_WIDGETS_STYLE),
                false,
                this
            )
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN_WIDGETS_TRANSPARENCY),
                false,
                this
            )
            updateSettings()
        }

        fun unobserve() {
            mContext.contentResolver.unregisterContentObserver(this)
        }

        fun updateSettings() {
            val widgetsEnabled = Settings.System.getInt(
                mContext.contentResolver,
                LOCKSCREEN_WIDGETS_ENABLED,
                0
            ) == 1

            mLockscreenWidgetsEnabled = widgetsEnabled

            if (mLockscreenWidgetsEnabled) {
                mMainLockscreenWidgetsList = Settings.System.getString(
                    mContext.contentResolver,
                    LOCKSCREEN_WIDGETS
                )
                mSecondaryLockscreenWidgetsList = Settings.System.getString(
                    mContext.contentResolver,
                    LOCKSCREEN_WIDGETS_EXTRAS
                )
                mThemeStyle = Settings.System.getInt(
                    mContext.contentResolver,
                    LOCKSCREEN_WIDGETS_STYLE,
                    0
                )
                mTransparency = Settings.System.getInt(
                    mContext.contentResolver,
                    LOCKSCREEN_WIDGETS_TRANSPARENCY,
                    30
                ) / 100f

                mMainWidgetsList = mMainLockscreenWidgetsList?.split(",") ?: emptyList()
                mSecondaryWidgetsList = mSecondaryLockscreenWidgetsList?.split(",") ?: emptyList()

                registerActiveCallbacks()
                updateCallbackRegistrations()
                updateWidgetViews()
            } else {
                unregisterActiveCallbacks()
                mMainLockscreenWidgetsList = null
                mSecondaryLockscreenWidgetsList = null
                mMainWidgetsList = emptyList()
                mSecondaryWidgetsList = emptyList()
                updateWidgetViews()
            }
        }
    }

    private fun updateHotspotState() {
        if (!isWidgetEnabled("hotspot")) return
        if (hotspotButton == null && hotspotButtonFab == null) return
        val hotspotString = mContext.resources.getString(HOTSPOT_LABEL)
        updateTileButtonState(
            hotspotButton, hotspotButtonFab, mHotspotController.isHotspotEnabled,
            HOTSPOT_ACTIVE, HOTSPOT_INACTIVE, hotspotString, hotspotString
        )
    }

    private fun toggleHotspot() {
        val nextState = !mHotspotController.isHotspotEnabled
        mHotspotController.isHotspotEnabled = nextState
        updateHotspotState()
        mHandler.postDelayed({
            updateHotspotState()
        }, 250)
    }

    private val mHotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            updateHotspotState()
        }
        override fun onHotspotAvailabilityChanged(available: Boolean) {}
    }
}