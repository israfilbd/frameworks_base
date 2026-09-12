package com.android.systemui.axdynamicbar.data

import android.util.Log
import com.android.systemui.axdynamicbar.data.source.AospChipIslandManager
import com.android.systemui.axdynamicbar.data.source.AppTrackingIslandManager
import com.android.systemui.axdynamicbar.data.source.BiometricIslandManager
import com.android.systemui.axdynamicbar.data.source.ConnectivityIslandManager
import com.android.systemui.axdynamicbar.data.source.MediaIslandManager
import com.android.systemui.axdynamicbar.data.source.NotificationIslandManager
import com.android.systemui.axdynamicbar.data.source.SmartspaceIslandManager
import com.android.systemui.axdynamicbar.data.source.SystemIslandManager
import com.android.systemui.axdynamicbar.data.source.TorchIslandManager
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SysUISingleton
class IslandEventRepository
@Inject
constructor(
    val media: MediaIslandManager,
    val connectivity: ConnectivityIslandManager,
    val system: SystemIslandManager,
    val notification: NotificationIslandManager,
    val appTracking: AppTrackingIslandManager,
    val torch: TorchIslandManager,
    val biometric: BiometricIslandManager,
    val smartspace: SmartspaceIslandManager,
    val aospChip: AospChipIslandManager,
    private val settings: AxDynamicBarSettings,
) {
    companion object {
        private const val TAG = "IslandEventRepository"
    }

    @Volatile private var listenersStarted = false

    private val _indicationEvents =
        MutableStateFlow<Map<String, IslandEvent.KeyguardIndication>>(emptyMap())

    fun updateIndicationEvent(event: IslandEvent.KeyguardIndication) {
        _indicationEvents.update { it + (event.indicationType.name to event) }
    }

    fun clearIndicationEvent(type: IslandEvent.KeyguardIndication.IndicationType) {
        _indicationEvents.update { it - type.name }
    }

    fun clearAllIndicationEvents() {
        _indicationEvents.value = emptyMap()
    }

    val events: Flow<List<IslandEvent>> = buildEventsFlow()

    private val disabled get() = settings.disabledEventTypes.value

    private fun isTypeEnabled(typeId: String): Boolean = typeId !in disabled

    fun startListening() {
        if (listenersStarted) return
        listenersStarted = true
        Log.d(TAG, "Starting event listeners")
        syncDisabledTypes()

        val isMainEnabled = settings.isEnabled.value
        val isLockscreenMediaEnabled = settings.isLockscreenMediaEnabled.value
        val isLockscreenMediaLyricsEnabled = settings.isLockscreenMediaLyricsEnabled.value
        val isDynamicIslandOngoingActive = settings.isDynamicIslandOngoingActive.value
        val isDynamicIslandCallsActive = settings.isDynamicIslandCallsActive.value

        if (isMainEnabled) {
            if (isTypeEnabled("media") || isLockscreenMediaEnabled || isLockscreenMediaLyricsEnabled) media.startListening()
            if (isTypeEnabled("bluetooth")) connectivity.startBluetooth()
            if (isTypeEnabled("hotspot")) connectivity.startHotspot()
            if (isTypeEnabled("vpn")) connectivity.startVpn()
            if (isTypeEnabled("charging")) system.startCharging()
            if (isTypeEnabled("ringer")) system.startRinger()
            if (isTypeEnabled("clipboard")) system.startClipboard()
            notification.startListening()
            if (isTypeEnabled("app_switch")) appTracking.startListening()
            if (isTypeEnabled("torch")) torch.startListening()
            if (isTypeEnabled("biometric_unlock")) biometric.startListening()
            if (isTypeEnabled("media") || isTypeEnabled("sports") || isLockscreenMediaEnabled || isLockscreenMediaLyricsEnabled) smartspace.startListening()
        } else {
            if (isLockscreenMediaEnabled || isLockscreenMediaLyricsEnabled) {
                media.startListening()
                smartspace.startListening()
            }
            if (isDynamicIslandOngoingActive || isDynamicIslandCallsActive) {
                notification.startListening()
            }
        }
    }

    fun stopListening() {
        if (!listenersStarted) return
        listenersStarted = false
        Log.d(TAG, "Stopping event listeners")
        media.stopListening()
        connectivity.stopListening()
        system.stopListening()
        notification.stopListening()
        torch.stopListening()
        appTracking.stopListening()
        biometric.stopListening()
        smartspace.stopListening()
    }

    fun refreshListeners() {
        if (!listenersStarted) return
        syncDisabledTypes()

        val isMainEnabled = settings.isEnabled.value
        val isLockscreenMediaEnabled = settings.isLockscreenMediaEnabled.value
        val isLockscreenMediaLyricsEnabled = settings.isLockscreenMediaLyricsEnabled.value
        val isDynamicIslandOngoingActive = settings.isDynamicIslandOngoingActive.value
        val isDynamicIslandCallsActive = settings.isDynamicIslandCallsActive.value

        if (isMainEnabled) {
            if (isTypeEnabled("media") || isLockscreenMediaEnabled || isLockscreenMediaLyricsEnabled) media.startListening()
            else media.stopListening()

            if (isTypeEnabled("bluetooth")) connectivity.startBluetooth()
            else connectivity.stopBluetooth()
            if (isTypeEnabled("hotspot")) connectivity.startHotspot()
            else connectivity.stopHotspot()
            if (isTypeEnabled("vpn")) connectivity.startVpn()
            else connectivity.stopVpn()

            if (isTypeEnabled("charging")) system.startCharging()
            else system.stopCharging()
            if (isTypeEnabled("ringer")) system.startRinger()
            else system.stopRinger()
            if (isTypeEnabled("clipboard")) system.startClipboard()
            else system.stopClipboard()

            if (isTypeEnabled("app_switch")) appTracking.startListening()
            else appTracking.stopListening()
            if (isTypeEnabled("torch")) torch.startListening()
            else torch.stopListening()
            if (isTypeEnabled("biometric_unlock")) biometric.startListening()
            else biometric.stopListening()

            if (isTypeEnabled("media") || isTypeEnabled("sports") || isLockscreenMediaEnabled || isLockscreenMediaLyricsEnabled) smartspace.startListening()
            else smartspace.stopListening()
        } else {
            connectivity.stopListening()
            system.stopListening()
            torch.stopListening()
            appTracking.stopListening()
            biometric.stopListening()

            if (isLockscreenMediaEnabled || isLockscreenMediaLyricsEnabled) {
                media.startListening()
                smartspace.startListening()
            } else {
                media.stopListening()
                smartspace.stopListening()
            }

            if (isDynamicIslandOngoingActive || isDynamicIslandCallsActive) {
                notification.startListening()
            } else {
                notification.stopListening()
            }
        }
    }

    private fun syncDisabledTypes() {
        notification.disabledTypes = disabled
    }

    private fun buildEventsFlow(): Flow<List<IslandEvent>> {

        val sportsGroup = combine(
            smartspace.sportsEvents,
            notification.sportsEvents,
        ) { qlSports, notifSports ->
            if (!isTypeEnabled("sports")) emptyList()
            else qlSports + notifSports.filter { ns ->
                qlSports.none { qs ->
                    qs.team1Name.equals(ns.team1Name, ignoreCase = true) &&
                        qs.team2Name.equals(ns.team2Name, ignoreCase = true)
                }
            }
        }
        val promotedGroup = combine(
            notification.promotedOngoingEvents,
            sportsGroup,
            settings.isDynamicIslandOngoingActive,
        ) { promoted, sports, isDynamicIslandOngoingActive ->
            (if (isTypeEnabled("promoted_ongoing") || isDynamicIslandOngoingActive) promoted else emptyList()) + sports
        }
        val highGroup = combine(
            notification.callEvents,
            torch.torchEvent,
            biometric.biometricEvent,
            settings.isDynamicIslandCallsActive,
        ) { call, t, bio, isDynamicIslandCallsActive ->
                (if (isTypeEnabled("call") || isDynamicIslandCallsActive) call else emptyList()) +
                listOfNotNull(
                    t?.takeIf { isTypeEnabled("torch") },
                    bio?.takeIf { isTypeEnabled("biometric_unlock") },
                )
            }

        val isLockscreenMediaActiveFlow = combine(
            settings.isLockscreenMediaEnabled,
            settings.isLockscreenMediaLyricsEnabled
        ) { lockscreenMedia, lockscreenLyrics ->
            lockscreenMedia || lockscreenLyrics
        }

        val activeMediaEventFlow = combine(
            media.mediaEvent,
            isLockscreenMediaActiveFlow
        ) { m, lockscreenMediaActive ->
            m?.takeIf { isTypeEnabled("media") || lockscreenMediaActive }
        }

        val midGroup =
            combine(
                activeMediaEventFlow,
                connectivity.bluetoothEvent,
                connectivity.hotspotEvent,
                system.chargingEvent,
                notification.alarmEvent,
            ) { m, bt, hotspot, charging, alarm ->
                listOfNotNull(
                    m,
                    bt?.takeIf { isTypeEnabled("bluetooth") },
                    hotspot?.takeIf { isTypeEnabled("hotspot") },
                    charging?.takeIf { isTypeEnabled("charging") },
                    alarm?.takeIf { isTypeEnabled("alarm") },
                )
            }
        val lowGroupA =
            combine(
                notification.timerEvent,
                notification.stopwatchEvent,
                system.ringerEvent,
                connectivity.vpnEvent,
                system.clipboardEvent,
            ) { timer, stopwatch, ringer, vpn, clipboard ->
                listOfNotNull(
                    timer?.takeIf { isTypeEnabled("timer") },
                    stopwatch?.takeIf { isTypeEnabled("stopwatch") },
                    ringer?.takeIf { isTypeEnabled("ringer") },
                    vpn?.takeIf { isTypeEnabled("vpn") },
                    clipboard?.takeIf { isTypeEnabled("clipboard") },
                )
            }

        val activeNowPlayingEventFlow = combine(
            smartspace.nowPlayingEvent,
            isLockscreenMediaActiveFlow
        ) { nowPlaying, lockscreenMediaActive ->
            nowPlaying?.takeIf { isTypeEnabled("media") || lockscreenMediaActive }
        }

        val lowGroup =
            combine(
                lowGroupA,
                appTracking.appSwitchEvent,
                notification.audioRecordingEvent,
                activeNowPlayingEventFlow,
            ) { a, appSwitch, audioRec, nowPlaying ->
                a + listOfNotNull(
                    appSwitch?.takeIf { isTypeEnabled("app_switch") },
                    audioRec?.takeIf { isTypeEnabled("audio_recording") },
                    nowPlaying,
                )
            }
        val transientGroup = combine(midGroup, lowGroup) { mid, low -> mid + low }

        val indicationGroup = _indicationEvents.map { it.values.toList() }

        val allEvents = combine(
            highGroup,
            transientGroup,
            promotedGroup,
            indicationGroup,
            aospChip.aospChipEvents,
        ) { high, transient, promoted, indication, aosp ->
            high + transient + promoted + indication + aosp
        }

        return allEvents.map { events ->
            events.sorted()
        }.distinctUntilChanged { old, new ->
            old.size == new.size && old.indices.all { i ->
                old[i].withoutDrawables() == new[i].withoutDrawables()
            }
        }
    }
}
