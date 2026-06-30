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
import android.os.Bundle
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
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity.PasswordSummaryProvider

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    var dirty = false

    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@GroupSettingsActivity)
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

    private lateinit var frontProxyPreference: ListPreference
    private lateinit var landingProxyPreference: ListPreference

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        val sub = if (type == GroupType.SUBSCRIPTION) {
            subscription ?: SubscriptionBean().applyDefaultValues()
        } else {
            SubscriptionBean().applyDefaultValues()
        }
        DataStore.subscriptionType = sub.type
        DataStore.subscriptionLink = sub.link
        DataStore.subscriptionDeduplication = sub.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = sub.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = sub.customUserAgent
        DataStore.subscriptionAutoUpdate = sub.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = sub.autoUpdateDelay
        DataStore.subscriptionLastUpdated = sub.lastUpdated
        DataStore.subscriptionBytesUsed = sub.bytesUsed
        DataStore.subscriptionBytesRemaining = sub.bytesRemaining
        DataStore.subscriptionExpiryDate = sub.expiryDate
        DataStore.subscriptionNameFilter = sub.nameFilter
        DataStore.subscriptionNameFilter1 = sub.nameFilter1
        DataStore.subscriptionHTTPHeaders = sub.httpHeaders
        DataStore.subscriptionAgePrivateKey = sub.agePrivateKey
        DataStore.frontProxyOutbound = frontProxy
        DataStore.landingProxyOutbound = landingProxy
        DataStore.frontProxy = if (frontProxy >= 0) 1 else 0
        DataStore.landingProxy = if (landingProxy >= 0) 1 else 0
    }

    fun ProxyGroup.serialize() {
        type = DataStore.groupType
        name = DataStore.groupName.takeIf { it.isNotEmpty() } ?:
                if (type == GroupType.SUBSCRIPTION) {
                    getString(R.string.subscription)
                } else {
                    getString(R.string.menu_group)
                }
        order = DataStore.groupOrder

        frontProxy = if (DataStore.frontProxy == 1) DataStore.frontProxyOutbound else -1
        landingProxy = if (DataStore.landingProxy == 1) DataStore.landingProxyOutbound else -1
        if (type == GroupType.SUBSCRIPTION) {
            subscription = SubscriptionBean().applyDefaultValues().apply {
                type = DataStore.subscriptionType
                link = DataStore.subscriptionLink
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
                lastUpdated = DataStore.subscriptionLastUpdated
                bytesUsed = DataStore.subscriptionBytesUsed
                bytesRemaining = DataStore.subscriptionBytesRemaining
                expiryDate = DataStore.subscriptionExpiryDate
                nameFilter = DataStore.subscriptionNameFilter
                nameFilter1 = DataStore.subscriptionNameFilter1
                httpHeaders = DataStore.subscriptionHTTPHeaders
                agePrivateKey = DataStore.subscriptionAgePrivateKey
            }
        } else {
            subscription = SubscriptionBean().applyDefaultValues()
        }
    }

    fun needSave(): Boolean {
        return dirty
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        if (DataStore.frontProxy == 1) {
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-s2-release/core/java/android/preference/ListPreference.java#167
            // If the summary has a {@linkplain java.lang.String#format String formatting} marker in it,
            // (i.e. "%s" or "%1$s"), then the current entry value will be substituted in its place.
            frontProxyPreference.setSummary(ProfileManager.getProfile(DataStore.frontProxyOutbound)?.displayName()?.replace("%", "%%"))
        } else {
            frontProxyPreference.setSummary(resources.getString(R.string.disable))
        }
        frontProxyPreference.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "1") {
                    selectProfileForAddFront.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                    )
                    false
                } else {
                    setSummary(resources.getString(R.string.disable))
                    true
                }
            }
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        if (DataStore.landingProxy == 1) {
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-s2-release/core/java/android/preference/ListPreference.java#167
            // If the summary has a {@linkplain java.lang.String#format String formatting} marker in it,
            // (i.e. "%s" or "%1$s"), then the current entry value will be substituted in its place.
            landingProxyPreference.setSummary(ProfileManager.getProfile(DataStore.landingProxyOutbound)?.displayName()?.replace("%", "%%"))
        } else {
            landingProxyPreference.setSummary(resources.getString(R.string.disable))
        }
        landingProxyPreference.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "1") {
                    selectProfileForAddLanding.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                    )
                    false
                } else {
                    setSummary(resources.getString(R.string.disable))
                    true
                }
            }
        }

        val groupType = findPreference<ListPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!
        val subscriptionType = findPreference<ListPreference>(Key.SUBSCRIPTION_TYPE)!!
        val httpHeaders = findPreference<EditTextPreference>(Key.SUBSCRIPTION_HTTP_HEADERS)!!.apply {
            dialogMessage = getString(R.string.format, "\nKey1: Value1\nKey2: Value2")
        }
        val agePrivateKey = findPreference<EditTextPreference>(Key.SUBSCRIPTION_AGE_PRIVATE_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
            httpHeaders.isVisible = isSubscription
            agePrivateKey.isVisible = isSubscription && (subscriptionType.value as String).toInt() == SubscriptionType.AGE
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        fun updateSubscriptionType(subscriptionType: Int = DataStore.subscriptionType) {
            agePrivateKey.isVisible = subscriptionType == SubscriptionType.AGE
        }
        updateSubscriptionType()
        subscriptionType.setOnPreferenceChangeListener { _, newValue ->
            updateSubscriptionType((newValue as String).toInt())
            true
        }

        val subscriptionAutoUpdate = findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay = findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!
        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            newValue.toIntOrNull() != null && newValue.toInt() >= 15
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val KEY_DIRTY = "dirty"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    ProxyGroup().init()
                } else {
                    val entity = SagerDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                    DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
                }
            }
        } else {
            savedInstanceState.getBoolean(KEY_DIRTY).let {
                dirty = it
                onBackPressedCallback.isEnabled = it
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DIRTY, dirty)
    }

    suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            GroupManager.createGroup(ProxyGroup().apply { serialize() })
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            GroupManager.updateGroup(entity.apply { serialize() })
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
                    .setTitle(R.string.delete_group_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            GroupManager.deleteGroup(DataStore.editingId)
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

        val activity: GroupSettingsActivity
            get() = requireActivity() as GroupSettingsActivity

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
        }
    }

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.frontProxyOutbound = profile.id
            onMainDispatcher {
                frontProxyPreference.value = "1"
                // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-s2-release/core/java/android/preference/ListPreference.java#167
                // If the summary has a {@linkplain java.lang.String#format String formatting} marker in it,
                // (i.e. "%s" or "%1$s"), then the current entry value will be substituted in its place.
                frontProxyPreference.setSummary(profile.displayName().replace("%", "%%"))
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.landingProxyOutbound = profile.id
            onMainDispatcher {
                landingProxyPreference.value = "1"
                // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-s2-release/core/java/android/preference/ListPreference.java#167
                // If the summary has a {@linkplain java.lang.String#format String formatting} marker in it,
                // (i.e. "%s" or "%1$s"), then the current entry value will be substituted in its place.
                landingProxyPreference.setSummary(profile.displayName().replace("%", "%%"))
            }
        }
    }

}