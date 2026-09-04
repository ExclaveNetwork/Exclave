/******************************************************************************
 * Copyright (C) 2021 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.ColorUtils
import io.nekohasekai.sagernet.utils.CrashHandler
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import kotlin.time.Duration.Companion.milliseconds

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    lateinit var binding: LayoutLogcatBinding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutLogcatBinding.bind(view)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.logsScrollView)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                top = dp2px(8),
                left = bars.left + dp2px(8),
                right = bars.right + dp2px(8),
                bottom = bars.bottom + dp2px(64),
            )
            insets
        }

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = true

        runOnIoDispatcher {
            streamingLog()
        }
    }

    private suspend fun streamingLog() = onIoDispatcher {
        var process: Process? = null
        var stdout: BufferedReader? = null
        val bufferedLogLines = arrayListOf<String>()
        var timeLastNotify = System.nanoTime()
        // The timeout is initially small so that the view gets populated immediately.
        var timeout = 1000000000L / 2
        try {
            while (true) {
                if (process == null) {
                    try {
                        process = ProcessBuilder(
                            listOf("logcat",
                                "-T", "2048",
                                "-v", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "tag,color" else "tag",
                                "-s", arrayOf(
                                    "AndroidRuntime:D",
                                    "ProxyInstance:D",
                                    "GuardedProcessPool:D",
                                    "VpnService:D",
                                    "Go:D",
                                    "exclave-core:D",
                                    "libexclavecore:D",
                                    "libnaive:D",
                                    "libshadowquic:D",
                                    "Exclave:D",
                                    "*:S",
                                ).joinToString(",")
                            )
                        ).start()
                        stdout = BufferedReader(InputStreamReader(process.inputStream))
                    } catch (_: Exception) {
                        return@onIoDispatcher
                    }
                }
                val line = stdout!!.readLine()
                if (line == null) {
                    process.destroy()
                    stdout.close()
                    process = null
                    delay(5000.milliseconds)
                    continue
                }
                bufferedLogLines.add(line)
                val timeNow = System.nanoTime()
                if (
                    bufferedLogLines.size < (1 shl 14) - 1 &&
                    (timeNow - timeLastNotify) < timeout && stdout.ready()
                ) continue

                // Increase the timeout after the initial view has something in it.
                timeout = 1000000000L * 5 / 2
                timeLastNotify = timeNow

                onMainDispatcher {
                    if (binding.logsTextView.lineCount + bufferedLogLines.size > 2048) {
                        binding.logsTextView.text = ""
                        if (bufferedLogLines.size > 2048) {
                            bufferedLogLines.drop(bufferedLogLines.size - 2048)
                        }
                    }
                    binding.logsTextView.append(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ColorUtils.ansiEscapeToSpannable(binding.root.context,
                                bufferedLogLines.joinToString(separator = "\n", postfix = "\n")
                            )
                        } else {
                            bufferedLogLines.joinToString(separator = "\n", postfix = "\n")
                        }
                    )
                    bufferedLogLines.clear()
                    binding.logsScrollView.post {
                        binding.logsScrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        } finally {
            process?.destroy()
            stdout?.close()
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                runOnIoDispatcher {
                    val command = listOf("logcat", "-c")
                    val process = ProcessBuilder(command).start()
                    process.waitFor()
                    onMainDispatcher {
                        binding.logsTextView.text = ""
                    }
                }
            }
            R.id.action_send_logcat -> {
                val context = requireContext()
                runOnDefaultDispatcher {
                    val logFile = File.createTempFile("Exclave ",
                        ".log",
                        File(app.externalCacheDir, "log").also { it.mkdirs() })

                    var report = CrashHandler.buildReportHeader()

                    report += "Logcat: \n\n"

                    logFile.writeText(report)

                    try {
                        Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.use(
                            FileOutputStream(
                                logFile, true
                            )
                        )
                    } catch (e: IOException) {
                        Logs.w(e)
                        logFile.appendText("Export logcat error: " + CrashHandler.formatThrowable(e))
                    }

                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        context, BuildConfig.APPLICATION_ID + ".cache", logFile
                                    )
                                ), context.getString(androidx.appcompat.R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }
            }
        }
        return true
    }
}