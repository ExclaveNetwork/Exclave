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
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.exportBackup
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import java.util.*

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

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

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }
            R.id.action_update_all_subscriptions -> {
                val connected = SagerNet.started && DataStore.startedProfile > 0
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.update_all_subscriptions)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .filter { if (connected) true else !it.subscription!!.updateWhenConnectedOnly }
                            .forEach {
                                GroupUpdater.startUpdate(it, byUser = false) // Do not display changelog or error message
                            }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return true
    }

    private lateinit var selectedGroup: ProxyGroup

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
                    (requireActivity() as MainActivity).contentResolver.openOutputStream(
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
                    (requireActivity() as MainActivity).contentResolver.openOutputStream(
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
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
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
                    showCode(proxyGroup.subscription!!.link!!)
                }
                R.id.action_subscription_link_clipboard -> {
                    val link = proxyGroup.subscription!!.link!!
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
            }

            return true
        }

        fun bind(group: ProxyGroup) {
            proxyGroup = group

            itemView.setOnClickListener(null)

            editButton.isGone = group.ungrouped
            updateButton.isVisible = group.type == GroupType.SUBSCRIPTION
            optionsButton.isGone = false
            groupName.text = group.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(group, true)
            }

            optionsButton.setOnClickListener {
                selectedGroup = group

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (group.type != GroupType.SUBSCRIPTION) {
                    popup.menu.findItem(R.id.action_share).subMenu?.removeItem(R.id.action_export_backup)
                    popup.menu.findItem(R.id.action_share).subMenu?.removeItem(R.id.action_subscription_link)
                }

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
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isVisible = group.type == GroupType.SUBSCRIPTION
                editButton.isGone = group.ungrouped
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
                val size = SagerDatabase.proxyDao.countByGroup(group.id)
                onMainDispatcher {
                    try {
                        when (group.type) {
                            GroupType.BASIC -> {
                                if (size == 0L) {
                                    groupStatus.setText(R.string.group_status_empty)
                                } else {
                                    groupStatus.text = (requireActivity() as MainActivity).resources.getQuantityString(R.plurals.group_status_proxies, size.toInt(), size)
                                }
                            }
                            GroupType.SUBSCRIPTION -> {
                                groupStatus.text = when {
                                    size == 0L -> getString(R.string.group_status_empty_subscription)
                                    group.subscription!!.lastUpdated <= 0L -> context!!.resources.getQuantityString(R.plurals.group_status_proxies, size.toInt(), size)
                                    else -> (requireActivity() as MainActivity).resources.getQuantityString(R.plurals.group_status_proxies_subscription, size.toInt(), size,
                                        DateUtils.getRelativeTimeSpanString(context, group.subscription!!.lastUpdated * 1000)
                                            // hack for Chinese, "1月1日" -> "1 月 1 日","上午0:00" -> 上午 0:00"
                                            .replace("^([1-9]|1[0-2])月([1-9]|1[0-9]|2[0-9]|3[0-1])日+".toRegex(), "$1 月 $2 日")
                                            .replace("^上午(([1-9]|1[0-2]):([0-5][0-9]))+".toRegex(), "上午 $1")
                                            .replace("^下午(([1-9]|1[0-2]):([0-5][0-9]))+".toRegex(), "下午 $1")
                                    )
                                }
                            }
                        }
                    } catch (e: IllegalStateException) {
                        Logs.e(e.readableMessage)
                    }
                }
            }

        }
    }

}