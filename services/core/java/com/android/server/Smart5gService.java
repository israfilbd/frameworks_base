/*
 * Copyright (C) 2023 ArrowOS
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

package com.android.server;

import static android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED;
import static android.provider.Settings.Global.MOBILE_DATA;
import static android.provider.Settings.System.SMART_5G;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED;
import static android.telephony.TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

public class Smart5gService extends SystemService {

    private static final String TAG = "Smart5gService";
    private static final boolean DEBUG = "eng".equals(SystemProperties.get("ro.build.type"));

    private static final int NETWORK_TYPE_NR_NSA = 20;
    private static final int NETWORK_TYPE_NR_SA = 21;
    private static final long NETWORK_TYPE_BITMASK_NR_NSA = 1L << (NETWORK_TYPE_NR_NSA - 1);
    private static final long NETWORK_TYPE_BITMASK_NR_SA = 1L << (NETWORK_TYPE_NR_SA - 1);
    private static final long NETWORK_TYPE_BITMASK_NR = NETWORK_TYPE_BITMASK_NR_NSA | NETWORK_TYPE_BITMASK_NR_SA;

    private static final NetworkRequest INTERNET_NETWORK_REQUEST = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build();

    private final Context mContext;
    private final Object mLock = new Object();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Executor mExecutor = command -> mHandler.post(command);

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubManager;
    private ConnectivityManager mConnectivityManager;
    private PowerManager mPowerManager;

    private boolean mIsOnMobileData;
    private boolean mIsPowerSaveMode;
    private boolean mIsScreenOff;
    private int[] mActiveSubIds = new int[0];
    private int mDefaultDataSubId = INVALID_SUBSCRIPTION_ID;

    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            dlog("SettingObserver: onChange");
            update();
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            dlog("Received intent: " + action);
            switch (action) {
                case ACTION_POWER_SAVE_MODE_CHANGED:
                    handlePowerSaveModeChange();
                    break;
                case ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                    handleDefaultDataSubChange();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    mIsScreenOff = true;
                    dlog("Screen turned off");
                    update();
                    break;
                case Intent.ACTION_SCREEN_ON:
                    mIsScreenOff = false;
                    dlog("Screen turned on");
                    update();
                    break;
                case Intent.ACTION_BATTERY_CHANGED:
                    update();
                    break;
                default:
                    Slog.e(TAG, "Unhandled intent: " + action);
            }
        }
    };

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        private final Map<Network, NetworkCapabilities> mNetworkCaps = new HashMap<>();

        @Override
        public void onLost(Network network) {
            dlog("NetworkCallback: onLost");
            mNetworkCaps.remove(network);
            refresh();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            dlog("NetworkCallback: onCapabilitiesChanged");
            mNetworkCaps.put(network, caps);
            refresh();
        }

        private void refresh() {
            boolean isInternetConnected = !mNetworkCaps.isEmpty();
            boolean isMobileDataActive = mNetworkCaps.values().stream()
                    .anyMatch(nc -> nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            boolean isOnMobileData = isMobileDataActive || !isInternetConnected;

            if (isOnMobileData != mIsOnMobileData) {
                mIsOnMobileData = isOnMobileData;
                update();
            }
        }
    };

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            dlog("onSubscriptionsChanged");
            int[] subs = mSubManager.getActiveSubscriptionIdList();
            if (!Arrays.equals(subs, mActiveSubIds)) {
                dlog("Active subs changed, was: " + Arrays.toString(mActiveSubIds)
                        + ", now: " + Arrays.toString(subs));
                mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
                for (int subId : subs) {
                    dlog("Registering content observer for subId " + subId);
                    mContext.getContentResolver().registerContentObserver(
                            Settings.System.getUriFor(SMART_5G + subId), false, mSettingObserver);
                    mContext.getContentResolver().registerContentObserver(
                            Settings.Global.getUriFor(MOBILE_DATA + subId), false, mSettingObserver);
                }
                mActiveSubIds = subs;
                update();
            }
        }
    };

    public Smart5gService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting Smart5gService");
        publishLocalService(Smart5gService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            dlog("onBootPhase PHASE_SYSTEM_SERVICES_READY");
            initializeServices();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            dlog("onBootPhase PHASE_BOOT_COMPLETED");
            registerReceiversAndListeners();
        }
    }

    private void initializeServices() {
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mSubManager = mContext.getSystemService(SubscriptionManager.class);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mPowerManager = mContext.getSystemService(PowerManager.class);
    }

    private void registerReceiversAndListeners() {
        mIsPowerSaveMode = mPowerManager.isPowerSaveMode();
        mDefaultDataSubId = mSubManager.getDefaultDataSubscriptionId();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mIntentReceiver, filter);

        mConnectivityManager.registerNetworkCallback(INTERNET_NETWORK_REQUEST, mNetworkCallback);
        mSubManager.addOnSubscriptionsChangedListener(mExecutor, mSubListener);
    }

    private void handlePowerSaveModeChange() {
        boolean isPowerSaveMode = mPowerManager.isPowerSaveMode();
        if (isPowerSaveMode != mIsPowerSaveMode) {
            mIsPowerSaveMode = isPowerSaveMode;
            dlog("Power save mode changed, new: " + isPowerSaveMode);
            update();
        }
    }

    private void handleDefaultDataSubChange() {
        int subId = mSubManager.getDefaultDataSubscriptionId();
        if (subId != mDefaultDataSubId) {
            mDefaultDataSubId = subId;
            dlog("Default data subscription changed, new: " + subId);
            update();
        }
    }

    private void update() {
        synchronized (mLock) {
            if (mActiveSubIds == null || mActiveSubIds.length == 0) {
                dlog("update: No active subscriptions!");
                return;
            }

            for (int subId : mActiveSubIds) {
                TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
                long supportedNrBitmask = getSupportedNrBitmask(tm, subId);
                if (supportedNrBitmask == 0) continue;

                long allowedNetworkTypes = tm.getAllowedNetworkTypesForReason(
                        ALLOWED_NETWORK_TYPES_REASON_POWER);
                boolean is5gAllowed = (allowedNetworkTypes & supportedNrBitmask) != 0;
                boolean shouldDisable = shouldDisable5g(subId);

                dlog("update: subId=" + subId + " is5gAllowed=" + is5gAllowed
                        + " shouldDisable=" + shouldDisable);

                if (shouldDisable && is5gAllowed) {
                    allowedNetworkTypes &= ~supportedNrBitmask;
                } else if (!shouldDisable && !is5gAllowed) {
                    allowedNetworkTypes |= supportedNrBitmask;
                } else {
                    continue;
                }

                tm.setAllowedNetworkTypesForReason(ALLOWED_NETWORK_TYPES_REASON_POWER,
                        allowedNetworkTypes);
            }
        }
    }

    private boolean shouldDisable5g(int subId) {
        if (!isEnabled(subId)) {
            dlog("shouldDisable5g: Smart 5G is disabled for subId " + subId);
            return false;
        }
        if (!isMobileDataEnabled(subId)) {
            dlog("shouldDisable5g: Mobile data is disabled for subId " + subId);
            return true;
        }
        if (mIsScreenOff) {
            dlog("shouldDisable5g: Screen is off");
            return true;
        }
        if (isConservativeMode() && isLowSignal(subId)) {
            dlog("shouldDisable5g: Conservative mode with low signal");
            return true;
        }
        if (isConnectedToWifi()) {
            dlog("shouldDisable5g: Connected to Wi-Fi");
            return true;
        }
        if (isBatteryLow()) {
            dlog("shouldDisable5g: Battery is low");
            return true;
        }
        return mIsPowerSaveMode || !mIsOnMobileData
                || (mDefaultDataSubId != INVALID_SUBSCRIPTION_ID && subId != mDefaultDataSubId);
    }

    private boolean isEnabled(int subId) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), SMART_5G + subId, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private boolean isMobileDataEnabled(int subId) {
        return Settings.Global.getInt(mContext.getContentResolver(), MOBILE_DATA + subId, 1) == 1;
    }

    private static long getSupportedNrBitmask(TelephonyManager tm, int subId) {
        long supportedRaf = tm.getSupportedRadioAccessFamily();
        if ((supportedRaf & NETWORK_TYPE_BITMASK_NR) != 0) {
            dlog("subId " + subId + " supports 5G EnhancedRadioCapability");
            return NETWORK_TYPE_BITMASK_NR;
        } else if ((supportedRaf & NETWORK_TYPE_BITMASK_NR_NSA) != 0) {
            dlog("subId " + subId + " supports 5G AOSP");
            return NETWORK_TYPE_BITMASK_NR_NSA;
        } else {
            dlog("subId " + subId + " does not support 5G!");
            return 0;
        }
    }

    private boolean isConservativeMode() {
        return "conservative".equals(SystemProperties.get("persist.sys.device_power_mode", ""));
    }

    private boolean isLowSignal(int subId) {
        SignalStrength signalStrength = mTelephonyManager.createForSubscriptionId(subId)
                .getSignalStrength();
        if (signalStrength == null) {
            dlog("isLowSignal: SignalStrength is null");
            return false;
        }
        int level = signalStrength.getLevel();
        dlog("isLowSignal: Signal level is " + level);
        return level <= SignalStrength.SIGNAL_STRENGTH_POOR;
    }

    private boolean isConnectedToWifi() {
        Network activeNetwork = mConnectivityManager.getActiveNetwork();
        if (activeNetwork == null) return false;
        NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(activeNetwork);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private boolean isBatteryLow() {
        BatteryManager batteryManager = mContext.getSystemService(BatteryManager.class);
        int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        dlog("isBatteryLow: Battery level is " + batteryLevel + "%");
        return batteryLevel < 10;
    }

    private static void dlog(String msg) {
        if (DEBUG) Slog.d(TAG, msg);
    }
}