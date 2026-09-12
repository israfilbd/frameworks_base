/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.IActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.telecom.TelecomManager
import android.util.Log
import android.view.WindowManagerGlobal
import android.widget.Button
import android.widget.Toast
import com.android.internal.logging.MetricsLogger
import com.android.internal.util.GcUtils
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.Icon
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import java.util.concurrent.Executor
import javax.inject.Inject

class InfinityMemoryBoostTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val panelInteractor: PanelInteractor,
    @Background private val bgExecutor: Executor,
) : QSTileImpl<QSTile.State>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {

    companion object {
        const val TILE_SPEC = "infinity_memoryboost"
        private const val MEM_TAG = "InfinityMemoryBoostTile"

        private val systemCriticalPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.telephony",
            "com.android.server.telecom",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.bluetooth",
            "com.android.nfc",
        )
    }

    private var tileIcon: Icon? = null

    override fun newTileState(): QSTile.State = QSTile.State().apply {
        handlesLongClick = false
        expandedAccessibilityClassName = Button::class.java.name
    }

    override fun handleDestroy() {
        super.handleDestroy()
    }

    override fun isAvailable(): Boolean = true

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
    }

    override fun handleClick(expandable: Expandable?) {
        // Close the Quick Settings panel immediately
        panelInteractor.collapsePanels()

        // Perform memory boost in background
        bgExecutor.execute {
            performInfinityMemoryBoost()
        }
    }

    private fun performInfinityMemoryBoost() {
        val am = mContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        
        // Measure initial available RAM
        am.getMemoryInfo(memInfo)
        val ramBefore = memInfo.availMem

        val currentUserId = UserHandle.myUserId()

        // Resolve active launcher
        val launcherPackage = getInfinityLauncherPackageName()

        // Resolve active IME
        val activeImePackage = getInfinityActiveImePackageName()

        // Resolve active dialer
        val telecomManager = mContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val defaultDialer = telecomManager?.defaultDialerPackage

        // Combine all packages that must not be killed
        val excludePackages = mutableSetOf<String>().apply {
            addAll(systemCriticalPackages)
            launcherPackage?.let { add(it) }
            activeImePackage?.let { add(it) }
            defaultDialer?.let { add(it) }
        }

        var killedCount = 0

        try {
            val atmService = ActivityTaskManager.getService()
            val amService = ActivityManager.getService()

            // 1. Kill the foreground app
            val runningTasks = am.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                val topTask = runningTasks[0]
                val topPkg = topTask.topActivity?.packageName ?: topTask.baseActivity?.packageName
                if (topPkg != null && topPkg !in excludePackages) {
                    try {
                        amService.forceStopPackage(topPkg, currentUserId)
                        atmService.removeTask(topTask.taskId)
                        killedCount++
                    } catch (e: Exception) {
                        Log.w(MEM_TAG, "Failed to force stop foreground app: $topPkg", e)
                    }
                }
            }

            // 2. Clear all recent apps / tasks
            val recentTasks = ActivityTaskManager.getInstance().getRecentTasks(100, ActivityManager.RECENT_IGNORE_UNAVAILABLE, currentUserId)
            if (recentTasks != null) {
                for (task in recentTasks) {
                    val pkg = task.baseActivity?.packageName
                        ?: task.topActivity?.packageName
                        ?: task.baseIntent?.component?.packageName
                        ?: task.origActivity?.packageName
                    if (pkg != null && pkg !in excludePackages) {
                        try {
                            amService.forceStopPackage(pkg, currentUserId)
                            atmService.removeTask(task.taskId)
                            killedCount++
                        } catch (e: Exception) {
                            Log.w(MEM_TAG, "Failed to clear task for package: $pkg", e)
                        }
                    }
                }
            }

            // 3. Force stop all running user/third-party app packages
            val runningProcesses = am.runningAppProcesses
            if (runningProcesses != null) {
                for (proc in runningProcesses) {
                    // Force stop any non-critical package regardless of its background/cached state
                    for (pkg in proc.pkgList) {
                        if (pkg !in excludePackages) {
                            try {
                                amService.forceStopPackage(pkg, currentUserId)
                                killedCount++
                            } catch (e: Exception) {
                                try {
                                    // Fallback to standard background kill if forceStop fails
                                    am.killBackgroundProcesses(pkg)
                                    killedCount++
                                } catch (innerEx: Exception) {
                                    Log.w(MEM_TAG, "Failed to stop package: $pkg", innerEx)
                                }
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(MEM_TAG, "Error performing memory boost", e)
        }

        // 4. Force SystemUI to trim its own graphics and view memory caches
        try {
            WindowManagerGlobal.getInstance().trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        } catch (e: Exception) {
            Log.w(MEM_TAG, "Failed to trim SystemUI window memory", e)
        }

        // 5. Run standard garbage collection and synchronously finalize references
        try {
            GcUtils.runGcAndFinalizersSync()
        } catch (e: Exception) {
            System.gc()
        }

        try {
            Thread.sleep(400)
        } catch (e: InterruptedException) {
        }

        // Measure final available RAM
        am.getMemoryInfo(memInfo)
        val ramAfter = memInfo.availMem
        val ramFreedBytes = ramAfter - ramBefore
        val ramFreedMb = ramFreedBytes / (1024 * 1024)

        // Display toast
        mainHandler.post {
            val message = if (ramFreedMb >= 20) {
                mContext.getString(R.string.quick_settings_infinity_memoryboost_toast, ramFreedMb)
            } else {
                mContext.getString(R.string.quick_settings_infinity_memoryboost_no_ram)
            }
            Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getInfinityLauncherPackageName(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = mContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    private fun getInfinityActiveImePackageName(): String? {
        val activeIme = Settings.Secure.getString(mContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return activeIme?.split("/")?.firstOrNull()
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_infinity_memoryboost_label)

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        if (tileIcon == null) {
            tileIcon = maybeLoadResourceIcon(R.drawable.ic_qs_infinity_memoryboost)
        }
        state.icon = tileIcon
        state.label = mContext.getString(R.string.quick_settings_infinity_memoryboost_label)
        state.state = Tile.STATE_INACTIVE
        state.contentDescription = mContext.getString(R.string.quick_settings_infinity_memoryboost_label)
    }

    override fun getMetricsCategory(): Int = MetricsLogger.VIEW_UNKNOWN
}
