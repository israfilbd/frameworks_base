/*
 * Copyright (C) 2019 The PixelExperience Project
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

package com.android.server.policy;

import android.app.ActivityManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

public class SwipeToScreenshotListener implements PointerEventListener {
    private static final String TAG = "SwipeToScreenshotListener";
    private static final int THREE_GESTURE_STATE_NONE = 0;
    private static final int THREE_GESTURE_STATE_DETECTING = 1;
    private static final int THREE_GESTURE_STATE_DETECTED_FALSE = 2;
    private static final int THREE_GESTURE_STATE_DETECTED_TRUE = 3;
    private static final int THREE_GESTURE_STATE_NO_DETECT = 4;

    private boolean mBootCompleted;
    private final Context mContext;
    private boolean mDeviceProvisioned;
    private final float[] mInitMotionY = new float[3];
    private final int[] mPointerIds = new int[3];
    private int mThreeGestureState = THREE_GESTURE_STATE_NONE;
    private final int mThreeGestureThreshold;
    private final int mThreshold;
    private final Callbacks mCallbacks;
    private final DisplayMetrics mDisplayMetrics;
    private float[] motionDistances = new float[3];

    public SwipeToScreenshotListener(Context context, Callbacks callbacks) {
        mContext = context;
        mCallbacks = callbacks;
        mDisplayMetrics = mContext.getResources().getDisplayMetrics();
        mThreshold = (int) (50.0f * mDisplayMetrics.density);
        mThreeGestureThreshold = mThreshold * 3;
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (!mBootCompleted) {
            mBootCompleted = SystemProperties.getBoolean("sys.boot_completed", false);
            return;
        }
        if (!mDeviceProvisioned) {
            mDeviceProvisioned = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                changeThreeGestureState(THREE_GESTURE_STATE_NONE);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mThreeGestureState == THREE_GESTURE_STATE_NONE && event.getPointerCount() == 3) {
                    if (checkIsStartThreeGesture(event)) {
                        changeThreeGestureState(THREE_GESTURE_STATE_DETECTING);
                        for (int i = 0; i < 3; i++) {
                            mPointerIds[i] = event.getPointerId(i);
                            mInitMotionY[i] = event.getY(i);
                        }
                    } else {
                        changeThreeGestureState(THREE_GESTURE_STATE_NO_DETECT);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mThreeGestureState == THREE_GESTURE_STATE_DETECTING) {
                    if (event.getPointerCount() != 3) {
                        changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_FALSE);
                        return;
                    }
                    float totalDistance = 0.0f;
                    for (int i = 0; i < 3; i++) {
                        int index = event.findPointerIndex(mPointerIds[i]);
                        if (index < 0 || index >= 3) {
                            changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_FALSE);
                            return;
                        }
                        motionDistances[i] = event.getY(index) - mInitMotionY[i];
                        totalDistance += motionDistances[i];
                    }
                    if (totalDistance >= mThreeGestureThreshold) {
                        changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_TRUE);
                        mCallbacks.onSwipeThreeFinger();
                    }
                }
                break;
            default:
                break;
        }
    }

    private void changeThreeGestureState(int state) {
        if (mThreeGestureState != state) {
            mThreeGestureState = state;
            boolean shouldEnable = mThreeGestureState == THREE_GESTURE_STATE_DETECTED_TRUE ||
                                   mThreeGestureState == THREE_GESTURE_STATE_DETECTING;
            try {
                ActivityManager.getService().setSwipeToScreenshotGestureActive(shouldEnable);
            } catch (RemoteException e) {
                Log.e(TAG, "setSwipeToScreenshotGestureActive exception", e);
            }
        }
    }

    private boolean checkIsStartThreeGesture(MotionEvent event) {
        if (event.getEventTime() - event.getDownTime() > 500) {
            return false;
        }
        int height = mDisplayMetrics.heightPixels;
        int width = mDisplayMetrics.widthPixels;
        float minX = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;

        for (int i = 0; i < event.getPointerCount(); i++) {
            float x = event.getX(i);
            float y = event.getY(i);
            if (y > height - mThreshold) {
                return false;
            }
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }

        return maxY - minY <= mDisplayMetrics.density * 150.0f && maxX - minX <= Math.min(width, height);
    }

    interface Callbacks {
        void onSwipeThreeFinger();
    }
}
