package com.android.systemui.axdynamicbar.domain

import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Global
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.EVENT_TYPE_IDS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@SysUISingleton
class AxDynamicBarSettings @Inject constructor(
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings,
    private val systemSettings: SystemSettings,
) {
    companion object {
        const val KEY_ENABLED = "ax_dynamic_bar_enabled"
        const val KEY_EVENTS = "ax_dynamic_bar_events"
        const val KEY_KEYGUARD_ENABLED = "ax_dynamic_bar_keyguard_enabled"
        const val KEY_LOCKSCREEN_MEDIA_ENABLED = "infinity_bar_lockscreen_media_enabled"
        const val KEY_LOCKSCREEN_MEDIA_LYRICS_ENABLED = "infinity_bar_lockscreen_media_lyrics_enabled"
        const val KEY_KEYGUARD_BATTERY_CHIP_MODE = "ax_dynamic_bar_keyguard_battery_chip_mode"
        const val KEY_COMPACT_NOTIFICATIONS = "ax_dynamic_bar_compact_notifications"
        const val KEY_CHIP_STYLE = "ax_dynamic_bar_chip_style"
    }

    private val _isEnabled = MutableStateFlow(false)
    @get:JvmName("getIsEnabled") val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isKeyguardEnabled = MutableStateFlow(true)
    val isKeyguardEnabled: StateFlow<Boolean> = _isKeyguardEnabled.asStateFlow()

    private val _isLockscreenMediaEnabled = MutableStateFlow(false)
    val isLockscreenMediaEnabled: StateFlow<Boolean> = _isLockscreenMediaEnabled.asStateFlow()

    private val _isLockscreenMediaLyricsEnabled = MutableStateFlow(false)
    val isLockscreenMediaLyricsEnabled: StateFlow<Boolean> = _isLockscreenMediaLyricsEnabled.asStateFlow()

    private val _keyguardBatteryChipMode = MutableStateFlow(1)
    val keyguardBatteryChipMode: StateFlow<Int> = _keyguardBatteryChipMode.asStateFlow()

    private val _compactNotifications = MutableStateFlow(true)
    val compactNotifications: StateFlow<Boolean> = _compactNotifications.asStateFlow()

    private val _isHeadsUpEnabled = MutableStateFlow(true)
    val isHeadsUpEnabled: StateFlow<Boolean> = _isHeadsUpEnabled.asStateFlow()

    private val _chipStyle = MutableStateFlow(0)
    val chipStyle: StateFlow<Int> = _chipStyle.asStateFlow()

    private val _isDynamicIslandOngoingActive = MutableStateFlow(false)
    val isDynamicIslandOngoingActive: StateFlow<Boolean> = _isDynamicIslandOngoingActive.asStateFlow()

    private val _isDynamicIslandCallsActive = MutableStateFlow(false)
    val isDynamicIslandCallsActive: StateFlow<Boolean> = _isDynamicIslandCallsActive.asStateFlow()

    private val _disabledEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val disabledEventTypes: StateFlow<Set<String>> = _disabledEventTypes.asStateFlow()

    private val _areLyricsShowingOnLockscreen = MutableStateFlow(false)
    val areLyricsShowingOnLockscreen: StateFlow<Boolean> = _areLyricsShowingOnLockscreen.asStateFlow()

    fun setAreLyricsShowingOnLockscreen(showing: Boolean) {
        _areLyricsShowingOnLockscreen.value = showing
    }

    init {
        refresh()
    }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        refresh()
        secureSettings.registerContentObserverForUserSync(
            KEY_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_EVENTS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_LOCKSCREEN_MEDIA_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_LOCKSCREEN_MEDIA_LYRICS_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_BATTERY_CHIP_MODE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_COMPACT_NOTIFICATIONS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_CHIP_STYLE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        globalSettings.registerContentObserverSync(
            Global.HEADS_UP_NOTIFICATIONS_ENABLED,
            false,
            settingsObserver,
        )
        systemSettings.registerContentObserverForUserSync(
            "status_bar_show_dynamic_island",
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        systemSettings.registerContentObserverForUserSync(
            "status_bar_dynamic_island_ongoing_activities",
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        systemSettings.registerContentObserverForUserSync(
            "status_bar_dynamic_island_calls",
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
    }

    fun destroy() {
        if (!initialized) return
        initialized = false
        secureSettings.getContentResolver().unregisterContentObserver(settingsObserver)
        globalSettings.getContentResolver().unregisterContentObserver(settingsObserver)
        systemSettings.getContentResolver().unregisterContentObserver(settingsObserver)
    }

    private fun refresh() {
        _isEnabled.value =
            secureSettings.getIntForUser(KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _isKeyguardEnabled.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_ENABLED, 1, UserHandle.USER_CURRENT) == 1
        _isLockscreenMediaEnabled.value =
            secureSettings.getIntForUser(KEY_LOCKSCREEN_MEDIA_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _isLockscreenMediaLyricsEnabled.value =
            secureSettings.getIntForUser(KEY_LOCKSCREEN_MEDIA_LYRICS_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _keyguardBatteryChipMode.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_BATTERY_CHIP_MODE, 1, UserHandle.USER_CURRENT)
        _compactNotifications.value =
            secureSettings.getIntForUser(KEY_COMPACT_NOTIFICATIONS, 1, UserHandle.USER_CURRENT) == 1
        _isHeadsUpEnabled.value =
            globalSettings.getInt(Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1
        _chipStyle.value =
            secureSettings.getIntForUser(KEY_CHIP_STYLE, 0, UserHandle.USER_CURRENT)

        val diEnabled = systemSettings.getIntForUser("status_bar_show_dynamic_island", 0, UserHandle.USER_CURRENT) == 1
        val ongoingEnabled = systemSettings.getIntForUser("status_bar_dynamic_island_ongoing_activities", 1, UserHandle.USER_CURRENT) == 1
        _isDynamicIslandOngoingActive.value = diEnabled && ongoingEnabled

        val callsEnabled = systemSettings.getIntForUser("status_bar_dynamic_island_calls", 1, UserHandle.USER_CURRENT) == 1
        _isDynamicIslandCallsActive.value = diEnabled && callsEnabled

        val json = secureSettings.getStringForUser(KEY_EVENTS, UserHandle.USER_CURRENT) ?: ""
        _disabledEventTypes.value =
            try {
                if (json.isBlank()) emptySet()
                else {
                    val arr = JSONArray(json)
                    (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
                }
            } catch (_: Exception) {
                emptySet()
            }
    }

    fun isEventEnabled(event: IslandEvent): Boolean {
        val typeId = EVENT_TYPE_IDS[event::class.java] ?: return true
        return typeId !in _disabledEventTypes.value
    }

    fun isNotificationEventsActive(): Boolean =
        _isEnabled.value && "notification" !in _disabledEventTypes.value

    fun isKeyguardBiometricUnlockEventsActive(): Boolean =
        _isEnabled.value && _isKeyguardEnabled.value &&
            "biometric_unlock" !in _disabledEventTypes.value
}
