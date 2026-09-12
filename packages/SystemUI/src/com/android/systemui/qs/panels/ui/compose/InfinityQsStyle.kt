/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: LicenseRef-InfinityX-Proprietary
 */

package com.android.systemui.qs.panels.ui.compose

import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

const val INFINITY_QS_STYLE_SETTING = "infinity_revamped_qs"
const val INFINITY_QS_STYLE_STOCK = 0
const val INFINITY_QS_STYLE_INFINITY_X = 1
const val INFINITY_QS_STYLE_HYPEROS = 2
const val INFINITY_QS_STYLE_OXYGENOS = 3

fun sanitizeInfinityQsStyle(value: Int): Int {
    return when (value) {
        INFINITY_QS_STYLE_STOCK,
        INFINITY_QS_STYLE_INFINITY_X,
        INFINITY_QS_STYLE_HYPEROS,
        INFINITY_QS_STYLE_OXYGENOS -> value
        else -> INFINITY_QS_STYLE_STOCK
    }
}

private object InfinityQsStyleStore {
    var style by mutableIntStateOf(INFINITY_QS_STYLE_STOCK)
        private set
    private var initialized = false

    fun ensureInitialized(context: android.content.Context) {
        if (initialized) return
        initialized = true
        val contentResolver = context.applicationContext.contentResolver

        fun readCurrent(): Int {
            return runCatching {
                Settings.System.getIntForUser(
                    contentResolver,
                    INFINITY_QS_STYLE_SETTING,
                    INFINITY_QS_STYLE_STOCK,
                    UserHandle.USER_CURRENT,
                )
            }.getOrElse { INFINITY_QS_STYLE_STOCK }
                .let(::sanitizeInfinityQsStyle)
        }

        style = readCurrent()

        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    style = readCurrent()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(INFINITY_QS_STYLE_SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
    }
}

@Composable
fun rememberInfinityQsStyle(): Int {
    val context = LocalContext.current
    DisposableEffect(context) {
        InfinityQsStyleStore.ensureInitialized(context)
        onDispose {}
    }
    return InfinityQsStyleStore.style
}
