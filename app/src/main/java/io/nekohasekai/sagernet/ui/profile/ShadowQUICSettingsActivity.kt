/******************************************************************************
 *                                                                            *
 * Copyright (C) 2025 dyhkwong                                                *
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
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.shadowquic.ShadowQUICBean
import io.nekohasekai.sagernet.ktx.unwrapIDN

class ShadowQUICSettingsActivity : ProfileSettingsActivity<ShadowQUICBean>() {

    override fun createEntity() = ShadowQUICBean()

    override fun ShadowQUICBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCongestionController = congestionControl
        DataStore.serverReduceRTT = zeroRTT
        DataStore.serverBrookUdpOverStream = udpOverStream
        DataStore.serverShadowQUICDisableALPN = disableALPN
        DataStore.serverShadowQUICUseSunnyQUIC = useSunnyQUIC
        DataStore.serverCertificates = certificate
        DataStore.serverUploadSpeed = brutalUploadBandwidth
    }

    override fun ShadowQUICBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        congestionControl = DataStore.serverCongestionController
        zeroRTT = DataStore.serverReduceRTT
        udpOverStream = DataStore.serverBrookUdpOverStream
        disableALPN = DataStore.serverShadowQUICDisableALPN
        useSunnyQUIC = DataStore.serverShadowQUICUseSunnyQUIC
        certificate = DataStore.serverCertificates
        brutalUploadBandwidth = DataStore.serverUploadSpeed
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.shadowquic_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        val disableALPN = findPreference<SwitchPreference>(Key.SERVER_SHADOWQUIC_DISABLE_ALPN)!!
        val alpn = findPreference<EditTextPreference>(Key.SERVER_ALPN)!!
        alpn.isEnabled = !disableALPN.isChecked
        disableALPN.setOnPreferenceChangeListener { _, newValue ->
            alpn.isEnabled = !(newValue as Boolean)
            true
        }

        val useSunnyQUIC = findPreference<SwitchPreference>(Key.SERVER_SHADOWQUIC_USE_SUNNYQUIC)!!
        val certificate = findPreference<EditTextPreference>(Key.SERVER_CERTIFICATES)!!
        certificate.isEnabled = useSunnyQUIC.isChecked
        useSunnyQUIC.setOnPreferenceChangeListener { _, newValue ->
            certificate.isEnabled = newValue as Boolean
            true
        }
        val congestionControl = findPreference<ListPreference>(Key.SERVER_CONGESTION_CONTROLLER)!!
        val brutalUploadBandwidth = findPreference<EditTextPreference>(Key.SERVER_UPLOAD_SPEED)!!
        brutalUploadBandwidth.isEnabled = congestionControl.value == "brutal"
        congestionControl.setOnPreferenceChangeListener { _, newValue ->
            brutalUploadBandwidth.isEnabled = (newValue as String) == "brutal"
            true
        }
    }

}