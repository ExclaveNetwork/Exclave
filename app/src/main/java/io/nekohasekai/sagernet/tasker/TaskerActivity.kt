/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.tasker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.TaskerProfilePreference

class TaskerActivity : ThemedActivity(R.layout.layout_config_settings),
    OnPreferenceDataStoreChangeListener {

    companion object {
        const val KEY_DIRTY = "dirty"

        private val componentName by lazy { ComponentName(app, TaskerActivity::class.java) }
        var enabled: Boolean
            get() = when (app.packageManager.getComponentEnabledSetting(componentName)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> {
                    app.packageManager.getReceiverInfo(componentName, 0).enabled
                }
                else -> true
            }
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName,
                if (value) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
    }

    val settings by lazy { TaskerBundle.fromIntent(intent) }

    var dirty = false
    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@TaskerActivity)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DataStore.disableTaskerIntegration) {
            enabled = false
            finish()
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings)) { v, insets ->
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
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.tasker_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, MyPreferenceFragmentCompat())
            .commit()

        DataStore.profileCacheStore.registerChangeListener(this)

        savedInstanceState?.getBoolean(KEY_DIRTY)?.let {
            dirty = it
            onBackPressedCallback.isEnabled = it
        }
    }

    lateinit var profile: TaskerProfilePreference
    lateinit var action: ListPreference

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.tasker_preferences)
        profile = findPreference(Key.TASKER_PROFILE)!!
        profile.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == "1") {
                selectProfileForTasker.launch(Intent(
                    this@TaskerActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, settings.profileId)
                })
            }
            true
        }
        action = findPreference(Key.TASKER_ACTION)!!
        profile.isEnabled = action.value == "0"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DIRTY, dirty)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            dirty = true
            onBackPressedCallback.isEnabled = true
        }
        when (key) {
            Key.TASKER_ACTION -> {
                settings.action = DataStore.taskerAction
                profile.isEnabled = settings.action == TaskerBundle.ACTION_START
            }
            Key.TASKER_PROFILE -> {
                if (DataStore.taskerProfile == 0) DataStore.taskerProfileId = -1L
            }
            Key.TASKER_PROFILE_ID -> {
                settings.profileId = DataStore.taskerProfileId
                if (settings.profileId > 0L) runOnMainDispatcher {
                    profile.summaryProvider = profile.summaryProvider
                }
            }
        }
    }

    val selectProfileForTasker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) {
            val profileId = data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, -1L)
            runOnDefaultDispatcher {
                DataStore.taskerProfileId = profileId
            }
        }
    }

    fun saveAndExit() {
        setResult(RESULT_OK, settings.toIntent())
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_apply_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
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

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        val activity: TaskerActivity
            get() = requireActivity() as TaskerActivity

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

}