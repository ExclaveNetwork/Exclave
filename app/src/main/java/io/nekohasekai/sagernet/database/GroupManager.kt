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

package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.util.Locale

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        if (SagerDatabase.proxyDao.isIdInGroup(DataStore.selectedProxy, groupId) > 0) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.proxyDao.deleteAll(groupId)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    private suspend fun applyGroupOrder(groups: List<ProxyGroup>) {
        groups.forEachIndexed { index, group ->
            group.userOrder = (index + 1).toLong()
            SagerDatabase.groupDao.updateGroup(group)
        }
        iterator { groupUpdated(0L) }
    }

    private fun pinnedFirst(
        groups: List<ProxyGroup>,
        sortedUnpinned: List<ProxyGroup>
    ): List<ProxyGroup> {
        return groups.filter { it.pinned }.sortedBy { it.userOrder } +
                sortedUnpinned.filter { !it.pinned }
    }

    suspend fun sortGroupsByTotalProfiles() {
        val groups = SagerDatabase.groupDao.allGroups()
        val sortedUnpinned = groups.filter { !it.pinned }.sortedWith(compareBy<ProxyGroup>(
            { group -> if (SagerDatabase.proxyDao.countByGroup(group.id) == 0L) 1 else 0 },
            { group -> SagerDatabase.proxyDao.countByGroup(group.id) },
            { group -> group.displayName().lowercase(Locale.ROOT) }
        ))
        applyGroupOrder(pinnedFirst(groups, sortedUnpinned))
    }

    suspend fun sortGroupsByWorkingPercent() {
        val groups = SagerDatabase.groupDao.allGroups()
        val sortedUnpinned = groups.filter { !it.pinned }.sortedWith(
            compareBy<ProxyGroup> { group ->
                val total = SagerDatabase.proxyDao.countByGroup(group.id)
                if (total == 0L) 1 else 0
            }.thenByDescending { group ->
                val total = SagerDatabase.proxyDao.countByGroup(group.id)
                val working = SagerDatabase.proxyDao.countAvailableByGroup(group.id)
                if (total > 0L) working.toDouble() / total.toDouble() else -1.0
            }.thenByDescending { group ->
                SagerDatabase.proxyDao.countAvailableByGroup(group.id)
            }.thenByDescending { group ->
                SagerDatabase.proxyDao.countByGroup(group.id)
            }.thenBy { group ->
                group.displayName().lowercase(Locale.ROOT)
            }
        )
        applyGroupOrder(pinnedFirst(groups, sortedUnpinned))
    }

    suspend fun sortGroupsByHealthChecks() {
        val groups = SagerDatabase.groupDao.allGroups()
        val sortedUnpinned = groups.filter { !it.pinned }.sortedWith(
            compareByDescending<ProxyGroup> { it.healthCheckSuccessPercent() ?: -1 }
                .thenByDescending { it.parsedHealthCheckHistory().size }
                .thenBy { it.displayName().lowercase(Locale.ROOT) }
        )
        applyGroupOrder(pinnedFirst(groups, sortedUnpinned))
    }

    suspend fun recordHealthChecks(): Pair<Int, Int> {
        if (!DataStore.groupHealthCheckStatsEnabled) return 0 to 0
        val groups = SagerDatabase.groupDao.allGroups()
        var workingGroups = 0
        for (group in groups) {
            val working = SagerDatabase.proxyDao.countAvailableByGroup(group.id) > 0L
            group.recordHealthCheck(working)
            if (working) {
                workingGroups++
            }
        }
        SagerDatabase.groupDao.updateGroup(groups)
        iterator { groupUpdated(0L) }
        return workingGroups to groups.size
    }

    suspend fun resetHealthChecks() {
        val groups = SagerDatabase.groupDao.allGroups()
        for (group in groups) {
            group.resetHealthChecks()
        }
        SagerDatabase.groupDao.updateGroup(groups)
        iterator { groupUpdated(0L) }
    }

    suspend fun resetHealthChecks(group: ProxyGroup) {
        val target = SagerDatabase.groupDao.getById(group.id) ?: return
        target.resetHealthChecks()
        SagerDatabase.groupDao.updateGroup(target)
        iterator { groupUpdated(target) }
    }

    suspend fun setGroupPinned(group: ProxyGroup, pinned: Boolean) {
        val target = SagerDatabase.groupDao.getById(group.id) ?: return
        target.pinned = pinned
        SagerDatabase.groupDao.updateGroup(target)
        applyGroupOrder(SagerDatabase.groupDao.allGroups())
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        SagerDatabase.groupDao.deleteById(groupId)
        SagerDatabase.proxyDao.deleteByGroup(groupId)
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        SagerDatabase.groupDao.deleteGroup(group)
        SagerDatabase.proxyDao.deleteByGroup(group.map { it.id }.toLongArray())
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}
