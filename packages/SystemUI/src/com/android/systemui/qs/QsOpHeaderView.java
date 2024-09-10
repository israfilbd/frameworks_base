/*
 * Copyright (C) 2024 Project Infinity X Android Project
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
package com.android.systemui.qs;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.UserHandle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.UserHandle;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TypedValue;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ImageView.ScaleType;
import android.widget.Space;
import android.widget.TextView;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.systemui.qs.QSLayoutUtils;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;

import com.bosphere.fadingedgelayout.FadingEdgeLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.Exception;
import java.lang.Math;
import java.util.Iterator;
import java.util.List;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.systemui.plugins.ActivityStarter;
import android.media.session.MediaController;
import android.media.session.MediaSessionLegacyHelper;
import android.net.ConnectivityManager;
import com.superior.org.utils.palette.Palette;

import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialogViewModel;
import com.android.systemui.qs.tiles.dialog.InternetDialogManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;

public class QsOpHeaderView extends LinearLayout
        implements BluetoothCallback, NotificationMediaManager.MediaListener, Palette.PaletteAsyncListener {
        
    private static final String TAG = "QsOpHeaderView";

    private int colorActive = Color.BLACK;
    private int colorInactive = Color.BLACK;
    private int colorLabelActive = Color.WHITE;
    private int colorLabelInactive = Color.WHITE;

    private int mColorArtwork = Color.BLACK;
    private int mMediaTextIconColor = Color.WHITE;

    private ViewGroup mBluetoothButton;
    private ImageView mBluetoothIcon;
    private TextView mBluetoothText;
    private ImageView mBluetoothChevron;
    private boolean mBluetoothEnabled = false;
    
    private float labelSize = 15f;

    private ViewGroup mInternetButton;
    private ImageView mInternetIcon;
    private TextView mInternetText;
    private ImageView mInternetChevron;
    private boolean mInternetEnabled = false;

    private ImageView mMediaPlayerBackground;
    private ImageView mAppIcon, mMediaOutputSwitcher;
    private TextView mMediaPlayerTitle, mMediaPlayerSubtitle;
    private ImageButton mMediaBtnPrev, mMediaBtnNext, mMediaBtnPlayPause;

    private String mMediaTitle, mMediaArtist;
    private Bitmap mMediaArtwork;
    private boolean mMediaIsPlaying = false;

    private Runnable mUpdateRunnableBluetooth;
    private Runnable mUpdateRunnableInternet;

    private final Handler mHandler;
    private final ActivityStarter mActivityStarter;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;
    private final ConnectivityManager mConnectivityManager;
    private final SubscriptionManager mSubManager;
    private final WifiManager mWifiManager;
    private final NotificationMediaManager mNotificationMediaManager;
    private final BluetoothController mBluetoothController;
    private final BluetoothTileDialogViewModel mBluetoothTileDialogViewModel;
    private final InternetDialogManager mInternetDialogManager;
    private final AccessPointController mAccessPointController;

    public QsOpHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;

        mHandler = Dependency.get(Dependency.MAIN_HANDLER);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mMediaOutputDialogFactory = Dependency.get(MediaOutputDialogFactory.class);
        mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mSubManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mNotificationMediaManager = Dependency.get(NotificationMediaManager.class);
        mBluetoothController = Dependency.get(BluetoothController.class);
        mBluetoothTileDialogViewModel = Dependency.get(BluetoothTileDialogViewModel.class);
        mInternetDialogManager = Dependency.get(InternetDialogManager.class);
        mAccessPointController = Dependency.get(AccessPointController.class);
        labelSize = QSLayoutUtils.getQSTileLabelSize(context);

        updateColors();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        initLayout();
        initBluetoothManager();
        initListeners();
        updateResources();
        startUpdatingTiles();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View view, int visibility) {
        super.onVisibilityChanged(view, visibility);

        if (visibility == View.VISIBLE) {
            updateResources();
            startUpdatingTiles();
        } else {
            stopUpdatingTiles();
        }
    }

    private void initLayout() {
        mInternetButton = findViewById(R.id.qs_op_button_internet);
        mInternetIcon = findViewById(R.id.qs_op_internet_icon);
        mInternetText = findViewById(R.id.qs_op_internet_text);
        mInternetChevron = findViewById(R.id.qs_op_chevron_internet);
        mBluetoothButton = findViewById(R.id.qs_op_button_bluetooth);
        mBluetoothIcon = findViewById(R.id.qs_op_bluetooth_icon);
        mBluetoothText = findViewById(R.id.qs_op_bluetooth_text);
        mBluetoothChevron = findViewById(R.id.qs_op_chevron_bluetooth);        

        mMediaPlayerBackground = findViewById(R.id.qs_op_media_player_bg);
        mAppIcon = findViewById(R.id.op_media_player_app_icon);
        mMediaOutputSwitcher = findViewById(R.id.op_media_player_output_switcher);
        mMediaPlayerTitle = findViewById(R.id.op_media_player_title);
        mMediaPlayerSubtitle = findViewById(R.id.op_media_player_subtitle);
        mMediaBtnPrev = findViewById(R.id.op_media_player_action_prev);
        mMediaBtnNext = findViewById(R.id.op_media_player_action_next);
        mMediaBtnPlayPause = findViewById(R.id.op_media_player_action_play_pause);
    }

    private void initListeners() {
        mNotificationMediaManager.addCallback(this);

        mMediaPlayerBackground.setOnClickListener(mOnClickListener);
        mMediaOutputSwitcher.setOnClickListener(mOnClickListener);
        mMediaBtnPrev.setOnClickListener(mOnClickListener);
        mMediaBtnNext.setOnClickListener(mOnClickListener);
        mMediaBtnPlayPause.setOnClickListener(mOnClickListener);
        mInternetButton.setOnClickListener(mOnClickListener);
        mBluetoothButton.setOnClickListener(mOnClickListener);
        mInternetButton.setOnLongClickListener(mOnLongClickListener);
        mBluetoothButton.setOnLongClickListener(mOnLongClickListener);
    }

    private void updateResources() {
        updateColors();
        updateMediaPlayer();
    }

    public void updateColors() {
        colorActive = Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorAccent);
        colorInactive = Utils.getColorAttrDefaultColor(mContext, R.attr.shadeInactive);
        colorLabelActive = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.textColorPrimaryInverse);
        colorLabelInactive = Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorPrimary);
    }
    
    private void initBluetoothManager() {
        LocalBluetoothManager localBluetoothManager = LocalBluetoothManager.getInstance(mContext, null);

        if (localBluetoothManager != null) {
            localBluetoothManager.getEventManager().registerCallback(this);

            LocalBluetoothAdapter localBluetoothAdapter = localBluetoothManager.getBluetoothAdapter();
            if (localBluetoothAdapter != null) {
                synchronized (localBluetoothAdapter) {
                    int bluetoothState = localBluetoothAdapter.getAdapter().getState();
                    if (bluetoothState != localBluetoothAdapter.getBluetoothState()) {
                        localBluetoothAdapter.setBluetoothStateInt(bluetoothState);
                    }
                    updateBluetoothState(bluetoothState);
                }
            } else {
                Log.e(TAG, "LocalBluetoothAdapter is null.");
            }
        } else {
            Log.e(TAG, "LocalBluetoothManager is null.");
        }
    }

    @Override
    public void onBluetoothStateChanged(@AdapterState int bluetoothState) {
        updateBluetoothState(bluetoothState);
    }

    private void updateBluetoothState(@AdapterState int bluetoothState) {
        mBluetoothEnabled = bluetoothState == BluetoothAdapter.STATE_ON
                || bluetoothState == BluetoothAdapter.STATE_TURNING_ON;
        updateBluetoothTile();
    }

    private final void updateBluetoothTile() {
        if (mBluetoothButton == null
                || mBluetoothIcon == null
                || mBluetoothText == null
                || mBluetoothChevron == null)
            return;

        Drawable background = mBluetoothButton.getBackground();
        String deviceName = mBluetoothEnabled
                            ? mBluetoothController.getConnectedDeviceName()
                            : "";
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = mContext.getResources().getString(R.string.quick_settings_bluetooth_label);
        }
        int iconResId = R.drawable.qs_bluetooth_icon_off;
        mBluetoothText.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize);

        if (mBluetoothEnabled) {
            if (mBluetoothController.isBluetoothConnected()) {
	       iconResId = R.drawable.qs_bluetooth_icon_off;
	    } else {
	       iconResId = R.drawable.qs_bluetooth_icon_on;
	    }
            background.setTint(colorActive);
            mBluetoothText.setTextColor(colorLabelActive);
            mBluetoothChevron.setColorFilter(colorLabelActive);
            mBluetoothIcon.setColorFilter(colorLabelActive);
        } else {
            background.setTint(colorInactive);
            mBluetoothText.setTextColor(colorLabelInactive);
            mBluetoothChevron.setColorFilter(colorLabelInactive);
            mBluetoothIcon.setColorFilter(colorLabelInactive);
            iconResId = R.drawable.qs_bluetooth_icon_on;
        }

        mBluetoothText.setText(deviceName);
        mBluetoothIcon.setImageResource(iconResId);
    }

    private void updateInterntTile() {
        if (mInternetButton == null
                || mInternetIcon == null
                || mInternetText == null
                || mInternetChevron == null)
            return;

        String carrier = "";
        int iconResId = 0;

        if (isWifiConnected()) {
            carrier = getWifiSsid();
            mInternetEnabled = true;
            iconResId = mContext.getResources().getIdentifier("ic_wifi_signal_4", "drawable", "android");
        } else if (isCarrierDataConnected()) {
            carrier = getSlotCarrierName();
            mInternetEnabled = true;
            iconResId = mContext.getResources().getIdentifier("ic_signal_cellular_4_4_bar", "drawable", "android");
        } else {
            carrier = "No Connection";
            mInternetEnabled = false;
            iconResId = mContext.getResources().getIdentifier("ic_qs_no_internet_unavailable", "drawable", "com.android.systemui");
        }
        
        mInternetText.setText(carrier);
        mInternetIcon.setImageResource(iconResId);
        Drawable background = mInternetButton.getBackground();
        mInternetText.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize);

        if (mInternetEnabled) {
            background.setTint(colorActive);
            mInternetIcon.setColorFilter(colorLabelActive);
            mInternetText.setTextColor(colorLabelActive);
            mInternetChevron.setColorFilter(colorLabelActive);
        } else {
            background.setTint(colorInactive);
            mInternetIcon.setColorFilter(colorLabelInactive);
            mInternetText.setTextColor(colorLabelInactive);
            mInternetChevron.setColorFilter(colorLabelInactive);
        }
        mInternetButton.setEnabled(true);
    }
    
    private boolean isCarrierDataConnected() {
	    final Network network = mConnectivityManager.getActiveNetwork();
	    if (network != null) {
		NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
		return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
	    } else {
		return false;
	    }
	}

    private boolean isWifiConnected() {
        final Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            return false;
        }
    }

    private String getSlotCarrierName() {
        CharSequence result = mContext.getResources().getString(R.string.quick_settings_internet_label);
        int subId = mSubManager.getDefaultDataSubscriptionId();
        final List<SubscriptionInfo> subInfoList = mSubManager.getActiveSubscriptionInfoList(true);
        if (subInfoList != null) {
            for (SubscriptionInfo subInfo : subInfoList) {
                if (subId == subInfo.getSubscriptionId()) {
                    result = subInfo.getDisplayName();
                    break;
                }
            }
        }
        return result.toString();
    }

    private String getWifiSsid() {
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        if (wifiInfo.getHiddenSSID() || wifiInfo.getSSID() == WifiManager.UNKNOWN_SSID) {
            return mContext.getResources().getString(R.string.quick_settings_wifi_label);
        } else {
            return wifiInfo.getSSID().replace("\"", "");
        }
    }

    @Override
    public void onPrimaryMetadataOrStateChanged(MediaMetadata metadata, @PlaybackState.State int state) {
        if (metadata != null) {
            CharSequence title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);

            mMediaTitle = title != null ? title.toString() : null;
            mMediaArtist = artist != null ? artist.toString() : null;
            Bitmap albumArtwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            Bitmap mediaArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            mMediaArtwork = (albumArtwork != null) ? albumArtwork : mediaArt;

            if (mMediaArtwork != null) {
                Palette.generateAsync(mMediaArtwork, this);
            }
        } else {
            mMediaTitle = null;
            mMediaArtist = null;
            mMediaArtwork = null;
        }

        if (mMediaArtwork == null) {
            mMediaPlayerTitle.setTextColor(colorLabelInactive);
            mMediaPlayerSubtitle.setTextColor(colorLabelInactive);
            mMediaBtnPrev.setColorFilter(colorLabelInactive);
            mMediaBtnPlayPause.setColorFilter(colorLabelInactive);
            mMediaBtnNext.setColorFilter(colorLabelInactive);
            mMediaOutputSwitcher.setColorFilter(colorLabelInactive);
            mMediaPlayerBackground.setColorFilter(null);
        }

        mMediaIsPlaying = state == PlaybackState.STATE_PLAYING;

        updateMediaPlayer();
    }

    @Override
    public void setMediaNotificationColor(int color) {
    }

    @Override
    public void onGenerated(Palette palette) {
        int mShadow = 120;
        int alphaValue = 100;
        mColorArtwork = ColorUtils.setAlphaComponent(palette.getDarkVibrantColor(Color.BLACK), mShadow);
        int mMediaOutputIconColor = palette.getLightVibrantColor(Color.WHITE);

        mMediaPlayerTitle.setTextColor(mMediaTextIconColor);
        mMediaPlayerSubtitle.setTextColor(mMediaTextIconColor);
        mMediaBtnPrev.setColorFilter(mMediaTextIconColor);
        mMediaBtnPlayPause.setColorFilter(mMediaTextIconColor);
        mMediaBtnNext.setColorFilter(mMediaTextIconColor);
        mMediaOutputSwitcher.setColorFilter(mMediaOutputIconColor);
        mMediaPlayerBackground.setColorFilter(ColorUtils.setAlphaComponent(mColorArtwork, alphaValue),
                PorterDuff.Mode.SRC_ATOP);

    }

    private void updateMediaPlayer() {
        if (mMediaPlayerBackground == null
                || mAppIcon == null
                || mMediaOutputSwitcher == null
                || mMediaPlayerTitle == null
                || mMediaPlayerSubtitle == null
                || mMediaBtnPrev == null
                || mMediaBtnNext == null
                || mMediaBtnPlayPause == null) {
            return;
        }

        mMediaPlayerTitle.setText(mMediaTitle == null
                ? mContext.getResources().getString(R.string.op_media_player_default_title)
                : mMediaTitle);
        mMediaPlayerSubtitle.setText(mMediaArtist == null ? "" : mMediaArtist);
        mMediaPlayerSubtitle.setVisibility(mMediaArtist == null ? View.GONE : View.VISIBLE);

        if (mMediaIsPlaying) {
            mMediaBtnPlayPause.setImageResource(R.drawable.ic_op_media_player_action_pause);
            mMediaPlayerTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            mMediaPlayerTitle.setSingleLine(true);
            mMediaPlayerTitle.setMarqueeRepeatLimit(-1);
            mMediaPlayerTitle.setFocusableInTouchMode(true);
            mMediaPlayerTitle.setFocusable(true);
            mMediaPlayerTitle.setSelected(true);
        } else {
            mMediaBtnPlayPause.setImageResource(R.drawable.ic_op_media_player_action_play);
            mMediaPlayerTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            mMediaPlayerTitle.setSingleLine(true);
            mMediaPlayerTitle.setMarqueeRepeatLimit(0);
            mMediaPlayerTitle.setFocusable(false);
            mMediaPlayerTitle.setSelected(false);
        }

        // Enable fading edges
        mMediaPlayerTitle.setHorizontalFadingEdgeEnabled(true);
        mMediaPlayerTitle.setFadingEdgeLength(50);

        if (mNotificationMediaManager != null && mNotificationMediaManager.getMediaIcon() != null
                && mMediaTitle != null) {
            mAppIcon.setImageIcon(mNotificationMediaManager.getMediaIcon());
        } else {
            mAppIcon.setImageResource(R.drawable.ic_op_media_player_icon);
        }
        mAppIcon.setColorFilter(colorLabelActive);

        mMediaPlayerBackground.setImageDrawable(getMediaArtwork());
        mMediaPlayerBackground.setClipToOutline(true);
    }

    private Drawable getMediaArtwork() {
        if (mMediaArtwork == null) {
            Drawable artwork = ContextCompat.getDrawable(mContext, R.drawable.qs_op_media_player_bg);
            DrawableCompat.setTint(DrawableCompat.wrap(artwork), colorInactive);
            return artwork;
        } else {
            Drawable artwork = new BitmapDrawable(mContext.getResources(), mMediaArtwork);
            return artwork;
        }
    }

    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (v == mInternetButton) {
                new Handler().post(() -> mInternetDialogManager.create(true,
                        mAccessPointController.canConfigMobileData(),
                        mAccessPointController.canConfigWifi(), v));
            } else if (v == mBluetoothButton) {
                new Handler().post(() -> mBluetoothTileDialogViewModel.showDialog(mContext, v));
            } else if (v == mMediaBtnPrev) {
                mNotificationMediaManager.skipTrackPrevious();
            } else if (v == mMediaBtnPlayPause) {
                mNotificationMediaManager.playPauseTrack();
            } else if (v == mMediaBtnNext) {
                mNotificationMediaManager.skipTrackNext();
            } else if (v == mMediaPlayerBackground) {
                launchMediaPlayer();
            } else if (v == mMediaOutputSwitcher) {
                launchMediaOutputSwitcher(v);
            }
        }
    };

    private final View.OnLongClickListener mOnLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (v == mInternetButton) {
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(Settings.ACTION_WIFI_SETTINGS), 0);
            } else if (v == mBluetoothButton) {
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0);
            } else {
                return false;
            }
            return true;
        }
    };

    private void launchMediaPlayer() {
        String packageName = mNotificationMediaManager.getMediaController() != null
                ? mNotificationMediaManager.getMediaController().getPackageName()
                : null;
        Intent appIntent = packageName != null
                ? new Intent(mContext.getPackageManager().getLaunchIntentForPackage(packageName))
                : null;
        if (appIntent != null) {
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appIntent.setPackage(packageName);
            mActivityStarter.startActivity(appIntent, true);
            return;
        }

        sendMediaButtonClickEvent();
    }

    private void launchMediaOutputSwitcher(View v) {
        String packageName = mNotificationMediaManager.getMediaController() != null
                ? mNotificationMediaManager.getMediaController().getPackageName()
                : null;
        if (packageName != null) {
            mMediaOutputDialogFactory.create(packageName, true, v);
        }
    }

    private void sendMediaButtonClickEvent() {
        long now = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(now, now, 0, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        helper.sendMediaButtonEvent(keyEvent, true);
        helper.sendMediaButtonEvent(KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_UP), true);
    }

    private void startUpdatingTiles() {
        startUpdateInterntTileStateAsync();
        startUpdateBluetoothTileStateAsync();
    }

    private void startUpdateInterntTileStateAsync() {
        AsyncTask.execute(new Runnable() {
            public void run() {
                startUpdateInterntTileState();
            }
        });
    }

    private void startUpdateBluetoothTileStateAsync() {
        AsyncTask.execute(new Runnable() {
            public void run() {
                startUpdateBluetoothTileState();
            }
        });
    }

    private void startUpdateInterntTileState() {
        Runnable runnable = mUpdateRunnableInternet;

        if (runnable == null) {
            mUpdateRunnableInternet = new Runnable() {
                public void run() {
                    updateInterntTile();
                    scheduleInternetUpdate();
                }
            };
        } else {
            mHandler.removeCallbacks(runnable);
        }

        scheduleInternetUpdate();
    }

    private void startUpdateBluetoothTileState() {
        Runnable runnable = mUpdateRunnableBluetooth;

        if (runnable == null) {
            mUpdateRunnableBluetooth = new Runnable() {
                public void run() {
                    updateBluetoothTile();
                    scheduleBluetoothUpdate();
                }
            };
        } else {
            mHandler.removeCallbacks(runnable);
        }

        scheduleBluetoothUpdate();
    }

    private void scheduleInternetUpdate() {
        Runnable runnable;
        if ((runnable = mUpdateRunnableInternet) != null) {
            mHandler.postDelayed(runnable, 1000);
        }
    }

    private void scheduleBluetoothUpdate() {
        Runnable runnable;
        if ((runnable = mUpdateRunnableBluetooth) != null) {
            mHandler.postDelayed(runnable, 1000);
        }
    }

    private void stopUpdatingTiles() {
        stopUpdatingInternetTile();
        stopUpdatingBluetoothTile();
    }

    private void stopUpdatingInternetTile() {
        if (mUpdateRunnableInternet != null) {
            mHandler.removeCallbacks(mUpdateRunnableInternet);
        }
        mUpdateRunnableInternet = null;
    }

    private void stopUpdatingBluetoothTile() {
        if (mUpdateRunnableBluetooth != null) {
            mHandler.removeCallbacks(mUpdateRunnableBluetooth);
        }
        mUpdateRunnableBluetooth = null;
    }
}
