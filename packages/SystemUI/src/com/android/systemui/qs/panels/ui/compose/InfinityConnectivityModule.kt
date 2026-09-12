/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: LicenseRef-InfinityX-Proprietary
 */

package com.android.systemui.qs.panels.ui.compose

import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.Dependency
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQsGradient
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQsTileBackgroundBrush
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.AccessPointController

private val TILE_SHAPE = RoundedCornerShape(20.dp)
private val TILE_CAPSULE_SHAPE = RoundedCornerShape(percent = 50)
private val TILE_INACTIVE = Color.White.copy(alpha = 0.18f)

private object InfinityConnectivitySharedState {
    var wifiEnabled by mutableStateOf(false)
    var wifiName by mutableStateOf<String?>(null)
    var mobileDataAvailable by mutableStateOf(false)
    var mobileDataEnabled by mutableStateOf(false)
    var mobileCarrierName by mutableStateOf<String?>(null)
}

@Composable
fun InfinityConnectivityModule(
    modifier: Modifier = Modifier,
    stackMobileBelowWifi: Boolean = false,
    capsuleStyle: Boolean = false,
    showWifiTile: Boolean = true,
    showMobileTile: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val cr = context.contentResolver
    val wifiManager = remember { context.getSystemService(WifiManager::class.java) }
    val baseTelephony = remember { context.getSystemService(TelephonyManager::class.java) }
    val subscriptionManager = remember { SubscriptionManager.from(context) }
    val internetDialogManager = remember { Dependency.get(InternetDialogManager::class.java) }
    val accessPointController = remember { Dependency.get(AccessPointController::class.java) }
    val activeGradientBrush = rememberQsTileBackgroundBrush()

    fun sanitizeSsid(ssid: String?): String? {
        if (ssid.isNullOrBlank()) return null
        if (ssid == WifiManager.UNKNOWN_SSID) return null
        return ssid.trim().trim('"').takeIf { it.isNotBlank() }
    }

    fun readConnectedWifiName(): String? {
        val wifiManager = wifiManager ?: return null
        if (!wifiManager.isWifiEnabled) return null
        return try {
            @Suppress("DEPRECATION")
            sanitizeSsid(wifiManager.connectionInfo?.ssid)
        } catch (_: Exception) {
            null
        }
    }

    fun readCarrierName(subId: Int): String? {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return null
        val carrier = try {
            subscriptionManager.getActiveSubscriptionInfo(subId)?.carrierName?.toString()
        } catch (_: Exception) {
            null
        }
        if (!carrier.isNullOrBlank()) return carrier

        val telephony = try {
            baseTelephony?.createForSubscriptionId(subId)
        } catch (_: Exception) {
            return null
        }
        if (telephony == null) return null
        val simName = try {
            telephony.simOperatorName
        } catch (_: Exception) {
            null
        }
        return simName?.takeIf { !TextUtils.isEmpty(it) }
    }

    fun refreshMobileState(
        setAvailable: (Boolean) -> Unit,
        setEnabled: (Boolean) -> Unit,
        setCarrierName: (String?) -> Unit,
    ) {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val available = SubscriptionManager.isValidSubscriptionId(subId)
        setAvailable(available)
        setCarrierName(readCarrierName(subId))
        if (!available) {
            setEnabled(false)
            return
        }

        val telephony = try {
            baseTelephony?.createForSubscriptionId(subId)
        } catch (_: Exception) {
            null
        }
        val enabled = try {
            telephony?.isDataEnabled == true
        } catch (_: Exception) {
            false
        }
        setEnabled(enabled)
    }

    fun refreshWifiState(
        setEnabled: (Boolean) -> Unit,
        setWifiName: (String?) -> Unit,
    ) {
        setEnabled(wifiManager?.isWifiEnabled == true)
        setWifiName(readConnectedWifiName())
    }

    val wifiEnabled = InfinityConnectivitySharedState.wifiEnabled
    val wifiName = InfinityConnectivitySharedState.wifiName
    val mobileDataAvailable = InfinityConnectivitySharedState.mobileDataAvailable
    val mobileDataEnabled = InfinityConnectivitySharedState.mobileDataEnabled
    val mobileCarrierName = InfinityConnectivitySharedState.mobileCarrierName

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION,
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        refreshWifiState(
                            setEnabled = { InfinityConnectivitySharedState.wifiEnabled = it },
                            setWifiName = { InfinityConnectivitySharedState.wifiName = it },
                        )
                    }
                    TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED,
                    TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED -> {
                        refreshMobileState(
                            setAvailable = { InfinityConnectivitySharedState.mobileDataAvailable = it },
                            setEnabled = { InfinityConnectivitySharedState.mobileDataEnabled = it },
                            setCarrierName = { InfinityConnectivitySharedState.mobileCarrierName = it },
                        )
                    }
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        refreshWifiState(
                            setEnabled = { InfinityConnectivitySharedState.wifiEnabled = it },
                            setWifiName = { InfinityConnectivitySharedState.wifiName = it },
                        )
                        refreshMobileState(
                            setAvailable = { InfinityConnectivitySharedState.mobileDataAvailable = it },
                            setEnabled = { InfinityConnectivitySharedState.mobileDataEnabled = it },
                            setCarrierName = { InfinityConnectivitySharedState.mobileCarrierName = it },
                        )
                    }
                    else -> {
                        refreshWifiState(
                            setEnabled = { InfinityConnectivitySharedState.wifiEnabled = it },
                            setWifiName = { InfinityConnectivitySharedState.wifiName = it },
                        )
                        refreshMobileState(
                            setAvailable = { InfinityConnectivitySharedState.mobileDataAvailable = it },
                            setEnabled = { InfinityConnectivitySharedState.mobileDataEnabled = it },
                            setCarrierName = { InfinityConnectivitySharedState.mobileCarrierName = it },
                        )
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
            addAction(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter)

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshMobileState(
                    setAvailable = { InfinityConnectivitySharedState.mobileDataAvailable = it },
                    setEnabled = { InfinityConnectivitySharedState.mobileDataEnabled = it },
                    setCarrierName = { InfinityConnectivitySharedState.mobileCarrierName = it },
                )
            }
        }
        cr.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.MOBILE_DATA),
            false,
            observer,
        )

        refreshWifiState(
            setEnabled = { InfinityConnectivitySharedState.wifiEnabled = it },
            setWifiName = { InfinityConnectivitySharedState.wifiName = it },
        )
        refreshMobileState(
            setAvailable = { InfinityConnectivitySharedState.mobileDataAvailable = it },
            setEnabled = { InfinityConnectivitySharedState.mobileDataEnabled = it },
            setCarrierName = { InfinityConnectivitySharedState.mobileCarrierName = it },
        )

        onDispose {
            context.unregisterReceiver(receiver)
            cr.unregisterContentObserver(observer)
        }
    }

    fun showInternetPopup() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        internetDialogManager.create(
            aboveStatusBar = true,
            canConfigMobileData = accessPointController.canConfigMobileData(),
            canConfigWifi = accessPointController.canConfigWifi(),
            expandable = null,
        )
    }

    fun launchMobileDataSettings() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        try {
            (context.getSystemService(Context.STATUS_BAR_SERVICE) as? StatusBarManager)
                ?.collapsePanels()
        } catch (_: Exception) {}
        val intent =
            Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun toggleWifi() {
        val wifiManager = wifiManager ?: return
        val target = !wifiEnabled
        try {
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(target)
            InfinityConnectivitySharedState.wifiEnabled = target
            view.performHapticFeedback(
                if (target) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            )
        } catch (_: Exception) {}
    }

    fun toggleMobileData() {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return

        val telephony = try {
            baseTelephony?.createForSubscriptionId(subId)
        } catch (_: Exception) {
            return
        }
        if (telephony == null) return

        val target = !mobileDataEnabled
        try {
            telephony.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, target)
            InfinityConnectivitySharedState.mobileDataEnabled = target
            view.performHapticFeedback(
                if (target) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            )
        } catch (_: Exception) {}
    }

    if (stackMobileBelowWifi) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showWifiTile) {
                InfinityConnectivityTile(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = context.getString(R.string.quick_settings_wifi_label),
                    subtitle = when {
                        !wifiEnabled -> "Off"
                        !wifiName.isNullOrBlank() -> wifiName!!
                        else -> "On"
                    },
                    iconRes = if (wifiEnabled) R.drawable.ic_wifi_24 else R.drawable.ic_wifi_off_24,
                    enabled = wifiEnabled,
                    available = true,
                    activeBackgroundBrush = activeGradientBrush,
                    onClick = { toggleWifi() },
                    onLongClick = { showInternetPopup() },
                    capsuleStyle = capsuleStyle,
                )
            }

            if (showMobileTile) {
                InfinityConnectivityTile(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = context.getString(R.string.mobile_data_settings_title),
                    subtitle = when {
                        !mobileDataAvailable -> "Unavailable"
                        mobileDataEnabled && !mobileCarrierName.isNullOrBlank() -> mobileCarrierName!!
                        mobileDataEnabled -> "On"
                        else -> "Off"
                    },
                    iconRes = if (mobileDataEnabled) {
                        R.drawable.ic_signal_cellular_alt_24
                    } else {
                        R.drawable.ic_mobiledata_off_24
                    },
                    enabled = mobileDataEnabled,
                    available = mobileDataAvailable,
                    activeBackgroundBrush = activeGradientBrush,
                    onClick = { toggleMobileData() },
                    onLongClick = { launchMobileDataSettings() },
                    capsuleStyle = capsuleStyle,
                )
            }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showWifiTile) {
                InfinityConnectivityTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = context.getString(R.string.quick_settings_wifi_label),
                    subtitle = when {
                        !wifiEnabled -> "Off"
                        !wifiName.isNullOrBlank() -> wifiName!!
                        else -> "On"
                    },
                    iconRes = if (wifiEnabled) R.drawable.ic_wifi_24 else R.drawable.ic_wifi_off_24,
                    enabled = wifiEnabled,
                    available = true,
                    activeBackgroundBrush = activeGradientBrush,
                    onClick = { toggleWifi() },
                    onLongClick = { showInternetPopup() },
                    capsuleStyle = capsuleStyle,
                )
            }

            if (showMobileTile) {
                InfinityConnectivityTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = context.getString(R.string.mobile_data_settings_title),
                    subtitle = when {
                        !mobileDataAvailable -> "Unavailable"
                        mobileDataEnabled && !mobileCarrierName.isNullOrBlank() -> mobileCarrierName!!
                        mobileDataEnabled -> "On"
                        else -> "Off"
                    },
                    iconRes = if (mobileDataEnabled) {
                        R.drawable.ic_signal_cellular_alt_24
                    } else {
                        R.drawable.ic_mobiledata_off_24
                    },
                    enabled = mobileDataEnabled,
                    available = mobileDataAvailable,
                    activeBackgroundBrush = activeGradientBrush,
                    onClick = { toggleMobileData() },
                    onLongClick = { launchMobileDataSettings() },
                    capsuleStyle = capsuleStyle,
                )
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun InfinityConnectivityTile(
    label: String,
    subtitle: String,
    iconRes: Int,
    enabled: Boolean,
    available: Boolean,
    activeBackgroundBrush: Brush?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    capsuleStyle: Boolean,
    modifier: Modifier = Modifier,
) {
    val qsTileGradientEnabled = rememberQsGradient()
    val bg = if (enabled) MaterialTheme.colorScheme.primary else TILE_INACTIVE
    val iconTint = if (enabled && !qsTileGradientEnabled) MaterialTheme.colorScheme.onPrimary else Color.White
    val textColor = if (enabled && !qsTileGradientEnabled) MaterialTheme.colorScheme.onPrimary else Color.White
    val tileBackgroundModifier =
        if (enabled && activeBackgroundBrush != null) {
            Modifier.background(activeBackgroundBrush)
        } else {
            Modifier.background(bg)
        }

    Row(
        modifier = modifier
            .clip(if (capsuleStyle) TILE_CAPSULE_SHAPE else TILE_SHAPE)
            .then(tileBackgroundModifier)
            .combinedClickable(
                onClick = { if (available) onClick() },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(30.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily(Font(DeviceFontFamilyName("variable-title-medium-emphasized"))),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = textColor.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily(Font(DeviceFontFamilyName("variable-body-small"))),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}