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

package io.nekohasekai.sagernet.ui.profile

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.github.shadowsocks.plugin.*
import com.github.shadowsocks.preference.PluginConfigurationDialogFragment
import com.github.shadowsocks.preference.PluginPreference
import com.github.shadowsocks.preference.PluginPreferenceDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getBooleanProperty
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.unwrapIDN
import io.nekohasekai.sagernet.widget.NonBlackEditTextPreference
import kotlinx.coroutines.launch

abstract class StandardV2RaySettingsActivity : ProfileSettingsActivity<StandardV2RayBean>(),
    Preference.OnPreferenceChangeListener {
    override fun StandardV2RayBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        when (this) {
            is TrojanBean -> DataStore.serverUserId = password
            is ShadowsocksBean -> {
                DataStore.serverEncryption = method
                DataStore.serverUserId = password
            }
            is VMessBean -> {
                DataStore.serverUserId = uuid
                DataStore.serverEncryption = encryption
            }
            is VLESSBean -> {
                DataStore.serverUserId = uuid
                DataStore.serverEncryptionForVless = encryption
            }
            is HttpBean -> {
                DataStore.serverUserId = password
                DataStore.serverUsername = username
            }
            is SOCKSBean -> {
                DataStore.serverUserId = password
                DataStore.serverUsername = username
            }
        }
        DataStore.serverNetwork = type
        DataStore.serverHeader = headerType
        DataStore.serverHost = host

        when (type) {
            "kcp" -> DataStore.serverPath = mKcpSeed
            "quic" -> DataStore.serverPath = quicKey
            "grpc" -> DataStore.serverPath = grpcServiceName
            "meek" -> DataStore.serverPath = meekUrl
            else -> DataStore.serverPath = path
        }

        DataStore.serverSecurity = security
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = certificates
        when (this) {
            is VLESSBean -> DataStore.serverFlow = flow
            is VMessBean -> {
                DataStore.serverAlterId = alterId
                DataStore.serverVMessExperimentalAuthenticatedLength = experimentalAuthenticatedLength
                DataStore.serverVMessExperimentalNoTerminationSignal = experimentalNoTerminationSignal
            }
            is ShadowsocksBean -> {
                DataStore.serverPlugin = plugin
                DataStore.serverReducedIvHeadEntropy = experimentReducedIvHeadEntropy
            }
            is SOCKSBean -> {
                DataStore.serverProtocolVersion  = protocol

            }
        }
        DataStore.serverPinnedCertificateChain = pinnedPeerCertificateChainSha256
        DataStore.serverPinnedCertificatePublicKey = pinnedPeerCertificatePublicKeySha256
        DataStore.serverPinnedCertificate = pinnedPeerCertificateSha256
        DataStore.serverQuicSecurity = quicSecurity
        DataStore.serverWsMaxEarlyData = maxEarlyData
        DataStore.serverEarlyDataHeaderName = earlyDataHeaderName
        DataStore.serverSplithttpMode = splithttpMode
        DataStore.serverSplithttpExtra = splithttpExtra
        DataStore.serverUTLSFingerprint = utlsFingerprint
        DataStore.serverEchEnabled = echEnabled
        DataStore.serverEchConfig = echConfig
        DataStore.serverMtlsCertificate = mtlsCertificate
        DataStore.serverMtlsCertificatePrivateKey = mtlsCertificatePrivateKey

        DataStore.serverRealityPublicKey = realityPublicKey
        DataStore.serverRealityShortId = realityShortId
        DataStore.serverRealityMldsa65Verify = realityMldsa65Verify
        DataStore.serverRealityFingerprint = realityFingerprint
        DataStore.serverRealityDisableX25519Mlkem768 = realityDisableX25519Mlkem768

        DataStore.serverGrpcServiceNameCompat = grpcServiceNameCompat
        DataStore.serverGrpcMultiMode = grpcMultiMode

        DataStore.serverUploadSpeed = hy2UpMbps
        DataStore.serverDownloadSpeed = hy2DownMbps
        DataStore.serverPassword = hy2Password

        DataStore.serverMekyaKcpSeed = mekyaKcpSeed
        DataStore.serverMekyaKcpHeaderType = mekyaKcpHeaderType
        DataStore.serverMekyaUrl = mekyaUrl

        DataStore.serverWsBrowserForwarding = wsUseBrowserForwarder
        DataStore.serverShBrowserForwarding = shUseBrowserForwarder
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverPacketEncoding = packetEncoding

        DataStore.serverMux = mux
        DataStore.serverMuxConcurrency = muxConcurrency
        DataStore.serverMuxPacketEncoding = muxPacketEncoding
        when (this) {
            is ShadowsocksBean -> DataStore.serverSingUot = singUoT
            is SOCKSBean -> DataStore.serverSingUot = singUoT
        }
        DataStore.serverSingMux = singMux
        DataStore.serverSingMuxProtocol = singMuxProtocol
        DataStore.serverSingMuxMaxConnections = singMuxMaxConnections
        DataStore.serverSingMuxMinStreams = singMuxMinStreams
        DataStore.serverSingMuxMaxStreams = singMuxMaxStreams
        DataStore.serverSingMuxPadding = singMuxPadding
    }

    override fun StandardV2RayBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        when (this) {
            is TrojanBean -> {
                password = DataStore.serverUserId
            }
            is ShadowsocksBean -> {
                method = DataStore.serverEncryption
                password = DataStore.serverUserId
            }
            is VMessBean -> {
                uuid = DataStore.serverUserId
                encryption = DataStore.serverEncryption
            }
            is VLESSBean -> {
                uuid = DataStore.serverUserId
                encryption = DataStore.serverEncryptionForVless
            }
            is HttpBean -> {
                password = DataStore.serverUserId
                username = DataStore.serverUsername
            }
            is SOCKSBean -> {
                password = DataStore.serverUserId
                username = DataStore.serverUsername
            }
        }
        type = DataStore.serverNetwork
        headerType = DataStore.serverHeader
        host = DataStore.serverHost
        when (type) {
            "kcp" -> mKcpSeed = DataStore.serverPath
            "quic" -> quicKey = DataStore.serverPath
            "grpc" -> grpcServiceName = DataStore.serverPath
            "meek" -> meekUrl = DataStore.serverPath
            else -> path = DataStore.serverPath
        }
        security = DataStore.serverSecurity
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        certificates = DataStore.serverCertificates
        pinnedPeerCertificateChainSha256 = DataStore.serverPinnedCertificateChain
        pinnedPeerCertificatePublicKeySha256 = DataStore.serverPinnedCertificatePublicKey
        pinnedPeerCertificateSha256 = DataStore.serverPinnedCertificate
        when (this) {
            is VLESSBean -> flow = DataStore.serverFlow
            is VMessBean -> {
                alterId = DataStore.serverAlterId
                experimentalAuthenticatedLength = DataStore.serverVMessExperimentalAuthenticatedLength
                experimentalNoTerminationSignal = DataStore.serverVMessExperimentalNoTerminationSignal
            }
            is ShadowsocksBean -> {
                plugin = DataStore.serverPlugin
                experimentReducedIvHeadEntropy = DataStore.serverReducedIvHeadEntropy
            }
            is SOCKSBean -> protocol = DataStore.serverProtocolVersion
        }
        quicSecurity = DataStore.serverQuicSecurity
        maxEarlyData = DataStore.serverWsMaxEarlyData
        earlyDataHeaderName = DataStore.serverEarlyDataHeaderName
        splithttpMode = DataStore.serverSplithttpMode
        splithttpExtra = DataStore.serverSplithttpExtra
        utlsFingerprint = DataStore.serverUTLSFingerprint
        mtlsCertificate = DataStore.serverMtlsCertificate
        mtlsCertificatePrivateKey = DataStore.serverMtlsCertificatePrivateKey
        echEnabled = DataStore.serverEchEnabled
        echConfig = DataStore.serverEchConfig

        realityPublicKey = DataStore.serverRealityPublicKey
        realityShortId = DataStore.serverRealityShortId
        realityMldsa65Verify = DataStore.serverRealityMldsa65Verify
        realityFingerprint = DataStore.serverRealityFingerprint
        realityDisableX25519Mlkem768 = DataStore.serverRealityDisableX25519Mlkem768

        grpcServiceNameCompat = DataStore.serverGrpcServiceNameCompat
        grpcMultiMode = DataStore.serverGrpcMultiMode

        hy2UpMbps = DataStore.serverUploadSpeed
        hy2DownMbps = DataStore.serverDownloadSpeed
        hy2Password = DataStore.serverPassword

        mekyaKcpSeed = DataStore.serverMekyaKcpSeed
        mekyaKcpHeaderType = DataStore.serverMekyaKcpHeaderType
        mekyaUrl = DataStore.serverMekyaUrl

        wsUseBrowserForwarder = DataStore.serverWsBrowserForwarding
        shUseBrowserForwarder = DataStore.serverShBrowserForwarding
        allowInsecure = DataStore.serverAllowInsecure
        packetEncoding = DataStore.serverPacketEncoding

        mux = DataStore.serverMux
        muxConcurrency = DataStore.serverMuxConcurrency
        muxPacketEncoding = DataStore.serverMuxPacketEncoding

        when (this) {
            is ShadowsocksBean -> singUoT = DataStore.serverSingUot
            is SOCKSBean -> singUoT = DataStore.serverSingUot
        }
        singMux = DataStore.serverSingMux
        singMuxProtocol = DataStore.serverSingMuxProtocol
        singMuxMaxConnections = DataStore.serverSingMuxMaxConnections
        singMuxMinStreams = DataStore.serverSingMuxMinStreams
        singMuxMaxStreams = DataStore.serverSingMuxMaxStreams
        singMuxPadding = DataStore.serverSingMuxPadding
    }

    lateinit var encryption: ListPreference
    lateinit var vlessEncryption: NonBlackEditTextPreference
    lateinit var network: ListPreference
    lateinit var header: ListPreference
    lateinit var requestHost: EditTextPreference
    lateinit var path: EditTextPreference
    lateinit var quicSecurity: ListPreference
    lateinit var security: ListPreference
    lateinit var xtlsFlow: ListPreference
    lateinit var alterId: EditTextPreference

    lateinit var sni: EditTextPreference
    lateinit var alpn: EditTextPreference
    lateinit var securityCategory: PreferenceCategory
    lateinit var certificates: EditTextPreference
    lateinit var pinnedCertificateChain: EditTextPreference
    lateinit var pinnedCertificatePublickey: EditTextPreference
    lateinit var pinnedCertificate: EditTextPreference
    lateinit var allowInsecure: SwitchPreference
    lateinit var utlsFingerprint: ListPreference
    lateinit var mtlsCertificate: EditTextPreference
    lateinit var mtlsCertificatePrivateKey: EditTextPreference
    lateinit var echEnabled: SwitchPreference
    lateinit var echConfig: EditTextPreference

    lateinit var realityPublicKey: EditTextPreference
    lateinit var realityShortId: EditTextPreference
    lateinit var realityMldsa65Verify: EditTextPreference
    lateinit var realityFingerprint: ListPreference
    lateinit var realityDisableX25519Mlkem768: SwitchPreference

    lateinit var packetEncoding: ListPreference

    lateinit var hy2UpMbps: EditTextPreference
    lateinit var hy2DownMbps: EditTextPreference
    lateinit var hy2Password: EditTextPreference

    lateinit var mekyaKcpSeed: EditTextPreference
    lateinit var mekyaKcpHeaderType: ListPreference
    lateinit var mekyaUrl: EditTextPreference

    lateinit var socksProtocol: ListPreference
    lateinit var passwordUUID: EditTextPreference

    lateinit var wsCategory: PreferenceCategory
    lateinit var wsUseBrowserForwarder: SwitchPreference
    lateinit var splithttpCategory: PreferenceCategory
    lateinit var splithttpMode: ListPreference
    lateinit var splithttpExtra: EditTextPreference
    lateinit var grpcCategory: PreferenceCategory
    lateinit var ssExperimentsCategory: PreferenceCategory

    lateinit var plugin: PluginPreference
    lateinit var pluginConfigure: EditTextPreference
    lateinit var pluginConfiguration: PluginConfiguration
    lateinit var receiver: BroadcastReceiver

    lateinit var mux: SwitchPreference
    lateinit var muxConcurrency: EditTextPreference
    lateinit var muxPacketEncoding: ListPreference
    lateinit var singMux: SwitchPreference
    lateinit var singMuxProtocol: ListPreference
    lateinit var singMuxMaxConnections: EditTextPreference
    lateinit var singMuxMinStreams: EditTextPreference
    lateinit var singMuxMaxStreams: EditTextPreference
    lateinit var singMuxPadding: SwitchPreference

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.standard_v2ray_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        encryption = findPreference(Key.SERVER_ENCRYPTION)!!
        vlessEncryption = findPreference(Key.SERVER_ENCRYPTION_FOR_VLESS)!!
        network = findPreference(Key.SERVER_NETWORK)!!
        header = findPreference(Key.SERVER_HEADER)!!
        requestHost = findPreference(Key.SERVER_HOST)!!
        path = findPreference(Key.SERVER_PATH)!!
        quicSecurity = findPreference(Key.SERVER_QUIC_SECURITY)!!
        security = findPreference(Key.SERVER_SECURITY)!!

        sni = findPreference(Key.SERVER_SNI)!!
        alpn = findPreference(Key.SERVER_ALPN)!!
        securityCategory = findPreference(Key.SERVER_SECURITY_CATEGORY)!!
        certificates = findPreference(Key.SERVER_CERTIFICATES)!!
        pinnedCertificateChain = findPreference(Key.SERVER_PINNED_CERTIFICATE_CHAIN)!!
        pinnedCertificatePublickey = findPreference(Key.SERVER_PINNED_CERTIFICATE_PUBLIC_KEY)!!
        pinnedCertificate = findPreference(Key.SERVER_PINNED_CERTIFICATE)!!
        allowInsecure = findPreference(Key.SERVER_ALLOW_INSECURE)!!
        xtlsFlow = findPreference(Key.SERVER_FLOW)!!
        alterId = findPreference(Key.SERVER_ALTER_ID)!!
        utlsFingerprint = findPreference(Key.SERVER_UTLS_FINGERPRINT)!!
        mtlsCertificate = findPreference(Key.SERVER_MTLS_CERTIFICATE)!!
        mtlsCertificatePrivateKey = findPreference(Key.SERVER_MTLS_CERTIFICATE_PRIVATE_KEY)!!
        echEnabled = findPreference(Key.SERVER_ECH_ENABLED)!!
        echConfig = findPreference(Key.SERVER_ECH_CONFIG)!!
        echConfig.isEnabled = echEnabled.isChecked
        echEnabled.setOnPreferenceChangeListener { _, newValue ->
            echConfig.isEnabled = newValue as Boolean
            true
        }

        realityPublicKey = findPreference(Key.SERVER_REALITY_PUBLIC_KEY)!!
        realityShortId = findPreference(Key.SERVER_REALITY_SHORT_ID)!!
        realityMldsa65Verify = findPreference(Key.SERVER_REALITY_MLDSA65_VERIFY)!!
        realityFingerprint = findPreference(Key.SERVER_REALITY_FINGERPRINT)!!
        realityDisableX25519Mlkem768 = findPreference(Key.SERVER_REALITY_DISABLE_X25519MLKEM768)!!

        realityPublicKey.apply {
            summaryProvider = PasswordSummaryProvider
        }

        hy2UpMbps = findPreference(Key.SERVER_UPLOAD_SPEED)!!
        hy2UpMbps.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        hy2DownMbps = findPreference(Key.SERVER_DOWNLOAD_SPEED)!!
        hy2DownMbps.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        hy2Password = findPreference(Key.SERVER_PASSWORD)!!
        hy2Password.apply {
            summaryProvider = PasswordSummaryProvider
            title = resources.getString(R.string.hysteria2_password)
            dialogTitle = resources.getString(R.string.hysteria2_password)
        }

        mekyaKcpSeed = findPreference(Key.SERVER_MEKYA_KCP_SEED)!!
        mekyaKcpHeaderType = findPreference(Key.SERVER_MEKYA_KCP_HEADER_TYPE)!!
        mekyaUrl = findPreference(Key.SERVER_MEKYA_URL)!!

        wsCategory = findPreference(Key.SERVER_WS_CATEGORY)!!
        wsUseBrowserForwarder = findPreference(Key.SERVER_WS_BROWSER_FORWARDING)!!
        splithttpCategory = findPreference(Key.SERVER_SH_CATEGORY)!!
        splithttpMode = findPreference(Key.SERVER_SPLITHTTP_MODE)!!
        splithttpExtra = findPreference(Key.SERVER_SPLITHTTP_EXTRA)!!

        grpcCategory = findPreference(Key.SERVER_GRPC_CATAGORY)!!

        findPreference<SwitchPreference>(Key.SERVER_WS_BROWSER_FORWARDING)!!.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                runOnMainDispatcher {
                    MaterialAlertDialogBuilder(this@StandardV2RaySettingsActivity)
                        .setMessage(getString(R.string.browser_forwarder_hint, packageName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            true
        }

        when (bean) {
            is VLESSBean -> {
                encryption.isVisible = false
                val xfv = resources.getStringArray(R.array.xtls_flow_value)
                if (xtlsFlow.value !in xfv) {
                    xtlsFlow.value = xfv[0]
                }
            }
            is VMessBean -> {
                vlessEncryption.isVisible = false
                encryption.setEntries(R.array.vmess_encryption_value)
                encryption.setEntryValues(R.array.vmess_encryption_value)
                val vev = resources.getStringArray(R.array.vmess_encryption_value)
                if (encryption.value !in vev) {
                    encryption.value = "auto"
                }
            }
            is ShadowsocksBean -> {
                vlessEncryption.isVisible = false
                encryption.setEntries(R.array.enc_method_value)
                encryption.setEntryValues(R.array.enc_method_value)
                val sev = resources.getStringArray(R.array.enc_method_value)
                if (encryption.value !in sev) {
                    encryption.value = "none"
                }
            }
            else -> {
                encryption.isVisible = false
                vlessEncryption.isVisible = false
            }
        }

        passwordUUID = findPreference(Key.SERVER_USER_ID)!!
        passwordUUID.apply {
            summaryProvider = PasswordSummaryProvider
            if (bean is TrojanBean || bean is ShadowsocksBean || bean is SOCKSBean || bean is HttpBean) {
                title = resources.getString(R.string.password)
                dialogTitle = resources.getString(R.string.password)
            }
        }

        alterId.isVisible = bean is VMessBean
        alterId.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)

        updateView(network.value)
        network.setOnPreferenceChangeListener { _, newValue ->
            updateView(newValue as String)
            true
        }

        security.setOnPreferenceChangeListener { _, newValue ->
            updateTle(newValue as String)
            true
        }

        findPreference<PreferenceCategory>(Key.SERVER_VMESS_EXPERIMENTS_CATEGORY)!!.isVisible = bean is VMessBean
        findPreference<PreferenceCategory>(Key.SERVER_PLUGIN_CATEGORY)!!.isVisible = bean is ShadowsocksBean

        xtlsFlow.isVisible = bean is VLESSBean
        val fv = resources.getStringArray(R.array.xtls_flow_value)
        packetEncoding = findPreference(Key.SERVER_PACKET_ENCODING)!!
        packetEncoding.isVisible = bean is VMessBean || bean is VLESSBean
        packetEncoding.isEnabled = bean is VMessBean || (bean is VLESSBean && xtlsFlow.value == fv[0])
        val pev = resources.getStringArray(R.array.packet_encoding_value)
        if (packetEncoding.value !in pev) {
            packetEncoding.value = pev[0]
        }
        if (bean is VLESSBean && xtlsFlow.value != fv[0]) {
            packetEncoding.value = pev[2]
        }
        xtlsFlow.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            packetEncoding.isEnabled = newValue == fv[0]
            if (newValue != fv[0]) {
                packetEncoding.value = pev[2]
            }
            true
        }

        ssExperimentsCategory = findPreference(Key.SERVER_SS_EXPERIMENTS_CATEGORY)!!
        fun updateMethod(method: String) {
            ssExperimentsCategory.isVisible = bean is ShadowsocksBean && !method.startsWith("2022-blake3-")
        }
        updateMethod(DataStore.serverEncryption)
        encryption.setOnPreferenceChangeListener { _, newValue ->
            updateMethod(newValue as String)
            true
        }

        socksProtocol = findPreference(Key.SERVER_PROTOCOL)!!
        socksProtocol.isVisible = bean is SOCKSBean
        fun updateProtocol(version: Int) {
            passwordUUID.isVisible = version == SOCKSBean.PROTOCOL_SOCKS5
        }
        if (bean is SOCKSBean) {
            updateProtocol(DataStore.serverProtocolVersion)
        }
        socksProtocol.setOnPreferenceChangeListener { _, newValue ->
            updateProtocol((newValue as String).toInt())
            true
        }

        findPreference<EditTextPreference>(Key.SERVER_USERNAME)!!.isVisible = bean is SOCKSBean || bean is HttpBean

        mux = findPreference(Key.SERVER_MUX)!!
        muxConcurrency = findPreference(Key.SERVER_MUX_CONCURRENCY)!!
        muxConcurrency.isVisible = mux.isChecked
        muxConcurrency.setOnBindEditTextListener(EditTextPreferenceModifiers.Mux)
        muxPacketEncoding = findPreference(Key.SERVER_MUX_PACKET_ENCODING)!!
        muxPacketEncoding.isVisible = mux.isChecked
        if (muxPacketEncoding.value !in pev) {
            muxPacketEncoding.value = pev[0]
        }
        mux.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            muxConcurrency.isVisible = newValue
            muxPacketEncoding.isVisible = newValue
            true
        }

        plugin = findPreference(Key.SERVER_PLUGIN)!!
        pluginConfigure = findPreference(Key.SERVER_PLUGIN_CONFIGURE)!!
        pluginConfigure.setOnBindEditTextListener(EditTextPreferenceModifiers.Monospace)
        pluginConfigure.onPreferenceChangeListener = this@StandardV2RaySettingsActivity
        pluginConfiguration = PluginConfiguration(DataStore.serverPlugin)
        initPlugins()

        findPreference<PreferenceCategory>(Key.SERVER_SING_UOT_CATEGORY)!!.isVisible =
            (bean is ShadowsocksBean || bean is SOCKSBean) && DataStore.experimentalFlagsProperties.getBooleanProperty("singuot")
        findPreference<PreferenceCategory>(Key.SERVER_SING_MUX_CATEGORY)!!.isVisible =
            (bean is ShadowsocksBean || bean is TrojanBean || bean is VMessBean || bean is VLESSBean) &&
                    DataStore.experimentalFlagsProperties.getBooleanProperty("singmux")
        singMux = findPreference(Key.SERVER_SING_MUX)!!
        singMuxProtocol = findPreference(Key.SERVER_SING_MUX_PROTOCOL)!!
        singMuxMaxConnections = findPreference(Key.SERVER_SING_MUX_MAX_CONNECTIONS)!!
        singMuxMinStreams = findPreference(Key.SERVER_SING_MUX_MIN_STREAMS)!!
        singMuxMaxStreams = findPreference(Key.SERVER_SING_MUX_MAX_STREAMS)!!
        singMuxPadding = findPreference(Key.SERVER_SING_MUX_PADDING)!!
        singMuxProtocol.isVisible = singMux.isChecked
        singMuxMaxConnections.isVisible = singMux.isChecked
        singMuxMinStreams.isVisible = singMux.isChecked
        singMuxMaxStreams.isVisible = singMux.isChecked
        singMuxPadding.isVisible = singMux.isChecked
        singMuxMaxConnections.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        singMuxMinStreams.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        singMuxMaxStreams.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        val singMuxProtocolValues = resources.getStringArray(R.array.sing_mux_protocol_value)
        if (singMuxProtocol.value !in singMuxProtocolValues) {
            singMuxProtocol.value = singMuxProtocolValues[0]
        }
        singMux.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            singMuxProtocol.isVisible = newValue
            singMuxMaxConnections.isVisible = newValue
            singMuxMinStreams.isVisible = newValue
            singMuxMaxStreams.isVisible = newValue
            singMuxPadding.isVisible = newValue
            true
        }
    }

    val tcpHeadersValue = app.resources.getStringArray(R.array.tcp_headers_value)
    val kcpQuicHeadersValue = app.resources.getStringArray(R.array.kcp_quic_headers_value)
    val quicSecurityValue = app.resources.getStringArray(R.array.quic_security_value)

    fun updateView(network: String) {
        security.setEntries(R.array.transport_layer_encryption_entry)
        security.setEntryValues(R.array.transport_layer_encryption_value)
        security.value = DataStore.serverSecurity

        val tlev = resources.getStringArray(R.array.transport_layer_encryption_value)
        if (security.value !in tlev) {
            security.value = tlev[0]
        }

        updateTle(security.value)

        hy2UpMbps.isVisible = network == "hysteria2"
        hy2DownMbps.isVisible = network == "hysteria2"
        hy2Password.isVisible = network == "hysteria2"
        quicSecurity.isVisible = network == "quic"
        mekyaKcpSeed.isVisible = network == "mekya"
        mekyaKcpHeaderType.isVisible = network == "mekya"
        mekyaUrl.isVisible = network == "mekya"
        if (network == "quic") {
            if (DataStore.serverQuicSecurity !in quicSecurityValue) {
                quicSecurity.value = quicSecurityValue[0]
            } else {
                quicSecurity.value = DataStore.serverQuicSecurity
            }
        }

        wsCategory.isVisible = network == "ws" || network == "httpupgrade"
        if (network == "ws") wsCategory.setTitle(R.string.cag_ws)
        if (network == "httpupgrade") wsCategory.setTitle(R.string.cag_httpupgrade)
        wsUseBrowserForwarder.isVisible = network == "ws"

        splithttpCategory.isVisible = network == "splithttp"
        if (splithttpMode.value !in resources.getStringArray(R.array.splithttp_mode_value)) {
            splithttpMode.value = resources.getStringArray(R.array.splithttp_mode_value)[0]
        }
        // Do not translate this as it is not a stable string.
        splithttpExtra.dialogMessage = "`downloadSettings` is not supported. Use custom config for `downloadSettings`."

        grpcCategory.isVisible = network == "grpc"

        when (network) {
            "tcp" -> {
                header.setEntries(R.array.tcp_headers_entry)
                header.setEntryValues(R.array.tcp_headers_value)

                if (DataStore.serverHeader !in tcpHeadersValue) {
                    header.value = tcpHeadersValue[0]
                } else {
                    header.value = DataStore.serverHeader
                }

                var isHttp = header.value == "http"
                requestHost.isVisible = isHttp
                path.isVisible = isHttp

                header.setOnPreferenceChangeListener { _, newValue ->
                    isHttp = newValue == "http"
                    requestHost.isVisible = isHttp
                    path.isVisible = isHttp
                    true
                }

                requestHost.setTitle(R.string.http_host)
                requestHost.setDialogTitle(R.string.http_host)
                path.setTitle(R.string.http_path)
                path.setDialogTitle(R.string.http_path)

                header.isVisible = true
            }
            "http", "httpupgrade", "splithttp" -> {
                requestHost.setTitle(R.string.http_host)
                requestHost.setDialogTitle(R.string.http_host)
                path.setTitle(R.string.http_path)
                path.setDialogTitle(R.string.http_path)

                header.isVisible = false
                requestHost.isVisible = true
                path.isVisible = true
            }
            "ws" -> {
                requestHost.setTitle(R.string.ws_host)
                requestHost.setDialogTitle(R.string.ws_host)
                path.setTitle(R.string.ws_path)
                path.setDialogTitle(R.string.ws_path)

                header.isVisible = false
                requestHost.isVisible = true
                path.isVisible = true
            }
            "kcp" -> {
                header.setEntries(R.array.kcp_quic_headers_value)
                header.setEntryValues(R.array.kcp_quic_headers_value)
                path.setTitle(R.string.kcp_seed)
                path.setDialogTitle(R.string.kcp_seed)

                if (DataStore.serverHeader !in kcpQuicHeadersValue) {
                    header.value = kcpQuicHeadersValue[0]
                } else {
                    header.value = DataStore.serverHeader
                }

                header.onPreferenceChangeListener = null

                header.isVisible = true
                requestHost.isVisible = false
                path.isVisible = true
            }
            "quic" -> {
                header.setEntries(R.array.kcp_quic_headers_entry)
                header.setEntryValues(R.array.kcp_quic_headers_value)
                path.setTitle(R.string.quic_key)
                path.setDialogTitle(R.string.quic_key)

                if (DataStore.serverHeader !in kcpQuicHeadersValue) {
                    header.value = kcpQuicHeadersValue[0]
                } else {
                    header.value = DataStore.serverHeader
                }

                header.onPreferenceChangeListener = null

                header.isVisible = true
                requestHost.isVisible = false
                path.isVisible = true
            }
            "grpc" -> {
                path.setTitle(R.string.grpc_service_name)
                path.setDialogTitle(R.string.grpc_service_name)

                header.isVisible = false
                requestHost.isVisible = false
                path.isVisible = true
            }
            "meek" -> {
                path.setTitle(R.string.url)
                path.setDialogTitle(R.string.url)

                header.isVisible = false
                requestHost.isVisible = false
                path.isVisible = true
            }
            "hysteria2" -> {
                path.isVisible = false
                header.isVisible = false
                requestHost.isVisible = false
            }
            "mekya" -> {
                path.isVisible = false
                header.isVisible = false
                requestHost.isVisible = false

                mekyaKcpHeaderType.setEntries(R.array.kcp_quic_headers_entry)
                mekyaKcpHeaderType.setEntryValues(R.array.kcp_quic_headers_value)
                if (DataStore.serverMekyaKcpHeaderType !in kcpQuicHeadersValue) {
                    mekyaKcpHeaderType.value = kcpQuicHeadersValue[0]
                } else {
                    mekyaKcpHeaderType.value = DataStore.serverMekyaKcpHeaderType
                }
                mekyaKcpHeaderType.onPreferenceChangeListener = null
            }
        }
    }

    fun updateTle(security: String) {
        securityCategory.isVisible = security == "tls" || security == "reality"
        certificates.isVisible = security == "tls"
        pinnedCertificateChain.isVisible = security == "tls"
        pinnedCertificatePublickey.isVisible = security == "tls"
        pinnedCertificate.isVisible = security == "tls"
        allowInsecure.isVisible = security == "tls"
        sni.isVisible = security == "tls" || security == "reality"
        alpn.isVisible = security == "tls"
        realityPublicKey.isVisible = security == "reality"
        realityShortId.isVisible = security == "reality"
        realityMldsa65Verify.isVisible = security == "reality"
        utlsFingerprint.isVisible = security == "tls" && (network.value == "tcp" || network.value == "ws"
                || network.value == "http" || network.value == "meek" || network.value == "httpupgrade"
                || network.value == "grpc" || network.value == "splithttp" || network.value == "mekya")
        mtlsCertificate.isVisible = security == "tls"
        mtlsCertificatePrivateKey.isVisible = security == "tls"
        echEnabled.isVisible = security == "tls"
        echConfig.isVisible = security == "tls"
        realityFingerprint.isVisible = security == "reality"
        realityDisableX25519Mlkem768.isVisible = security == "reality"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        receiver = listenForPackageChanges(false) {
            // wait until changes were flushed
            lifecycleScope.launch { initPlugins() }
        }
    }
    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        setFragmentResultListener(PluginPreferenceDialogFragment::class.java.name) { _, bundle ->
            val selected = plugin.plugins.lookup.getValue(
                bundle.getString(PluginPreferenceDialogFragment.KEY_SELECTED_ID)!!
            )
            val override = pluginConfiguration.pluginsOptions.keys.firstOrNull {
                plugin.plugins.lookup[it] == selected
            }
            pluginConfiguration = PluginConfiguration(
                pluginConfiguration.pluginsOptions, override ?: selected.id
            )
            DataStore.serverPlugin = pluginConfiguration.toString()
            dirty = true
            onBackPressedCallback.isEnabled = true
            plugin.value = pluginConfiguration.selected
            pluginConfigure.isEnabled = selected !is NoPlugin
            pluginConfigure.text = pluginConfiguration.getOptions().toString()
        }
    }

    private fun initPlugins() {
        plugin.value = pluginConfiguration.selected
        plugin.init()
        pluginConfigure.isEnabled = plugin.selectedEntry?.let { it is NoPlugin } == false
        pluginConfigure.text = pluginConfiguration.getOptions().toString()
    }

    private fun showPluginEditor() {
        PluginConfigurationDialogFragment().apply {
            setArg(Key.SERVER_PLUGIN_CONFIGURE, pluginConfiguration.selected)
            setTargetFragment(child, 0)
        }.showAllowingStateLoss(supportFragmentManager, Key.SERVER_PLUGIN_CONFIGURE)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean = try {
        val selected = pluginConfiguration.selected
        pluginConfiguration = PluginConfiguration(
            (pluginConfiguration.pluginsOptions + (pluginConfiguration.selected to PluginOptions(
                selected, newValue as? String?
            ))).toMutableMap(), selected
        )
        DataStore.serverPlugin = pluginConfiguration.toString()
        dirty = true
        onBackPressedCallback.isEnabled = true
        true
    } catch (exc: RuntimeException) {
        Snackbar.make(child.requireView(), exc.readableMessage, Snackbar.LENGTH_LONG).show()
        false
    }

    private val configurePlugin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
        when (resultCode) {
            RESULT_OK -> {
                val options = data?.getStringExtra(PluginContract.EXTRA_OPTIONS)
                pluginConfigure.text = options
                onPreferenceChange(pluginConfigure, options)
            }
            PluginContract.RESULT_FALLBACK -> showPluginEditor()
        }
    }

    override fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        when (preference.key) {
            Key.SERVER_PLUGIN -> PluginPreferenceDialogFragment().apply {
                setArg(Key.SERVER_PLUGIN)
                setTargetFragment(child, 0)
            }.showAllowingStateLoss(supportFragmentManager, Key.SERVER_PLUGIN)
            Key.SERVER_PLUGIN_CONFIGURE -> {
                val intent = PluginManager.buildIntent(
                    plugin.selectedEntry!!.id, PluginContract.ACTION_CONFIGURE
                )
                val component = intent.resolveActivity(packageManager)
                when {
                    DataStore.experimentalFlagsProperties.getBooleanProperty("disableShadowsocksPluginConfigActivity") -> {
                        showPluginEditor()
                    }
                    plugin.selectedEntry!!.id == "v2ray-plugin" && (component == null || DataStore.experimentalFlagsProperties.getBooleanProperty("useInternalV2RayPlugin")) -> {
                        configurePlugin.launch(
                            Intent().apply {
                                setClassName(app.packageName, "com.github.shadowsocks.plugin.v2ray.ConfigActivity")
                                putExtra(
                                    PluginContract.EXTRA_OPTIONS,
                                    pluginConfiguration.getOptions().toString()
                                )
                            }
                        )
                    }
                    plugin.selectedEntry!!.id == "obfs-local" && (component == null || DataStore.experimentalFlagsProperties.getBooleanProperty("useInternalObfsLocal")) -> {
                        configurePlugin.launch(
                            Intent().apply {
                                setClassName(app.packageName, "com.github.shadowsocks.plugin.obfs_local.ConfigActivity")
                                putExtra(
                                    PluginContract.EXTRA_OPTIONS,
                                    pluginConfiguration.getOptions().toString()
                                )
                            }
                        )
                    }
                    component == null -> {
                        showPluginEditor()
                    }
                    else -> {
                        configurePlugin.launch(
                            intent.putExtra(
                                PluginContract.EXTRA_OPTIONS,
                                pluginConfiguration.getOptions().toString()
                            )
                        )
                    }
                }
            }
            else -> return false
        }
        return true
    }

    val pluginHelp = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == RESULT_OK) MaterialAlertDialogBuilder(this).setTitle("?")
            .setMessage(data?.getCharSequenceExtra(PluginContract.EXTRA_HELP_MESSAGE))
            .show()
    }

}
