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

package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.StatsEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getBooleanProperty
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.Subnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import libsagernetcore.*
import android.net.VpnService as BaseVpnService

@SuppressLint("VpnServicePolicy")
class VpnService : BaseVpnService(),
    BaseService.Interface,
    TrafficListener,
    Protector,
    LocalResolver {

    companion object {
        var instance: VpnService? = null

        const val DEFAULT_MTU = 1500
        val PRIVATE_VLAN4_CLIENT =
            DataStore.experimentalFlagsProperties.getProperty("tunIPv4Address")?.substringBefore("/") ?: "172.19.0.1"
        val PRIVATE_VLAN4_CLIENT_PREFIX =
            DataStore.experimentalFlagsProperties.getProperty("tunIPv4Address")?.substringAfter("/")?.toInt() ?: 30

        val PRIVATE_VLAN4_DNS =
            DataStore.experimentalFlagsProperties.getProperty("tunIPv4DNSAddress") ?: "172.19.0.2"
        val PRIVATE_VLAN6_CLIENT =
            DataStore.experimentalFlagsProperties.getProperty("tunIPv6Address")?.substringBefore("/") ?: "fdfe:dcba:9876::1"
        val PRIVATE_VLAN6_CLIENT_PREFIX =
            DataStore.experimentalFlagsProperties.getProperty("tunIPv6Address")?.substringAfter("/")?.toInt() ?: 126

        val PRIVATE_VLAN6_DNS =
            DataStore.experimentalFlagsProperties.getProperty("tunIPv6DNSAddress")
        val FAKEDNS_VLAN4_CLIENT =
            DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv4Pool")?.substringBefore("/") ?: "198.18.0.0"
        val FAKEDNS_VLAN4_CLIENT_PREFIX =
            DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv4Pool")?.substringAfter("/")?.toInt() ?: 15
        val FAKEDNS_VLAN4_CLIENT_POOL_SIZE =
            DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv4PoolSize")?.toInt() ?: 65535
        val FAKEDNS_VLAN6_CLIENT =
            DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv6Pool")?.substringBefore("/") ?: "fc00::"
        val FAKEDNS_VLAN6_CLIENT_PREFIX =
            DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv6Pool")?.substringAfter("/")?.toInt() ?: 18
        val FAKEDNS_VLAN6_CLIENT_POOL_SIZE =
            DataStore.experimentalFlagsProperties.getProperty("fakeDNSIPv6PoolSize")?.toInt() ?: 65535
    }

    lateinit var conn: ParcelFileDescriptor
    var tun: Tun2ray? = null

    private var active = false
    private var metered = false

    @Volatile
    override var underlyingNetwork: Network? = null
        set(value) {
            field = value
            if (active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(underlyingNetworks)
            }
        }
    private val underlyingNetworks
        get() = // clearing underlyingNetworks makes Android 9 consider the network to be metered
            if (Build.VERSION.SDK_INT == 28 && metered) null else underlyingNetwork?.let {
                arrayOf(it)
            }
    private var networkListenerIsRunning = false

    override suspend fun startProcesses() {
        startVpn()
        super.startProcesses()
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun killProcesses() {
        data.proxy?.v2rayPoint?.withLocalResolver(null)
        tun?.apply {
            close()
        }
        if (::conn.isInitialized) conn.close()
        super.killProcesses()
        persistAppStats()
        active = false
        tun?.apply {
            tun = null
        }
        networkListenerIsRunning = false
        GlobalScope.launch(Dispatchers.Default) { DefaultNetworkListener.stop(this) }
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn", true)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this, VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    override suspend fun preInit() {
        networkListenerIsRunning = true
        DefaultNetworkListener.start(this) {
            if (networkListenerIsRunning) {
                underlyingNetwork = it
                SagerNet.reloadNetwork(it)
            }
        }
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    private fun startVpn() {
        instance = this

        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)

        builder.addAddress(PRIVATE_VLAN4_CLIENT, PRIVATE_VLAN4_CLIENT_PREFIX)
        if (DataStore.enableVPNInterfaceIPv6Address) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, PRIVATE_VLAN6_CLIENT_PREFIX)
        }

        if (DataStore.bypassLan) {
            val customIPv4Route = DataStore.experimentalFlagsProperties.getProperty( "tunIPv4RouteAddress")
            if (customIPv4Route != null) {
                customIPv4Route.split(",").forEach {
                    val subnet = Subnet.fromString(it)!!
                    builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
                }
            } else {
                resources.getStringArray(R.array.bypass_private_route).forEach {
                    val subnet = Subnet.fromString(it)!!
                    builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
                }
                if (PRIVATE_VLAN4_DNS.isNotEmpty()) {
                    builder.addRoute(PRIVATE_VLAN4_DNS, 32)
                }
                if (DataStore.enableFakeDns) {
                    builder.addRoute(FAKEDNS_VLAN4_CLIENT, FAKEDNS_VLAN4_CLIENT_PREFIX)
                }
            }
            if (DataStore.enableVPNInterfaceIPv6Address) {
                val customIPv6Route = DataStore.experimentalFlagsProperties.getProperty("tunIPv6RouteAddress")
                if (customIPv6Route != null) {
                    customIPv6Route.split(",").forEach {
                        val subnet = Subnet.fromString(it)!!
                        builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
                    }
                } else {
                    // https://issuetracker.google.com/issues/149636790
                    builder.addRoute("2000::", 3)
                    if (DataStore.enableFakeDns) {
                        builder.addRoute(FAKEDNS_VLAN6_CLIENT, FAKEDNS_VLAN6_CLIENT_PREFIX)
                    }
                }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (DataStore.enableVPNInterfaceIPv6Address) {
                builder.addRoute("::", 0)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            builder.setUnderlyingNetworks(underlyingNetworks)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        val tunImplementation = DataStore.tunImplementation
        val needIncludeSelf = tunImplementation == TunImplementation.SYSTEM
        if (proxyApps) {
            val bypass = DataStore.bypass
            val individual = mutableSetOf<String>()
            individual.addAll(DataStore.individual.split('\n').filter { it.isNotEmpty() })
            individual.apply {
                if (bypass xor needIncludeSelf) add(packageName) else remove(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }
        } else if (!needIncludeSelf) {
            builder.addDisallowedApplication(packageName)
        }

        if (PRIVATE_VLAN4_DNS.isNotEmpty()) {
            builder.addDnsServer(PRIVATE_VLAN4_DNS)
        }
        if (!PRIVATE_VLAN6_DNS.isNullOrEmpty()) {
            builder.addDnsServer(PRIVATE_VLAN6_DNS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy && DataStore.requireHttp
            && DataStore.httpUsername.isEmpty() && DataStore.httpPassword.isEmpty()) {
            if (DataStore.httpProxyException.isNotEmpty()) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.httpPort,
                    DataStore.httpProxyException.listByLineOrComma()))
            } else {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.httpPort))
            }
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)

        if (DataStore.allowAppsBypassVpn) {
            builder.allowBypass()
        }

        conn = builder.establish() ?: throw NullConnectionException()
        active = true   // possible race condition here?

        data.proxy!!.v2rayPoint.withLocalResolver(this)

        val config = TunConfig().apply {
            fileDescriptor = conn.fd
            protect = needIncludeSelf
            mtu = DataStore.mtu
            discardICMP = DataStore.discardICMP
            v2Ray = data.proxy!!.v2rayPoint
            addr4 = PRIVATE_VLAN4_CLIENT
            addr6 = PRIVATE_VLAN6_CLIENT
            dns4 = PRIVATE_VLAN4_DNS
            dns6 = PRIVATE_VLAN6_DNS ?: ""
            enableIPv6 = DataStore.enableVPNInterfaceIPv6Address
            implementation = tunImplementation
            sniffing = DataStore.trafficSniffing
            overrideDestination = DataStore.destinationOverride
            // fakeDNS = DataStore.enableFakeDns
            fakeDNS = data.proxy!!.config.useFakeDNS
            dumpUID = data.proxy!!.config.dumpUID
            trafficStats = DataStore.appTrafficStatistics
            pCap = DataStore.enablePcap
            protector = this@VpnService
        }

        if (DataStore.experimentalFlagsProperties.getBooleanProperty("discardIPv6BasedOnNetwork")) {
            config.discardIPv6BasedOnNetwork = true
        }

        if (tunImplementation == TunImplementation.SYSTEM) {
            config.protectPath = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
        }

        tun = Libsagernetcore.newTun2ray(config)
    }

    val appStats = mutableListOf<AppStats>()

    override fun updateStats(stats: AppStats) {
        appStats.add(stats)
    }

    fun persistAppStats() {
        if (!DataStore.appTrafficStatistics) return
        val tun = tun ?: return
        appStats.clear()
        tun.readAppTraffics(this)
        val toUpdate = mutableListOf<StatsEntity>()
        val all = SagerDatabase.statsDao.all().associateBy { it.uid }
        for (stats in appStats) {
            val uid = stats.uid
            if (!all.containsKey(uid)) {
                SagerDatabase.statsDao.create(
                    StatsEntity(
                        uid = uid,
                        tcpConnections = stats.tcpConnTotal,
                        udpConnections = stats.udpConnTotal,
                        uplink = stats.uplinkTotal,
                        downlink = stats.downlinkTotal
                    )
                )
            } else {
                val entity = all[uid]!!
                entity.tcpConnections += stats.tcpConnTotal
                entity.udpConnections += stats.udpConnTotal
                entity.uplink += stats.uplinkTotal
                entity.downlink += stats.downlinkTotal
                toUpdate.add(entity)
            }
        }
        if (toUpdate.isNotEmpty()) {
            SagerDatabase.statsDao.update(toUpdate)
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
    }

}