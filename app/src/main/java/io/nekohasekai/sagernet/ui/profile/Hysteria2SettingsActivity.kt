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

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.ktx.isValidHysteriaMultiPort
import io.nekohasekai.sagernet.ktx.unwrapIDN

class Hysteria2SettingsActivity : ProfileSettingsActivity<Hysteria2Bean>() {

    override fun createEntity() = Hysteria2Bean()

    override fun Hysteria2Bean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverObfs = obfs
        DataStore.serverPassword = auth
        DataStore.serverSNI = sni
        DataStore.serverCertificates = certificates
        DataStore.serverPinnedCertificateChain = pinnedPeerCertificateChainSha256
        DataStore.serverPinnedCertificatePublicKey = pinnedPeerCertificatePublicKeySha256
        DataStore.serverPinnedCertificate = pinnedPeerCertificateSha256
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverPorts = serverPorts
        DataStore.serverHopInterval = hopInterval
        DataStore.serverHopIntervalMin = hopIntervalMin
        DataStore.serverHopIntervalMax = hopIntervalMax
        DataStore.serverEchEnabled = echEnabled
        DataStore.serverEchConfig = echConfig
        DataStore.serverMtlsCertificate = mtlsCertificate
        DataStore.serverMtlsCertificatePrivateKey = mtlsCertificatePrivateKey
        DataStore.serverCongestionController = congestionControl
        DataStore.serverHysteria2BBRProfile = bbrProfile
        DataStore.serverHysteria2OmitMaxDatagramFrameSize = omitMaxDatagramFrameSize
    }

    override fun Hysteria2Bean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        obfs = DataStore.serverObfs
        auth = DataStore.serverPassword
        sni = DataStore.serverSNI
        certificates = DataStore.serverCertificates
        pinnedPeerCertificateChainSha256 = DataStore.serverPinnedCertificateChain
        pinnedPeerCertificatePublicKeySha256 = DataStore.serverPinnedCertificatePublicKey
        pinnedPeerCertificateSha256 = DataStore.serverPinnedCertificate
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        serverPorts = DataStore.serverPorts
        hopInterval = DataStore.serverHopInterval
        hopIntervalMin = DataStore.serverHopIntervalMin
        hopIntervalMax = DataStore.serverHopIntervalMax
        echEnabled = DataStore.serverEchEnabled
        echConfig = DataStore.serverEchConfig
        mtlsCertificate = DataStore.serverMtlsCertificate
        mtlsCertificatePrivateKey = DataStore.serverMtlsCertificatePrivateKey
        congestionControl = DataStore.serverCongestionController
        bbrProfile = DataStore.serverHysteria2BBRProfile
        omitMaxDatagramFrameSize = DataStore.serverHysteria2OmitMaxDatagramFrameSize
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.hysteria2_preferences)

        findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        findPreference<EditTextPreference>(Key.SERVER_UPLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_DOWNLOAD_SPEED)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_OBFS)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        val echEnabled = findPreference<SwitchPreference>(Key.SERVER_ECH_ENABLED)!!
        val echConfig = findPreference<EditTextPreference>(Key.SERVER_ECH_CONFIG)!!
        echConfig.isEnabled = echEnabled.isChecked
        echEnabled.setOnPreferenceChangeListener { _, newValue ->
            echConfig.isEnabled = newValue as Boolean
            true
        }

        val serverPorts = findPreference<EditTextPreference>(Key.SERVER_PORTS)!!
        val isValidHysteriaMultiPort = serverPorts.text?.isValidHysteriaMultiPort() ?: false
        val hopInterval = findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL)!!
        val hopIntervalMin = findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL_MIN)!!
        val hopIntervalMax = findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL_MAX)!!
        hopInterval.isVisible = isValidHysteriaMultiPort && (hopIntervalMin.text.isNullOrEmpty() || hopIntervalMin.text!!.toIntOrNull() == 0) && (hopIntervalMax.text.isNullOrEmpty() || hopIntervalMax.text!!.toIntOrNull() == 0)
        hopIntervalMin.isVisible = isValidHysteriaMultiPort && (hopInterval.text.isNullOrEmpty() || hopInterval.text!!.toIntOrNull() == 0)
        hopIntervalMax.isVisible = isValidHysteriaMultiPort && (hopInterval.text.isNullOrEmpty() || hopInterval.text!!.toIntOrNull() == 0)
        hopInterval.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            val isValidHysteriaMultiPort = serverPorts.text?.isValidHysteriaMultiPort() ?: false
            hopIntervalMin.isVisible = isValidHysteriaMultiPort && (newValue.isEmpty() || newValue.toIntOrNull() == 0)
            hopIntervalMax.isVisible = isValidHysteriaMultiPort && (newValue.isEmpty() || newValue.toIntOrNull() == 0)
            true
        }
        hopIntervalMin.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            hopInterval.isVisible = serverPorts.text?.isValidHysteriaMultiPort() == true && (newValue.isEmpty() || newValue.toIntOrNull() == 0) && (hopIntervalMax.text.isNullOrEmpty() || hopIntervalMax.text!!.toIntOrNull() == 0)
            true
        }
        hopIntervalMax.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            hopInterval.isVisible = serverPorts.text?.isValidHysteriaMultiPort() == true && (newValue.isEmpty() || newValue.toIntOrNull() == 0) && (hopIntervalMin.text.isNullOrEmpty() || hopIntervalMin.text!!.toIntOrNull() == 0)
            true
        }
        serverPorts.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            val isValidHysteriaMultiPort = newValue.isValidHysteriaMultiPort()
            hopInterval.isVisible = isValidHysteriaMultiPort && (hopIntervalMin.text.isNullOrEmpty() || hopIntervalMin.text!!.toIntOrNull() == 0) && (hopIntervalMax.text.isNullOrEmpty() || hopIntervalMax.text!!.toIntOrNull() == 0)
            hopIntervalMin.isVisible = isValidHysteriaMultiPort && (hopInterval.text.isNullOrEmpty() || hopInterval.text!!.toIntOrNull() == 0)
            hopIntervalMax.isVisible = isValidHysteriaMultiPort && (hopInterval.text.isNullOrEmpty() || hopInterval.text!!.toIntOrNull() == 0)
            true
        }

        val congestionControl = findPreference<ListPreference>(Key.SERVER_CONGESTION_CONTROLLER)!!
        val bbrProfile = findPreference<ListPreference>(Key.SERVER_HYSTERIA2_BBR_PROFILE)!!
        bbrProfile.isVisible = congestionControl.isEnabled && congestionControl.value == "bbr"
        congestionControl.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            bbrProfile.isVisible = newValue == "bbr"
            true
        }
    }

}