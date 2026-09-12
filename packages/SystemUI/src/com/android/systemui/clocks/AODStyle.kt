/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 * Copyright (C) 2024-2026 Lunaris AOSP 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.clocks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import com.android.settingslib.drawable.CircleFramedDrawable
import com.android.systemui.Dependency
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.tuner.TunerService
import java.io.ByteArrayOutputStream

class AODStyle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs), TunerService.Tunable {

    private val tunerService: TunerService = Dependency.get(TunerService::class.java)
    private val statusBarStateController: StatusBarStateController =
        Dependency.get(StatusBarStateController::class.java)

    private var aodImageView: ImageView? = null
    private var imagePath: String? = null
    private var currImagePath: String? = null
    private var aodImageEnabled = false
    private var imageLoaded = false
    private var customClockEnabled = false
    private var clockFrameMarginTop = DEFAULT_MARGIN_TOP
    private var isDozing = false

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {}

        override fun onDozingChanged(dozing: Boolean) {
            if (isDozing == dozing) return
            isDozing = dozing
            updateAodImageView()
        }
    }

    init {
        tunerService.addTunable(
            this,
            ClockStyle.CLOCK_STYLE_KEY,
            ClockStyle.CLOCK_FRAME_MARGIN_TOP_KEY,
            CUSTOM_AOD_IMAGE_URI_KEY,
            CUSTOM_AOD_IMAGE_ENABLED_KEY,
        )
        statusBarStateController.addCallback(statusBarStateListener)
        statusBarStateListener.onDozingChanged(statusBarStateController.isDozing)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        aodImageView = findViewById(R.id.custom_aod_image_view)
        updateAodFrameMargin()
        loadAodImage()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        statusBarStateController.removeCallback(statusBarStateListener)
        tunerService.removeTunable(this)
        aodImageView?.apply {
            animate().cancel()
            setImageDrawable(null)
        }
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        when (key) {
            ClockStyle.CLOCK_STYLE_KEY -> {
                val clockStyle = TunerService.parseInteger(newValue, 0)
                customClockEnabled = clockStyle != 0
            }
            CUSTOM_AOD_IMAGE_URI_KEY -> {
                imagePath = newValue
                val path = imagePath
                if (!path.isNullOrEmpty() && path != currImagePath) {
                    currImagePath = path
                    imageLoaded = false
                    loadAodImage()
                }
            }
            CUSTOM_AOD_IMAGE_ENABLED_KEY -> {
                aodImageEnabled = TunerService.parseIntegerSwitch(newValue, false)
                        && customClockEnabled
            }
            ClockStyle.CLOCK_FRAME_MARGIN_TOP_KEY -> {
                clockFrameMarginTop = TunerService.parseInteger(newValue, DEFAULT_MARGIN_TOP)
                    .coerceIn(0, 200)
                updateAodFrameMargin()
            }
        }
    }

    private fun updateAodFrameMargin() {
        val aodFrame = findViewById<View?>(R.id.aod_style_frame) ?: return
        val params = aodFrame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.topMargin = (clockFrameMarginTop * resources.displayMetrics.density).toInt()
        aodFrame.layoutParams = params
    }

    private fun updateAodImageView() {
        val imageView = aodImageView
        if (imageView == null || !aodImageEnabled) {
            imageView?.visibility = View.GONE
            return
        }
        loadAodImage()
        if (isDozing) {
            imageView.visibility = View.VISIBLE
            imageView.scaleX = 0f
            imageView.scaleY = 0f
            imageView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .start()
        } else {
            imageView.animate()
                .scaleX(0f)
                .scaleY(0f)
                .setDuration(250)
                .withEndAction {
                    imageView.visibility = View.GONE
                }
                .start()
        }
    }

    private fun loadAodImage() {
        val imageView = aodImageView ?: return
        val path = currImagePath
        if (path.isNullOrEmpty() || imageLoaded) return

        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                val targetSize = context.resources.getDimension(R.dimen.custom_aod_image_size).toInt()
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
                ByteArrayOutputStream().use { stream ->
                    scaledBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 90, stream)
                    val byteArray = stream.toByteArray()
                    val compressedBitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    val roundedImg: Drawable = CircleFramedDrawable(compressedBitmap, targetSize)
                    imageView.setImageDrawable(roundedImg)
                    scaledBitmap.recycle()
                    compressedBitmap.recycle()
                    imageLoaded = true
                }
            } else {
                imageLoaded = false
                imageView.visibility = View.GONE
            }
        } catch (e: Exception) {
            imageLoaded = false
            imageView.visibility = View.GONE
        } finally {
            bitmap?.recycle()
        }
    }

    companion object {
        private const val CUSTOM_AOD_IMAGE_URI_KEY = "system:custom_aod_image_uri"
        private const val CUSTOM_AOD_IMAGE_ENABLED_KEY = "system:custom_aod_image_enabled"
        private const val DEFAULT_MARGIN_TOP = 15
    }
}
