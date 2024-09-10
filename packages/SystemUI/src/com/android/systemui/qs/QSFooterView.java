/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.BidiFormatter;
import android.text.format.Formatter;
import android.text.format.Formatter.BytesResult;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.net.DataUsageController;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.res.R;

import java.util.List;

public class QSFooterView extends FrameLayout {
    private PageIndicator mPageIndicator;
    private TextView mUsageText;
    private TextView mFooterText;
    private View mEditButton;
    private View mSpace;

    @Nullable
    protected TouchAnimator mFooterAnimator;

    private boolean mQsDisabled;
    private boolean mExpanded;
    private float mExpansionAmount;

    @Nullable
    private OnClickListener mExpandClickListener;

    private DataUsageController mDataController;
    private ConnectivityManager mConnectivityManager;
    private WifiManager mWifiManager;
    private SubscriptionManager mSubManager;
    private boolean mShouldShowDataUsage;

    public QSFooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDataController = new DataUsageController(context);
        mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mSubManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPageIndicator = findViewById(R.id.footer_page_indicator);
        mUsageText = findViewById(R.id.usage_text);
        mFooterText = findViewById(R.id.footer_text);
        mEditButton = findViewById(android.R.id.edit);
        mSpace = findViewById(R.id.spacer);

        updateResources();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateFooterTextVisibility();
        setUsageText();
    }

    private void updateFooterTextVisibility() {
        boolean isShow = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.OMNI_FOOTER_TEXT_SHOW, 0,
                UserHandle.USER_CURRENT) == 1;
        mShouldShowDataUsage = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QS_FOOTER_DATA_USAGE, 0,
                UserHandle.USER_CURRENT) == 1;

        if (isShow) {
            mUsageText.setVisibility(View.GONE);
            mFooterText.setVisibility(View.VISIBLE);
            mSpace.setVisibility(View.GONE);
            setFooterText();
        } else if (mShouldShowDataUsage) {
	    mFooterText.setVisibility(View.GONE);
	    mUsageText.setVisibility(View.VISIBLE);
	    mSpace.setVisibility(View.GONE);
	    setUsageText();
	} else {
	    mFooterText.setVisibility(View.GONE);
	    mUsageText.setVisibility(View.GONE);
	    mSpace.setVisibility(View.VISIBLE);
        }
    }

    private void setFooterText() {
        String text = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.X_FOOTER_TEXT_STRING,
                UserHandle.USER_CURRENT);
        if (text == null || text.isEmpty()) {
            mFooterText.setText("Infinity X");
        } else {
            mFooterText.setText(text);
        }
    }

    private void setUsageText() {
        if (mUsageText == null) return;
        DataUsageController.DataUsageInfo info;
        String suffix;
        if (isWifiConnected()) {
            info = mDataController.getWifiDailyDataUsageInfo();
            suffix = mContext.getResources().getString(R.string.usage_wifi_default_suffix);
        } else {
            mDataController.setSubscriptionId(
                    SubscriptionManager.getDefaultDataSubscriptionId());
            info = mDataController.getDailyDataUsageInfo();
            suffix = mContext.getResources().getString(R.string.usage_data_default_suffix);
        }
        if (info != null) {
          mUsageText.setText(formatDataUsage(info.usageLevel) + " " +
                  mContext.getResources().getString(R.string.usage_data) +
                  " (" + suffix + ")");
        } else {
           mUsageText.setText(" ");
	}
    }

    private CharSequence formatDataUsage(long byteValue) {
        final BytesResult res = Formatter.formatBytes(mContext.getResources(), byteValue,
                Formatter.FLAG_IEC_UNITS);
        return BidiFormatter.getInstance().unicodeWrap(mContext.getString(
                com.android.internal.R.string.fileSizeSuffix, res.value, res.units));
    }

    private boolean isWifiConnected() {
        final Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            return capabilities != null &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            return false;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void updateResources() {
        updateFooterAnimator();
        updateEditButtonResources();
        updateBuildTextResources();
        updateUsageTextResources();
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        lp.height = getResources().getDimensionPixelSize(R.dimen.qs_footer_height);
        int sideMargin = getResources().getDimensionPixelSize(R.dimen.qs_footer_margin);
        lp.leftMargin = sideMargin;
        lp.rightMargin = sideMargin;
        lp.bottomMargin = getResources().getDimensionPixelSize(R.dimen.qs_footers_margin_bottom);
        setLayoutParams(lp);
    }

    private void updateEditButtonResources() {
        int size = getResources().getDimensionPixelSize(R.dimen.qs_footer_action_button_size);
        int padding = getResources().getDimensionPixelSize(R.dimen.qs_footer_icon_padding);
        MarginLayoutParams lp = (MarginLayoutParams) mEditButton.getLayoutParams();
        lp.height = size;
        lp.width = size;
        mEditButton.setLayoutParams(lp);
        mEditButton.setPadding(padding, padding, padding, padding);
    }

    private void updateBuildTextResources() {
        FontSizeUtils.updateFontSizeFromStyle(mFooterText, R.style.TextAppearance_QS_Status_Build);
    }
    
    private void updateUsageTextResources() {
        FontSizeUtils.updateFontSizeFromStyle(mUsageText, R.style.TextAppearance_QS_Status_Build);
    }

    private void updateFooterAnimator() {
        mFooterAnimator = createFooterAnimator();
    }

    @Nullable
    private TouchAnimator createFooterAnimator() {
        TouchAnimator.Builder builder = new TouchAnimator.Builder()
                .addFloat(mPageIndicator, "alpha", 0, 1)
                .addFloat(mUsageText, "alpha", 0, 1)
                .addFloat(mFooterText, "alpha", 0, 1)
                .addFloat(mEditButton, "alpha", 0, 1)
                .setStartDelay(0.9f);
        return builder.build();
    }

    public void setKeyguardShowing() {
        setExpansion(mExpansionAmount);
    }

    public void setExpandClickListener(OnClickListener onClickListener) {
        mExpandClickListener = onClickListener;
    }

    void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mFooterAnimator != null) {
            mFooterAnimator.setPosition(headerExpansionFraction);
        }

        if (mUsageText == null) return;
        if (mShouldShowDataUsage && headerExpansionFraction == 1.0f) {
            mUsageText.postDelayed(() -> mUsageText.setSelected(true), 1000);
        } else {
            mUsageText.setSelected(false);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.OMNI_FOOTER_TEXT_SHOW), false,
                mSettingsObserver, UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.X_FOOTER_TEXT_STRING), false,
                mSettingsObserver, UserHandle.USER_ALL);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        super.onDetachedFromWindow();
    }

    void disable(int state2) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateEverything();
    }

    void updateEverything() {
        post(() -> {
            updateVisibilities();
            setClickable(false);
        });
    }

    private void updateVisibilities() {
        if (mExpanded) {
            updateFooterTextVisibility();
        } else {
            mUsageText.setVisibility(View.INVISIBLE);
            mFooterText.setVisibility(View.INVISIBLE);
        }
    }

    private final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateFooterTextVisibility();
        }
    };
}
