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

package com.android.systemui.statusbar.featurepods.promotedongoing.ui.viewmodel

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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class PromotedOngoingPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val axDynamicBarInteractor: AxDynamicBarInteractor,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("PromotedOngoingPopupChipViewModel.hydrator")

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.PromotedOngoing),
            source =
                axDynamicBarInteractor.settings.isDynamicIslandOngoingActive
                    .flatMapLatest { enabled ->
                        if (!enabled) {
                            flowOf(PopupChipModel.Hidden(PopupChipId.PromotedOngoing))
                        } else {
                            axDynamicBarInteractor.uiState.map { uiState ->
                                val event = uiState.events.firstOrNull { it is IslandEvent.PromotedOngoing } as? IslandEvent.PromotedOngoing
                                if (event == null) {
                                    PopupChipModel.Hidden(PopupChipId.PromotedOngoing)
                                } else {
                                    toPopupChipModel(event)
                                }
                            }
                        }
                    },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(event: IslandEvent.PromotedOngoing): PopupChipModel {
        val iconModel = event.appIcon?.let { drawable ->
            Icon.Loaded(drawable, ContentDescription.Loaded(event.appName))
        } ?: Icon.Resource(
            resId = com.android.systemui.res.R.drawable.ic_info,
            contentDescription = ContentDescription.Loaded(event.appName)
        )

        val chipText = if (event.progress >= 0f) {
            "${(event.progress * 100).toInt()}%"
        } else if (event.shortText.isNotEmpty()) {
            event.shortText
        } else {
            ""
        }

        return PopupChipModel.Shown(
            chipId = PopupChipId.PromotedOngoing,
            icons = listOf(ChipIcon(icon = iconModel)),
            chipText = chipText,
            colors = ColorsModel.DynamicIsland,
            contentDescription = event.appName,
            popupContent = PopupContentModel.PromotedOngoing(event),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): PromotedOngoingPopupChipViewModel
    }
}
