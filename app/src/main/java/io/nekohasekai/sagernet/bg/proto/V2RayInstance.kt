/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
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

package io.nekohasekai.sagernet.bg.proto

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.nekohasekai.sagernet.RootCAProvider
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.V2rayBuildResult
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libexclavecore.V2RayInstance
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

abstract class V2RayInstance(
    val profile: ProxyEntity,
) : AbstractInstance {

    lateinit var config: V2rayBuildResult
    lateinit var v2rayPoint: V2RayInstance
    private lateinit var wsForwarder: WebView
    private lateinit var shForwarder: WebView

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    fun isInitialized(): Boolean {
        return ::config.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildV2RayConfig(profile)
    }

    protected open fun loadConfig() {
        v2rayPoint.loadConfig(config.config)
    }

    open suspend fun init() {
        v2rayPoint = V2RayInstance()
        buildConfig()
        for ((_, chain) in config.index) {
            chain.entries.forEachIndexed { _, (triple, profile) ->
                val port = triple.first
                val username = triple.second
                val password = triple.third
                when (val bean = profile.requireBean()) {
                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port, username, password)
                    }
                }
            }
        }
        loadConfig()
    }

    @OptIn(ExperimentalUuidApi::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun launch() {
        val context = SagerNet.application
        var pemFile: File? = null
        val providerRootCA = DataStore.providerRootCA
        for ((_, chain) in config.index) {
            chain.entries.forEachIndexed { _, (triple, profile) ->
                val port = triple.first
                val bean = profile.requireBean()
                val (_, config) = pluginConfigs[port] ?: (0 to "")
                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }
                    bean is NaiveBean -> {
                        val configFile = File(
                            app.cacheDir,
                            "naiveproxy_" + Uuid.generateV4().toHexString() + ".json"
                        ).apply {
                            parentFile?.mkdirs()
                            writeText(config)
                            cacheFiles.add(this)
                        }
                        val env = mutableMapOf<String, String>()
                        if (bean.certificate.isNotEmpty()) {
                            File(app.cacheDir, "naiveproxy_" + Uuid.generateV4().toHexString() + ".pem").apply {
                                parentFile?.mkdirs()
                                writeText(bean.certificate)
                                cacheFiles.add(this)
                                env["SSL_CERT_FILE"] = canonicalPath
                            }
                        } else if (providerRootCA == RootCAProvider.CUSTOM) {
                            File(app.externalAssets, "root_store.certs").apply {
                                env["SSL_CERT_FILE"] = canonicalPath
                            }
                        } else if (providerRootCA == RootCAProvider.MOZILLA || providerRootCA == RootCAProvider.ANDROID) {
                            if (pemFile == null) {
                                pemFile = File(app.cacheDir, "naiveproxy_" + Uuid.generateV4().toHexString() + ".pem").apply {
                                    parentFile?.mkdirs()
                                    writeBytes(if (providerRootCA == RootCAProvider.MOZILLA) {
                                        app.assets.open("mozilla_included.pem").use { it.readBytes() }
                                    } else {
                                        app.certificates()
                                    })
                                    cacheFiles.add(this)
                                }
                            }
                            env["SSL_CERT_FILE"] = pemFile.canonicalPath
                        }
                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.canonicalPath
                        )
                        processes.start(commands, env)
                    }
                }
            }
        }
        v2rayPoint.start()
        if (config.requireWs) {
            val url = "http://" + joinHostPort(LOCALHOST, config.wsPort) + "/"
            runOnMainDispatcher {
                wsForwarder = WebView(context)
                wsForwarder.settings.javaScriptEnabled = true
                wsForwarder.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Logs.d("WebView load r: $error")
                        runOnMainDispatcher {
                            wsForwarder.loadUrl("about:blank")
                            delay(1000L)
                            wsForwarder.loadUrl(url)
                        }
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        Logs.d("WebView loaded: ${view.title}")
                    }
                }
                wsForwarder.loadUrl(url)
            }
        }
        if (config.requireSh) {
            val url = "http://" + joinHostPort(LOCALHOST, config.shPort) + "/"
            runOnMainDispatcher {
                shForwarder = WebView(context)
                shForwarder.settings.javaScriptEnabled = true
                shForwarder.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Logs.d("WebView load r: $error")
                        runOnMainDispatcher {
                            shForwarder.loadUrl("about:blank")
                            delay(1000L)
                            shForwarder.loadUrl(url)
                        }
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        Logs.d("WebView loaded: ${view.title}")
                    }
                }
                shForwarder.loadUrl(url)
            }
        }
    }

    private var isClosed = false

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        if (isClosed) return

        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::wsForwarder.isInitialized) {
            runBlocking {
                onMainDispatcher {
                    wsForwarder.loadUrl("about:blank")
                    wsForwarder.destroy()
                }
            }
        }

        if (::shForwarder.isInitialized) {
            runBlocking {
                onMainDispatcher {
                    shForwarder.loadUrl("about:blank")
                    shForwarder.destroy()
                }
            }
        }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::v2rayPoint.isInitialized) {
            v2rayPoint.close()
        }

        isClosed = true
    }

}
