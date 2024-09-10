/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.usage.R;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Progress bar preference with a usage summary and a total summary.
 * This preference shows number in usage summary with enlarged font size.
 */
public class UsageProgressBarPreference extends StorageUsageProgressBarPreference {

    private final Pattern mNumberPattern = Pattern.compile("[\\d]*[\\٫.,]?[\\d]+");
    private static final int ANIM_DURATION = 1200;

    private CharSequence mUsageSummary;
    private CharSequence mTotalSummary;
    private CharSequence mBottomSummary;
    private ImageView mCustomImageView;
    private int mPercent = -1;

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The {@link Context} this is associated with, through which it can
     *                access the current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs   The attributes of the XML tag that is inflating the preference
     */
    public UsageProgressBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Constructor to create a preference.
     *
     * @param context The Context this is associated with.
     */
    public UsageProgressBarPreference(Context context) {
        super(context);
    }

    /** Set usage summary, number in the summary will show with enlarged font size. */
    public void setUsageSummary(CharSequence usageSummary) {
        if (TextUtils.equals(mUsageSummary, usageSummary)) {
            return;
        }
        mUsageSummary = usageSummary;
        notifyChanged();
    }

    /** Set total summary. */
    public void setTotalSummary(CharSequence totalSummary) {
        if (TextUtils.equals(mTotalSummary, totalSummary)) {
            return;
        }
        mTotalSummary = totalSummary;
        notifyChanged();
    }

    /** Set bottom summary. */
    public void setBottomSummary(CharSequence bottomSummary) {
        if (TextUtils.equals(mBottomSummary, bottomSummary)) {
            return;
        }
        mBottomSummary = bottomSummary;
        notifyChanged();
    }

    /** Set percentage of the progress bar. */
    public void setPercent(long usage, long total) {
        if (usage > total) {
            return;
        }
        if (total == 0L) {
            if (mPercent != 0) {
                mPercent = 0;
                notifyChanged();
            }
            return;
        }
        final int percent = (int) (usage / (double) total * 100);
        if (mPercent == percent) {
            return;
        }
        mPercent = percent;
        notifyChanged();
    }

    /** Set custom ImageView to the right side of total summary. */
    public <T extends ImageView> void setCustomContent(T imageView) {
        if (imageView == mCustomImageView) {
            return;
        }
        mCustomImageView = imageView;
        notifyChanged();
    }

    /**
     * Binds the created View to the data for this preference.
     *
     * <p>This is a good place to grab references to custom Views in the layout and set
     * properties on them.
     *
     * <p>Make sure to call through to the superclass's implementation.
     *
     * @param holder The ViewHolder that provides references to the views to fill in. These views
     *               will be recycled, so you should not hold a reference to them after this method
     *               returns.
     */
    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Context context = getContext();

        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);

        final TextView usageSummary = (TextView) holder.findViewById(R.id.usage_summary);
        if (usageSummary != null && mUsageSummary != null) {
            usageSummary.setText(mUsageSummary);
        }

        final TextView totalSummary = (TextView) holder.findViewById(R.id.total_summary);
        if (totalSummary != null && mTotalSummary != null) {
            totalSummary.setText(mTotalSummary);
        }

        final TextView bottomSummary = (TextView) holder.findViewById(R.id.bottom_summary);
        if (bottomSummary != null) {
            if (TextUtils.isEmpty(mBottomSummary)) {
                bottomSummary.setVisibility(View.GONE);
            } else {
                bottomSummary.setVisibility(View.VISIBLE);
                bottomSummary.setText(mBottomSummary);
            }
        }

        final ProgressBar progressBar = (ProgressBar) holder.findViewById(android.R.id.progress);
        final ValueAnimator animator = ValueAnimator.ofInt(0, mPercent);
        if (mPercent > 0) {
            progressBar.setIndeterminate(false);
            // Animate our new progress layout
            animator.setDuration(ANIM_DURATION);
            animator.addUpdateListener(animation -> {
                int animProgress = (Integer) animation.getAnimatedValue();
                progressBar.setProgress(animProgress);
                int color = getRgbColorForPercent(animProgress);
                progressBar.setProgressTintList(ColorStateList.valueOf(color));
                progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(color));
            });
            animator.start();
        }
    }

    private CharSequence enlargeFontOfNumber(CharSequence summary) {
        if (TextUtils.isEmpty(summary)) {
            return "";
        }

        final Matcher matcher = mNumberPattern.matcher(summary);
        if (matcher.find()) {
            final SpannableString spannableSummary = new SpannableString(summary);
            spannableSummary.setSpan(new AbsoluteSizeSpan(64, true /* dip */), matcher.start(),
                    matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return spannableSummary;
        }
        return summary;
    }

    private void animateBatteryLevel(final ProgressBar progressbar, final int startValue, final int endValue) {
        final ValueAnimator animator = ValueAnimator.ofInt(startValue, endValue);
        animator.setDuration(1000);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int animatedValue = (int) animation.getAnimatedValue();
                progressbar.setProgress(animatedValue);
            }
        });
        animator.start();
    }

    private int getRgbColorForPercent(int percent) {
        int red = (int) (255 * (100 - percent) / 100.0);
        int green = (int) (255 * percent / 100.0);
        return Color.rgb(red, green, 0);
    }
}
