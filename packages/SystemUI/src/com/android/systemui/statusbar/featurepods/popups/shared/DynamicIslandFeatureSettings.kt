/*
 * Copyright (C) 2026 The Android Open Source Project
 * Copyright (C) 2026 Project Infinity X
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

package com.android.systemui.statusbar.featurepods.popups.shared

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object DynamicIslandFeatureSettings {
    const val MEDIA_CONTROLS = "status_bar_dynamic_island_media_controls"
    const val SCREEN_RECORDING = "status_bar_dynamic_island_screen_recording"
    const val FLASHLIGHT = "status_bar_dynamic_island_flashlight"
    const val LIVE_SCORES = "status_bar_dynamic_island_live_scores"
    const val SHOW_LYRICS = "status_bar_dynamic_island_lyrics"
    const val ONGOING_ACTIVITIES = "status_bar_dynamic_island_ongoing_activities"
    const val ONGOING_CALLS = "status_bar_dynamic_island_calls"

    fun ContentResolver.readDynamicIslandFeatureEnabled(
        key: String,
        defaultValue: Boolean = true,
    ): Boolean {
        return Settings.System.getIntForUser(
            this,
            key,
            if (defaultValue) 1 else 0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    const val COMPACT_WIDTH = "status_bar_dynamic_island_width"

    fun ContentResolver.readDynamicIslandWidth(
        defaultValue: Int = 110,
    ): Int {
        return Settings.System.getIntForUser(
            this,
            COMPACT_WIDTH,
            defaultValue,
            UserHandle.USER_CURRENT,
        )
    }

    fun observeDynamicIslandWidth(
        context: Context,
        defaultValue: Int = 110,
    ): Flow<Int> {
        return callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(context.contentResolver.readDynamicIslandWidth(defaultValue))
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(COMPACT_WIDTH),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            trySend(context.contentResolver.readDynamicIslandWidth(defaultValue))
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }.distinctUntilChanged()
    }

    fun observeDynamicIslandFeatureEnabled(
        context: Context,
        key: String,
        defaultValue: Boolean = true,
    ): Flow<Boolean> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(
                            context.contentResolver.readDynamicIslandFeatureEnabled(
                                key,
                                defaultValue,
                            )
                        )
                    }
                }

            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            trySend(context.contentResolver.readDynamicIslandFeatureEnabled(key, defaultValue))
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }.distinctUntilChanged()
}