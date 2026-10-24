/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Job

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private var testJob: Job? = null
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior
    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    override fun performShow() {
        super.performShow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isNavigationBarAtBottom(this)) {
            val activity = context.findActivity<MainActivity>()!!
            val insetController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            insetController.isAppearanceLightNavigationBars =
                if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
        }
    }

    override fun performHide() {
        super.performHide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isNavigationBarAtBottom(this)) {
            val activity = context.findActivity<MainActivity>()!!
            val insetController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            insetController.isAppearanceLightNavigationBars = !Theme.usingNightMode()
        }
    }

    private fun isNavigationBarAtBottom(view: View): Boolean {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return false
        val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        return navigationBar.bottom > 0 && navigationBar.left == 0 && navigationBar.right == 0
    }

    class YourBehavior : Behavior() {
        private fun isNavigationBarAtBottom(child: View): Boolean {
            val insets = ViewCompat.getRootWindowInsets(child) ?: return false
            val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            return navigationBar.bottom > 0 && navigationBar.left == 0 && navigationBar.right == 0
        }

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isNavigationBarAtBottom(child)) {
                val activity = child.context.findActivity<MainActivity>()!!
                val insetController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                when {
                    dyConsumed + dyUnconsumed > 0 -> {
                        insetController.isAppearanceLightNavigationBars = !Theme.usingNightMode()
                    }
                    dyConsumed + dyUnconsumed < 0 -> {
                        insetController.isAppearanceLightNavigationBars =
                            if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
                    }
                }
            }
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            doOnPreDraw {
                performShow()
                setStatus(context.getText(R.string.vpn_connected))
            }
            isEnabled = true
        } else {
            doOnPreDraw {
                performHide()
            }
            testJob?.cancel()
            updateTraffic(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateTraffic(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, FormatFileSizeCompat.formatFileSize(context, txRate, DataStore.useIECUnit)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, FormatFileSizeCompat.formatFileSize(context, rxRate, DataStore.useIECUnit)
            )
        }"
    }

    fun testConnection() {
        testJob?.cancel()
        val activity = context.findActivity<MainActivity>()!!
        isEnabled = false
        setStatus(context.getText(R.string.connection_test_testing))
        testJob = runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        context.getString(
                            if (DataStore.connectionTestURL.startsWith("https://", ignoreCase = true)) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    isEnabled = true
                    var msg = e.localizedMessage ?: e.readableMessage
                    when {
                        msg.contains("timeout") || msg.contains("deadline") -> {
                            msg = context.getString(R.string.connection_test_timeout)
                        }
                        msg.contains("refused") || msg.contains("closed pipe") -> {
                            msg = context.getString(R.string.connection_test_refused)
                        }
                    }
                    setStatus(context.getString(R.string.connection_test_error, msg))
                }
            }
        }
    }

}
