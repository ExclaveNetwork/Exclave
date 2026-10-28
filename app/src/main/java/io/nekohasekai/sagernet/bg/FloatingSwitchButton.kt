/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026                                                         *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify        *
 * it under the terms of the GNU General Public License as published by        *
 * the Free Software Foundation, either version 3 of the License, or           *
 *  (at your option) any later version.                                        *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.DiagnosticLog
import kotlin.math.abs

object FloatingSwitchButton {

    const val ACTION_SYNC = "io.nekohasekai.sagernet.action.FLOATING_SWITCH_SYNC"
    const val ACTION_HIDE = "io.nekohasekai.sagernet.action.FLOATING_SWITCH_HIDE"

    private var windowManager: WindowManager? = null
    private var button: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var callbacksRegistered = false

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handleConfigurationChanged()
        }

        override fun onLowMemory() = Unit
    }

    fun canDrawOverlays(context: Context = app): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun sync(context: Context = app) {
        if (!isOverlayOwner(context)) {
            dispatchCommand(context, ACTION_SYNC)
            return
        }
        runOnMainDispatcher {
            val appContext = context.applicationContext
            if (!DataStore.autoVpnFloatingSwitchEnabled ||
                !SagerNet.started ||
                DataStore.startedProfile <= 0L
            ) {
                hideOnMain()
                return@runOnMainDispatcher
            }
            if (!canDrawOverlays(appContext)) {
                DiagnosticLog.warn("floating-switch", "overlay permission is missing")
                hideOnMain()
                return@runOnMainDispatcher
            }
            showOnMain(appContext)
        }
    }

    fun hide() {
        if (!isOverlayOwner(app)) {
            dispatchCommand(app, ACTION_HIDE)
            return
        }
        runOnMainDispatcher {
            hideOnMain()
        }
    }

    fun handleCommand(context: Context, action: String?) {
        when (action) {
            ACTION_SYNC -> sync(context)
            ACTION_HIDE -> hide()
        }
    }

    private fun dispatchCommand(context: Context, action: String) {
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(context, FloatingSwitchCommandReceiver::class.java).setAction(action),
            )
        }.onFailure {
            DiagnosticLog.warn("floating-switch", "command dispatch failed: ${it.message.orEmpty()}")
        }
    }

    private fun isOverlayOwner(context: Context): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                @Suppress("PrivateApi")
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentProcessName")
                    .invoke(null) as? String
            }.getOrNull()
        }
        return processName == "${context.packageName}:bg"
    }

    private fun showOnMain(context: Context) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val existingView = button
        val existingParams = layoutParams
        if (existingView != null && existingParams != null) {
            snapToNearestEdge(context, existingParams)
            updateLayout(manager, existingView, existingParams)
            persistPosition(existingParams)
            return
        }

        val size = dp2px(48)
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = DataStore.autoVpnFloatingSwitchX.takeIf { it >= 0 }
                ?: (context.resources.displayMetrics.widthPixels - size - dp2px(16)).coerceAtLeast(0)
            y = DataStore.autoVpnFloatingSwitchY.takeIf { it >= 0 }
                ?: (context.resources.displayMetrics.heightPixels / 2)
            snapToNearestEdge(context, this)
        }

        val view = TextView(context).apply {
            text = "VPN"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.82f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAA1976D2.toInt())
                setStroke(dp2px(1), 0xD9FFFFFF.toInt())
            }
            elevation = dp2px(8).toFloat()
            setOnClickListener {
                AutoVpnManager.switchFromFloatingButton()
            }
        }

        attachDragHandler(context, view, manager, params)

        try {
            manager.addView(view, params)
            windowManager = manager
            button = view
            layoutParams = params
            registerCallbacks(context)
            DiagnosticLog.event("floating-switch", "shown x=${params.x} y=${params.y}")
        } catch (e: Exception) {
            DiagnosticLog.error("floating-switch", "show failed", e)
            button = null
            layoutParams = null
            windowManager = null
        }
    }

    private fun attachDragHandler(
        context: Context,
        view: TextView,
        manager: WindowManager,
        params: WindowManager.LayoutParams,
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampToScreen(context, params)
                        updateLayout(manager, target, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToNearestEdge(context, params)
                        updateLayout(manager, target, params)
                        persistPosition(params)
                        DiagnosticLog.event("floating-switch", "moved x=${params.x} y=${params.y}")
                    } else {
                        target.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun handleConfigurationChanged() {
        runOnMainDispatcher {
            val wasVisible = button != null
            if (wasVisible) {
                hideOnMain(immediate = true)
                sync(app)
            }
        }
    }

    private fun clampToScreen(context: Context, params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - params.height).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun snapToNearestEdge(context: Context, params: WindowManager.LayoutParams) {
        clampToScreen(context, params)
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
        params.x = if (params.x + params.width / 2 < metrics.widthPixels / 2) 0 else maxX
        clampToScreen(context, params)
    }

    private fun updateLayout(manager: WindowManager, target: View, params: WindowManager.LayoutParams) {
        runCatching {
            manager.updateViewLayout(target, params)
        }.onFailure {
            DiagnosticLog.warn("floating-switch", "layout update failed: ${it.message.orEmpty()}")
        }
    }

    private fun persistPosition(params: WindowManager.LayoutParams) {
        DataStore.autoVpnFloatingSwitchX = params.x
        DataStore.autoVpnFloatingSwitchY = params.y
    }

    private fun registerCallbacks(context: Context) {
        if (callbacksRegistered) return
        context.applicationContext.registerComponentCallbacks(componentCallbacks)
        callbacksRegistered = true
    }

    private fun unregisterCallbacks() {
        if (!callbacksRegistered) return
        runCatching {
            app.unregisterComponentCallbacks(componentCallbacks)
        }.onFailure {
            DiagnosticLog.warn("floating-switch", "unregister callbacks failed: ${it.message.orEmpty()}")
        }
        callbacksRegistered = false
    }

    private fun hideOnMain(immediate: Boolean = false) {
        val manager = windowManager
        val view = button
        if (manager != null && view != null) {
            runCatching {
                if (immediate) {
                    manager.removeViewImmediate(view)
                } else {
                    manager.removeView(view)
                }
            }.onFailure {
                DiagnosticLog.warn("floating-switch", "hide failed: ${it.message.orEmpty()}")
            }
            DiagnosticLog.event("floating-switch", "hidden")
        }
        button = null
        layoutParams = null
        windowManager = null
        unregisterCallbacks()
    }
}
