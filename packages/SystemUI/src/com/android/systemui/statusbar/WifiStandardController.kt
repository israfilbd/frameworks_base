/*
 * Copyright (C) 2025 The AxionAOSP Android Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.UserHandle
import android.provider.Settings

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.flatMapLatest

object WifiStandardController {

    private val views = mutableSetOf<WifiStandardImageView>()
    private var context: Context? = null
    private var coroutineScope: CoroutineScope? = null
    private var lastStandard: Int = -1
    private var lastEnabled: Boolean = false
    private var hasCachedValue = false

    fun INSTANCE(context: Context): WifiStandardController {
        this.context = context.applicationContext
        return this
    }

    fun attachView(view: WifiStandardImageView) {
        views.add(view)
        if (hasCachedValue) {
            view.updateWifiStatus(lastStandard, lastEnabled)
        }
        if (coroutineScope == null) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            startCollecting()
        }
    }

    fun detachView(view: WifiStandardImageView) {
        views.remove(view)
        if (views.isEmpty()) {
            coroutineScope?.cancel()
            coroutineScope = null
            hasCachedValue = false
        }
    }

    private fun startCollecting() {
        coroutineScope?.launch {
            wifiStandardEnabledFlow()
                .flatMapLatest { enabled ->
                    if (enabled) {
                        wifiStandardFlow().map { standard -> Pair(standard, true) }
                    } else {
                        flowOf(Pair(-1, false))
                    }
                }
                .collect { (standard, enabled) ->
                    lastStandard = standard
                    lastEnabled = enabled
                    hasCachedValue = true
                    views.forEach { it.updateWifiStatus(standard, enabled) }
                }
        }
    }

    private fun wifiStandardFlow(): Flow<Int> = callbackFlow {
        val connectivityManager = context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context!!.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val updateWifiStandard = {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val wifiStandard = if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                wifiManager.connectionInfo.wifiStandard
            } else {
                -1
            }
            trySend(wifiStandard)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wifiStandard = wifiManager.connectionInfo.wifiStandard
                    trySend(wifiStandard)
                }
            }

            override fun onLost(network: Network) {
                trySend(-1)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateWifiStandard()
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        context!!.registerReceiver(receiver, filter)

        // Emit initial state immediately
        updateWifiStandard()

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
            context!!.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    private fun wifiStandardEnabledFlow(): Flow<Boolean> = callbackFlow {
        val contentResolver = context!!.contentResolver
        val settingUri = Settings.System.getUriFor(Settings.System.WIFI_STANDARD_ICON)

        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(isWifiStandardEnabled())
            }
        }

        contentResolver.registerContentObserver(settingUri, false, observer)
        send(isWifiStandardEnabled())

        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun isWifiStandardEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context!!.contentResolver,
            Settings.System.WIFI_STANDARD_ICON,
            0,
            UserHandle.USER_CURRENT
        ) == 1
    }
}
