/*
 * Copyright (C) 2024 The risingOS Android Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.tuner.TunerService;
import com.android.internal.util.infinity.ThemeUtils;

public class VolumeUtils implements TunerService.Tunable {
    private static final String TAG = "VolumeUtils";

    public static final String CUSTOM_VOLUME_STYLES =
            "system:" + "custom_volume_styles";

    private AudioManager mAudioManager;
    private Context mContext;
    private Handler mHandler;
    
    private int customVolumeStyles = 0;
    private ThemeUtils mThemeUtils;

    private static final int[] seekbarDrawables = {
            R.drawable.volume_row_seekbar_aosp,
            R.drawable.volume_row_seekbar_rui,
            R.drawable.volume_row_seekbar,
            R.drawable.volume_row_seekbar_double_layer,
            R.drawable.volume_row_seekbar_gradient,
            R.drawable.volume_row_seekbar_neumorph,
            R.drawable.volume_row_seekbar_neumorph_outline,
            R.drawable.volume_row_seekbar_outline,
            R.drawable.volume_row_seekbar_shaded_layer
        };

    public VolumeUtils(Context context, AudioManager audioManager) {
        mAudioManager = audioManager;
        mContext = context;
        mHandler = new Handler();
        mThemeUtils = new ThemeUtils(mContext);
        Dependency.get(TunerService.class).addTunable(this,
                CUSTOM_VOLUME_STYLES);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case CUSTOM_VOLUME_STYLES:
                final int selectedVolStyle = TunerService.parseInteger(newValue, 2);
                if (selectedVolStyle != customVolumeStyles) {
                    customVolumeStyles = selectedVolStyle;
                    mHandler.post(() -> {
                        String volumeStyleKey = customVolumeStyles > 2 || customVolumeStyles == 0 ?
                                "com.android.system.volume.style" + customVolumeStyles : "com.android.systemui";
                        setVolumeStyle(volumeStyleKey, "android.theme.customization.volume_panel");
                    });
                }
                break;
            default:
                break;
        }
    }

    private void setVolumeStyle(String pkgName, String category) {
        mThemeUtils.setOverlayEnabled(category, pkgName, "com.android.systemui");
    }
    
    public View getRowView(LayoutInflater inflater) {
        int layoutResId;
        switch (customVolumeStyles) {
            case 1:
                layoutResId = R.layout.volume_dialog_row_rui;
                break;
            case 2:
                layoutResId = R.layout.volume_dialog_row;
                break;
            default:
                layoutResId = R.layout.volume_dialog_row_aosp;
                break;
        }
        return inflater.inflate(layoutResId, null);
    }
    
    public int getRowDrawable() {
        return seekbarDrawables[customVolumeStyles];
    }
}
