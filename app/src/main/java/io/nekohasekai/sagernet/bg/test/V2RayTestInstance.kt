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

package io.nekohasekai.sagernet.bg.test

import android.net.Network
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.LocalResolver
import io.nekohasekai.sagernet.bg.proto.V2RayInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import libsagernetcore.Libsagernetcore
import java.util.concurrent.atomic.AtomicBoolean

class V2RayTestInstance(profile: ProxyEntity, val link: String, val timeout: Int, val protectPath: String = "") : V2RayInstance(
    profile,
), LocalResolver {
    private val closeStarted = AtomicBoolean(false)

    suspend fun doTest(): Int = withContext(Dispatchers.IO) {
        processes = GuardedProcessPool {
            Logs.w(it)
        }
        init()
        launch()
        if (pluginConfigs.isNotEmpty()) {
            delay(500L)
        }
        Libsagernetcore.urlTest(v2rayPoint, "", link, timeout)
    }

    @Volatile
    override var underlyingNetwork: Network? = null

    override suspend fun init() {
        super.init()
        v2rayPoint.withProtect(protectPath)
        v2rayPoint.withLocalResolver(this)
        DefaultNetworkListener.start(this) {
            underlyingNetwork = it
        }
    }

    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        runCatching {
            v2rayPoint.withLocalResolver(null)
        }.onFailure {
            if (it !is UninitializedPropertyAccessException) {
                Logs.w(it)
            }
        }
        runCatching {
            runBlocking {
                processes.closeAndJoin()
            }
        }.onFailure {
            if (it !is UninitializedPropertyAccessException) {
                Logs.w(it)
            }
        }
        runCatching {
            runBlocking {
                DefaultNetworkListener.stop(this@V2RayTestInstance)
            }
        }.onFailure {
            Logs.w(it)
        }
        super.close()
    }

    override fun buildConfig() {
        config = buildV2RayConfig(profile, forTest = true)
    }
}
