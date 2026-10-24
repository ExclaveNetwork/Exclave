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

package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference

@Suppress("UNCHECKED_CAST")
class RouteSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_settings_activity,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    var dirty = false

    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                .setTitle(R.string.unsaved_changes_prompt)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher {
                        saveAndExit()
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish()
                }
                .show()
        }
    }

    fun init(packageName: String?) {
        RuleEntity().apply {
            if (!packageName.isNullOrEmpty()) {
                packages = listOf(packageName)
                PackageCache.awaitLoadSync()
                name = getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.init()
    }

    fun RuleEntity.init() {
        DataStore.routeName = name
        DataStore.routeDomain = domains
        DataStore.routeIP = ip
        DataStore.routePort = port
        DataStore.routeSourcePort = sourcePort
        DataStore.routeNetwork = network
        DataStore.routeSource = source
        DataStore.routeProtocol = protocol
        DataStore.routeAttrs = attrs
        DataStore.routeOutboundRule = outbound
        DataStore.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> 3
        }
        DataStore.routeReverse = reverse
        DataStore.routeRedirect = redirect
        DataStore.routePackages = packages.joinToString("\n")
        DataStore.routeCustomPackageNameOrUid = customPackageNames.joinToString("\n")
        DataStore.routeNetworkType = networkType
        DataStore.routeSSID = ssid
    }

    fun RuleEntity.serialize() {
        name = DataStore.routeName
        domains = DataStore.routeDomain
        ip = DataStore.routeIP
        port = DataStore.routePort
        sourcePort = DataStore.routeSourcePort
        network = DataStore.routeNetwork
        source = DataStore.routeSource
        protocol = DataStore.routeProtocol
        attrs = DataStore.routeAttrs
        outbound = when (DataStore.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> DataStore.routeOutboundRule
        }
        reverse = DataStore.routeReverse
        redirect = DataStore.routeRedirect
        packages = DataStore.routePackages.split("\n").filter { it.isNotEmpty() }
        customPackageNames = DataStore.routeCustomPackageNameOrUid.listByLineOrComma()
        networkType = DataStore.routeNetworkType
        ssid = DataStore.routeSSID

        if (DataStore.editingId == 0L) {
            enabled = true
        }
    }

    fun needSave(): Boolean {
        if (!dirty) return false
        if (DataStore.routePackages.isEmpty() && DataStore.routeCustomPackageNameOrUid.isEmpty() && DataStore.routeDomain.isEmpty() && DataStore.routeIP.isEmpty() && DataStore.routePort.isEmpty() && DataStore.routeSourcePort.isEmpty() && DataStore.routeNetwork.isEmpty() && DataStore.routeSource.isEmpty() && DataStore.routeProtocol.isEmpty() && DataStore.routeAttrs.isEmpty() && !(DataStore.routeReverse && DataStore.routeRedirect.isEmpty()) && DataStore.routeNetworkType.isEmpty()) {
            return false
        }
        return true
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.route_preferences)
    }

    val selectProfileForAdd = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                data!!.getLongExtra(
                    ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                )
            ) ?: return@runOnDefaultDispatcher
            DataStore.routeOutboundRule = profile.id
            onMainDispatcher {
                outbound.value = "3"
                // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-s2-release/core/java/android/preference/ListPreference.java#167
                // If the summary has a {@linkplain java.lang.String#format String formatting} marker in it,
                // (i.e. "%s" or "%1$s"), then the current entry value will be substituted in its place.
                outbound.setSummary(profile.displayName().replace("%", "%%"))
            }
        }
    }

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (_, _) ->
        apps.postUpdate()
    }

    lateinit var outbound: ListPreference
    lateinit var reverse: SwitchPreference
    lateinit var redirect: EditTextPreference
    lateinit var apps: AppListPreference
    lateinit var customPackageNames: EditTextPreference
    lateinit var networkType: MultiSelectListPreference
    lateinit var ssid: EditTextPreference

    fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        findPreference<EditTextPreference>(Key.ROUTE_SOURCE_PORT)!!.dialogMessage = getString(R.string.format, "53,443,1000-2000")
        findPreference<EditTextPreference>(Key.ROUTE_PORT)!!.dialogMessage = getString(R.string.format, "53,443,1000-2000")
        outbound = findPreference(Key.ROUTE_OUTBOUND)!!
        reverse = findPreference(Key.ROUTE_REVERSE)!!
        redirect = findPreference(Key.ROUTE_REDIRECT)!!
        apps = findPreference(Key.ROUTE_PACKAGES)!!
        customPackageNames = findPreference(Key.ROUTE_CUSTOM_PACKAGE_NAME_OR_UID)!!
        networkType = findPreference(Key.ROUTE_NETWORK_TYPE)!!
        ssid = findPreference(Key.ROUTE_SSID)!!

        apps.isEnabled = customPackageNames.text.isNullOrEmpty()
        customPackageNames.setOnPreferenceChangeListener { _, newValue ->
            apps.isEnabled = (newValue as String).isEmpty()
            true
        }

        fun updateReverse(enabled: Boolean = outbound.value == "3") {
            reverse.isVisible = enabled
            redirect.isVisible = enabled
            redirect.isEnabled = reverse.isChecked
        }

        updateReverse()

        reverse.setOnPreferenceChangeListener { _, newValue ->
            redirect.isEnabled = newValue as Boolean
            true
        }

        val outboundEntries = resources.getStringArray(R.array.outbound_entry)
        if (DataStore.routeOutbound == 3) {
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-s2-release/core/java/android/preference/ListPreference.java#167
            // If the summary has a {@linkplain java.lang.String#format String formatting} marker in it,
            // (i.e. "%s" or "%1$s"), then the current entry value will be substituted in its place.
            outbound.setSummary(ProfileManager.getProfile(DataStore.routeOutboundRule)?.displayName()?.replace("%", "%%"))
        } else {
            outbound.setSummary(outboundEntries[DataStore.routeOutbound.toString().toInt()])
        }
        outbound.apply {
            setEntries(R.array.outbound_entry)
            setEntryValues(R.array.outbound_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "3") {
                    updateReverse(true)
                    selectProfileForAdd.launch(
                        Intent(this@RouteSettingsActivity, ProfileSelectActivity::class.java)
                    )
                    false
                } else {
                    updateReverse(false)
                    setSummary(outboundEntries[newValue.toString().toInt()])
                    true
                }
            }
        }

        apps.setOnPreferenceClickListener {
            selectAppList.launch(
                Intent(
                    this@RouteSettingsActivity, AppListActivity::class.java
                )
            )
            true
        }

        fun updateNetwork(newValues: Set<String> = networkType.values) {
            networkType.summary = if (newValues.isEmpty()) {
                getString(androidx.preference.R.string.not_set)
            } else {
                val types = mutableListOf<String>()
                if (newValues.contains("data")) types.add(getString(R.string.network_data))
                if (newValues.contains("wifi")) types.add(getString(R.string.network_wifi))
                if (newValues.contains("bluetooth")) types.add(getString(R.string.network_bt))
                if (newValues.contains("ethernet")) types.add(getString(R.string.network_eth))
                if (newValues.contains("usb")) types.add(getString(R.string.network_usb))
                if (newValues.contains("satellite")) types.add(getString(R.string.network_satellite))
                types.joinToString("\n")
            }
            ssid.isVisible = newValues.contains("wifi")
        }

        updateNetwork()

        networkType.setOnPreferenceChangeListener { _, newValues ->
            newValues as Set<String>
            when {
                newValues.contains("usb") -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    runOnMainDispatcher {
                        MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                            .setMessage(getString(R.string.network_invalid, "12"))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                newValues.contains("satellite") -> {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) < 12 ||
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        runOnMainDispatcher {
                            MaterialAlertDialogBuilder(this@RouteSettingsActivity)
                                .setMessage(getString(R.string.network_invalid, "15"))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                }
            }
            updateNetwork(newValues)
            true
        }
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
        const val KEY_DIRTY = "dirty"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    init(intent.getStringExtra(EXTRA_PACKAGE_NAME))
                } else {
                    val ruleEntity = SagerDatabase.rulesDao.getById(editingId)
                    if (ruleEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    ruleEntity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                    DataStore.profileCacheStore.registerChangeListener(this@RouteSettingsActivity)
                }
            }


        }

        savedInstanceState?.getBoolean(KEY_DIRTY)?.let {
            dirty = it
            onBackPressedCallback.isEnabled = it
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DIRTY, dirty)
    }

    suspend fun saveAndExit() {

        if (!needSave()) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.empty_route)
                    .setMessage(R.string.empty_route_notice)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            if (intent.hasExtra(EXTRA_PACKAGE_NAME)) {
                setResult(RESULT_OK, Intent())
            }

            ProfileManager.createRule(RuleEntity().apply { serialize() })
        } else {
            val entity = SagerDatabase.rulesDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            ProfileManager.updateRule(entity.apply { serialize() })
        }

        finish()

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_delete -> {
            if (DataStore.editingId == 0L) {
                finish()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_route_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            ProfileManager.deleteRule(DataStore.editingId)
                        }
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        R.id.action_apply -> {
            runOnDefaultDispatcher {
                saveAndExit()
            }
            true
        }
        else -> false
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            dirty = true
            onBackPressedCallback.isEnabled = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        val activity: RouteSettingsActivity
            get() = requireActivity() as RouteSettingsActivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity.apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    left = bars.left,
                    right = bars.right,
                    bottom = bars.bottom,
                )
                insets
            }
            activity.apply {
                viewCreated(view, savedInstanceState)
            }
        }

    }

}