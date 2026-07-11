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
        DataStore.serverSnellPSK = psk
        DataStore.serverSnellUserKey = userKey
        DataStore.serverSnellReuse = reuse
        DataStore.serverSnellVersion = version
        when (version) {
            4 -> {
                DataStore.serverSnellObfsMode = obfsMode
                DataStore.serverSnellObfsHost = obfsHost
            }
            6 -> {
                DataStore.serverSnellMode = mode
            }
        }
    }

    override fun SnellBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        psk = DataStore.serverSnellPSK
        userKey = DataStore.serverSnellUserKey
        reuse = DataStore.serverSnellReuse
        version = DataStore.serverSnellVersion
        when (version) {
            4 -> {
                obfsMode = DataStore.serverSnellObfsMode
                obfsHost = DataStore.serverSnellObfsHost
            }
            6 -> {
                mode = DataStore.serverSnellMode
            }
        }
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
        val versionPref = findPreference<SimpleMenuPreference>(Key.SERVER_SNELL_VERSION)!!
        val modePref = findPreference<SimpleMenuPreference>(Key.SERVER_SNELL_MODE)!!
        val obfsPref = findPreference<SimpleMenuPreference>(Key.SERVER_SNELL_OBFS_MODE)!!
        val obfsHostPref = findPreference<EditTextPreference>(Key.SERVER_SNELL_OBFS_HOST)!!
        fun updateVisibility(v: Int) {
            val isV6 = v == 6
            modePref.isVisible = isV6
            obfsPref.isVisible = !isV6
            obfsHostPref.isVisible = !isV6
        }
        val cur = versionPref.value.toInt()
        updateVisibility(cur)
        versionPref.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility((newValue as String).toInt())
            true
        }
    }
}
