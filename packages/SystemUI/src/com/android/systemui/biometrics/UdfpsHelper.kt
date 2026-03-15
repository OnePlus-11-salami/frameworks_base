/*
 * Copyright (C) 2024-2025 The LineageOS Project
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

package com.android.systemui.biometrics

import android.animation.ValueAnimator
import android.annotation.UiThread
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import java.io.File
import java.io.IOException
import com.android.internal.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.settings.brightness.domain.interactor.BrightnessMirrorShowingInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "UdfpsHelper"

/**
 * Facilitates implementations that use GHBM where dim layer
 * and pressed icon aren't controlled by kernel
 */
@UiThread
class UdfpsHelper(
    private val context: Context,
    private val windowManager: WindowManager,
    private val shadeInteractor: ShadeInteractor,
    @RequestReason val requestReason: Int,
    private val brightnessMirrorShowingInteractor: BrightnessMirrorShowingInteractor,
    private var view: View = View(context).apply {
        background = ColorDrawable(Color.BLACK)
        visibility = View.GONE
    }
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)!!
    private val isKeyguard = requestReason == REASON_AUTH_KEYGUARD

    private val brightnessAlphaMap: Map<Int, Int> = context.resources
        .getStringArray(com.android.systemui.res.R.array.config_udfpsDimmingBrightnessAlphaArray)
        .associate {
            val (brightness, alpha) = it.split(",").map { value -> value.trim().toInt() }
            brightness to alpha
        }
    val maxPanelBrightness: Int = brightnessAlphaMap.keys.maxOrNull() ?: 0

    private val backlightSysNode: String = context.resources.getString(
        com.android.systemui.res.R.string.config_udfpsBacklightSysNode
    )

    private var lastBacklightReadErrorUptime: Long = 0L

    private val dimLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
        0 /* flags are set in computeLayoutParams() */,
        PixelFormat.TRANSLUCENT
    ).apply {
        title = "Dim Layer for UDFPS"
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        flags = Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        // Avoid announcing window title
        accessibilityTitle = " "
        inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
    }

    private val alphaAnimator = ValueAnimator().apply {
        duration = 0L
        addUpdateListener { animator ->
            applyAlphaToBackground(animator.animatedValue as Float)
            try {
                windowManager.updateViewLayout(view, dimLayoutParams)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "View not attached to WindowManager", e)
            }
        }
    }

    private fun applyAlphaToBackground(alpha: Float) {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        val color = ColorUtils.setAlphaComponent(Color.BLACK, a)
        when (val bg = view.background) {
            is ColorDrawable -> bg.color = color
            else -> view.setBackgroundColor(color)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && view.isAttachedToWindow) {
                brightnessToAlpha()
                windowManager.updateViewLayout(view, dimLayoutParams)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {}
    }

    private fun interpolate(
        value: Float,
        fromMin: Int,
        fromMax: Int,
        toMin: Int,
        toMax: Int
    ): Float {
        return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }

    private fun lookupOrInterpolateAlpha(brightness: Int): Float {
        // Exact match
        brightnessAlphaMap[brightness]?.let { return it / 255.0f }

        // Find surrounding entries for interpolation
        val lowerEntry = brightnessAlphaMap.entries.lastOrNull { it.key <= brightness }
        val upperEntry = brightnessAlphaMap.entries.firstOrNull { it.key >= brightness }

        return when {
            lowerEntry == null && upperEntry == null -> 0f
            lowerEntry == null -> upperEntry!!.value / 255.0f
            upperEntry == null -> lowerEntry.value / 255.0f
            lowerEntry.key == upperEntry.key -> lowerEntry.value / 255.0f
            else -> {
                // Linear interpolation
                val b1 = lowerEntry.key
                val a1 = lowerEntry.value
                val b2 = upperEntry.key
                val a2 = upperEntry.value
                interpolate(brightness.toFloat(), b1, b2, a1, a2) / 255.0f
            }
        }
    }

    private fun readBacklightFromSysNodeOrNull(): Int? {
        if (backlightSysNode.isBlank()) return null
        return try {
            val text = File(backlightSysNode).readText().trim()
            text.toIntOrNull()
        } catch (_: SecurityException) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastBacklightReadErrorUptime > 5_000) {
                lastBacklightReadErrorUptime = now
                Log.w(TAG, "Failed to read UDFPS backlight sysfs node (permission denied): $backlightSysNode")
            }
            null
        } catch (_: IOException) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastBacklightReadErrorUptime > 5_000) {
                lastBacklightReadErrorUptime = now
                Log.w(TAG, "Failed to read UDFPS backlight sysfs node (I/O error): $backlightSysNode")
            }
            null
        }
    }

    private fun readBacklightOrNull(): Int? {
        return readBacklightFromSysNodeOrNull()
    }

    // The current function does not account for Doze state where the brightness can go lower
    // than what is set on config_screenBrightnessSettingMinimumFloat.
    // While it's possible to operate with floats, the dimming array was made by referencing
    // brightness_alpha_lut array from the kernel. This provides a comparable array.
    private fun brightnessToAlpha() {
        if (brightnessAlphaMap.isEmpty() || maxPanelBrightness <= 0) {
            applyAlphaToBackground(0f)
            return
        }

        val rawBrightness = readBacklightOrNull() ?: run {
            applyAlphaToBackground(0f)
            return
        }
        val adjustedBrightness = rawBrightness.coerceIn(0, maxPanelBrightness)

        val targetAlpha = lookupOrInterpolateAlpha(adjustedBrightness)

        Log.i(TAG, "Adjusted Brightness: $adjustedBrightness, Alpha: $targetAlpha")

        val currentAlpha = ((view.background as? ColorDrawable)?.alpha ?: 0) / 255.0f
        alphaAnimator.setFloatValues(currentAlpha, targetAlpha)
        applyAlphaToBackground(targetAlpha)
    }

    fun addDimLayer() {
        if (view.isAttachedToWindow) {
            brightnessToAlpha()
            try {
                windowManager.updateViewLayout(view, dimLayoutParams)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "View not attached to WindowManager", e)
            }
            return
        }
        brightnessToAlpha()
        try {
            windowManager.addView(view, dimLayoutParams)
            displayManager.registerDisplayListener(
                displayListener,
                null,
                DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS,
            )
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to add dim layer", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to add dim layer", e)
        }
    }

    fun removeDimLayer() {
        if (!view.isAttachedToWindow) {
            return
        }
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to remove dim layer", e)
        }
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to unregister display listener", e)
        }
    }

    init {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenForBrightnessMirror(this)

                if (isKeyguard) {
                    listenForShadeTouchability(this)
                }
            }
        }

        if (!isKeyguard) {
            view.isVisible = true
        }
    }

    private suspend fun listenForBrightnessMirror(scope: CoroutineScope): Job {
        return scope.launch {
            brightnessMirrorShowingInteractor.isShowing.collect {
                view.isVisible = !it
            }
        }
    }

    private suspend fun listenForShadeTouchability(scope: CoroutineScope): Job {
        return scope.launch {
            shadeInteractor.isShadeTouchable.collect {
                view.isVisible = it
                if (view.isVisible) {
                    brightnessToAlpha()
                    alphaAnimator.cancel()
                    alphaAnimator.start()
                }
            }
        }
    }
}
