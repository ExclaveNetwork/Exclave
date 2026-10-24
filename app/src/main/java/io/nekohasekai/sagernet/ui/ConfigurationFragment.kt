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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.AutoVpnManager
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ProfileTestRunner
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.exportBackup
import io.nekohasekai.sagernet.fmt.wireguard.toConf
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.Protocols
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.profile.*
import io.nekohasekai.sagernet.utils.DiagnosticLog
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libsagernetcore.Libsagernetcore
import java.util.*
import java.util.zip.ZipInputStream
import androidx.core.net.toUri
import io.nekohasekai.sagernet.database.SagerDatabase

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false,
    val selectedItem: ProxyEntity? = null,
    val titleRes: Int = 0,
    val showAllGroupInSelect: Boolean = false
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    ProfileTestRunner.Listener,
    AutoVpnManager.Listener {

    val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            searchView?.onActionViewCollapsed()
            searchView?.clearFocus()
        }
    }

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    lateinit var profileTestStatusBar: View
    lateinit var profileTestProgress: LinearProgressIndicator
    lateinit var profileTestProgressText: TextView
    lateinit var profileTestWorkingText: TextView
    var searchView: SearchView? = null
    val selectedGroup: ProxyGroup
        get() {
            if (!::adapter.isInitialized || adapter.groupList.isEmpty()) return ProxyGroup()
            if (::groupPager.isInitialized) {
                adapter.groupList.getOrNull(groupPager.currentItem)?.let { return it }
            }
            if (::tabLayout.isInitialized && !tabLayout.isGone) {
                adapter.groupList.getOrNull(tabLayout.selectedTabPosition)?.let { return it }
            }
            return adapter.groupList[0]
        }
    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }
    private var profileTestResumeCheckJob: Job? = null
    private var profileTestSortJob: Job? = null
    private var profileTestStatusPollJob: Job? = null
    private var observedAutoTestResultsResetGeneration = AutoVpnManager.testResultsResetGeneration
    private var autoStatusReceiver: BroadcastReceiver? = null

    companion object {
        private const val ALL_GROUP_ID = 0L
        private const val PROFILE_PAGE_SIZE = 300
        private const val PROFILE_ROW_UPDATE_BATCH_MS = 250L
        private const val DB_QUERY_CHUNK_SIZE = 500
        private var activeProfileTestFragment: ConfigurationFragment? = null

        private fun refreshActiveProfileTestUi() {
            activeProfileTestFragment?.let {
                if (!it.isAdded || it.view == null) return
                it.updateProfileTestStatusBar()
                it.updateProfileTestMenu()
            }
        }

        fun reloadActiveProfileListsWithCurrentOrder() {
            activeProfileTestFragment?.reloadProfileListsWithCurrentOrder()
        }

        fun startProfileTestFromActiveFragment(): Boolean {
            val fragment = activeProfileTestFragment ?: return false
            if (!fragment.isAdded || fragment.view == null || fragment.select) return false
            fragment.urlTest()
            return true
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }

        override fun onPageSelected(position: Int) {
            if (adapter.groupList.size <= position) return
            val groupId = adapter.groupList[position].id
            DataStore.selectedGroup = groupId
            groupPager.post {
                if (!isAdded || view == null) return@post
                (childFragmentManager.findFragmentByTag("f$groupId") as? GroupFragment)?.let {
                    it.scrollToTopIfReady()
                    it.reloadProfilesIfReady(forceScrollTop = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!select) {
            activeProfileTestFragment = this
            ProfileTestRunner.addListener(this)
            ProfileTestRunner.reconcileWorkStateAsync()
            AutoVpnManager.addListener(this)
            if (autoStatusReceiver == null) {
                autoStatusReceiver = broadcastReceiver { _, intent ->
                    if (intent.action == Action.AUTO_STATUS_CHANGED) {
                        AutoVpnManager.syncVisibleStateFromStore()
                        ProfileTestRunner.syncFromStore()
                        onAutoVpnStateChanged()
                    }
                }
                val filter = IntentFilter(Action.AUTO_STATUS_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requireContext().registerReceiver(
                        autoStatusReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    requireContext().registerReceiver(autoStatusReceiver, filter)
                }
            }
        }
        profileTestStatusBar = view.findViewById(R.id.profile_test_status_bar)
        profileTestProgress = view.findViewById(R.id.profile_test_progress)
        profileTestProgressText = view.findViewById(R.id.profile_test_progress_text)
        profileTestWorkingText = view.findViewById(R.id.profile_test_working)
        updateProfileTestStatusBar()
        ViewCompat.setOnApplyWindowInsetsListener(profileTestStatusBar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom + dp2px(96),
            )
            insets
        }
        ViewCompat.requestApplyInsets(profileTestStatusBar)

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
            highlightProfileMenuActions()
            updateProfileTestMenu()
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.group_tab)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
            )
            insets
        }

        searchView = toolbar.findViewById(R.id.action_search)
        searchView?.maxWidth = Int.MAX_VALUE
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(query: String?): Boolean {
                query?.let {
                    try {
                        val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
                        fragment?.adapter?.filter(query)
                    } catch (_: Exception) {}
                }
                return false
            }
        })
        searchView?.setOnQueryTextFocusChangeListener { _, hasFocus ->
            onBackPressedCallback.isEnabled = hasFocus
        }
        searchView?.let {
            // override onBackPressedCallback of MainActivity
            (requireActivity() as? MainActivity)?.onBackPressedDispatcher?.addCallback(this, onBackPressedCallback)
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                searchView?.onActionViewCollapsed()
                searchView?.clearFocus()
                refreshProfileTestResumeAvailability()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
                fragment?.adapter?.filter("")
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}

        })

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = groupTitle(adapter.groupList[position])
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        toolbar.setOnClickListener {

            val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex = fragment.adapter.configurationIdList.indexOf(
                    selectedProxy
                )
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        toolbar.setOnLongClickListener {
            val selectedProxy = selectedItem
                ?: SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                ?: return@setOnLongClickListener true
            val groupIndex = adapter.groupList.indexOfFirst {
                it.id == selectedProxy.groupId
            }
            if (groupIndex < 0) return@setOnLongClickListener true
            DataStore.selectedGroup = selectedProxy.groupId
            groupPager.currentItem = groupIndex

            val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
            if (fragment != null) {
                val selectedProfileIndex = fragment.adapter.configurationIdList.indexOfFirst {
                    it == selectedProxy.id
                }
                if (selectedProfileIndex > 0) {
                    fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                }
            }

            true
        }

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.critical(
            "configuration-ui",
            "onResume select=$select group=${runCatching { selectedGroup.id }.getOrDefault(-1L)} " +
                    "profileTest=${ProfileTestRunner.diagnosticStateSummary()} ${DiagnosticLog.stateSummary()}"
        )
        if (!select) {
            ProfileTestRunner.syncFromStore()
            ProfileTestRunner.reconcileWorkStateAsync()
            AutoVpnManager.syncVisibleStateFromStore()
            updateProfileTestStatusBar()
            updateProfileTestMenu()
            updateProfileTestStatusPollJob()
            updateProfileTestSortJob()
            if (::adapter.isInitialized) {
                if (ProfileTestRunner.hasVisibleState()) {
                    adapter.refreshLoadedProfileTestResults()
                }
                adapter.refreshAutoRows()
            }
        }
        DiagnosticLog.critical(
            "configuration-ui",
            "onResume-complete select=$select statusBarReady=${::profileTestStatusBar.isInitialized} " +
                    "profileTest=${ProfileTestRunner.diagnosticStateSummary()} ${DiagnosticLog.stateSummary()}"
        )
    }

    override fun onStop() {
        DiagnosticLog.event(
            "configuration-ui",
            "onStop select=$select profileTest=${ProfileTestRunner.diagnosticStateSummary()} " +
                    "${DiagnosticLog.stateSummary()}"
        )
        profileTestStatusPollJob?.cancel()
        profileTestStatusPollJob = null
        profileTestSortJob?.cancel()
        profileTestSortJob = null
        super.onStop()
    }

    override fun onDestroy() {
        DiagnosticLog.critical(
            "configuration-ui",
            "onDestroy select=$select profileTest=${ProfileTestRunner.diagnosticStateSummary()} " +
                    "${DiagnosticLog.stateSummary()}"
        )
        profileTestResumeCheckJob?.cancel()
        profileTestStatusPollJob?.cancel()
        profileTestSortJob?.cancel()
        if (activeProfileTestFragment === this) {
            activeProfileTestFragment = null
        }
        if (!select) {
            ProfileTestRunner.removeListener(this)
            AutoVpnManager.removeListener(this)
        }
        autoStatusReceiver?.let {
            runCatching {
                context?.unregisterReceiver(it)
            }
            autoStatusReceiver = null
        }
        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private data class ImportDuplicateSplit<T>(
        val fresh: List<T>,
        val duplicates: List<T>,
    )

    private fun normalizeImportLink(link: String): String {
        val trimmed = link.trim()
        return runCatching {
            trimmed.toUri().buildUpon().fragment(null).build().toString()
        }.getOrElse {
            trimmed.substringBefore("#")
        }
    }

    private fun profileImportKey(bean: AbstractBean): String? {
        return ProxyEntity().apply { putBean(bean) }.importKeyCache.takeIf { it.isNotBlank() }
    }

    private fun profileImportKey(entity: ProxyEntity): String? {
        return entity.importKeyCache.takeIf { it.isNotBlank() }
    }

    private fun <T> splitImportDuplicates(
        items: List<T>,
        existingKeys: Set<String>,
        keyOf: (T) -> String?,
    ): ImportDuplicateSplit<T> {
        val seen = existingKeys.toMutableSet()
        val fresh = ArrayList<T>()
        val duplicates = ArrayList<T>()
        for (item in items) {
            val key = keyOf(item)
            if (key == null || seen.add(key)) {
                fresh.add(item)
            } else {
                duplicates.add(item)
            }
        }
        return ImportDuplicateSplit(fresh, duplicates)
    }

    private suspend fun showImportDuplicatesDialog(
        freshCount: Int,
        duplicateCount: Int,
        onSkipDuplicates: () -> Unit,
        onAddDuplicates: () -> Unit,
    ) {
        onMainDispatcher {
            if (!isAdded || view == null) return@onMainDispatcher
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_duplicates_title)
                .setMessage(
                    getString(
                        R.string.import_duplicates_message,
                        duplicateCount,
                        freshCount
                    )
                )
                .setNegativeButton(R.string.import_duplicates_skip) { _, _ ->
                    onSkipDuplicates()
                }
                .setPositiveButton(R.string.import_duplicates_add_anyway) { _, _ ->
                    onAddDuplicates()
                }
                .show()
        }
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        var fileText = ""
        if (file != null) runOnDefaultDispatcher {
            try {
                val fileName = requireContext().contentResolver.query(file, null, null, null, null)
                    ?.use { cursor ->
                        cursor.moveToFirst()
                        cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            .let(cursor::getString)
                    }

                val proxies = mutableListOf<AbstractBean>()
                if (fileName != null && fileName.endsWith(".zip")) {
                    // try parse wireguard zip
                    val zip = ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        fileText = zip.bufferedReader().readText()
                        RawUpdater.parseRaw(fileText)?.let { pl -> proxies.addAll(pl) }
                        zip.closeEntry()
                    }
                    runCatching {
                        zip.close()
                    }
                } else {
                    fileText = requireContext().contentResolver.openInputStream(file)!!.use {
                        it.bufferedReader().readText()
                    }
                    RawUpdater.parseRaw(fileText)?.let { pl -> proxies.addAll(pl) }
                }

                importParsedContent(fileText, proxies, R.string.no_proxies_found_in_file)
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    private suspend fun importParsedContent(
        text: String,
        parsedProxies: List<AbstractBean>?,
        noFoundRes: Int
    ) {
        val normalizedText = normalizeImportText(text)
        val parsed = parsedProxies.orEmpty()
        val normalizedProxies = if (parsedProxies == null || normalizedText != text) {
            RawUpdater.parseRaw(normalizedText).orEmpty()
        } else {
            emptyList()
        }
        val proxies = if (normalizedProxies.size > parsed.size) normalizedProxies else parsed
        val subscriptions = if (proxies.isEmpty() && isJsonImportDocument(text)) {
            emptyList()
        } else {
            parseSubscriptionImports(text)
        }
        if (proxies.isNotEmpty()) {
            import(proxies)
        }

        if (subscriptions.isNotEmpty()) {
            importSubscriptions(subscriptions)
        }

        if (proxies.isNotEmpty() || subscriptions.isNotEmpty()) {
            return
        }

        val singleLine = !text.contains("\n") && !text.contains("\r")
        if (singleLine && text.startsWith("exclave://", ignoreCase = true)
            && text.substring("exclave://".length).startsWith("subscription?", ignoreCase = true)) {
            onMainDispatcher {
                (requireActivity() as? MainActivity)?.importSubscription(text.toUri())
            }
        } else if (singleLine && isHTTPorHTTPSURL(text)) {
            val builder = Libsagernetcore.newURL("exclave").apply {
                host = "subscription"
            }
            builder.addQueryParameter("url", text)
            onMainDispatcher {
                (requireActivity() as? MainActivity)?.importSubscription(builder.string.toUri())
            }
        } else onMainDispatcher {
            snackbar(getString(noFoundRes)).show()
        }
    }

    suspend fun import(proxies: List<AbstractBean>) {
        val existingKeys = ProfileManager.importKeys()
        val split = splitImportDuplicates(proxies, existingKeys, ::profileImportKey)
        if (split.duplicates.isNotEmpty()) {
            showImportDuplicatesDialog(
                freshCount = split.fresh.size,
                duplicateCount = split.duplicates.size,
                onSkipDuplicates = {
                    if (split.fresh.isEmpty()) {
                        snackbar(R.string.import_duplicates_skipped).show()
                    } else {
                        runOnDefaultDispatcher {
                            confirmImport(split.fresh)
                        }
                    }
                },
                onAddDuplicates = {
                    runOnDefaultDispatcher {
                        confirmImport(split.fresh + split.duplicates)
                    }
                }
            )
            return
        }

        confirmImport(proxies)
    }

    private suspend fun confirmImport(proxies: List<AbstractBean>) {
        if (proxies.isEmpty()) {
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                snackbar(R.string.import_duplicates_skipped).show()
            }
            return
        }
        if (proxies.size <= 32) {
            finishImport(proxies)
        } else {
            val name = SagerDatabase.groupDao.getById(DataStore.selectedGroupForImport())!!.displayName()
            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.profile_import)
                    .setMessage(resources.getQuantityString(R.plurals.profile_multi_import_message, proxies.size, proxies.size, name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            finishImport(proxies)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private suspend fun finishImport(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        val targetIndex = if (::adapter.isInitialized) {
            adapter.groupList.indexOfFirst { it.id == targetId }
        } else {
            -1
        }

        ProfileManager.createProfiles(targetId, proxies)
        SagerDatabase.groupDao.getById(targetId)?.let { GroupManager.updateGroup(it) }

        onMainDispatcher {
            if (!isAdded || view == null || !::adapter.isInitialized) return@onMainDispatcher
            if (adapter.groupList.isEmpty() || selectedGroup.id != targetId) {
                if (targetIndex != -1) {
                    tabLayout.getTabAt(targetIndex)?.select()
                } else {
                    DataStore.selectedGroup = targetId
                    adapter.reload()
                }
            }

            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    private fun parseSubscriptionImports(text: String): List<ProxyGroup> {
        val items = extractSubscriptionLinksForImport(text)
        if (items.isEmpty()) return emptyList()
        val groups = ArrayList<ProxyGroup>()
        val existingNames = SagerDatabase.groupDao.allGroups()
            .mapTo(mutableSetOf()) { it.displayName() }
        for (item in items) {
            if (!isHTTPorHTTPSURL(item)) continue
            val uri = item.toUri()
            val name = uri.fragment?.takeIf { it.isNotBlank() }
            val groupName = name ?: SubscriptionNames.next(existingNames)
            if (name != null) existingNames += name
            val cleanLink = uri.buildUpon().fragment(null).build().toString()
            groups.add(ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                this.name = groupName
                subscription = SubscriptionBean().applyDefaultValues().apply {
                    type = SubscriptionType.RAW
                    link = cleanLink
                    deduplication = DataStore.subscriptionRemoveDuplicates
                    autoUpdate = true
                    autoUpdateDelay = DataStore.subscriptionGlobalAutoUpdateDelay
                }
            })
        }
        return groups
    }

    private suspend fun importSubscriptions(subscriptions: List<ProxyGroup>) {
        val existingKeys = SagerDatabase.groupDao.subscriptions()
            .mapNotNullTo(mutableSetOf()) { group ->
                group.subscription?.link?.takeIf { it.isNotBlank() }?.let(::normalizeImportLink)
            }
        val split = splitImportDuplicates(
            subscriptions,
            existingKeys
        ) { group ->
            group.subscription?.link?.takeIf { it.isNotBlank() }?.let(::normalizeImportLink)
        }

        if (split.duplicates.isNotEmpty()) {
            showImportDuplicatesDialog(
                freshCount = split.fresh.size,
                duplicateCount = split.duplicates.size,
                onSkipDuplicates = {
                    if (split.fresh.isEmpty()) {
                        snackbar(R.string.import_duplicates_skipped).show()
                    } else {
                        runOnDefaultDispatcher {
                            finishImportSubscriptions(split.fresh)
                        }
                    }
                },
                onAddDuplicates = {
                    runOnDefaultDispatcher {
                        finishImportSubscriptions(split.fresh + split.duplicates)
                    }
                }
            )
            return
        }

        finishImportSubscriptions(subscriptions)
    }

    private suspend fun finishImportSubscriptions(subscriptions: List<ProxyGroup>) {
        if (subscriptions.isEmpty()) {
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                snackbar(R.string.import_duplicates_skipped).show()
            }
            return
        }
        val groups = subscriptions.map { GroupManager.createGroup(it) }
        onMainDispatcher {
            val mainActivity = requireActivity() as? MainActivity
            mainActivity?.displayFragmentWithId(R.id.nav_group)
            snackbar(
                resources.getQuantityString(
                    R.plurals.added,
                    groups.size,
                    groups.size
                )
            ).show()
            mainActivity?.let {
                SubscriptionUpdateDialog.showManual(it, groups)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_auto_switch_profile -> {
                snackbar(R.string.auto_vpn_switch_requested).show()
                AutoVpnManager.switchNowFromApp()
            }
            R.id.action_auto_switch_gemini_soft -> {
                snackbar(R.string.auto_vpn_switch_requested).show()
                AutoVpnManager.switchGeminiSoftFromApp()
            }
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }
            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else {
                    runOnDefaultDispatcher {
                        try {
                            importParsedContent(text, null, R.string.no_proxies_found_in_clipboard)
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
            }
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }
            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }
            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }
            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }
            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }
            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }
            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VLESSSettingsActivity::class.java))
            }
            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }
            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }
            R.id.action_new_hysteria2 -> {
                startActivity(Intent(requireActivity(), Hysteria2SettingsActivity::class.java))
            }
            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }
            R.id.action_new_tuic5 -> {
                startActivity(Intent(requireActivity(), Tuic5SettingsActivity::class.java))
            }
            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }
            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }
            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }
            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }
            R.id.action_new_http3 -> {
                startActivity(Intent(requireActivity(), Http3SettingsActivity::class.java))
            }
            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }
            R.id.action_new_shadowquic -> {
                startActivity(Intent(requireActivity(), ShadowQUICSettingsActivity::class.java))
            }
            R.id.action_new_trusttunnel -> {
                startActivity(Intent(requireActivity(), TrustTunnelSettingsActivity::class.java))
            }
            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingsActivity::class.java))
            }
            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }
            R.id.action_new_balancer -> {
                startActivity(Intent(requireActivity(), BalancerSettingsActivity::class.java))
            }
            R.id.action_clear_traffic_statistics -> {
                val currentGroup = selectedGroup
                runOnDefaultDispatcher {
                    val profiles = profilesForSelectedScope(currentGroup)
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        SagerDatabase.proxyDao.updateProxy(toClear)
                        adapter.reloadProfileListsWithCurrentOrder()
                    }
                }
            }
            R.id.action_connection_test_clear_results -> {
                if (!isProfileTestRunning()) {
                    ProfileTestRunner.clearState()
                    updateProfileTestStatusBar(show = false)
                    updateProfileTestMenu()
                }
                val currentGroup = selectedGroup
                runOnDefaultDispatcher {
                    val profiles = profilesForSelectedScope(currentGroup)
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        SagerDatabase.proxyDao.updateProxy(toClear)
                        adapter.reloadProfileListsWithCurrentOrder()
                    }
                }
            }
            R.id.action_remove_duplicate -> {
                val currentGroup = selectedGroup
                runOnDefaultDispatcher {
                    val profiles = profilesForSelectedScope(currentGroup)
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (p in profiles) {
                        val proxy = Protocols.Deduplication(p.requireBean(), p.displayType())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += p
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_multi_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                        adapter.reloadProfileListsWithCurrentOrder()
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }
            R.id.action_connection_test_delete_unavailable -> {
                val currentGroup = selectedGroup
                runOnDefaultDispatcher {
                    val profiles = profilesForSelectedScope(currentGroup)
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != -1 && profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_multi_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                        adapter.reloadProfileListsWithCurrentOrder()
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }
            R.id.action_connection_url_test -> {
                DiagnosticLog.critical(
                    "configuration-ui",
                    "menu-url-test-click group=${selectedGroup.id} ${DiagnosticLog.stateSummary()}"
                )
                urlTest()
            }
            R.id.action_connection_test_stop -> {
                DiagnosticLog.critical(
                    "configuration-ui",
                    "menu-test-stop-click profileRunning=${ProfileTestRunner.isRunning()} " +
                            "autoRunning=${AutoVpnManager.isAutoRunning()} ${DiagnosticLog.stateSummary()}"
                )
                if (ProfileTestRunner.isRunning() && !AutoVpnManager.isAutoRunning()) {
                    stopProfileTest()
                } else {
                    AutoVpnManager.stopScanFromApp()
                }
            }
            R.id.action_connection_test_resume -> {
                val canResumeProfileTest = ProfileTestRunner.hasPendingResume()
                DiagnosticLog.critical(
                    "configuration-ui",
                    "menu-test-resume-click canResumeProfileTest=$canResumeProfileTest " +
                            "autoHasProgress=${AutoVpnManager.hasProgressState()} " +
                            "autoRunning=${AutoVpnManager.isAutoRunning()} ${DiagnosticLog.stateSummary()}"
                )
                if (canResumeProfileTest) {
                    urlTest(resume = true)
                } else if (AutoVpnManager.hasProgressState() && !AutoVpnManager.isAutoRunning()) {
                    AutoVpnManager.resumeScanFromApp()
                } else {
                    urlTest(resume = true)
                }
            }
            R.id.action_connection_test_gemini_filter -> {
                DataStore.manualGeminiTestFilterEnabled = !DataStore.manualGeminiTestFilterEnabled
                item.isChecked = DataStore.manualGeminiTestFilterEnabled
                snackbar(
                    if (DataStore.manualGeminiTestFilterEnabled) {
                        R.string.connection_test_gemini_filter_enabled
                    } else {
                        R.string.connection_test_gemini_filter_disabled
                    }
                ).show()
            }
            R.id.action_group_sort_by_percent -> {
                runOnDefaultDispatcher {
                    GroupManager.sortGroupsByWorkingPercent()
                    adapter.reload()
                }
            }
        }
        return true
    }

    private fun ProxyEntity.useBrowserForwarder(): Boolean {
        return runCatching {
            ProfileTestRunner.useBrowserForwarder(this)
        }.onFailure {
            Logs.w(it)
        }.getOrDefault(false)
    }

    private fun isProfileTestRunning(): Boolean {
        ProfileTestRunner.syncFromStore()
        return ProfileTestRunner.isRunning()
    }

    private fun isProfileTestRefreshDeferred(): Boolean {
        ProfileTestRunner.syncFromStore()
        return ProfileTestRunner.refreshDeferred
    }

    private fun isUiActiveForProfileTest(): Boolean {
        return isAdded &&
                view != null &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.STARTED
                )
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) requireContext().getColour(typedValue.resourceId) else typedValue.data
    }

    private fun highlightedMenuTitle(titleRes: Int): CharSequence {
        val title = "  ${getString(titleRes)}  "
        val background = resolveThemeColor(android.R.attr.colorAccent)
        val luminance = (Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114) / 1000
        val foreground = if (luminance > 160) Color.BLACK else Color.WHITE
        return SpannableString(title).apply {
            setSpan(BackgroundColorSpan(background), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(foreground), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun highlightProfileMenuActions() {
        val primary = emptyMap<Int, Int>()

        fun apply(menu: Menu) {
            for (index in 0 until menu.size()) {
                val item = menu.getItem(index)
                primary[item.itemId]?.let { item.title = highlightedMenuTitle(it) }
                item.subMenu?.let(::apply)
            }
        }

        apply(toolbar.menu)
    }

    private fun updateProfileTestMenu() {
        AutoVpnManager.syncVisibleStateFromStore()
        ProfileTestRunner.syncFromStore()
        val menu = toolbar.menu ?: return
        menu.findItem(R.id.action_connection_test_stop)?.isEnabled =
            isProfileTestRunning() || AutoVpnManager.isAutoRunning()
        val profileTestRunning = ProfileTestRunner.isRunning()
        val canResumeProfileTest = !profileTestRunning && ProfileTestRunner.hasPendingResume()
        menu.findItem(R.id.action_connection_test_resume)?.isEnabled =
                !profileTestRunning &&
                    (canResumeProfileTest || (AutoVpnManager.hasProgressState() && !AutoVpnManager.isAutoRunning()))
        menu.findItem(R.id.action_auto_switch_gemini_soft)?.isVisible = AutoVpnManager.isGeminiModeActive()
        menu.findItem(R.id.action_connection_test_gemini_filter)?.isChecked =
            DataStore.manualGeminiTestFilterEnabled
    }

    private fun showProfileTestStatusBar() {
        updateProfileTestStatusBar(show = ProfileTestRunner.hasVisibleState() || AutoVpnManager.hasProgressState())
        updateProfileTestMenu()
    }

    private fun updateProfileTestStatusBar(
        show: Boolean = ProfileTestRunner.hasVisibleState() || AutoVpnManager.hasProgressState()
    ) {
        AutoVpnManager.syncVisibleStateFromStore()
        ProfileTestRunner.syncFromStore()
        if (!isAdded || view == null || !::profileTestStatusBar.isInitialized) return
        val profileTestRunning = ProfileTestRunner.isRunning()
        val autoProgressVisible = AutoVpnManager.hasProgressState()
        val profileTestHasPriority = profileTestRunning ||
                (!autoProgressVisible && !profileTestRunning && ProfileTestRunner.hasPendingResume())
        val showAutoProgress = autoProgressVisible && !profileTestRunning
        val shouldShow = show || showAutoProgress
        profileTestStatusBar.isVisible = !select && shouldShow
        if (!shouldShow) return

        if (showAutoProgress) {
            profileTestProgressText.text = AutoVpnManager.progressText()
            profileTestWorkingText.text = AutoVpnManager.appWorkingText()
            profileTestProgress.apply {
                isIndeterminate = AutoVpnManager.autoTotal == 0 && AutoVpnManager.autoRunning
                if (AutoVpnManager.autoTotal > 0) {
                    setProgressCompat(AutoVpnManager.progressPercent(), true)
                } else {
                    setProgressCompat(0, false)
                }
            }
            return
        }

        val boundedFinished = if (ProfileTestRunner.total > 0) {
            ProfileTestRunner.finished.coerceIn(0, ProfileTestRunner.total)
        } else {
            ProfileTestRunner.finished.coerceAtLeast(0)
        }
        profileTestProgressText.text = getString(
            R.string.connection_test_progress,
            boundedFinished,
            ProfileTestRunner.total
        )
        profileTestWorkingText.text = getString(
            R.string.connection_test_working,
            ProfileTestRunner.working
        )
        profileTestProgress.apply {
            isIndeterminate = ProfileTestRunner.total == 0 && ProfileTestRunner.isRunning()
            if (ProfileTestRunner.total > 0) {
                setProgressCompat(
                    ((boundedFinished.toDouble() / ProfileTestRunner.total.toDouble()) * 100).toInt(),
                    true
                )
            } else {
                setProgressCompat(0, false)
            }
        }
    }

    private fun stopProfileTest() {
        ProfileTestRunner.stop()
        updateProfileTestStatusBar()
        updateProfileTestMenu()
        updateProfileTestStatusPollJob()
    }

    private fun reloadProfileListsWithCurrentOrder(forceScrollTop: Boolean = false) {
        if (!isAdded || view == null || !::adapter.isInitialized) return
        runOnDefaultDispatcher {
            adapter.reloadProfileListsWithCurrentOrder(forceScrollTop)
        }
    }

    private fun reloadCurrentProfileListWithCurrentOrder(forceScrollTop: Boolean = false) {
        if (!isAdded || view == null || !::adapter.isInitialized) return
        val groupId = selectedGroup.id
        groupPager.post {
            if (!isAdded || view == null) return@post
            (childFragmentManager.findFragmentByTag("f$groupId") as? GroupFragment)
                ?.reloadProfilesIfReady(forceScrollTop)
            adapter.refreshProfileTestTabs()
        }
    }

    private fun updateProfileTestSortJob() {
        ProfileTestRunner.syncFromStore()
        if (select || !ProfileTestRunner.isRunning()) {
            profileTestSortJob?.cancel()
            profileTestSortJob = null
            return
        }
        if (profileTestSortJob?.isActive == true) return

        profileTestSortJob = runOnDefaultDispatcher {
            while (isActive) {
                val intervalMillis = DataStore.connectionTestSortIntervalSeconds.coerceAtLeast(1) * 1000L
                delay(intervalMillis)
                if (!isActive) break
                ProfileTestRunner.syncFromStore()
                if (!ProfileTestRunner.isRunning()) break
                onMainDispatcher {
                    if (!isAdded || view == null) return@onMainDispatcher
                    reloadCurrentProfileListWithCurrentOrder(forceScrollTop = true)
                }
            }
        }
    }

    private fun updateProfileTestStatusPollJob() {
        if (select) {
            profileTestStatusPollJob?.cancel()
            profileTestStatusPollJob = null
            return
        }
        ProfileTestRunner.syncFromStore()
        AutoVpnManager.syncVisibleStateFromStore()
        if (!ProfileTestRunner.hasVisibleState() && !AutoVpnManager.hasProgressState()) {
            profileTestStatusPollJob?.cancel()
            profileTestStatusPollJob = null
            return
        }
        if (profileTestStatusPollJob?.isActive == true) return

        profileTestStatusPollJob = runOnDefaultDispatcher {
            while (isActive) {
                val intervalMillis = DataStore.connectionTestProgressIntervalSeconds
                    .coerceAtLeast(1) * 1000L
                delay(intervalMillis)
                if (!isActive) break

                ProfileTestRunner.syncFromStore()
                AutoVpnManager.syncVisibleStateFromStore()
                val visible = ProfileTestRunner.hasVisibleState() || AutoVpnManager.hasProgressState()
                DiagnosticLog.event(
                    "configuration-ui",
                    "status-poll visible=$visible profileRunning=${ProfileTestRunner.isRunning()} " +
                            "profile=${ProfileTestRunner.diagnosticStateSummary()} " +
                            "autoRunning=${AutoVpnManager.isAutoRunning()} autoText=${AutoVpnManager.progressText()}"
                )

                onMainDispatcher {
                    if (!isAdded || view == null) return@onMainDispatcher
                    updateProfileTestStatusBar(show = visible)
                    updateProfileTestMenu()
                    updateProfileTestSortJob()
                    if (::adapter.isInitialized) {
                        if (AutoVpnManager.hasVisibleState()) {
                            adapter.refreshAutoRows()
                        }
                        if (ProfileTestRunner.isRunning()) {
                            val groupId = selectedGroup.id
                            (childFragmentManager.findFragmentByTag("f$groupId") as? GroupFragment)
                                ?.refreshLoadedProfileTestResultsIfReady()
                        }
                    }
                }

                if (!visible) break
            }
        }
    }

    override fun onProfileTestStateChanged(reloadProfiles: Boolean) {
        if (!isUiActiveForProfileTest()) return
        updateProfileTestStatusBar()
        updateProfileTestMenu()
        updateProfileTestStatusPollJob()
        updateProfileTestSortJob()
        if (AutoVpnManager.hasVisibleState() && ::adapter.isInitialized) {
            adapter.refreshAutoRows()
        }
        if (ProfileTestRunner.isRunning() && ::adapter.isInitialized) {
            val groupId = selectedGroup.id
            (childFragmentManager.findFragmentByTag("f$groupId") as? GroupFragment)
                ?.refreshLoadedProfileTestResultsIfReady()
        }
        if (reloadProfiles) {
            reloadProfileListsWithCurrentOrder(forceScrollTop = true)
        }
    }

    override fun onAutoVpnStateChanged() {
        if (!isUiActiveForProfileTest()) return
        updateProfileTestStatusBar()
        updateProfileTestMenu()
        updateProfileTestStatusPollJob()
        if (::adapter.isInitialized) {
            val resetGeneration = AutoVpnManager.testResultsResetGeneration
            if (observedAutoTestResultsResetGeneration != resetGeneration) {
                observedAutoTestResultsResetGeneration = resetGeneration
                adapter.clearLoadedProfileTestResults()
                reloadProfileListsWithCurrentOrder(forceScrollTop = true)
            } else {
                adapter.refreshAutoRows()
            }
        }
    }

    private fun groupsForSelectedScope(group: ProxyGroup = selectedGroup): List<ProxyGroup> {
        return if (group.id == ALL_GROUP_ID) {
            enabledGroups()
        } else {
            listOf(group).filter {
                it.id > 0L && DataStore.isGroupEnabled(it.id)
            }
        }
    }

    private fun profilesForSelectedScope(group: ProxyGroup = selectedGroup): List<ProxyEntity> {
        return groupsForSelectedScope(group).flatMap {
            SagerDatabase.proxyDao.getByGroup(it.id)
        }
    }

    private fun visibleGroupsForSelectedScope(group: ProxyGroup = selectedGroup): List<ProxyGroup> {
        return if (group.id == ALL_GROUP_ID && ::adapter.isInitialized) {
            adapter.groupList.filter {
                it.id > 0L &&
                        DataStore.isGroupEnabled(it.id) &&
                        (!it.ungrouped || SagerDatabase.proxyDao.countByGroup(it.id) > 0L)
            }
        } else {
            groupsForSelectedScope(group)
        }
    }

    private fun targetGroupsForTest(group: ProxyGroup): List<ProxyGroup> {
        return groupsForSelectedScope(group)
    }

    private fun collectProfileIdsForTest(
        groups: List<ProxyGroup>,
        resume: Boolean,
        geminiFilter: Boolean = false,
    ): List<Long> {
        if (groups.isEmpty()) return emptyList()
        val groupIds = groups.map { it.id }.distinct()
        val ids = if (groupIds.size == 1) {
            if (resume) {
                SagerDatabase.proxyDao.getUntestedIdsByGroup(groupIds.first())
            } else {
                SagerDatabase.proxyDao.getIdsByGroup(groupIds.first())
            }
        } else if (resume) {
            groupIds.chunked(DB_QUERY_CHUNK_SIZE).flatMap {
                SagerDatabase.proxyDao.getUntestedIdsByGroups(it)
            }
        } else {
            groupIds.chunked(DB_QUERY_CHUNK_SIZE).flatMap {
                SagerDatabase.proxyDao.getIdsByGroups(it)
            }
        }
        if (!geminiFilter) return ids

        val allIds = if (resume) {
            if (groupIds.size == 1) {
                SagerDatabase.proxyDao.getIdsByGroup(groupIds.first())
            } else {
                groupIds.chunked(DB_QUERY_CHUNK_SIZE).flatMap(SagerDatabase.proxyDao::getIdsByGroups)
            }
        } else {
            ids
        }
        val groupNames = groups.associate { it.id to it.displayName() }
        val eligibleIds = allIds.chunked(DB_QUERY_CHUNK_SIZE).flatMap { chunk ->
            val entities = SagerDatabase.proxyDao.getEntities(chunk).associateBy { it.id }
            chunk.filter { id ->
                entities[id]?.let { AutoVpnManager.isKnownNonRussianProfile(it, groupNames[it.groupId].orEmpty()) } == true
            }
        }
        if (!resume) return eligibleIds
        return eligibleIds.chunked(DB_QUERY_CHUNK_SIZE).flatMap(SagerDatabase.proxyDao::getUntestedIds)
    }

    private fun profileTestSnapshot(
        groups: List<ProxyGroup>,
        geminiFilter: Boolean = DataStore.profileTestGeminiFilter,
    ): ProfileTestRunner.Snapshot {
        if (geminiFilter) {
            val eligibleIds = collectProfileIdsForTest(groups, resume = false, geminiFilter = true)
            val untested = eligibleIds.chunked(DB_QUERY_CHUNK_SIZE).sumOf {
                SagerDatabase.proxyDao.getUntestedIds(it).size
            }
            val working = eligibleIds.chunked(DB_QUERY_CHUNK_SIZE).sumOf {
                SagerDatabase.proxyDao.countAvailableByIds(it)
            }
            val safeTotal = eligibleIds.size
            val finished = (safeTotal - untested).coerceAtLeast(0)
            return ProfileTestRunner.Snapshot(
                total = safeTotal,
                finished = finished,
                working = working.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                hasPending = untested > 0,
                hasResult = finished > 0,
            )
        }

        val groupIds = groups.map { it.id }.distinct()
        if (groupIds.isEmpty()) {
            return ProfileTestRunner.Snapshot(0, 0, 0, hasPending = false, hasResult = false)
        }
        val total = groupIds.chunked(DB_QUERY_CHUNK_SIZE).sumOf {
            SagerDatabase.proxyDao.countByGroups(it)
        }
        val untested = groupIds.chunked(DB_QUERY_CHUNK_SIZE).sumOf {
            SagerDatabase.proxyDao.countUntestedByGroups(it)
        }
        val working = groupIds.chunked(DB_QUERY_CHUNK_SIZE).sumOf {
            SagerDatabase.proxyDao.countAvailableByGroups(it)
        }
        val safeTotal = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val safeUntested = untested.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val finished = (safeTotal - safeUntested).coerceAtLeast(0)
        return ProfileTestRunner.Snapshot(
            total = safeTotal,
            finished = finished,
            working = working.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            hasPending = safeUntested > 0,
            hasResult = finished > 0
        )
    }

    private fun applyProfileTestSnapshot(snapshot: ProfileTestRunner.Snapshot) {
        ProfileTestRunner.applySnapshot(snapshot)
        updateProfileTestStatusBar(show = ProfileTestRunner.hasVisibleState() || snapshot.hasResult)
        updateProfileTestMenu()
    }

    private fun refreshProfileTestResumeAvailability() {
        if (select || !::adapter.isInitialized) return
        ProfileTestRunner.syncFromStore()
        if (ProfileTestRunner.isRunning()) {
            profileTestResumeCheckJob?.cancel()
            updateProfileTestMenu()
            updateProfileTestStatusBar()
            return
        }
        val groups = groupsForSelectedScope()
        profileTestResumeCheckJob?.cancel()
        profileTestResumeCheckJob = runOnDefaultDispatcher {
            val snapshot = profileTestSnapshot(groups)
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                applyProfileTestSnapshot(snapshot)
            }
        }
    }

    private fun sortProfilesForCurrentOrder(profiles: List<ProxyEntity>): List<ProxyEntity> {
        return when (DataStore.allGroupOrder) {
            GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }
            GroupOrder.BY_DELAY -> profiles.sortedBy { if (it.status == 1) it.ping else 114514 }
            else -> profiles
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    fun urlTest(resume: Boolean = false) {
        if (ProfileTestRunner.isRunning()) {
            DiagnosticLog.critical(
                "configuration-ui",
                "urlTest-ignored-already-running resume=$resume ${ProfileTestRunner.diagnosticStateSummary()}"
            )
            showProfileTestStatusBar()
            return
        }

        val currentGroup = selectedGroup
        val groups = groupsForSelectedScope(currentGroup)
        val geminiFilter = if (resume) {
            DataStore.profileTestGeminiFilter
        } else {
            DataStore.manualGeminiTestFilterEnabled
        }
        val testUrl = if (resume) {
            DataStore.profileTestUrl.ifBlank {
                if (geminiFilter) DataStore.autoVpnGeminiTestUrl else DataStore.connectionTestURL
            }
        } else if (geminiFilter) {
            DataStore.autoVpnGeminiTestUrl.trim().ifBlank { "https://gemini.google.com/" }
        } else {
            DataStore.connectionTestURL
        }
        DiagnosticLog.critical(
            "configuration-ui",
            "urlTest-request resume=$resume currentGroup=${currentGroup.id} groups=${groups.map { it.id }.take(20)} " +
                    "groupCount=${groups.size} geminiFilter=$geminiFilter ${DiagnosticLog.stateSummary()}"
        )
        runOnDefaultDispatcher {
            val snapshot = if (resume) {
                profileTestSnapshot(groups, geminiFilter)
            } else {
                null
            }
            val profileIds = collectProfileIdsForTest(groups, resume, geminiFilter)
            DiagnosticLog.critical(
                "configuration-ui",
                "urlTest-collected resume=$resume profileIds=${profileIds.size} distinct=${profileIds.distinct().size} " +
                        "snapshot=$snapshot groups=${groups.size} geminiFilter=$geminiFilter"
            )
            if (profileIds.isEmpty()) {
                if (resume) {
                    ProfileTestRunner.clearState()
                }
                DiagnosticLog.critical(
                    "configuration-ui",
                    "urlTest-no-profiles resume=$resume snapshot=$snapshot"
                )
                onMainDispatcher {
                    if (!isAdded || view == null) return@onMainDispatcher
                    snackbar(
                        if (geminiFilter) R.string.connection_test_no_gemini_profiles
                        else R.string.no_proxies_found
                    ).show()
                }
                return@runOnDefaultDispatcher
            }

            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                if (resume) {
                    beginUrlTest(
                        profileIds, groups, resume = true, resumeSnapshot = snapshot,
                        testUrl = testUrl, geminiFilter = geminiFilter,
                    )
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.confirm)
                        .setMessage(getString(R.string.connection_test_confirm, profileIds.size))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            beginUrlTest(
                                profileIds, groups, resume = false,
                                testUrl = testUrl, geminiFilter = geminiFilter,
                            )
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun beginUrlTest(
        profileIdsToTest: List<Long>,
        groups: List<ProxyGroup>,
        resume: Boolean,
        resumeSnapshot: ProfileTestRunner.Snapshot? = null,
        testUrl: String = DataStore.connectionTestURL,
        geminiFilter: Boolean = false,
    ) {
        DiagnosticLog.critical(
            "configuration-ui",
            "beginUrlTest resume=$resume ids=${profileIdsToTest.size} groups=${groups.size} " +
                    "snapshot=$resumeSnapshot geminiFilter=$geminiFilter"
        )
        runOnDefaultDispatcher {
            ProfileTestRunner.start(
                profileIdsToTest = profileIdsToTest,
                resetGroupIds = if (resume || geminiFilter) emptyList() else groups.map { it.id },
                resume = resume,
                resumeSnapshot = resumeSnapshot,
                testUrl = testUrl,
                geminiFilter = geminiFilter,
                resetProfileIds = if (!resume && geminiFilter) profileIdsToTest else emptyList(),
            )
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                DiagnosticLog.critical(
                    "configuration-ui",
                    "beginUrlTest-ui-ready resume=$resume ${ProfileTestRunner.diagnosticStateSummary()}"
                )
                showProfileTestStatusBar()
            }
        }
    }

    private fun enabledGroups(): List<ProxyGroup> {
        return SagerDatabase.groupDao.allGroups().filter {
            DataStore.isGroupEnabled(it.id) &&
                    (!it.ungrouped || SagerDatabase.proxyDao.countByGroup(it.id) > 0L)
        }
    }

    private fun groupProfileCounts(group: ProxyGroup): Pair<Int, Int> {
        return if (group.id == ALL_GROUP_ID) {
            val groups = enabledGroups()
            groups.sumOf { SagerDatabase.proxyDao.countAvailableByGroup(it.id).toInt() } to
                    groups.sumOf { SagerDatabase.proxyDao.countByGroup(it.id).toInt() }
        } else {
            SagerDatabase.proxyDao.countAvailableByGroup(group.id).toInt() to
                    SagerDatabase.proxyDao.countByGroup(group.id).toInt()
        }
    }

    private fun groupTitle(group: ProxyGroup, maxNameLength: Int = 24): String {
        val name = group.displayName().let {
            if (it.length <= maxNameLength) it else it.take(maxNameLength - 3) + "..."
        }
        if (ProfileTestRunner.isRunning()) {
            if (group.id == ALL_GROUP_ID && ProfileTestRunner.total > 0) {
                val percent = if (ProfileTestRunner.total > 0) {
                    ((ProfileTestRunner.working.toDouble() / ProfileTestRunner.total.toDouble()) * 100).toInt()
                } else {
                    0
                }
                return "$name ${app.getString(R.string.group_counter, ProfileTestRunner.working, ProfileTestRunner.total, percent)}"
            }
            return name
        }
        val (working, total) = groupProfileCounts(group)
        val percent = if (total > 0) ((working.toDouble() / total.toDouble()) * 100).toInt() else 0
        return "$name ${app.getString(R.string.group_counter, working, total, percent)}"
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }
                if (!select || showAllGroupInSelect) {
                    newGroupList = ArrayList(newGroupList.filter { DataStore.isGroupEnabled(it.id) })
                    newGroupList.add(0, ProxyGroup(id = ALL_GROUP_ID, name = app.getString(R.string.group_all)))
                }

                var selectedGroup = selectedItem?.groupId ?: if (select && !showAllGroupInSelect) DataStore.currentGroupId() else ALL_GROUP_ID
                var set = false
                if (selectedGroup >= 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    if (selectedGroupIndex < 0) selectedGroupIndex = 0
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                val activity = activity ?: return@runOnDefaultDispatcher
                val runFunc = if (now) activity::runOnUiThread else groupPager::post
                runFunc {
                    if (isAdded && view != null) {
                        groupList = newGroupList
                        notifyDataSetChanged()
                        if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                        val hideTab = groupList.size < 2
                        tabLayout.isGone = hideTab
                        toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                        if (!select) {
                            groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                            refreshProfileTestResumeAvailability()
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        private fun refreshGroupTabs(vararg groupIds: Long) {
            val ids = groupIds.toSet() + ALL_GROUP_ID
            tabLayout.post {
                groupList.forEachIndexed { index, group ->
                    if (group.id in ids) {
                        tabLayout.getTabAt(index)?.text = groupTitle(group)
                    }
                }
            }
        }

        fun reloadProfileListsWithCurrentOrder(forceScrollTop: Boolean = false) {
            groupFragments.values.toList().forEach { it.reloadProfilesIfReady(forceScrollTop) }
            refreshGroupTabs(*groupList.map { it.id }.toLongArray())
        }

        fun refreshProfileTestTabs() {
            refreshGroupTabs(*groupList.map { it.id }.toLongArray())
        }

        fun refreshAutoRows() {
            groupFragments.values.toList().forEach { it.refreshAutoRowsIfReady() }
        }

        fun clearLoadedProfileTestResults() {
            groupFragments.values.toList().forEach { it.clearLoadedProfileTestResultsIfReady() }
        }

        fun refreshLoadedProfileTestResults() {
            groupFragments.values.toList().forEach { it.refreshLoadedProfileTestResultsIfReady() }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            tabLayout.post {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) return@post
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (!select && (index == -1 || !DataStore.isGroupEnabled(group.id))) {
                reload()
                return
            }
            if (index == -1) return

            tabLayout.post {
                if (!isAdded || view == null) return@post
                tabLayout.getTabAt(index)?.text = groupTitle(group)
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            } else {
                refreshGroupTabs(profile.groupId)
            }
        }

        override suspend fun onAdded(profiles: List<ProxyEntity>) {
            val groupIds = profiles.mapTo(mutableSetOf()) { it.groupId }
            if (groupIds.any { groupId -> groupList.find { it.id == groupId } == null }) {
                DataStore.selectedGroup = profiles.firstOrNull()?.groupId ?: DataStore.selectedGroup
                reload()
            } else {
                refreshGroupTabs(*groupIds.toLongArray())
            }
        }

        override suspend fun onUpdated(profileId: Long, trafficStats: TrafficStats) = Unit

        override suspend fun onUpdated(profile: ProxyEntity) {
            if (!isUiActiveForProfileTest()) return
            if (isProfileTestRefreshDeferred()) return
            refreshGroupTabs(profile.groupId)
        }

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            } else {
                refreshGroupTabs(groupId)
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false
        var scrolled = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View? {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        lateinit var adapter: ConfigurationAdapter

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
            if (::layoutManager.isInitialized) {
                outState.putInt("scrollPosition", layoutManager.findFirstVisibleItemPosition())
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                if (!::proxyGroup.isInitialized) proxyGroup = it
            }
            savedInstanceState?.getInt("scrollPosition", -1)?.takeIf { it >= 0 }?.let { pos ->
                scrolled = true
                configurationListView.scrollToPosition(pos)
            }
        }

        private val isEnabled: Boolean
            get() {
                return ((activity as? MainActivity)
                    ?: return false).state.let { it.canStop || it == BaseService.State.Stopped }
            }

        private fun isProfileEditable(id: Long): Boolean {
            return ((activity as? MainActivity)
                ?: return false).state == BaseService.State.Stopped || id != DataStore.selectedProxy
        }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView

        val parent get() = parentFragment as? ConfigurationFragment
        fun requirePrent() = requireParentFragment() as ConfigurationFragment

        fun reloadProfilesIfReady(forceScrollTop: Boolean = false) {
            if (::adapter.isInitialized) adapter.reloadProfiles(forceScrollTop)
        }

        fun refreshAutoRowsIfReady() {
            if (::adapter.isInitialized) adapter.refreshAutoRows()
        }

        fun clearLoadedProfileTestResultsIfReady() {
            if (::adapter.isInitialized) adapter.clearLoadedProfileTestResults()
        }

        fun refreshLoadedProfileTestResultsIfReady() {
            if (::adapter.isInitialized) adapter.refreshLoadedProfileTestResults()
        }

        fun scrollToTopIfReady() {
            if (!::configurationListView.isInitialized || !::layoutManager.isInitialized) return
            configurationListView.stopScroll()
            layoutManager.scrollToPositionWithOffset(0, 0)
            scrolled = true
        }

        override fun onResume() {
            super.onResume()

            if (::adapter.isInitialized) {
                if (adapter.itemCount == 0) {
                    runOnDefaultDispatcher { adapter.reloadProfiles() }
                }
            } else {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()

            if (!DataStore.experimentalFlagsProperties.getBooleanProperty("shadowquic")) {
                (parentFragment as? ToolbarFragment)
                    ?.toolbar?.menu?.findItem(R.id.action_new_shadowquic)?.isVisible  = false
            }

            if (::configurationListView.isInitialized) {
                configurationListView.requestFocus()
            }
        }

        fun checkOrderMenu() {
            if (parent?.select == true) return

            val pf = parentFragment as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            val order = DataStore.allGroupOrder
            when (order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }
                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }
                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (DataStore.allGroupOrder == order) return
                DataStore.allGroupOrder = order
                if (requirePrent().isProfileTestRefreshDeferred()) return
                requirePrent().reloadProfileListsWithCurrentOrder(forceScrollTop = true)
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
            }
            val parent = parent ?: return
            if (!::proxyGroup.isInitialized) return
            if (::adapter.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            ViewCompat.setOnApplyWindowInsetsListener(configurationListView) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    left = bars.left + dp2px(4),
                    right = bars.right + dp2px(4),
                    bottom = bars.bottom + dp2px(64),
                )
                insets
            }
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter)
            GroupManager.addListener(adapter)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)
            configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val last = layoutManager.findLastVisibleItemPosition()
                    if (last >= adapter.itemCount - 40) {
                        adapter.loadNextPage()
                    }
                }
            })

            if (!parent.select) {
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter)
            }

            if (!parent.select && proxyGroup.id != ALL_GROUP_ID && proxyGroup.type == GroupType.BASIC) {
                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter.move(
                            viewHolder.adapterPosition, target.adapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        override fun onDestroy() {
            if (::adapter.isInitialized) {
                ProfileManager.removeListener(adapter)
                GroupManager.removeListener(adapter)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyListItem>()
            private val configurationIndex = HashMap<Long, Int>()
            val pendingDeletedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
            private val pendingProfileRowUpdates = java.util.concurrent.ConcurrentHashMap<Long, ProxyEntity>()
            private var filterText = ""
            private var nextOffset = 0
            private var hasMorePages = false
            private var loadingProfiles = false
            private var pendingProfileReload = false
            private var pendingForceScrollTop = false
            private val profileRowUpdateFlushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
            private val pageAccess = Mutex()

            private fun getItem(profileId: Long): ProxyListItem? {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)?.asListItem()
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            private fun rebuildConfigurationIndex() {
                configurationIndex.clear()
                configurationIdList.forEachIndexed { index, id ->
                    configurationIndex[id] = index
                }
            }

            private fun refreshVisibleProfileHolders() {
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (firstVisible < 0 || lastVisible < firstVisible) return
                for (index in firstVisible..lastVisible) {
                    val profileId = configurationIdList.getOrNull(index) ?: continue
                    val item = configurationList[profileId] ?: continue
                    (configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder)
                        ?.takeIf { it.isBoundTo(profileId) }
                        ?.updateProfileResult(item)
                }
            }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    getItemAt(position)?.let {
                        holder.bind(it)
                    }
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String) {
                filterText = name.trim().lowercase(Locale.ROOT)
                reloadProfiles()
            }

            fun move(from: Int, to: Int) {
                val first = getItemAt(from) ?: return
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step) ?: return
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    ProfileManager.getProfile(next.id)?.also {
                        it.userOrder = next.userOrder
                        updated.add(it)
                    }
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                ProfileManager.getProfile(first.id)?.also {
                    it.userOrder = first.userOrder
                    updated.add(it)
                }
                rebuildConfigurationIndex()
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                rebuildConfigurationIndex()
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    pendingDeletedIds.remove(item.id)
                    configurationListView.post {
                        if (!configurationIdList.contains(item.id)) {
                            configurationList[item.id] = item.asListItem()
                            val safeIndex = index.coerceIn(0, configurationIdList.size)
                            configurationIdList.add(safeIndex, item.id)
                            rebuildConfigurationIndex()
                            notifyItemInserted(safeIndex)
                        }
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            private fun updateProfileRow(profile: ProxyEntity, flushUndo: Boolean = true) {
                if (parent?.isUiActiveForProfileTest() != true || !isFragmentReady()) return
                configurationListView.post {
                    if (!isFragmentReady()) return@post
                    val index = configurationIndex[profile.id] ?: return@post
                    if (flushUndo && ::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val currentStats = configurationList[profile.id]?.stats
                    configurationList[profile.id] = profile.asListItem().also {
                        it.stats = currentStats
                    }
                    (configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder)
                        ?.takeIf { it.isBoundTo(profile.id) }
                        ?.updateProfileResult(configurationList[profile.id] ?: return@post)
                    notifyItemChanged(index)
                }
            }

            private fun queueProfileRowUpdate(profile: ProxyEntity) {
                if (parent?.isUiActiveForProfileTest() != true || !isFragmentReady()) return
                if (!configurationIndex.containsKey(profile.id)) return
                pendingProfileRowUpdates[profile.id] = profile
                if (profileRowUpdateFlushScheduled.compareAndSet(false, true)) {
                    val delayMs = (
                            DataStore.connectionTestProgressIntervalSeconds.coerceAtLeast(1) * 1000L
                            ).coerceAtLeast(PROFILE_ROW_UPDATE_BATCH_MS)
                    configurationListView.postDelayed({
                        profileRowUpdateFlushScheduled.set(false)
                        flushQueuedProfileRowUpdates()
                    }, delayMs)
                }
            }

            private fun flushQueuedProfileRowUpdates() {
                if (!isFragmentReady()) {
                    pendingProfileRowUpdates.clear()
                    return
                }
                val updates = pendingProfileRowUpdates.values.toList()
                pendingProfileRowUpdates.clear()
                if (updates.isEmpty()) return

                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                for (profile in updates) {
                    val index = configurationIndex[profile.id] ?: continue
                    val currentStats = configurationList[profile.id]?.stats
                    configurationList[profile.id] = profile.asListItem().also {
                        it.stats = currentStats
                    }
                    if (index in firstVisible..lastVisible) {
                        val item = configurationList[profile.id] ?: continue
                        (configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder)
                            ?.takeIf { it.isBoundTo(profile.id) }
                            ?.updateProfileResult(item)
                        notifyItemChanged(index)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (!isFragmentReady()) return
                if (proxyGroup.id == ALL_GROUP_ID) {
                    if (DataStore.isGroupEnabled(profile.groupId)) reloadProfiles()
                    return
                }
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile.asListItem()
                    configurationIdList.add(profile.id)
                    configurationIndex[profile.id] = pos
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onAdded(profiles: List<ProxyEntity>) {
                if (!isFragmentReady()) return
                if (profiles.isEmpty()) return
                if (proxyGroup.id == ALL_GROUP_ID) {
                    if (profiles.any { DataStore.isGroupEnabled(it.groupId) }) reloadProfiles()
                    return
                }

                val groupProfiles = profiles.filter { it.groupId == proxyGroup.id }
                if (groupProfiles.isEmpty()) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val start = itemCount
                    for (profile in groupProfiles) {
                        configurationList[profile.id] = profile.asListItem()
                        configurationIndex[profile.id] = configurationIdList.size
                        configurationIdList.add(profile.id)
                    }
                    notifyItemRangeInserted(start, groupProfiles.size)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity) {
                if (parent?.isUiActiveForProfileTest() != true || !isFragmentReady()) return
                val deferRefresh = try {
                    requirePrent().isProfileTestRefreshDeferred()
                } catch (ignored: IllegalStateException) {
                    false
                }
                if (proxyGroup.id == ALL_GROUP_ID) {
                    if (!DataStore.isGroupEnabled(profile.groupId)) return
                    if (deferRefresh) {
                        queueProfileRowUpdate(profile)
                    } else {
                        reloadProfiles()
                    }
                    return
                }
                if (profile.groupId != proxyGroup.id) return
                if (deferRefresh) {
                    queueProfileRowUpdate(profile)
                } else {
                    updateProfileRow(profile)
                }
            }

            override suspend fun onUpdated(profileId: Long, trafficStats: TrafficStats) {
                if (parent?.isUiActiveForProfileTest() != true || !isFragmentReady()) return
                configurationListView.post {
                    if (!isFragmentReady()) return@post
                    val index = configurationIndex[profileId] ?: return@post
                    val profile = configurationList[profileId] ?: return@post
                    profile.stats = trafficStats
                    val holder = configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder
                    if (holder != null && holder.isBoundTo(profileId)) {
                        holder.updateTraffic(profile)
                    }
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (!isFragmentReady()) return
                if (proxyGroup.id != ALL_GROUP_ID && groupId != proxyGroup.id) return

                pendingDeletedIds.remove(profileId)
                configurationListView.post {
                    if (!isFragmentReady()) return@post
                    val index = configurationIndex[profileId] ?: -1
                    if (index >= 0) {
                        configurationIdList.removeAt(index)
                        configurationList.remove(profileId)
                        rebuildConfigurationIndex()
                        notifyItemRemoved(index)
                    }
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (!isFragmentReady()) return
                if (proxyGroup.id == ALL_GROUP_ID) {
                    reloadProfiles()
                    return
                }
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (!isFragmentReady()) return
                if (proxyGroup.id == ALL_GROUP_ID) {
                    reloadProfiles()
                    return
                }
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()
            }

            fun reloadProfiles(forceScrollTop: Boolean = false) {
                loadProfilePage(reset = true, forceScrollTop = forceScrollTop)
            }

            fun refreshAutoRows() {
                if (proxyGroup.id != ALL_GROUP_ID || filterText.isNotEmpty() || !isFragmentReady()) return
                configurationListView.post {
                    if (!isFragmentReady()) return@post
                    for (item in AutoVpnManager.autoListItems()) {
                        val index = configurationIndex[item.id] ?: continue
                        configurationList[item.id] = item
                        (configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder)
                            ?.takeIf { it.isBoundTo(item.id) }
                            ?.updateProfileResult(item)
                        notifyItemChanged(index)
                    }
                }
            }

            fun clearLoadedProfileTestResults() {
                if (!isFragmentReady()) return
                configurationListView.post {
                    if (!isFragmentReady()) return@post
                    var changed = false
                    for (profileId in configurationIdList) {
                        if (AutoVpnManager.isAutoProfileId(profileId)) continue
                        val item = configurationList[profileId] ?: continue
                        if (item.status == 0 && item.ping == 0 && item.error == null) continue
                        configurationList[profileId] = item.copy(status = 0, ping = 0, error = null)
                        changed = true
                    }
                    if (changed) {
                        notifyItemRangeChanged(0, configurationIdList.size)
                    }
                }
            }

            fun refreshLoadedProfileTestResults() {
                if (!isFragmentReady()) return
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val start = firstVisible.coerceAtLeast(0)
                val end = if (lastVisible >= start) {
                    (lastVisible + 1).coerceAtMost(configurationIdList.size)
                } else {
                    configurationIdList.size.coerceAtMost(PROFILE_PAGE_SIZE)
                }
                if (start >= end) return
                val ids = configurationIdList.subList(start, end)
                    .filterNot { AutoVpnManager.isAutoProfileId(it) }
                    .takeIf { it.isNotEmpty() }
                    ?: return
                runOnDefaultDispatcher {
                    val latest = ids.chunked(500)
                        .flatMap { SagerDatabase.proxyDao.getListItemsByIds(it) }
                        .associateBy { it.id }
                    configurationListView.post {
                        if (!isFragmentReady()) return@post
                        var changed = false
                        for (profileId in ids) {
                            val fresh = latest[profileId] ?: continue
                            val current = configurationList[profileId]
                            if (current == null || current.hasSameContent(fresh)) continue
                            configurationList[profileId] = fresh.also {
                                it.stats = current.stats
                            }
                            changed = true
                        }
                        if (changed) {
                            refreshVisibleProfileHolders()
                        }
                    }
                }
            }

            fun loadNextPage() {
                if (hasMorePages && !loadingProfiles) {
                    loadProfilePage(reset = false)
                }
            }

            private fun isFragmentReady(): Boolean {
                return isAdded && view != null && parent != null && ::configurationListView.isInitialized
            }

            private fun loadProfilePage(reset: Boolean, forceScrollTop: Boolean = false) {
                runOnDefaultDispatcher {
                    var shouldLoad = false
                    var currentForceScrollTop = forceScrollTop
                    pageAccess.withLock {
                        if (loadingProfiles) {
                            if (reset) {
                                pendingProfileReload = true
                                pendingForceScrollTop = pendingForceScrollTop || forceScrollTop
                            }
                        } else {
                            loadingProfiles = true
                            pendingForceScrollTop = forceScrollTop
                            currentForceScrollTop = pendingForceScrollTop
                            shouldLoad = true
                        }
                    }
                    if (!shouldLoad) return@runOnDefaultDispatcher
                    try {
                        if (!isFragmentReady()) return@runOnDefaultDispatcher
                        if (parent?.isProfileTestRunning() != true) {
                            ProfileManager.rebuildDisplayCacheIfNeeded()
                        }
                        val offset = if (reset) 0 else nextOffset
                        val rawPage = queryProfilePage(offset)
                        val page = rawPage.filter { it.id !in pendingDeletedIds }
                        val dataItemCount = rawPage.count { !AutoVpnManager.isAutoProfileId(it.id) }
                        hasMorePages = dataItemCount >= PROFILE_PAGE_SIZE
                        nextOffset = offset + dataItemCount
                        if (!isFragmentReady()) return@runOnDefaultDispatcher
                        val scrollTop = currentForceScrollTop
                        configurationListView.post {
                            if (!isFragmentReady()) return@post
                            if (scrollTop) {
                                configurationListView.stopScroll()
                            }
                            applyProfileItems(page, reset)
                            if (reset && page.isNotEmpty() && (scrollTop || !scrolled)) {
                                layoutManager.scrollToPositionWithOffset(0, 0)
                                scrolled = true
                            }
                            configurationListView.post {
                                if (isFragmentReady()) refreshVisibleProfileHolders()
                            }
                        }
                    } finally {
                        var retryReload = false
                        var retryForceScrollTop = false
                        pageAccess.withLock {
                            loadingProfiles = false
                            retryReload = pendingProfileReload
                            retryForceScrollTop = pendingForceScrollTop
                            pendingProfileReload = false
                            pendingForceScrollTop = false
                        }
                        if (retryReload && isFragmentReady()) {
                            loadProfilePage(reset = true, forceScrollTop = retryForceScrollTop)
                        }
                    }
                }
            }

            private fun queryProfilePage(offset: Int): List<ProxyListItem> {
                if (parent?.select == false && DataStore.autoVpnLiteMode) {
                    return if (offset == 0) AutoVpnManager.autoListItems() else emptyList()
                }

                val args = ArrayList<Any>()
                val sql = StringBuilder(
                    """
                    SELECT p.id, p.groupId, p.type, p.userOrder, p.tx, p.rx, p.status, p.ping,
                           p.error, p.profileNameCache, p.displayNameCache, p.displayTypeCache,
                           p.displayAddressCache
                    FROM proxy_entities p
                    """.trimIndent()
                )
                val where = ArrayList<String>()

                if (proxyGroup.id == ALL_GROUP_ID) {
                    val enabledGroupIds = SagerDatabase.groupDao.allGroups().filter {
                        DataStore.isGroupEnabled(it.id) &&
                                (!it.ungrouped || SagerDatabase.proxyDao.countByGroup(it.id) > 0L)
                    }.map { it.id }
                    if (enabledGroupIds.isEmpty()) {
                        return withAutoProfileRow(emptyList(), offset)
                    }
                    sql.append(" INNER JOIN proxy_groups g ON p.groupId = g.id")
                    where += "p.groupId IN (${enabledGroupIds.joinToString(",") { "?" }})"
                    args.addAll(enabledGroupIds)
                } else {
                    where += "p.groupId = ?"
                    args += proxyGroup.id
                }

                if (filterText.isNotEmpty()) {
                    where += "p.searchCache LIKE ?"
                    args += "%$filterText%"
                }

                if (where.isNotEmpty()) {
                    sql.append(" WHERE ").append(where.joinToString(" AND "))
                }

                sql.append(
                    when (DataStore.allGroupOrder) {
                        GroupOrder.BY_NAME -> " ORDER BY p.displayNameCache COLLATE NOCASE, p.userOrder"
                        GroupOrder.BY_DELAY -> " ORDER BY CASE WHEN p.status = 1 THEN p.ping ELSE 114514 END, p.userOrder"
                        else -> if (proxyGroup.id == ALL_GROUP_ID) {
                            " ORDER BY g.userOrder, p.userOrder"
                        } else {
                            " ORDER BY p.userOrder"
                        }
                    }
                )
                sql.append(" LIMIT ? OFFSET ?")
                args += PROFILE_PAGE_SIZE
                args += offset

                val page = SagerDatabase.proxyDao.rawProfileList(SimpleSQLiteQuery(sql.toString(), args.toArray()))
                return withAutoProfileRow(page, offset)
            }

            private fun withAutoProfileRow(page: List<ProxyListItem>, offset: Int): List<ProxyListItem> {
                val parent = parent ?: return page
                val shouldShowAuto = proxyGroup.id == ALL_GROUP_ID &&
                        offset == 0 &&
                        !parent.select &&
                        filterText.isEmpty()
                return if (shouldShowAuto) {
                    AutoVpnManager.autoListItems() + page
                } else {
                    page
                }
            }

            private fun applyProfileItems(page: List<ProxyListItem>, reset: Boolean) {
                if (!reset) {
                    val existing = configurationIdList.toHashSet()
                    val freshPage = page.filter { existing.add(it.id) }
                    if (freshPage.isEmpty()) return
                    val start = configurationIdList.size
                    for (item in freshPage) {
                        configurationList[item.id] = item
                        configurationIndex[item.id] = configurationIdList.size
                        configurationIdList += item.id
                    }
                    notifyItemRangeInserted(start, freshPage.size)
                    refreshVisibleProfileHolders()
                    return
                }

                val newItems = page
                configurationList.clear()
                configurationIdList.clear()
                configurationIndex.clear()
                for (item in newItems) {
                    configurationList[item.id] = item
                    configurationIdList += item.id
                    configurationIndex[item.id] = configurationIdList.lastIndex
                }
                notifyDataSetChanged()
                refreshVisibleProfileHolders()
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyListItem

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val deleteButton: ImageView = view.findViewById(R.id.deleteIcon)

            fun isBoundTo(profileId: Long): Boolean {
                return ::entity.isInitialized && entity.id == profileId
            }

            fun bind(proxyEntity: ProxyListItem) {
                val parent = parent ?: return

                entity = proxyEntity

                if (AutoVpnManager.isAutoProfileId(proxyEntity.id)) {
                    bindAutoProfile(proxyEntity)
                    return
                }

                view.setOnLongClickListener(null)
                if (parent.select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else {
                    val pa = activity as MainActivity

                    view.setOnClickListener {
                        runOnDefaultDispatcher {
                            var update: Boolean
                            var lastSelected: Long
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                AutoVpnManager.onManualProfileSelected()
                                DataStore.selectedProxy = proxyEntity.id
                                onMainDispatcher {
                                    selectedView.visibility = View.VISIBLE
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (pa.state.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (SagerNet.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }

                    }
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx

                val stats = proxyEntity.stats
                if (stats != null) {
                    rx += stats.rxTotal
                    tx += stats.txTotal
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        FormatFileSizeCompat.formatFileSize(view.context, tx, DataStore.useIECUnit),
                        FormatFileSizeCompat.formatFileSize(view.context, rx, DataStore.useIECUnit)
                    )
                }

                var address = proxyEntity.displayAddress()

                if (proxyEntity.profileNameCache.isEmpty() || !parent.alwaysShowAddress) {
                    address = ""
                }

                profileAddress.text = address
                (trafficText.parent as View).isGone = (!showTraffic || proxyEntity.status <= 0) && address.isEmpty()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = getString(R.string.available, proxyEntity.ping)
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    if (proxyEntity.status == 2) {
                        profileStatus.text = proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    profileStatus.setText(R.string.unavailable)
                    profileStatus.setOnClickListener {
                        alert(proxyEntity.error ?: "<?>").show()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }

                editButton.setOnClickListener {
                    ProfileManager.getProfile(proxyEntity.id)
                        ?.settingIntent(it.context, proxyGroup.type == GroupType.SUBSCRIPTION)
                        ?.let { intent ->
                            editProfileLauncher.launch(intent)
                    }
                }

                deleteButton.setOnClickListener {
                    adapter.let {
                        val index = it.configurationIdList.indexOf(proxyEntity.id)
                        if (index >= 0) {
                            val fullProfile = ProfileManager.getProfile(proxyEntity.id) ?: return@setOnClickListener
                            it.remove(index)
                            it.pendingDeletedIds.add(proxyEntity.id)
                            undoManager.remove(index to fullProfile)
                        }
                    }
                }

                editButton.isGone = parent.select
                deleteButton.isGone = parent.select
                shareButton.isGone = parent.select

                runOnDefaultDispatcher {
                    val selected = (parent.selectedItem?.id
                        ?: DataStore.selectedProxy) == proxyEntity.id
                    val started = selected && SagerNet.started && DataStore.startedProfile == proxyEntity.id
                    onMainDispatcher {
                        deleteButton.isEnabled = !started
                        selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    }

                    fun showShare(anchor: View) {
                        val fullProfile = ProfileManager.getProfile(proxyEntity.id) ?: return
                        val popup = PopupMenu(requireContext(), anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        if (!fullProfile.hasShareLink() && fullProfile.wgBean == null) {
                            popup.menu.removeItem(R.id.action_qr)
                            popup.menu.removeItem(R.id.action_clipboard)
                        }
                        if (!fullProfile.canExportBackup()) {
                            popup.menu.removeItem(R.id.action_export_backup)
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }

                    if (!parent.select) {
                        val isInsecure = DataStore.profileSecurityAdvisory &&
                                (ProfileManager.getProfile(proxyEntity.id)?.requireBean()?.isInsecure == true)
                        onMainDispatcher {
                            if (isInsecure) {
                                shareLayer.setBackgroundColor(Color.RED)
                                shareButton.setImageResource(R.drawable.ic_baseline_warning_24)
                                shareButton.setColorFilter(Color.WHITE)
                            } else {
                                shareLayer.setBackgroundColor(Color.TRANSPARENT)
                                shareButton.setImageResource(R.drawable.ic_social_share)
                                shareButton.setColorFilter(Color.GRAY)

                            }
                            shareButton.isVisible = true
                            if (isInsecure) {
                                shareLayout.setOnClickListener {
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(R.string.insecure_warn)
                                        .setMessage(R.string.insecure_warning_detail)
                                        .setPositiveButton(android.R.string.ok) { _, _ ->
                                            showShare(it)
                                        }
                                        .show()
                                }
                            } else {
                                shareLayout.setOnClickListener {
                                    showShare(it)
                                }
                            }

                        }
                    }
                }

            }

            fun updateProfileResult(proxyEntity: ProxyListItem) {
                val parent = parent ?: return
                if (!::entity.isInitialized || entity.id != proxyEntity.id) return

                if (AutoVpnManager.isAutoProfileId(proxyEntity.id)) {
                    bindAutoProfile(AutoVpnManager.autoListItem(AutoVpnManager.modeForProfileId(proxyEntity.id)))
                    return
                }

                val currentStats = entity.stats
                entity = proxyEntity
                entity.stats = currentStats

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx

                val stats = proxyEntity.stats
                if (stats != null) {
                    rx += stats.rxTotal
                    tx += stats.txTotal
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        FormatFileSizeCompat.formatFileSize(view.context, tx, DataStore.useIECUnit),
                        FormatFileSizeCompat.formatFileSize(view.context, rx, DataStore.useIECUnit)
                    )
                } else {
                    trafficText.text = ""
                }

                var address = proxyEntity.displayAddress()
                if (proxyEntity.profileNameCache.isEmpty() || !parent.alwaysShowAddress) {
                    address = ""
                }
                profileAddress.text = address
                (trafficText.parent as View).isGone = (!showTraffic || proxyEntity.status <= 0) && address.isEmpty()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = getString(R.string.available, proxyEntity.ping)
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    profileStatus.text = if (proxyEntity.status == 2) {
                        proxyEntity.error.orEmpty()
                    } else {
                        ""
                    }
                }

                if (proxyEntity.status == 3) {
                    profileStatus.setText(R.string.unavailable)
                    profileStatus.setOnClickListener {
                        alert(proxyEntity.error ?: "<?>").show()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }
            }

            private fun bindAutoProfile(proxyEntity: ProxyListItem) {
                val mode = AutoVpnManager.modeForProfileId(proxyEntity.id)
                view.setOnClickListener {
                    AutoVpnManager.selectMode(mode)
                    adapter.reloadProfiles(forceScrollTop = true)
                }
                view.setOnLongClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(proxyEntity.displayName())
                        .setMessage(AutoVpnManager.diagnosticsText(mode))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    true
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()
                profileAddress.text = proxyEntity.displayAddress()
                trafficText.isVisible = false
                (trafficText.parent as View).isGone = false

                if (DataStore.autoVpnEnabled && DataStore.autoVpnMode == mode) {
                    profileStatus.text = AutoVpnManager.cardStatusText(mode)
                    profileStatus.setTextColor(
                        requireContext().getColour(
                            if (AutoVpnManager.hasLastPingError()) {
                                R.color.material_red_500
                            } else {
                                R.color.material_green_500
                            }
                        )
                    )
                    selectedView.visibility = View.VISIBLE
                } else {
                    profileStatus.text = ""
                    selectedView.visibility = View.INVISIBLE
                }

                profileStatus.setOnClickListener(null)
                editButton.isGone = true
                deleteButton.isGone = true
                shareButton.isGone = true
                shareLayout.setOnClickListener(null)
                shareLayer.setBackgroundColor(Color.TRANSPARENT)
            }

            fun updateTraffic(proxyEntity: ProxyListItem) {
                val parent = parent ?: return
                if (!::entity.isInitialized) return
                if (AutoVpnManager.isAutoProfileId(entity.id)) return

                entity.stats = proxyEntity.stats

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx

                val stats = proxyEntity.stats
                if (stats != null) {
                    rx += stats.rxTotal
                    tx += stats.txTotal
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        FormatFileSizeCompat.formatFileSize(view.context, tx, DataStore.useIECUnit),
                        FormatFileSizeCompat.formatFileSize(view.context, rx, DataStore.useIECUnit)
                    )
                }

                var address = proxyEntity.displayAddress()
                if (proxyEntity.profileNameCache.isEmpty() || !parent.alwaysShowAddress) {
                    address = ""
                }

                (trafficText.parent as View).isGone = (!showTraffic || proxyEntity.status <= 0) && address.isEmpty()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                }
            }

            fun showCode(link: String) {
                QRCodeDialog(link).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    val fullEntity = ProfileManager.getProfile(entity.id) ?: return true
                    when (item.itemId) {
                        R.id.action_qr -> {
                            if (fullEntity.wgBean != null) {
                                fullEntity.wgBean?.toConf()?.let { showCode(it) }
                            } else {
                                fullEntity.toLink()?.let { showCode(it) }
                            }
                        }
                        R.id.action_clipboard -> {
                            if (fullEntity.wgBean != null) {
                                fullEntity.wgBean?.toConf()?.let { export(it) }
                            } else {
                                fullEntity.toLink()?.let { export(it) }
                            }
                        }
                        R.id.action_export_config_clipboard -> export(fullEntity.exportConfig().first)
                        R.id.action_export_config_file -> {
                            val cfg = fullEntity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                        R.id.action_export_backup_clipboard -> export(fullEntity.requireBean().exportBackup())
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

        private val editProfileLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    if (DataStore.currentProfile == DataStore.editingId) {
                        needReload()
                    }
                }
            }

    }

    private val exportConfig = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                        .bufferedWriter()
                        .use {
                            it.write(DataStore.serverConfig)
                        }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }

            }
        }
    }

}
