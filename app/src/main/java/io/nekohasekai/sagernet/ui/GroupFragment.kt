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

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.bg.GroupSourceSyncer
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.SubscriptionNames
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.exportBackup
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libsagernetcore.Libsagernetcore
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>
    private val selectedGroupIds = LinkedHashSet<Long>()
    private var groupDragModeEnabled = false
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            clearGroupSelection()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)
        refreshDeveloperMenuItems()
        refreshDragModeMenu()
        refreshSelectionMenu()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        groupListView = view.findViewById(R.id.group_list)
        ViewCompat.setOnApplyWindowInsetsListener(groupListView) { v, insets ->
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

        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(requireActivity() as ThemedActivity, groupAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun isLongPressDragEnabled(): Boolean {
                return groupDragModeEnabled
            }

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.adapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        }).attachToRecyclerView(groupListView)

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = true
    }

    override fun onResume() {
        super.onResume()
        if (::groupAdapter.isInitialized) {
            refreshDeveloperMenuItems()
            refreshDragModeMenu()
            refreshSelectionMenu()
            groupAdapter.reload()
        }
    }

    private fun refreshDeveloperMenuItems() {
        val enabled = DataStore.groupHealthCheckStatsEnabled
        toolbar.menu.findItem(R.id.action_record_group_health_check)?.isVisible = enabled
        toolbar.menu.findItem(R.id.action_reset_group_health_checks)?.isVisible = enabled
        toolbar.menu.findItem(R.id.action_group_sort_by_health_checks)?.isVisible = enabled
    }

    private fun refreshSelectionMenu() {
        val hasSelection = selectedGroupIds.isNotEmpty()
        toolbar.menu.findItem(R.id.action_copy_selected_subscription_links)?.isVisible = hasSelection
        toolbar.menu.findItem(R.id.action_delete_selected_groups)?.isVisible = hasSelection
        toolbar.title = if (hasSelection) {
            getString(R.string.group_selection_title, selectedGroupIds.size)
        } else {
            getString(R.string.menu_group)
        }
        selectionBackCallback.isEnabled = hasSelection
    }

    private fun refreshDragModeMenu() {
        toolbar.menu.findItem(R.id.action_group_drag_mode)?.isChecked = groupDragModeEnabled
    }

    private fun setGroupDragMode(enabled: Boolean) {
        groupDragModeEnabled = enabled
        if (enabled) clearGroupSelection()
        refreshDragModeMenu()
        snackbar(
            if (enabled) R.string.group_drag_mode_enabled
            else R.string.group_drag_mode_disabled
        ).show()
    }

    private fun toggleGroupSelection(group: ProxyGroup) {
        if (group.id in selectedGroupIds) {
            selectedGroupIds.remove(group.id)
        } else {
            selectedGroupIds.add(group.id)
        }
        groupAdapter.notifyGroupSelectionChanged(group.id)
        refreshSelectionMenu()
    }

    private fun clearGroupSelection() {
        if (selectedGroupIds.isEmpty()) return
        val ids = selectedGroupIds.toList()
        selectedGroupIds.clear()
        ids.forEach(groupAdapter::notifyGroupSelectionChanged)
        refreshSelectionMenu()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }
            R.id.action_update_all_subscriptions -> {
                SubscriptionUpdateDialog.showManual(
                    requireActivity() as ThemedActivity,
                    SagerDatabase.groupDao.subscriptions()
                )
            }
            R.id.action_group_sort_by_total -> {
                groupAdapter.sortByProfileCount()
            }
            R.id.action_group_sort_by_percent -> {
                groupAdapter.sortByWorkingPercent()
            }
            R.id.action_group_sort_by_health_checks -> {
                groupAdapter.sortByHealthChecks()
            }
            R.id.action_group_sort_by_profile_added -> {
                groupAdapter.sortByProfileAdded()
            }
            R.id.action_copy_all_subscription_links -> {
                copySubscriptionLinks(onlyWorking = false)
            }
            R.id.action_copy_working_subscription_links -> {
                copySubscriptionLinks(onlyWorking = true)
            }
            R.id.action_copy_selected_subscription_links -> {
                copySelectedSubscriptionLinks()
            }
            R.id.action_delete_selected_groups -> {
                deleteSelectedGroups()
            }
            R.id.action_groups_enable_all -> {
                setAllGroupsVisibility(enabled = true)
            }
            R.id.action_groups_disable_all -> {
                setAllGroupsVisibility(enabled = false)
            }
            R.id.action_group_drag_mode -> {
                setGroupDragMode(!groupDragModeEnabled)
            }
            R.id.action_delete_all_groups -> {
                deleteAllGroups()
            }
            R.id.action_record_group_health_check -> {
                recordGroupHealthCheck()
            }
            R.id.action_reset_group_health_checks -> {
                resetGroupHealthChecks()
            }
            R.id.action_sync_group_source -> {
                syncGroupSource()
            }
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }
            R.id.action_import_clipboard -> {
                importFromClipboard()
            }
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }
        }
        return true
    }

    private lateinit var selectedGroup: ProxyGroup

    private data class ImportDuplicateSplit<T>(
        val fresh: List<T>,
        val duplicates: List<T>,
    )

    private fun groupCounts(group: ProxyGroup): Pair<Int, Int> {
        return SagerDatabase.proxyDao.countAvailableByGroup(group.id).toInt() to
                SagerDatabase.proxyDao.countByGroup(group.id).toInt()
    }

    private fun groupTitle(group: ProxyGroup): String {
        return group.displayName()
    }

    private fun formatGroupUpdated(timestampSeconds: Long): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date(timestampSeconds * 1000L))
    }

    private fun subscriptionLinkWithName(group: ProxyGroup): String? {
        val link = group.subscription?.link?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanLink = runCatching {
            link.toUri().buildUpon().fragment(null).build().toString()
        }.getOrElse {
            link.substringBefore("#")
        }
        val name = subscriptionExportName(group)
        return if (name.isNotEmpty()) "$cleanLink#$name" else cleanLink
    }

    private fun subscriptionExportName(group: ProxyGroup): String {
        return group.displayName().trim().replace(Regex("[\\s\\u00A0]+"), "_")
    }

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

    private fun copySubscriptionLinks(onlyWorking: Boolean) {
        runOnDefaultDispatcher {
            val links = SagerDatabase.groupDao.subscriptions()
                .filter { !onlyWorking || SagerDatabase.proxyDao.countAvailableByGroup(it.id) > 0L }
                .mapNotNull(::subscriptionLinkWithName)
                .joinToString("\n")
            onMainDispatcher {
                if (links.isBlank()) {
                    snackbar(R.string.no_subscription_links).show()
                } else {
                    val success = SagerNet.trySetPrimaryClip(links)
                    snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                        .show()
                }
            }
        }
    }

    private fun copySelectedSubscriptionLinks() {
        val selectedIds = selectedGroupIds.toList()
        runOnDefaultDispatcher {
            val groupsById = SagerDatabase.groupDao.allGroups().associateBy { it.id }
            val links = selectedIds
                .mapNotNull(groupsById::get)
                .mapNotNull(::subscriptionLinkWithName)
                .joinToString("\n")
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                if (links.isBlank()) {
                    snackbar(R.string.no_subscription_links).show()
                } else {
                    val success = SagerNet.trySetPrimaryClip(links)
                    snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                        .show()
                }
            }
        }
    }

    private fun recordGroupHealthCheck() {
        runOnDefaultDispatcher {
            val (working, total) = GroupManager.recordHealthChecks()
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                groupAdapter.reload()
                snackbar(getString(R.string.group_health_check_recorded, working, total)).show()
            }
        }
    }

    private fun resetGroupHealthChecks() {
        runOnDefaultDispatcher {
            GroupManager.resetHealthChecks()
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                groupAdapter.reload()
                snackbar(R.string.group_health_check_reset_done).show()
            }
        }
    }

    private fun setAllGroupsVisibility(enabled: Boolean) {
        runOnDefaultDispatcher {
            val groups = SagerDatabase.groupDao.allGroups().filter { !it.ungrouped }
            val ids = DataStore.disabledGroups.toMutableSet()
            val groupIds = groups.mapTo(mutableSetOf()) { it.id.toString() }
            if (enabled) {
                ids.removeAll(groupIds)
            } else {
                ids.addAll(groupIds)
            }
            DataStore.disabledGroups = ids
            GroupManager.iterator { groupUpdated(0L) }
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                groupAdapter.reload()
                snackbar(
                    if (enabled) R.string.group_visibility_all_enabled
                    else R.string.group_visibility_all_disabled
                ).show()
            }
        }
    }

    private fun deleteAllGroups() {
        runOnDefaultDispatcher {
            val groups = SagerDatabase.groupDao.allGroups().filter { !it.ungrouped }
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                if (groups.isEmpty()) {
                    snackbar(R.string.delete_all_groups_empty).show()
                    return@onMainDispatcher
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_all_groups)
                    .setMessage(R.string.delete_all_groups_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            val groupIds = groups.mapTo(mutableSetOf()) { it.id.toString() }
                            DataStore.disabledGroups = DataStore.disabledGroups - groupIds
                            GroupManager.deleteGroup(groups)
                            onMainDispatcher {
                                if (!isAdded || view == null) return@onMainDispatcher
                                groupAdapter.reload()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun deleteSelectedGroups() {
        val selectedIds = selectedGroupIds.toSet()
        runOnDefaultDispatcher {
            val groups = SagerDatabase.groupDao.allGroups()
                .filter { it.id in selectedIds && !it.ungrouped }
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                if (groups.isEmpty()) {
                    snackbar(R.string.delete_all_groups_empty).show()
                    return@onMainDispatcher
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_selected_groups)
                    .setMessage(R.string.delete_selected_groups_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            val groupIds = groups.mapTo(mutableSetOf()) { it.id.toString() }
                            DataStore.disabledGroups = DataStore.disabledGroups - groupIds
                            GroupManager.deleteGroup(groups)
                            onMainDispatcher {
                                if (!isAdded || view == null) return@onMainDispatcher
                                clearGroupSelection()
                                groupAdapter.reload()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun syncGroupSource() {
        runOnDefaultDispatcher {
            try {
                val summary = GroupSourceSyncer.syncNow()
                onMainDispatcher {
                    if (!isAdded || view == null) return@onMainDispatcher
                    if (summary.disabled) {
                        snackbar(R.string.group_source_sync_disabled).show()
                    } else {
                        snackbar(
                            getString(
                                R.string.group_source_sync_result,
                                summary.added,
                                summary.kept,
                                summary.removed
                            )
                        ).show()
                        groupAdapter.reload()
                        if (summary.updateGroups.isNotEmpty()) {
                            SubscriptionUpdateDialog.showManual(
                                requireActivity() as ThemedActivity,
                                summary.updateGroups
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                Logs.w(e)
                onMainDispatcher {
                    if (!isAdded || view == null) return@onMainDispatcher
                    snackbar(getString(R.string.group_source_sync_error, e.readableMessage)).show()
                }
            }
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
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
                    val zip = ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        fileText = zip.bufferedReader().readText()
                        RawUpdater.parseRaw(fileText)?.let { proxies.addAll(it) }
                        zip.closeEntry()
                    }
                    runCatching {
                        zip.close()
                    }
                } else {
                    fileText = requireContext().contentResolver.openInputStream(file)!!.use {
                        it.bufferedReader().readText()
                    }
                    RawUpdater.parseRaw(fileText)?.let { proxies.addAll(it) }
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

    private fun importFromClipboard() {
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
            importProfiles(proxies)
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

    private suspend fun importProfiles(proxies: List<AbstractBean>) {
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
                            confirmImportProfiles(split.fresh)
                        }
                    }
                },
                onAddDuplicates = {
                    runOnDefaultDispatcher {
                        confirmImportProfiles(split.fresh + split.duplicates)
                    }
                }
            )
            return
        }

        confirmImportProfiles(proxies)
    }

    private suspend fun confirmImportProfiles(proxies: List<AbstractBean>) {
        if (proxies.isEmpty()) {
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                snackbar(R.string.import_duplicates_skipped).show()
            }
            return
        }
        if (proxies.size <= 32) {
            finishImportProfiles(proxies)
        } else {
            val name = SagerDatabase.groupDao.getById(DataStore.selectedGroupForImport())!!.displayName()
            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.profile_import)
                    .setMessage(
                        resources.getQuantityString(
                            R.plurals.profile_multi_import_message,
                            proxies.size,
                            proxies.size,
                            name
                        )
                    )
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            finishImportProfiles(proxies)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private suspend fun finishImportProfiles(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        ProfileManager.createProfiles(targetId, proxies)
        SagerDatabase.groupDao.getById(targetId)?.let {
            GroupManager.updateGroup(it)
        }
        onMainDispatcher {
            if (!isAdded || view == null) return@onMainDispatcher
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added,
                    proxies.size,
                    proxies.size
                )
            ).show()
            groupAdapter.reload()
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
            if (!isAdded || view == null) return@onMainDispatcher
            snackbar(
                resources.getQuantityString(
                    R.plurals.added,
                    groups.size,
                    groups.size
                )
            ).show()
            groupAdapter.reload()
            SubscriptionUpdateDialog.showManual(requireActivity() as ThemedActivity, groups)
        }
    }

    private val exportProfiles = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                val links = profiles.mapNotNull {
                    try {
                        it.toLink()
                    } catch (_: Exception) {
                        null
                    }
                }.joinToString("\n")
                try {
                    requireActivity().contentResolver.openOutputStream(
                        data
                    )!!.bufferedWriter().use {
                        it.write(links)
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

    private val exportBackupOfAllProfiles = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                val links = profiles.mapNotNull {
                    if (it.canExportBackup()) {
                        it.requireBean().exportBackup()
                    } else null
                }.joinToString("\n")
                try {
                    requireActivity().contentResolver.openOutputStream(
                        data
                    )!!.bufferedWriter().use {
                        it.write(links)
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

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            groups.find { it.ungrouped }?.let {
                if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                    groups.remove(it)
                }
            }

            groupList.clear()
            groupList.addAll(groups)
            groupListView.post {
                val validIds = groupList.mapTo(mutableSetOf()) { it.id }
                if (selectedGroupIds.retainAll(validIds)) {
                    refreshSelectionMenu()
                }
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        fun notifyGroupSelectionChanged(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index != -1) notifyItemChanged(index)
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        private fun applySortedGroups(sorted: List<ProxyGroup>) {
            val pinned = groupList.filter { it.pinned }.sortedBy { it.userOrder }
            val ordered = pinned + sorted.filter { !it.pinned }
            groupList.clear()
            groupList.addAll(ordered)
            notifyDataSetChanged()
            runOnDefaultDispatcher {
                ordered.forEachIndexed { index, group ->
                    if (!group.ungrouped) {
                        group.userOrder = (index + 1).toLong()
                        SagerDatabase.groupDao.updateGroup(group)
                    }
                }
                GroupManager.iterator { groupUpdated(0L) }
            }
        }

        fun sortByProfileCount() {
            val sorted = groupList.filter { !it.pinned }.sortedWith(compareBy<ProxyGroup>(
                { group ->
                    val (working, total) = groupCounts(group)
                    if (working == 0 && total == 0) 1 else 0
                },
                { group -> groupCounts(group).second },
                { group -> group.displayName().lowercase(Locale.ROOT) }
            ))
            applySortedGroups(sorted)
        }

        fun sortByWorkingPercent() {
            val sorted = groupList.filter { !it.pinned }.sortedWith(
                compareBy<ProxyGroup> { group ->
                    val (working, total) = groupCounts(group)
                    if (working == 0 && total == 0) 1 else 0
                }.thenByDescending { group ->
                    val (working, total) = groupCounts(group)
                    if (total > 0) working.toDouble() / total.toDouble() else -1.0
                }.thenByDescending { group ->
                    groupCounts(group).first
                }.thenByDescending { group ->
                    groupCounts(group).second
                }.thenBy { group ->
                    group.displayName().lowercase(Locale.ROOT)
                }
            )
            applySortedGroups(sorted)
        }

        fun sortByHealthChecks() {
            val sorted = groupList.filter { !it.pinned }.sortedWith(
                compareByDescending<ProxyGroup> { it.healthCheckSuccessPercent() ?: -1 }
                    .thenByDescending { it.parsedHealthCheckHistory().size }
                    .thenBy { it.displayName().lowercase(Locale.ROOT) }
            )
            applySortedGroups(sorted)
        }

        fun sortByProfileAdded() {
            val sorted = groupList.filter { !it.pinned }.sortedWith(
                compareBy<ProxyGroup> { group ->
                    SagerDatabase.proxyDao.latestProfileIdByGroup(group.id)
                }.thenBy { group ->
                    group.displayName().lowercase(Locale.ROOT)
                }
            )
            applySortedGroups(sorted)
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            groupList.add(group)
            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                /*if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }*/
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()

                groupList.removeAt(index)
                if (selectedGroupIds.remove(groupId)) {
                    refreshSelectionMenu()
                }
                notifyItemRemoved(index)
            }
        }


        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()

                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }


    inner class GroupHolder(binding: LayoutGroupItemBinding) : RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        val card = binding.root
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
        val visibilitySwitch = binding.groupVisibility
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        override fun onMenuItemClick(item: MenuItem): Boolean {
            fun showCode(link: String) {
                QRCodeDialog(link).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (requireActivity() as ThemedActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_subscription_link_qr -> {
                    showCode(subscriptionLinkWithName(proxyGroup) ?: proxyGroup.subscription!!.link!!)
                }
                R.id.action_subscription_link_clipboard -> {
                    val link = subscriptionLinkWithName(proxyGroup) ?: proxyGroup.subscription!!.link!!
                    runOnDefaultDispatcher {
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(link)
                            snackbar(R.string.action_export_msg).show()
                        }
                    }
                }
                R.id.action_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                        val links = profiles.mapNotNull {
                            try {
                                it.toLink()
                            } catch (_: Exception) {
                                null
                            }
                        }.joinToString("\n")
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(R.string.action_export_msg).show()
                        }
                    }
                }
                R.id.action_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }
                R.id.action_export_backup_clipboard -> {
                    export(proxyGroup.exportBackup())
                }
                R.id.action_export_backup_of_all_profiles_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                        val links = profiles.mapNotNull {
                            if (it.canExportBackup()) {
                                it.requireBean().exportBackup()
                            } else null
                        }.joinToString("\n")
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(R.string.action_export_msg).show()
                        }
                    }
                }
                R.id.action_export_backup_of_all_profiles_file -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                        val links = profiles.map {
                            if (it.canExportBackup()) {
                                it.requireBean().exportBackup()
                            }
                        }.joinToString("\n")
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(R.string.action_export_msg).show()
                        }
                    }
                    startFilesForResult(exportBackupOfAllProfiles, "profiles_${proxyGroup.displayName()}_backup.txt")
                }
                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                R.id.action_group_pin -> {
                    runOnDefaultDispatcher {
                        GroupManager.setGroupPinned(proxyGroup, !proxyGroup.pinned)
                        groupAdapter.reload()
                    }
                }
                R.id.action_reset_group_health_check -> {
                    runOnDefaultDispatcher {
                        GroupManager.resetHealthChecks(proxyGroup)
                        onMainDispatcher {
                            if (!isAdded || view == null) return@onMainDispatcher
                            snackbar(R.string.group_health_check_reset_done).show()
                            groupAdapter.reload()
                        }
                    }
                }
            }

            return true
        }

        fun bind(group: ProxyGroup) {
            proxyGroup = group

            val selected = group.id in selectedGroupIds
            card.strokeWidth = if (selected) dp2px(2) else 0
            card.strokeColor = itemView.context.getColorAttr(R.attr.selectedColorPrimary)
            itemView.isActivated = selected
            itemView.setOnClickListener {
                if (selectedGroupIds.isNotEmpty()) {
                    toggleGroupSelection(group)
                }
            }
            itemView.setOnLongClickListener {
                if (groupDragModeEnabled) return@setOnLongClickListener false
                toggleGroupSelection(group)
                true
            }

            editButton.isGone = group.ungrouped
            visibilitySwitch.isGone = group.ungrouped
            updateButton.isVisible = group.type == GroupType.SUBSCRIPTION
            optionsButton.isGone = false
            groupName.text = groupTitle(group)

            visibilitySwitch.setOnCheckedChangeListener(null)
            visibilitySwitch.isChecked = DataStore.isGroupEnabled(group.id)
            visibilitySwitch.setOnCheckedChangeListener { _, isChecked ->
                DataStore.setGroupEnabled(group.id, isChecked)
                runOnDefaultDispatcher {
                    GroupManager.postUpdate(group)
                }
            }

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                SubscriptionUpdateDialog.showManual(
                    requireActivity() as ThemedActivity,
                    listOf(group)
                )
            }

            optionsButton.setOnClickListener {
                selectedGroup = group

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (group.type != GroupType.SUBSCRIPTION) {
                    popup.menu.findItem(R.id.action_share).subMenu?.removeItem(R.id.action_export_backup)
                    popup.menu.findItem(R.id.action_share).subMenu?.removeItem(R.id.action_subscription_link)
                }
                popup.menu.findItem(R.id.action_group_pin)?.apply {
                    isVisible = group.type == GroupType.SUBSCRIPTION ||
                            group.displayName().equals("local", ignoreCase = true)
                    setTitle(if (group.pinned) R.string.group_unpin else R.string.group_pin)
                }
                popup.menu.findItem(R.id.action_reset_group_health_check)?.isVisible =
                    DataStore.groupHealthCheckStatsEnabled

                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            if (group.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(group.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    val progress = GroupUpdater.progress[group.id]!!
                    subscriptionUpdateProgress.max = progress.max
                    subscriptionUpdateProgress.progress = progress.progress
                }

                updateButton.isInvisible = true
                editButton.isGone = true
                optionsButton.isGone = true
                visibilitySwitch.isEnabled = false
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isVisible = group.type == GroupType.SUBSCRIPTION
                editButton.isGone = group.ungrouped
                visibilitySwitch.isGone = group.ungrouped
                visibilitySwitch.isEnabled = true
            }

            if (group.type == GroupType.SUBSCRIPTION) {
                val subscription = group.subscription!!
                val text = mutableListOf<String>()
                if (subscription.bytesUsed > 0L || subscription.bytesRemaining > 0L) {
                    text.add(if (subscription.bytesRemaining > 0L) {
                        getString(
                            R.string.subscription_traffic, FormatFileSizeCompat.formatFileSize(
                                context, subscription.bytesUsed, DataStore.useIECUnit
                            ), FormatFileSizeCompat.formatFileSize(
                                context, subscription.bytesRemaining, DataStore.useIECUnit
                            )
                        )
                    } else {
                        getString(
                            R.string.subscription_used, FormatFileSizeCompat.formatFileSize(
                                context, subscription.bytesUsed, DataStore.useIECUnit
                            )
                        )
                    })
                }
                if (subscription.expiryDate > 0L) {
                    text.add(getString(
                        R.string.subscription_expire,
                        DateUtils.getRelativeTimeSpanString(context, subscription.expiryDate * 1000)
                            // hack for Chinese, "1月1日" -> "1 月 1 日","上午0:00" -> 上午 0:00"
                            .replace("^([1-9]|1[0-2])月([1-9]|1[0-9]|2[0-9]|3[0-1])日+".toRegex(), "$1 月 $2 日")
                            .replace("^上午(([1-9]|1[0-2]):([0-5][0-9]))+".toRegex(), "上午 $1")
                            .replace("^下午(([1-9]|1[0-2]):([0-5][0-9]))+".toRegex(), "下午 $1")
                    ))
                }
                if (text.isNotEmpty()) {
                    groupTraffic.isVisible = true
                    groupTraffic.text = text.joinToString("\n")
                    groupStatus.setPadding(0)
                    if (proxyGroup.id !in GroupUpdater.updating && subscription.bytesRemaining > 0L) {
                        subscriptionUpdateProgress.apply {
                            isVisible = true
                            setProgressCompat(
                                ((subscription.bytesUsed.toDouble() / (subscription.bytesUsed + subscription.bytesRemaining).toDouble()) * 100).toInt(),
                                true
                            )
                        }
                    }
                } else {
                    groupTraffic.isVisible = false
                    groupStatus.setPadding(0, 0, 0, dp2px(4))
                }
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            runOnDefaultDispatcher {
                val (working, total) = groupCounts(group)
                val percent = if (total > 0) ((working.toDouble() / total.toDouble()) * 100).toInt() else 0
                val lastUpdated = group.subscription?.lastUpdated ?: 0L
                val updatedText = if (lastUpdated > 0L) formatGroupUpdated(lastUpdated) else ""
                val size = total.toLong()
                onMainDispatcher {
                    try {
                        when (group.type) {
                            GroupType.BASIC -> {
                                groupStatus.text = getString(
                                    R.string.group_status_profile_health,
                                    working,
                                    total,
                                    percent
                                )
                            }
                            GroupType.SUBSCRIPTION -> {
                                groupStatus.text = when {
                                    size == 0L -> getString(R.string.group_status_empty_subscription)
                                    group.subscription!!.lastUpdated <= 0L -> context!!.resources.getQuantityString(R.plurals.group_status_proxies, size.toInt(), size)
                                    else -> resources.getQuantityString(R.plurals.group_status_proxies_subscription, size.toInt(), size,
                                        DateUtils.getRelativeTimeSpanString(context, group.subscription!!.lastUpdated * 1000)
                                            // hack for Chinese, "1月1日" -> "1 月 1 日","上午0:00" -> 上午 0:00"
                                            .replace("^([1-9]|1[0-2])月([1-9]|1[0-9]|2[0-9]|3[0-1])日+".toRegex(), "$1 月 $2 日")
                                            .replace("^上午(([1-9]|1[0-2]):([0-5][0-9]))+".toRegex(), "上午 $1")
                                            .replace("^下午(([1-9]|1[0-2]):([0-5][0-9]))+".toRegex(), "下午 $1")
                                    )
                                }
                            }
                        }
                        groupStatus.text = if (group.type == GroupType.SUBSCRIPTION) {
                            if (updatedText.isNotEmpty()) {
                                getString(
                                    R.string.group_status_profile_health_updated,
                                    working,
                                    total,
                                    percent,
                                    updatedText
                                )
                            } else {
                                getString(
                                    R.string.group_status_profile_health_not_updated,
                                    working,
                                    total,
                                    percent
                                )
                            }
                        } else {
                            getString(
                                R.string.group_status_profile_health,
                                working,
                                total,
                                percent
                            )
                        }
                        if (DataStore.groupHealthCheckStatsEnabled) {
                            groupStatus.text = groupStatus.text.toString() + "\n" + getString(
                                R.string.group_health_check_status,
                                group.healthCheckStatusLabel()
                            )
                        }
                    } catch (e: IllegalStateException) {
                        Logs.e(e.readableMessage)
                    }
                }
            }

        }
    }

}
