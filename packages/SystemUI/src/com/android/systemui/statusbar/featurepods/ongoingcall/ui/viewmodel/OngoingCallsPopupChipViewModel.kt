/*
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

package com.android.systemui.statusbar.featurepods.ongoingcall.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.model.IslandEvent
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class OngoingCallsPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val axDynamicBarInteractor: AxDynamicBarInteractor,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("OngoingCallsPopupChipViewModel.hydrator")

    private val timerFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.OngoingCall),
            source =
                axDynamicBarInteractor.settings.isDynamicIslandCallsActive
                    .flatMapLatest { enabled ->
                        if (!enabled) {
                            flowOf(PopupChipModel.Hidden(PopupChipId.OngoingCall))
                        } else {
                            axDynamicBarInteractor.uiState
                                .map { uiState ->
                                    uiState.events.firstOrNull { it is IslandEvent.Call } as? IslandEvent.Call
                                }
                                .distinctUntilChanged()
                                .flatMapLatest { event ->
                                    if (event == null) {
                                        flowOf(PopupChipModel.Hidden(PopupChipId.OngoingCall))
                                    } else {
                                        timerFlow.map { currentTime ->
                                            toPopupChipModel(event, currentTime)
                                        }
                                    }
                                }
                        }
                    },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(event: IslandEvent.Call, currentTime: Long): PopupChipModel {
        val iconModel = Icon.Resource(
            resId = com.android.systemui.res.R.drawable.ic_call,
            contentDescription = ContentDescription.Loaded(event.callerName ?: "Call")
        )

        val elapsedMs = currentTime - event.callStartTimeMs
        val elapsedSecs = (elapsedMs / 1000).coerceAtLeast(0)
        val mins = elapsedSecs / 60
        val secs = elapsedSecs % 60
        val durationText = String.format("%d:%02d", mins, secs)

        return PopupChipModel.Shown(
            chipId = PopupChipId.OngoingCall,
            icons = listOf(ChipIcon(icon = iconModel)),
            chipText = durationText,
            colors = ColorsModel.DynamicIsland,
            contentDescription = event.callerName,
            popupContent = PopupContentModel.OngoingCall(event),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): OngoingCallsPopupChipViewModel
    }
}
