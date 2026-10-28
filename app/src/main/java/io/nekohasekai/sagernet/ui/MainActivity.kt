/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.text.util.Linkify
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.bottomappbar.BottomAppBar.FAB_ALIGNMENT_MODE_CENTER
import com.google.android.material.bottomappbar.BottomAppBar.FAB_ALIGNMENT_MODE_END
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.aidl.AppStats
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ProfileTestRunner
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Alerts
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.DiagnosticLog
import io.nekohasekai.sagernet.utils.PackageCache
import io.noties.markwon.Markwon
import libsagernetcore.Libsagernetcore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import java.util.Locale

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var releaseUpdateCheckScheduled = false

    val userInterface by lazy { GroupInterfaceAdapter(this) }

    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            displayFragmentWithId(R.id.nav_configuration)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.FLAVOR == "lite") {
            startActivity(Intent(this, LiteActivity::class.java).apply {
                action = intent?.action
                data = intent?.data
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
            finish()
            return
        }
        DiagnosticLog.critical(
            "ui",
            "MainActivity.onCreate savedState=${savedInstanceState != null} " +
                    "intent=${intent?.action.orEmpty()} ${DiagnosticLog.stateSummary()}"
        )

        binding = LayoutMainBinding.inflate(layoutInflater)
        when (DataStore.fabStyle) {
            FabStyle.SagerNet -> {
                binding.stats.fabAlignmentMode = FAB_ALIGNMENT_MODE_END
                binding.stats.fabCradleMargin = 0F
                binding.stats.fabCradleRoundedCornerRadius = 0F
                binding.stats.cradleVerticalOffset = dp2px(8).toFloat()
            }
            FabStyle.Shadowsocks -> {
                binding.stats.fabAlignmentMode = FAB_ALIGNMENT_MODE_CENTER
                binding.stats.fabCradleMargin = dp2px(5).toFloat()
                binding.stats.fabCradleRoundedCornerRadius = dp2px(6).toFloat()
                binding.stats.cradleVerticalOffset = 0F
            }
        }

        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black, R.style.Theme_SagerNet_LightBlack
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            ViewCompat.setOnApplyWindowInsetsListener(navigation) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    top = bars.top,
                    right = bars.right,
                    bottom = bars.bottom,
                )
                insets
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(navigation) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    top = bars.top,
                    left = bars.left,
                    bottom = bars.bottom,
                )
                insets
            }
        }

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }

        binding.fab.setOnClickListener {
            if (state.canStop) {
                DiagnosticLog.critical(
                    "ui",
                    "fab-stop-click state=$state ${DiagnosticLog.stateSummary()}"
                )
                SagerNet.stopService()
            } else {
                DiagnosticLog.critical(
                    "ui",
                    "fab-start-click state=$state selected=${DataStore.selectedProxy} " +
                            "autoEnabled=${DataStore.autoVpnEnabled} autoMode=${DataStore.autoVpnMode} " +
                            "${DiagnosticLog.stateSummary()}"
                )
                vpnStartRequestedFromUi = true
                connect.launch(null)
            }
        }
        binding.stats.setOnClickListener { if (state == BaseService.State.Connected) binding.stats.testConnection() }

        setContentView(binding.root)

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        if (!licenseAccepted()) {
            runOnMainDispatcher {
                AlertDialog.Builder(this@MainActivity).apply {
                    setTitle(R.string.license)
                    setView(
                        TextView(this@MainActivity).apply {
                            setPadding(dp2px(16))
                            text = getString(
                                if (Libsagernetcore.buildWithClash()) {
                                    R.string.license_gpl_v3_only
                                } else {
                                    R.string.license_gpl_v3_or_later
                                }
                            )
                            setTextIsSelectable(true)
                            Linkify.addLinks(this, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
                        }
                    )
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        DataStore.configurationStore.putBoolean(
                            if (Libsagernetcore.buildWithClash()) "gplv3OnlyAccepted"
                            else "gplv3OrLaterAccepted", true)
                        if (DataStore.configurationStore.getBoolean("permissionRequested") != true) {
                            DataStore.configurationStore.putBoolean("permissionRequested", true)
                            PackageCache.awaitLoadSync()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                                }
                            }
                        }
                        scheduleReleaseUpdateCheck()
                    }
                    setOnCancelListener { _ ->
                        DataStore.configurationStore.putBoolean(
                            if (Libsagernetcore.buildWithClash()) "gplv3OnlyAccepted"
                            else "gplv3OrLaterAccepted", true)
                        if (DataStore.configurationStore.getBoolean("permissionRequested") != true) {
                            DataStore.configurationStore.putBoolean("permissionRequested", true)
                            PackageCache.awaitLoadSync()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                                }
                            }
                        }
                        scheduleReleaseUpdateCheck()
                    }
                }.show()
            }
        }
    }

    private fun licenseAccepted(): Boolean {
        return DataStore.configurationStore.getBoolean(
            if (Libsagernetcore.buildWithClash()) "gplv3OnlyAccepted"
            else "gplv3OrLaterAccepted"
        ) == true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (isImportFileUri(uri)) {
                importProfileFile(uri)
            } else if ((uri.scheme?.lowercase() == "exclave" || uri.scheme?.lowercase() == "sn") && uri.host == "subscription") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    private fun isImportFileUri(uri: Uri): Boolean {
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("content", "file")
    }

    private fun profileImportKey(bean: AbstractBean): String? {
        return ProxyEntity().apply { putBean(bean) }.importKeyCache.takeIf { it.isNotBlank() }
    }

    private fun normalizeSubscriptionImportLink(link: String): String {
        val trimmed = link.trim()
        return runCatching {
            Uri.parse(trimmed).buildUpon().fragment(null).build().toString()
        }.getOrElse {
            trimmed.substringBefore("#")
        }
    }

    private suspend fun importProfileFile(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: error(getString(R.string.no_proxies_found_in_file))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        val normalizedText = normalizeImportText(text)
        val parsed = RawUpdater.parseRaw(text).orEmpty()
        val normalizedParsed = if (normalizedText != text) {
            RawUpdater.parseRaw(normalizedText).orEmpty()
        } else {
            emptyList()
        }
        val proxies = if (normalizedParsed.size > parsed.size) normalizedParsed else parsed
        val subscriptions = if (isJsonImportDocument(text)) {
            emptyList()
        } else {
            extractSubscriptionLinksForImport(text)
        }

        var addedProfiles = 0
        if (proxies.isNotEmpty()) {
            addedProfiles = finishImportProfilesFromFile(proxies)
        }
        val addedSubscriptions = if (subscriptions.isNotEmpty()) {
            finishImportSubscriptionsFromFile(subscriptions)
        } else {
            emptyList()
        }

        if (addedProfiles == 0 && addedSubscriptions.isEmpty()) {
            onMainDispatcher {
                snackbar(R.string.no_proxies_found_in_file).show()
            }
            return
        }

        onMainDispatcher {
            if (addedSubscriptions.isNotEmpty()) {
                displayFragmentWithId(R.id.nav_group)
                SubscriptionUpdateDialog.showManual(this@MainActivity, addedSubscriptions)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
                snackbar(resources.getQuantityString(R.plurals.added, addedProfiles, addedProfiles)).show()
            }
        }
    }

    private suspend fun finishImportProfilesFromFile(proxies: List<AbstractBean>): Int {
        val existingKeys = ProfileManager.importKeys().toMutableSet()
        val fresh = proxies.filter { bean ->
            val key = profileImportKey(bean)
            key == null || existingKeys.add(key)
        }
        if (fresh.isEmpty()) return 0
        val created = ProfileManager.createProfiles(DataStore.selectedGroupForImport(), fresh)
        return created.size
    }

    private suspend fun finishImportSubscriptionsFromFile(links: List<String>): List<ProxyGroup> {
        val existingGroups = SagerDatabase.groupDao.allGroups()
        val existingNames = existingGroups.mapTo(mutableSetOf()) { it.displayName() }
        val existingLinks = existingGroups.mapNotNullTo(mutableSetOf()) {
            it.subscription?.link?.takeIf(String::isNotBlank)?.let(::normalizeSubscriptionImportLink)
        }
        val groups = links.mapNotNull { link ->
            val normalized = normalizeSubscriptionImportLink(link)
            if (!existingLinks.add(normalized)) return@mapNotNull null
            ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                name = SubscriptionNames.next(existingNames)
                subscription = SubscriptionBean().apply {
                    this.link = link
                }
            }
        }
        return groups.map { GroupManager.createGroup(it) }
    }

    fun urlTest(): Int {
        if (state != BaseService.State.Connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // human-readable cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")

            val type = uri.getQueryParameter("type")
            when (type?.lowercase()) {
                "sip008" -> {
                    subscription.type = SubscriptionType.SIP008
                }
            }

        } else {
            // private binary format derived from SagerNet
            if (uri.scheme?.lowercase() != "exclave") {
                // do not be compatible with the private binary format from other software
                return
            }
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(data).zlibDecompress()
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrEmpty() } ?: group.subscription?.link
        if (name.isNullOrEmpty()) return

        group.name = group.name.takeIf { !it.isNullOrEmpty() }
            ?: SubscriptionNames.next()

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        val group = GroupManager.createGroup(subscription)
        onMainDispatcher {
            SubscriptionUpdateDialog.showManual(this@MainActivity, listOf(group))
        }
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseShareLinks(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        if (pluginName.startsWith("shadowsocks-")) pluginName.substringAfter("shadowsocks-") else pluginName
        val pluginEntity = PluginEntry.find(pluginName)
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, getString(pluginEntity.nameId)
                )
            ).show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    fun displayFragment(fragment: ToolbarFragment) {
        if (fragment !is LogcatFragment) {
            binding.fab.show()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
                connection.bandwidthTimeout = connection.bandwidthTimeout
            }
            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> {
                displayFragment(TrafficFragment())
                connection.trafficTimeout = connection.trafficTimeout
            }
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_about -> displayFragment(AboutFragment())
            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    fun stopVpnAndStartProfileTest() {
        profileTestAfterVpnStopPending = true
        SagerNet.stopService()
        displayFragmentWithId(R.id.nav_configuration)
        if (state == BaseService.State.Stopped || state == BaseService.State.Idle) {
            scheduleProfileTestAfterVpnStop()
        }
    }

    private fun scheduleProfileTestAfterVpnStop() {
        if (!profileTestAfterVpnStopPending) return
        profileTestAfterVpnStopPending = false
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!ConfigurationFragment.startProfileTestFromActiveFragment()) {
                displayFragmentWithId(R.id.nav_configuration)
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        ConfigurationFragment.startProfileTestFromActiveFragment()
                    }
                }, 500L)
            }
        }, 5_000L)
    }

    fun ruleCreated() {
        navigation.menu.findItem(R.id.nav_route).isChecked = true
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, RouteFragment())
            .commitAllowingStateLoss()
        if (SagerNet.started) {
            snackbar(getString(R.string.restart)).setAction(R.string.apply) {
                SagerNet.reloadService()
            }.show()
        }
    }

    var state = BaseService.State.Idle
    private var vpnStartRequestedFromUi = false
    private var profileTestAfterVpnStopPending = false

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        val previousState = this.state
        DiagnosticLog.critical(
            "ui",
            "MainActivity.changeState from=$previousState to=$state msg=${msg.orEmpty()} " +
                    "animate=$animate ${DiagnosticLog.stateSummary()}"
        )
        val started = state == BaseService.State.Connected

        if (!started) {
            statsUpdated(emptyList())
        }

        binding.fab.changeState(state, this.state, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(msg).show()
        this.state = state
        if (state == BaseService.State.Connected &&
            (vpnStartRequestedFromUi || previousState == BaseService.State.Connecting)
        ) {
            vpnStartRequestedFromUi = false
        } else if (state == BaseService.State.Stopped || state == BaseService.State.Idle) {
            vpnStartRequestedFromUi = false
            scheduleProfileTestAfterVpnStop()
        }

        when (state) {
            BaseService.State.Connected, BaseService.State.Stopped -> {
                statsUpdated(emptyList())
                runOnDefaultDispatcher {
                    // refresh view
                    ProfileManager.postUpdate(DataStore.currentProfile)
                }
            }
            else -> {}
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            anchorView = binding.fab
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        DiagnosticLog.critical(
            "ui",
            "service-callback-stateChanged state=$state profile=${profileName.orEmpty()} msg=${msg.orEmpty()}"
        )
        changeState(state, msg, true)
    }

    override fun statsUpdated(stats: List<AppStats>) {
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? TrafficFragment)?.emitStats(
            stats
        )
    }

    override fun routeAlert(type: Int, routeName: String) {
        val markwon = Markwon.create(this)
        when (type) {
            Alerts.ROUTE_ALERT_NOT_VPN -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_vpn, routeName))
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            Alerts.ROUTE_ALERT_NEED_COARSE_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_coarse_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_NEED_FINE_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_fine_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_NEED_BACKGROUND_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_background_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_LOCATION_DISABLED -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_location_enabled, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.location_disabled)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_ALL_PACKAGES_UNINSTALLED -> {
                val message = markwon.toMarkdown("Ignored $routeName because all packages are uninstalled.")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val connection = SagerConnection(true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(try {
        BaseService.State.entries[service.state].also {
            SagerNet.started = it.canStop
            DiagnosticLog.critical("ui", "service-connected state=$it ${DiagnosticLog.stateSummary()}")
        }
    } catch (e: RemoteException) {
        DiagnosticLog.warn("ui", "service-connected-state-read-failed", e)
        BaseService.State.Idle
    })

    override fun onServiceDisconnected() {
        DiagnosticLog.critical("ui", "service-disconnected ${DiagnosticLog.stateSummary()}")
        changeState(BaseService.State.Idle)
    }
    override fun onBinderDied() {
        DiagnosticLog.critical("ui", "service-binder-died ${DiagnosticLog.stateSummary()}")
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats, isCurrent: Boolean) {
        if (profileId == 0L) return

        if (isCurrent) binding.stats.updateTraffic(
            stats.txRateProxy, stats.rxRateProxy
        )

        runOnDefaultDispatcher {
            ProfileManager.postTrafficUpdated(profileId, stats)
        }
    }

    override fun profilePersisted(profileId: Long) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(profileId)
        }
    }

    override fun observatoryResultsUpdated(groupId: Long) {
        runOnDefaultDispatcher {
            GroupManager.postReload(groupId)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (state.canStop) {
                    snackbar(getString(R.string.restart)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticLog.critical(
            "ui",
            "MainActivity.onStart state=$state profileTest=${ProfileTestRunner.diagnosticStateSummary()} " +
                    "${DiagnosticLog.stateSummary()}"
        )
        connection.bandwidthTimeout = 1000
        GroupManager.userInterface = userInterface
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.critical(
            "ui",
            "MainActivity.onResume state=$state profileTest=${ProfileTestRunner.diagnosticStateSummary()} " +
                    "${DiagnosticLog.stateSummary()}"
        )
        scheduleReleaseUpdateCheck()
    }

    private fun scheduleReleaseUpdateCheck() {
        if (releaseUpdateCheckScheduled || !licenseAccepted()) return
        releaseUpdateCheckScheduled = true
        lifecycleScope.launch {
            delay(1200)
            if (isFinishing || isDestroyed) return@launch
            val update = ReleaseUpdateChecker.findAvailableUpdate() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.app_update_available_title, update.version))
                .setMessage(
                    getString(
                        R.string.app_update_available_message,
                        BuildConfig.VERSION_NAME,
                        update.version,
                        getString(if (update.prerelease) R.string.app_update_prerelease else R.string.app_update_stable)
                    )
                )
                .setNegativeButton(R.string.app_update_later, null)
                .setPositiveButton(R.string.app_update_download) { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }.onFailure {
                        snackbar(R.string.action_export_err).show()
                    }
                }
                .show()
        }
    }

    override fun onPause() {
        DiagnosticLog.event("ui", "MainActivity.onPause state=$state ${DiagnosticLog.stateSummary()}")
        super.onPause()
    }

    override fun onStop() {
        DiagnosticLog.event("ui", "MainActivity.onStop state=$state ${DiagnosticLog.stateSummary()}")
        connection.bandwidthTimeout = 0
        GroupManager.userInterface = null
        super.onStop()
    }

    override fun onDestroy() {
        DiagnosticLog.critical("ui", "MainActivity.onDestroy state=$state ${DiagnosticLog.stateSummary()}")
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
