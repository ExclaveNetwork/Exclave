package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.ktx.unwrapIDN

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {

    override fun createEntity() = SnellBean()

    override fun SnellBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverSnellPSK = psk
        DataStore.serverSnellUserKey = userKey
        DataStore.serverSnellReuse = reuse
        DataStore.serverSnellVersion = version
        DataStore.serverSnellObfsMode = if (version == SnellBean.VERSION_4) obfsMode else SnellBean.OBFS_NONE
        DataStore.serverSnellObfsHost = if (version == SnellBean.VERSION_4 && (obfsMode == SnellBean.OBFS_HTTP || obfsMode == SnellBean.OBFS_TLS)) {
            obfsHost
        } else ""
        DataStore.serverSnellObfsUri = if (version == SnellBean.VERSION_4 && obfsMode == SnellBean.OBFS_HTTP) obfsURI else ""
        DataStore.serverSnellMode = if (version == SnellBean.VERSION_6) mode else SnellBean.MODE_DEFAULT
    }

    override fun SnellBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        psk = DataStore.serverSnellPSK
        userKey = DataStore.serverSnellUserKey
        reuse = DataStore.serverSnellReuse
        version = DataStore.serverSnellVersion
        obfsMode = if (version == SnellBean.VERSION_4) DataStore.serverSnellObfsMode else SnellBean.OBFS_NONE
        obfsHost = if (version == SnellBean.VERSION_4 && (DataStore.serverSnellObfsMode == SnellBean.OBFS_HTTP || DataStore.serverSnellObfsMode == SnellBean.OBFS_TLS)) {
            DataStore.serverSnellObfsHost
        } else ""
        obfsURI = if (version == SnellBean.VERSION_4 && DataStore.serverSnellObfsMode == SnellBean.OBFS_HTTP) DataStore.serverSnellObfsUri else ""
        mode = if (version == SnellBean.VERSION_6) DataStore.serverSnellMode else SnellBean.MODE_DEFAULT
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.snell_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_SNELL_PSK)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_SNELL_USER_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<PreferenceCategory>(Key.SERVER_SING_SNELL_CATEGORY)!!.isVisible = DataStore.singSnellUserKey
        val versionPref = findPreference<ListPreference>(Key.SERVER_SNELL_VERSION)!!
        val modePref = findPreference<ListPreference>(Key.SERVER_SNELL_MODE)!!
        val obfsPref = findPreference<ListPreference>(Key.SERVER_SNELL_OBFS_MODE)!!
        val obfsHostPref = findPreference<EditTextPreference>(Key.SERVER_SNELL_OBFS_HOST)!!
        val obfsUriPref = findPreference<EditTextPreference>(Key.SERVER_SNELL_OBFS_URI)!!
        fun updateVisibility(version: Int, obfs: String) {
            obfsPref.isVisible = version == SnellBean.VERSION_4
            obfsHostPref.isVisible = version == SnellBean.VERSION_4 && (obfs == SnellBean.OBFS_HTTP || obfs == SnellBean.OBFS_TLS)
            obfsUriPref.isVisible = version == SnellBean.VERSION_4 && obfs == SnellBean.OBFS_HTTP
            modePref.isVisible = version == SnellBean.VERSION_6
        }
        updateVisibility(versionPref.value.toInt(), obfsPref.value)
        versionPref.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility((newValue as String).toInt(), obfsPref.value)
            true
        }
        obfsPref.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility(versionPref.value.toInt(), newValue as String)
            true
        }
    }
}
