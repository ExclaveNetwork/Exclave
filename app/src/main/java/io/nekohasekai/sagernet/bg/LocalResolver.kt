/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.bg

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import libsagernetcore.Libsagernetcore
import libsagernetcore.LocalResolver
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.suspendCoroutine

interface LocalResolver : LocalResolver {

    var underlyingNetwork: Network?

    override fun supportExchange(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun lookupIP(network: String, domain: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runBlocking {
                suspendCoroutine { continuation ->
                    val signal = CancellationSignal()
                    val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                        override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                            when {
                                answer.isNotEmpty() -> {
                                    continuation.tryResume((answer as Collection<InetAddress?>).mapNotNull { it?.hostAddress }
                                        .joinToString(","))
                                }
                                rcode == 0 -> {
                                    continuation.tryResume("")
                                }
                                else -> {
                                    continuation.tryResumeWithException(Exception("rcode $rcode"))
                                }
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            continuation.tryResumeWithException(error)
                        }
                    }
                    val type = when {
                        network.endsWith("4") -> DnsResolver.TYPE_A
                        network.endsWith("6") -> DnsResolver.TYPE_AAAA
                        else -> null
                    }
                    if (type != null) {
                        DnsResolver.getInstance().query(
                            underlyingNetwork,
                            domain,
                            type,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback
                        )
                    } else {
                        DnsResolver.getInstance().query(
                            underlyingNetwork,
                            domain,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback
                        )
                    }
                }
            }
        } else {
            val underlyingNetwork = underlyingNetwork ?: error("upstream network not found")
            val answer = try {
                underlyingNetwork.getAllByName(domain)
            } catch (_: UnknownHostException) {
                return ""
            }
            val filtered = mutableListOf<String>()
            when {
                network.endsWith("4") -> for (address in answer) {
                    address.hostAddress?.takeIf { Libsagernetcore.isIPv4(it) }?.also { filtered.add(it) }
                }
                network.endsWith("6") -> for (address in answer) {
                    address.hostAddress?.takeIf { Libsagernetcore.isIPv6(it) }?.also { filtered.add(it) }
                }
                else -> filtered.addAll(answer.mapNotNull { it.hostAddress })
            }
            if (filtered.isEmpty()) {
                return ""
            }
            return filtered.joinToString(",")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(message: ByteArray): ByteArray {
        return runBlocking {
            suspendCoroutine { continuation ->
                val signal = CancellationSignal()
                val callback = object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        when {
                            answer.isNotEmpty() -> {
                                continuation.tryResume(answer)
                            }
                            else -> {
                                continuation.tryResumeWithException(Exception("rcode $rcode"))
                            }
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        continuation.tryResumeWithException(error)
                    }
                }
                DnsResolver.getInstance().rawQuery(
                    underlyingNetwork,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            }
        }
    }
}