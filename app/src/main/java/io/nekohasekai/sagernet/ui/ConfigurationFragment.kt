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
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.exportBackup
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.toConf
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.Protocols
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.*
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libsagernetcore.Libsagernetcore
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream
import kotlin.concurrent.timerTask
import androidx.core.net.toUri
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener {

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
    var searchView: SearchView? = null
    val selectedGroup get() = if (tabLayout.isGone && adapter.groupList.size > 0) adapter.groupList[0] else (if (adapter.groupList.size > 0 && tabLayout.selectedTabPosition > -1) adapter.groupList[tabLayout.selectedTabPosition] else ProxyGroup())
    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
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
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
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
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
                fragment?.adapter?.filter("")
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}

        })

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
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

    override fun onDestroy() {
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

                if (proxies.isEmpty()) {
                    if (!fileText.contains("\n") && !fileText.contains("\r")
                        && fileText.startsWith("exclave://", ignoreCase = true)
                        && fileText.substring("exclave://".length).startsWith("subscription?", ignoreCase = true)) {
                        (requireActivity() as? MainActivity)?.importSubscription(fileText.toUri())
                    } else if (!fileText.contains("\n") && !fileText.contains("\r") && isHTTPorHTTPSURL(fileText)) {
                        val builder = Libsagernetcore.newURL("exclave").apply {
                            host = "subscription"
                        }
                        builder.addQueryParameter("url", fileText)
                        (requireActivity() as? MainActivity)?.importSubscription(builder.string.toUri())
                    } else {
                        onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_file)).show()
                        }
                    }
                } else import(proxies)
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    suspend fun import(proxies: List<AbstractBean>) {
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
        val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }

        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
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

            val group = SagerDatabase.groupDao.getById(targetId)!!
            GroupManager.updateGroup(group)
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
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
                            val proxies = RawUpdater.parseRaw(text)
                            if (proxies.isNullOrEmpty()) {
                                if (!text.contains("\n") && !text.contains("\r")
                                    && text.startsWith("exclave://", ignoreCase = true)
                                    && text.substring("exclave://".length).startsWith("subscription?", ignoreCase = true)) {
                                    (requireActivity() as? MainActivity)?.importSubscription(text.toUri())
                                } else if (!text.contains("\n") && !text.contains("\r") && isHTTPorHTTPSURL(text)) {
                                    val builder = Libsagernetcore.newURL("exclave").apply {
                                        host = "subscription"
                                    }
                                    builder.addQueryParameter("url", text)
                                    (requireActivity() as? MainActivity)?.importSubscription(builder.string.toUri())
                                } else onMainDispatcher {
                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                }
                            } else {
                                import(proxies)
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
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }
            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
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
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }
            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
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
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }
            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
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
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }
            R.id.action_connection_url_test -> {
                urlTest()
            }
            R.id.action_update_subscription -> {
                runOnDefaultDispatcher {
                    val currentGroup = DataStore.currentGroup()
                    if (currentGroup.type == GroupType.SUBSCRIPTION) {
                        if (currentGroup.id !in GroupUpdater.updating) {
                            GroupUpdater.startUpdate(currentGroup, true)
                        }
                    } else {
                        snackbar(R.string.group_not_a_subscription).show()
                    }
                }
            }
        }
        return true
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                close()
                cancel()
            }
            .setNeutralButton(" ", null)
            .setCancelable(false)
        lateinit var cancel: () -> Unit
        val results = ArrayList<ProxyEntity>()
        val adapter = TestAdapter()
        val scrollTimer = Timer("insert timer")
        var currentTask: TimerTask? = null

        fun insert(profile: ProxyEntity) {
            binding.listView.post {
                results.add(profile)
                val index = results.size - 1
                adapter.notifyItemInserted(index)
                try {
                    scrollTimer.schedule(timerTask {
                        binding.listView.post {
                            if (currentTask == this) binding.listView.smoothScrollToPosition(index)
                        }
                    }.also {
                        currentTask?.cancel()
                        currentTask = it
                    }, 500L)
                } catch (ignored: Exception) {
                }
            }
        }

        fun update(profile: ProxyEntity) {
            binding.listView.post {
                val index = results.indexOf(profile)
                adapter.notifyItemChanged(index)
            }
        }

        fun close() {
            try {
                scrollTimer.schedule(timerTask {
                    scrollTimer.cancel()
                }, 0)
            } catch (ignored: Exception) {
            }
        }

        init {
            binding.listView.layoutManager = FixedLinearLayoutManager(binding.listView)
            binding.listView.itemAnimator = DefaultItemAnimator()
            binding.listView.adapter = adapter
        }

        inner class TestAdapter : RecyclerView.Adapter<TestResultHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                TestResultHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))

            override fun onBindViewHolder(holder: TestResultHolder, position: Int) {
                holder.bind(results[position])
            }

            override fun getItemCount() = results.size
        }

        inner class TestResultHolder(val binding: LayoutProfileBinding) : RecyclerView.ViewHolder(
            binding.root
        ) {
            init {
                binding.edit.isGone = true
                binding.share.isGone = true
                binding.deleteIcon.isGone = true
            }

            fun bind(profile: ProxyEntity) {
                binding.profileName.text = profile.displayName()
                binding.profileType.text = profile.displayType()

                when (profile.status) {
                    -1 -> {
                        binding.profileStatus.text = profile.error
                        binding.profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                    }
                    0 -> {
                        binding.profileStatus.setText(R.string.connection_test_testing)
                        binding.profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                    }
                    1 -> {
                        binding.profileStatus.text = getString(R.string.available, profile.ping)
                        binding.profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                    }
                    2 -> {
                        binding.profileStatus.text = profile.error
                        binding.profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    }
                    3 -> {
                        binding.profileStatus.setText(R.string.unavailable)
                        binding.profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    }
                }

                if (profile.status == 3) {
                    binding.content.setOnClickListener {
                        alert(profile.error ?: "<?>").show()
                    }
                } else {
                    binding.content.setOnClickListener {}
                }
            }
        }

    }

    private fun ProxyEntity.useBrowserForwarder(): Boolean {
        return when (val bean = requireBean()) {
            is StandardV2RayBean -> {
                when (bean.type) {
                    "ws" -> bean.wsUseBrowserForwarder
                    "splithttp" -> bean.shUseBrowserForwarder
                    else -> false
                }
            }
            is ChainBean -> {
                SagerDatabase.proxyDao.getEntities(bean.proxies).any {
                    it.useBrowserForwarder()
                }
            }
            is BalancerBean -> {
                SagerDatabase.proxyDao.getEntities(bean.proxies).any {
                    it.useBrowserForwarder()
                }
            }
            else -> false
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    fun urlTest() {
        val test = TestDialog()
        val dialog = test.builder.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
        val testJobs = mutableListOf<Job>()

        val mainJob = runOnDefaultDispatcher {
            val group = DataStore.currentGroup()
            var profilesUnfiltered = SagerDatabase.proxyDao.getByGroup(group.id)
            profilesUnfiltered = profilesUnfiltered.filter {
                !it.useBrowserForwarder()
            }
            val profiles = ConcurrentLinkedQueue(profilesUnfiltered)

            val profileCount = profilesUnfiltered.size
            var finishedProfileCount = 0
            //stopService()

            val link = DataStore.connectionTestURL
            val timeout = 5000

            repeat(6) {
                testJobs.add(launch {
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0
                        test.insert(profile)

                        try {
                            val instance = if (DataStore.tunImplementation == TunImplementation.SYSTEM && DataStore.serviceMode == Key.MODE_VPN && SagerNet.started && DataStore.startedProfile > 0) {
                                V2RayTestInstance(profile, link, timeout, protectPath = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path")
                            } else {
                                V2RayTestInstance(profile, link, timeout)
                            }
                            val result = instance.use {
                                it.doTest()
                            }
                            profile.status = 1
                            profile.ping = result
                        } catch (e: PluginManager.PluginNotFoundException) {
                            profile.status = -1
                            profile.error = e.readableMessage
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }
                        onMainDispatcher {
                            finishedProfileCount++
                            test.binding.progressCircular.apply {
                                isVisible = true
                                setProgressCompat(
                                    ((finishedProfileCount.toDouble() / profileCount.toDouble()) * 100).toInt(),
                                    true
                                )
                            }
                            // TODO: fix l10n
                            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text = "$finishedProfileCount/$profileCount"
                        }

                        test.update(profile)
                        ProfileManager.updateProfile(profile)
                    }
                })
            }

            testJobs.joinAll()
            test.close()
            onMainDispatcher {
                test.binding.progressCircular.isGone = true
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.ok)
            }
        }
        test.cancel = {
            mainJob.cancel()
            runOnDefaultDispatcher {
                GroupManager.postReload(DataStore.currentGroupId())
            }
        }
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

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                try {
                    requireActivity()
                } catch (e: Exception) {
                    Logs.w(e)
                    return@runOnDefaultDispatcher
                }
                val runFunc = if (now) requireActivity()::runOnUiThread else groupPager::post
                runFunc {
                    groupList = newGroupList
                    notifyDataSetChanged()
                    if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                    val hideTab = groupList.size < 2
                    tabLayout.isGone = hideTab
                    toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                    if (!select) {
                        groupPager.registerOnPageChangeCallback(updateSelectedCallback)
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
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(profileId: Long, trafficStats: TrafficStats) = Unit

        override suspend fun onUpdated(profile: ProxyEntity) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
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
                proxyGroup = it
                onViewCreated(requireView(), null)
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
            when (proxyGroup.order) {
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
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
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
            val parent = parent ?: return
            if (!::proxyGroup.isInitialized) return

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

            if (!parent.select) {
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter)
            }

            if (!parent.select && proxyGroup.type == GroupType.BASIC) {
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
            val configurationList = HashMap<Long, ProxyEntity>()
            val pendingDeletedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

            private fun getItem(profileId: Long): ProxyEntity? {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

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
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                configurationIdList.clear()
                val lower = name.lowercase()
                configurationIdList.addAll(configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                            it.value.displayType().lowercase().contains(lower) ||
                            it.value.displayAddress().lowercase().contains(lower)
                }.keys)
                notifyDataSetChanged()
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
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    pendingDeletedIds.remove(item.id)
                    configurationListView.post {
                        if (!configurationIdList.contains(item.id)) {
                            configurationList[item.id] = item
                            val safeIndex = index.coerceIn(0, configurationIdList.size)
                            configurationIdList.add(safeIndex, item.id)
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

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                }
            }

            override suspend fun onUpdated(profileId: Long, trafficStats: TrafficStats) {
                val index = configurationIdList.indexOf(profileId)
                if (index != -1) {
                    val profile = configurationList[profileId]
                    if (profile != null) {
                        profile.stats = trafficStats
                        onMainDispatcher {
                            val holder = configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder
                            if (holder != null && holder.entity.id == profileId) {
                                holder.updateTraffic(profile)
                            }
                        }
                    }
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return

                pendingDeletedIds.remove(profileId)
                configurationListView.post {
                    val index = configurationIdList.indexOf(profileId)
                    if (index >= 0) {
                        configurationIdList.removeAt(index)
                        configurationList.remove(profileId)
                        notifyItemRemoved(index)
                    }
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()
            }

            fun reloadProfiles() {
                val selectedItem = try {
                    requirePrent().selectedItem
                } catch (ignored: IllegalStateException) {
                    return
                }


                var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                newProfiles = newProfiles.filter { it.id !in pendingDeletedIds }
                when (proxyGroup.order) {
                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }

                    }
                    GroupOrder.BY_DELAY -> {
                        newProfiles = newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                    }
                }

                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    notifyDataSetChanged()

                    if (selectedProfileIndex != -1 && !scrolled) {
                        configurationListView.scrollTo(selectedProfileIndex, true)
                        scrolled = true
                    } else if (newProfiles.isNotEmpty() && !scrolled) {
                        configurationListView.scrollTo(0, true)
                        scrolled = true
                    }

                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

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

            fun bind(proxyEntity: ProxyEntity) {
                val parent = parent ?: return

                entity = proxyEntity

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

                if (proxyEntity.requireBean().name.isEmpty() || !parent.alwaysShowAddress) {
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
                    proxyEntity.settingIntent(it.context, proxyGroup.type == GroupType.SUBSCRIPTION)?.let {
                        editProfileLauncher.launch(it)
                    }
                }

                deleteButton.setOnClickListener {
                    adapter.let {
                        val index = it.configurationIdList.indexOf(proxyEntity.id)
                        if (index >= 0) {
                            it.remove(index)
                            it.pendingDeletedIds.add(proxyEntity.id)
                            undoManager.remove(index to proxyEntity)
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
                        val popup = PopupMenu(requireContext(), anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        if (!proxyEntity.hasShareLink() && proxyEntity.wgBean == null) {
                            popup.menu.removeItem(R.id.action_qr)
                            popup.menu.removeItem(R.id.action_clipboard)
                        }
                        if (!proxyEntity.canExportBackup()) {
                            popup.menu.removeItem(R.id.action_export_backup)
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }

                    if (!parent.select) {
                        val isInsecure = DataStore.profileSecurityAdvisory && proxyEntity.requireBean().isInsecure
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

            fun updateTraffic(proxyEntity: ProxyEntity) {
                val parent = parent ?: return
                if (!::entity.isInitialized) return

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
                if (proxyEntity.requireBean().name.isEmpty() || !parent.alwaysShowAddress) {
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
                    when (item.itemId) {
                        R.id.action_qr -> {
                            if (entity.wgBean != null) {
                                entity.wgBean?.toConf()?.let { showCode(it) }
                            } else {
                                entity.toLink()?.let { showCode(it) }
                            }
                        }
                        R.id.action_clipboard -> {
                            if (entity.wgBean != null) {
                                entity.wgBean?.toConf()?.let { export(it) }
                            } else {
                                entity.toLink()?.let { export(it) }
                            }
                        }
                        R.id.action_export_config_clipboard -> export(entity.exportConfig().first)
                        R.id.action_export_config_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                        R.id.action_export_backup_clipboard -> export(entity.requireBean().exportBackup())
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
