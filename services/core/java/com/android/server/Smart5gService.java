/*
 * SPDX-FileCopyrightText: ArrowOS
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server;

import static android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED;
import static android.provider.Settings.Global.MOBILE_DATA;
import static android.provider.Settings.System.SMART_5G;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED;
import static android.telephony.TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class Smart5gService extends SystemService {

    private static final int NETWORK_TYPE_NR_NSA = 20;
    private static final int NETWORK_TYPE_NR_SA = 21;
    private static final long NETWORK_TYPE_BITMASK_NR_NSA = 1L << (NETWORK_TYPE_NR_NSA - 1);
    private static final long NETWORK_TYPE_BITMASK_NR_SA = 1L << (NETWORK_TYPE_NR_SA - 1);
    private static final long NETWORK_TYPE_BITMASK_NR = NETWORK_TYPE_BITMASK_NR_NSA | NETWORK_TYPE_BITMASK_NR_SA;

    private static final NetworkRequest INTERNET_NETWORK_REQUEST =
            new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build();

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Executor mExecutor = new HandlerExecutor(mHandler);

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubManager;
    private ConnectivityManager mConnectivityManager;
    private PowerManager mPowerManager;
    private WifiManager mWifiManager;

    private int mHotspotClientsCount = 0;

    private boolean mIsOnMobileData, mIsPowerSaveMode;
    private int[] mActiveSubIds = new int[0];
    private int mDefaultDataSubId = INVALID_SUBSCRIPTION_ID;

    private boolean mIsWifiConnected;
    private boolean mIsScreenOn = true;
    private boolean mIsHeavyTraffic = false;
    private long mLastTrafficBytes = 0;

    private static final long TRAFFIC_CHECK_INTERVAL_MS = 30000;
    private static final long TRAFFIC_THRESHOLD_BYTES_PER_SEC = 100 * 1024;
    private static final long TRAFFIC_THRESHOLD_BYTES = TRAFFIC_THRESHOLD_BYTES_PER_SEC * (TRAFFIC_CHECK_INTERVAL_MS / 1000);

    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            update();
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            switch (action) {
                case ACTION_POWER_SAVE_MODE_CHANGED:
                    final boolean on = mPowerManager.isPowerSaveMode();
                    if (on != mIsPowerSaveMode) {
                        mIsPowerSaveMode = on;
                        update();
                    }
                    break;
                case ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                    final int subId = mSubManager.getDefaultDataSubscriptionId();
                    if (subId != mDefaultDataSubId) {
                        mDefaultDataSubId = subId;
                        update();
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                    onScreenOn();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    onScreenOff();
                    break;
            }
        }
    };

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        Map<Network, NetworkCapabilities> mNetworkCaps = new HashMap<>();

        @Override
        public void onLost(Network network) {
            mNetworkCaps.remove(network);
            refresh();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            mNetworkCaps.put(network, caps);
            refresh();
        }

        private void refresh() {
            final boolean isInternetConnected = !mNetworkCaps.isEmpty();
            final boolean isMobileDataActive = mNetworkCaps.values().stream()
                    .anyMatch(nc -> nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            final boolean isOnMobileData = isMobileDataActive || !isInternetConnected;
            if (isOnMobileData != mIsOnMobileData) {
                mIsOnMobileData = isOnMobileData;
                update();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback mWifiNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        private final java.util.Set<Network> mWifiNetworks = new java.util.HashSet<>();

        @Override
        public void onAvailable(Network network) {
            synchronized (mWifiNetworks) {
                mWifiNetworks.add(network);
                updateWifiState();
            }
        }

        @Override
        public void onLost(Network network) {
            synchronized (mWifiNetworks) {
                mWifiNetworks.remove(network);
                updateWifiState();
            }
        }

        private void updateWifiState() {
            final boolean isWifiConnected = !mWifiNetworks.isEmpty();
            if (isWifiConnected != mIsWifiConnected) {
                mIsWifiConnected = isWifiConnected;
                update();
            }
        }
    };

    private final WifiManager.SoftApCallback mSoftApCallback = new WifiManager.SoftApCallback() {
        @Override
        public void onConnectedClientsChanged(List<WifiClient> clients) {
            final int count = clients.size();
            if (count != mHotspotClientsCount) {
                mHotspotClientsCount = count;
                update();
            }
        }
    };

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            final int[] subs = mSubManager.getActiveSubscriptionIdList();
            if (!Arrays.equals(subs, mActiveSubIds)) {
                mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
                for (int subId : subs) {
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
        publishLocalService(Smart5gService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
            mSubManager = mContext.getSystemService(SubscriptionManager.class);
            mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
            mPowerManager = mContext.getSystemService(PowerManager.class);
            mWifiManager = mContext.getSystemService(WifiManager.class);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mIsPowerSaveMode = mPowerManager.isPowerSaveMode();
            mDefaultDataSubId = mSubManager.getDefaultDataSubscriptionId();
            mIsScreenOn = mPowerManager.isInteractive();
            final IntentFilter filter = new IntentFilter(ACTION_POWER_SAVE_MODE_CHANGED);
            filter.addAction(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mIntentReceiver, filter);
            mConnectivityManager.registerNetworkCallback(INTERNET_NETWORK_REQUEST, mNetworkCallback);
            final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            mConnectivityManager.registerNetworkCallback(wifiRequest, mWifiNetworkCallback);
            if (mWifiManager != null) {
                mWifiManager.registerSoftApCallback(mExecutor, mSoftApCallback);
            }
            mSubManager.addOnSubscriptionsChangedListener(mExecutor, mSubListener);

            if (!mIsScreenOn) {
                onScreenOff();
            }
        }
    }

    private boolean isEnabled(int subId) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), SMART_5G + subId, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private boolean isMobileDataEnabled(int subId) {
        return Settings.Global.getInt(mContext.getContentResolver(), MOBILE_DATA + subId, 1) == 1;
    }

    private static long getSupportedNrBitmask(TelephonyManager tm, int subId) {
        if ((tm.getSupportedRadioAccessFamily() & NETWORK_TYPE_BITMASK_NR) != 0) {
            return NETWORK_TYPE_BITMASK_NR;
        } else if ((tm.getSupportedRadioAccessFamily() & NETWORK_TYPE_BITMASK_NR_NSA) != 0) {
            return NETWORK_TYPE_BITMASK_NR_NSA;
        } else {
            return 0;
        }
    }

    private synchronized void update() {
        if (mActiveSubIds == null || mActiveSubIds.length == 0) {
            return;
        }
        for (int subId : mActiveSubIds) {
            final TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
            final long supportedNrBitmask = getSupportedNrBitmask(tm, subId);
            if (supportedNrBitmask == 0) continue;
            long allowedNetworkTypes = tm.getAllowedNetworkTypesForReason(
                    ALLOWED_NETWORK_TYPES_REASON_POWER);
            final boolean is5gAllowed = (allowedNetworkTypes & supportedNrBitmask) != 0;
            final boolean shouldDisable = shouldDisable5g(subId);
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

    private final Runnable mTrafficMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsScreenOn) {
                return;
            }
            long currentBytes = getTrafficBytes();
            if (mLastTrafficBytes > 0) {
                long diff = currentBytes - mLastTrafficBytes;
                boolean heavy = diff >= TRAFFIC_THRESHOLD_BYTES;
                if (heavy) {
                    mIsHeavyTraffic = true;
                    mLastTrafficBytes = currentBytes;
                    mHandler.postDelayed(this, TRAFFIC_CHECK_INTERVAL_MS);
                } else {
                    mIsHeavyTraffic = false;
                    mLastTrafficBytes = 0;
                    update();
                }
            } else {
                mLastTrafficBytes = currentBytes;
                mHandler.postDelayed(this, TRAFFIC_CHECK_INTERVAL_MS);
            }
        }
    };

    private long getTrafficBytes() {
        long rx = TrafficStats.getMobileRxBytes();
        long tx = TrafficStats.getMobileTxBytes();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            rx = TrafficStats.getTotalRxBytes();
            tx = TrafficStats.getTotalTxBytes();
        }
        return rx + tx;
    }

    private void onScreenOn() {
        mIsScreenOn = true;
        mIsHeavyTraffic = false;
        mLastTrafficBytes = 0;
        mHandler.removeCallbacks(mTrafficMonitorRunnable);
        update();
    }

    private void onScreenOff() {
        mIsScreenOn = false;
        mIsHeavyTraffic = true;
        mLastTrafficBytes = getTrafficBytes();
        mHandler.removeCallbacks(mTrafficMonitorRunnable);
        mHandler.postDelayed(mTrafficMonitorRunnable, TRAFFIC_CHECK_INTERVAL_MS);
        update();
    }

    private boolean shouldDisable5g(int subId) {
        if (!isEnabled(subId)) {
            return false;
        } else if (!isMobileDataEnabled(subId)) {
            return true;
        }

        if (mIsWifiConnected) {
            return true;
        }

        if (!mIsScreenOn && !mIsHeavyTraffic && mHotspotClientsCount == 0) {
            return true;
        }

        final boolean powerSaveDisable = mIsPowerSaveMode && mHotspotClientsCount == 0;

        return powerSaveDisable
                || !mIsOnMobileData
                || (mDefaultDataSubId != INVALID_SUBSCRIPTION_ID && subId != mDefaultDataSubId);
    }
}