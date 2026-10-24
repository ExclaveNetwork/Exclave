/******************************************************************************
 *                                                                            *
 * Copyright (C) 2025  dyhkwong                                               *
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile


import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.anytls.AnyTLSBean
import io.nekohasekai.sagernet.ktx.unwrapIDN

class AnyTLSSettingsActivity: ProfileSettingsActivity<AnyTLSBean>() {

    override fun createEntity() = AnyTLSBean()

    override fun AnyTLSBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverPassword = password
        DataStore.serverAnyTLSIdleSessionCheckInterval = idleSessionCheckInterval
        DataStore.serverAnyTLSIdleSessionTimeout = idleSessionTimeout
        DataStore.serverAnyTLSMinIdleSession = minIdleSession
        DataStore.serverSecurity = security
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = certificates
        DataStore.serverPinnedCertificateChain = pinnedPeerCertificateChainSha256
        DataStore.serverPinnedCertificatePublicKey = pinnedPeerCertificatePublicKeySha256
        DataStore.serverPinnedCertificate = pinnedPeerCertificateSha256
        DataStore.serverUTLSFingerprint = utlsFingerprint
        DataStore.serverEchEnabled = echEnabled
        DataStore.serverEchConfig = echConfig
        DataStore.serverRealityPublicKey = realityPublicKey
        DataStore.serverRealityShortId = realityShortId
        DataStore.serverRealityFingerprint = realityFingerprint
        DataStore.serverRealityDisableX25519Mlkem768 = realityDisableX25519Mlkem768
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverMtlsCertificate = mtlsCertificate
        DataStore.serverMtlsCertificatePrivateKey = mtlsCertificatePrivateKey
    }

    override fun AnyTLSBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        password = DataStore.serverPassword
        idleSessionCheckInterval = DataStore.serverAnyTLSIdleSessionCheckInterval
        idleSessionTimeout = DataStore.serverAnyTLSIdleSessionTimeout
        minIdleSession = DataStore.serverAnyTLSMinIdleSession
        security = DataStore.serverSecurity
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        certificates = DataStore.serverCertificates
        pinnedPeerCertificateChainSha256 = DataStore.serverPinnedCertificateChain
        pinnedPeerCertificatePublicKeySha256 = DataStore.serverPinnedCertificatePublicKey
        pinnedPeerCertificateSha256 = DataStore.serverPinnedCertificate
        utlsFingerprint = DataStore.serverUTLSFingerprint
        echEnabled = DataStore.serverEchEnabled
        echConfig = DataStore.serverEchConfig
        realityPublicKey = DataStore.serverRealityPublicKey
        realityShortId = DataStore.serverRealityShortId
        realityFingerprint = DataStore.serverRealityFingerprint
        realityDisableX25519Mlkem768 = DataStore.serverRealityDisableX25519Mlkem768
        allowInsecure = DataStore.serverAllowInsecure
        mtlsCertificate = DataStore.serverMtlsCertificate
        mtlsCertificatePrivateKey = DataStore.serverMtlsCertificatePrivateKey
    }

    lateinit var password: EditTextPreference
    lateinit var security: ListPreference
    lateinit var sni: EditTextPreference
    lateinit var alpn: EditTextPreference
    lateinit var securityCategory: PreferenceCategory
    lateinit var certificates: EditTextPreference
    lateinit var pinnedCertificateChain: EditTextPreference
    lateinit var pinnedPeerCertificatePublicKey: EditTextPreference
    lateinit var pinnedPeerCertificate: EditTextPreference
    lateinit var allowInsecure: SwitchPreference
    lateinit var utlsFingerprint: ListPreference
    lateinit var mtlsCertificate: EditTextPreference
    lateinit var mtlsCertificatePrivateKey: EditTextPreference
    lateinit var echEnabled: SwitchPreference
    lateinit var echConfig: EditTextPreference
    lateinit var realityPublicKey: EditTextPreference
    lateinit var realityShortId: EditTextPreference
    lateinit var realityFingerprint: ListPreference
    lateinit var realityDisableX25519Mlkem768: SwitchPreference

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.anytls_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        password = findPreference(Key.SERVER_PASSWORD)!!
        findPreference<EditTextPreference>(Key.SERVER_ANYTLS_IDLE_SESSION_CHECK_INTERVAL)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_ANYTLS_IDLE_SESSION_TIMEOUT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_ANYTLS_MIN_IDLE_SESSION)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        security = findPreference(Key.SERVER_SECURITY)!!
        sni = findPreference(Key.SERVER_SNI)!!
        alpn = findPreference(Key.SERVER_ALPN)!!
        securityCategory = findPreference(Key.SERVER_SECURITY_CATEGORY)!!
        certificates = findPreference(Key.SERVER_CERTIFICATES)!!
        pinnedCertificateChain = findPreference(Key.SERVER_PINNED_CERTIFICATE_CHAIN)!!
        pinnedPeerCertificatePublicKey = findPreference(Key.SERVER_PINNED_CERTIFICATE_PUBLIC_KEY)!!
        pinnedPeerCertificate = findPreference(Key.SERVER_PINNED_CERTIFICATE)!!
        allowInsecure = findPreference(Key.SERVER_ALLOW_INSECURE)!!
        utlsFingerprint = findPreference(Key.SERVER_UTLS_FINGERPRINT)!!
        echEnabled = findPreference(Key.SERVER_ECH_ENABLED)!!
        echConfig = findPreference(Key.SERVER_ECH_CONFIG)!!
        echConfig.isEnabled = echEnabled.isChecked
        echEnabled.setOnPreferenceChangeListener { _, newValue ->
            echConfig.isEnabled = newValue as Boolean
            true
        }
        mtlsCertificate = findPreference(Key.SERVER_MTLS_CERTIFICATE)!!
        mtlsCertificatePrivateKey = findPreference(Key.SERVER_MTLS_CERTIFICATE_PRIVATE_KEY)!!
        realityPublicKey = findPreference(Key.SERVER_REALITY_PUBLIC_KEY)!!
        realityShortId = findPreference(Key.SERVER_REALITY_SHORT_ID)!!
        realityFingerprint = findPreference(Key.SERVER_REALITY_FINGERPRINT)!!
        realityDisableX25519Mlkem768 = findPreference(Key.SERVER_REALITY_DISABLE_X25519MLKEM768)!!
        password.apply {
            summaryProvider = PasswordSummaryProvider
        }
        realityPublicKey.apply {
            summaryProvider = PasswordSummaryProvider
        }

        val tlev = resources.getStringArray(R.array.transport_layer_encryption_value)
        if (security.value !in tlev) {
                security.value = tlev[1]
        }
        updateTle(security.value)
        security.setOnPreferenceChangeListener { _, newValue ->
            updateTle(newValue as String)
            true
        }
    }

    fun updateTle(security: String) {
        securityCategory.isVisible = security == "tls" || security == "reality"
        certificates.isVisible = security == "tls"
        pinnedCertificateChain.isVisible = security == "tls"
        pinnedPeerCertificatePublicKey.isVisible = security == "tls"
        pinnedPeerCertificate.isVisible = security == "tls"
        allowInsecure.isVisible = security == "tls"
        sni.isVisible = security == "tls" || security == "reality"
        alpn.isVisible = security == "tls"
        mtlsCertificate.isVisible = security == "tls"
        mtlsCertificatePrivateKey.isVisible = security == "tls"
        echEnabled.isVisible = security == "tls"
        echConfig.isVisible = security == "tls"
        realityPublicKey.isVisible = security == "reality"
        realityShortId.isVisible = security == "reality"
        utlsFingerprint.isVisible = security == "tls"
        realityFingerprint.isVisible = security == "reality"
        realityDisableX25519Mlkem768.isVisible = security == "reality"
    }

}
