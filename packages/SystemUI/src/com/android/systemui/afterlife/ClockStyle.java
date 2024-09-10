package com.android.systemui.afterlife;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextClock;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.tuner.TunerService;

public class ClockStyle extends RelativeLayout implements TunerService.Tunable {

    private static final int[] CLOCK_VIEW_IDS = {
            0,
            R.id.keyguard_clock_style_oos,
            R.id.keyguard_clock_style_ios,
            R.id.keyguard_clock_style_cos,
            R.id.keyguard_clock_style_custom,
            R.id.keyguard_clock_style_custom1,
            R.id.keyguard_clock_style_custom2,
            R.id.keyguard_clock_style_custom3,
            R.id.keyguard_clock_style_miui,
            R.id.keyguard_clock_style_ide,
            R.id.keyguard_clock_style_lottie,
            R.id.keyguard_clock_style_lottie2,
            R.id.keyguard_clock_style_fluid,
            R.id.keyguard_clock_style_hyper,
            R.id.keyguard_clock_style_dual,
            R.id.keyguard_clock_style_stylish,
            R.id.keyguard_clock_style_sidebar,
            R.id.keyguard_clock_style_minimal,
            R.id.keyguard_clock_style_minimal2,
            R.id.keyguard_clock_style_minimal3
    };

    private static final int DEFAULT_STYLE = 0;
    private static final String CLOCK_STYLE_KEY = "clock_style";
    private static final String TOGGLE_LAYOUT_KEY = "toggle_layout_visibility";
    private static final String LOCKSCREEN_CLOCK_COLORED_KEY = "lockscreen_clock_colored";
    private static final String CLOCK_MARGIN_TOP_KEY = "clock_margin_top";

    private static final String CLOCK_STYLE = "system:" + CLOCK_STYLE_KEY;
    private static final String TOGGLE_LAYOUT = "system:" + TOGGLE_LAYOUT_KEY;
    private static final String LOCKSCREEN_CLOCK_COLORED = "system:" + LOCKSCREEN_CLOCK_COLORED_KEY;
    private static final String CLOCK_MARGIN_TOP = "system:" + CLOCK_MARGIN_TOP_KEY;

    private Context mContext;
    private View[] clockViews;
    private int mClockStyle;

    private View toggleableLayout;
    private boolean isToggleableLayoutVisible;
    private boolean isLockscreenClockColored;
    private int mClockMarginTop;

    private static final long UPDATE_INTERVAL_MILLIS = 15 * 1000;
    private final Handler mHandler;
    private long lastUpdateTimeMillis = 0;

    private SharedPreferences sharedPreferences;

    private static final long BURN_IN_PROTECTION_INTERVAL = 60000;
    private static final float SHIFT_AMOUNT = 1.0f;
    private float shiftX = 0;
    private float shiftY = 0;
    private boolean shiftDirection = true;

    public ClockStyle(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mHandler = new Handler(context.getMainLooper());
        Dependency.get(TunerService.class).addTunable(this, CLOCK_STYLE, TOGGLE_LAYOUT, LOCKSCREEN_CLOCK_COLORED, CLOCK_MARGIN_TOP);

        sharedPreferences = context.getSharedPreferences("ClockStylePreferences", Context.MODE_PRIVATE);
        isToggleableLayoutVisible = sharedPreferences.getBoolean(TOGGLE_LAYOUT_KEY, false);
        mClockMarginTop = sharedPreferences.getInt(CLOCK_MARGIN_TOP_KEY, 0);

        mHandler.postDelayed(this::updateBurnInProtection, BURN_IN_PROTECTION_INTERVAL);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHandler.post(() -> {
            clockViews = new View[CLOCK_VIEW_IDS.length];
            for (int i = 0; i < CLOCK_VIEW_IDS.length; i++) {
                if (CLOCK_VIEW_IDS[i] != 0) {
                    clockViews[i] = findViewById(CLOCK_VIEW_IDS[i]);
                } else {
                    clockViews[i] = null;
                }
            }
            toggleableLayout = findViewById(R.id.toggleable_layout);
            updateClockView();
            toggleableLayout.setVisibility(isToggleableLayoutVisible ? View.VISIBLE : View.GONE);
        });
    }

