package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.ktx.unwrapIDN
import io.nekohasekai.sagernet.widget.SimpleMenuPreference

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {

    override fun createEntity() = SnellBean()

    override fun SnellBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverPassword = psk
        DataStore.serverPassword1 = userPSK
        DataStore.serverObfs = obfsMode
        DataStore.serverHost = obfsHost
        DataStore.serverProtocolVersion = version ?: SnellBean.VERSION_4
        DataStore.serverMux = reuse == true
        DataStore.serverProtocolParam = mode
    }

    override fun SnellBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        psk = DataStore.serverPassword
        userPSK = DataStore.serverPassword1
        obfsMode = DataStore.serverObfs?.ifEmpty { SnellBean.OBFS_NONE } ?: SnellBean.OBFS_NONE
        obfsHost = DataStore.serverHost
        version = DataStore.serverProtocolVersion.takeIf { it == 4 || it == 6 } ?: SnellBean.VERSION_4
        reuse = DataStore.serverMux
        mode = DataStore.serverProtocolParam?.ifEmpty { SnellBean.MODE_DEFAULT } ?: SnellBean.MODE_DEFAULT
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.snell_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val versionPref = findPreference<SimpleMenuPreference>(Key.SERVER_PROTOCOL)!!
        val modePref = findPreference<SimpleMenuPreference>(Key.SERVER_PROTOCOL_PARAM)!!
        val obfsPref = findPreference<SimpleMenuPreference>(Key.SERVER_OBFS)!!
        val obfsHostPref = findPreference<EditTextPreference>(Key.SERVER_HOST)!!
        fun updateVisibility(v: Int) {
            val isV6 = v >= 6
            modePref.isVisible = isV6
            // v4-only: obfs mode/host
            obfsPref.isVisible = !isV6
            obfsHostPref.isVisible = !isV6
        }
        val cur = versionPref.value?.toIntOrNull() ?: DataStore.serverProtocolVersion
        updateVisibility(cur)
        versionPref.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility((newValue as? String)?.toIntOrNull() ?: 4)
            true
        }
    }
}