    private void updateTextClockViews(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            mHandler.post(() -> {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    View childView = viewGroup.getChildAt(i);
                    updateTextClockViews(childView);
                    if (childView instanceof TextClock) {
                        ((TextClock) childView).refreshTime();
                    }
                }
            });
        }
    }

    public void onTimeChanged() {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - lastUpdateTimeMillis >= UPDATE_INTERVAL_MILLIS) {
            if (clockViews != null) {
                mHandler.post(() -> {
                    for (View clockView : clockViews) {
                        updateTextClockViews(clockView);
                        lastUpdateTimeMillis = currentTimeMillis;
                    }
                });
            }
        }
    }

    private void updateClockView() {
        if (clockViews != null) {
            mHandler.post(() -> {
                for (int i = 0; i < clockViews.length; i++) {
                    if (clockViews[i] != null) {
                        int visibility = (i == mClockStyle) ? View.VISIBLE : View.GONE;
                        if (clockViews[i].getVisibility() != visibility) {
                            clockViews[i].setVisibility(visibility);
                        }
                        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) clockViews[i].getLayoutParams();
                        layoutParams.topMargin = mClockMarginTop;
                        clockViews[i].setLayoutParams(layoutParams);
                    }
                }
                if (isLockscreenClockColored) {
                    setClockColorForIOS(getResources().getColor(android.R.color.system_accent1_100));
                } else {
                    setClockColorForIOS(mContext.getResources().getColor(android.R.color.white));
                }
            });
        }
    }

    public void setClockColorForIOS(int color) {
        View iosClockView = findViewById(R.id.keyguard_clock_style_ios);
        if (iosClockView != null) {
            setTextClockColor(iosClockView, color);
        }
    }

    private void setTextClockColor(View view, int color) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View childView = viewGroup.getChildAt(i);
                setTextClockColor(childView, color);
            }
        } else if (view instanceof TextClock) {
            ((TextClock) view).setTextColor(color);
        }
    }

    private void updateBurnInProtection() {
        if (clockViews != null) {
            mHandler.post(() -> {
                shiftDirection = !shiftDirection;
                float shiftValue = shiftDirection ? SHIFT_AMOUNT : -SHIFT_AMOUNT;
                shiftX += shiftValue;
                shiftY += shiftValue;

                for (View clockView : clockViews) {
                    if (clockView != null) {
                        clockView.setTranslationX(shiftX);
                        clockView.setTranslationY(shiftY);
                    }
                }
            });
            mHandler.postDelayed(this::updateBurnInProtection, BURN_IN_PROTECTION_INTERVAL);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case CLOCK_STYLE:
                mClockStyle = TunerService.parseInteger(newValue, 0);
                updateClockView();

                View sliceView = findViewById(R.id.keyguard_slice_view);
                if (sliceView != null) {
                    sliceView.setVisibility(mClockStyle != 0 ? View.GONE : View.VISIBLE);
                }
                break;
            case TOGGLE_LAYOUT:
                isToggleableLayoutVisible = TunerService.parseIntegerSwitch(newValue, false);
                if (toggleableLayout != null) {
                    toggleableLayout.setVisibility(isToggleableLayoutVisible ? View.VISIBLE : View.GONE);
                }
                if (sharedPreferences != null) {
                    sharedPreferences.edit().putBoolean(TOGGLE_LAYOUT_KEY, isToggleableLayoutVisible).apply();
                }
                break;
            case LOCKSCREEN_CLOCK_COLORED:
                isLockscreenClockColored = TunerService.parseIntegerSwitch(newValue, false);
                updateClockView();
                break;
            case CLOCK_MARGIN_TOP:
                mClockMarginTop = TunerService.parseInteger(newValue, 0);
                if (sharedPreferences != null) {
                    sharedPreferences.edit().putInt(CLOCK_MARGIN_TOP_KEY, mClockMarginTop).apply();
                }
                updateClockView();
                break;
            default:
                break;
        }
    }
}
