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

package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.ExtraType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.SubscriptionRequestHeaders
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocksConfig
import io.nekohasekai.sagernet.ktx.*
import libsagernetcore.Libsagernetcore

object SIP008Updater : GroupUpdater() {

    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        val sip008Response: JsonObject
        try {
            if (link.startsWith("content://", ignoreCase = true)) {
                val contentText = app.contentResolver.openInputStream(link.toUri())
                    ?.bufferedReader()
                    ?.readText()

                sip008Response = contentText?.let { parseJson(contentText).asJsonObject }
                    ?: error(app.getString(R.string.no_proxies_found_in_subscription))
            } else {

                val response = Libsagernetcore.newHttpClient().apply {
                    if (SagerNet.started && DataStore.startedProfile > 0) {
                        useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
                    }
                }.newRequest().apply {
                    setURL(subscription.link)
                    if (subscription.customUserAgent.isNotEmpty()) {
                        setUserAgent(subscription.customUserAgent)
                    } else {
                        setUserAgent(USER_AGENT)
                    }
                    SubscriptionRequestHeaders.applyTo { key, value -> setHeader(key, value) }
                }.execute()

                sip008Response = parseJson(response.contentString).asJsonObject
            }
        } catch (_: Exception) {
            error("invalid response")
        }


        subscription.bytesUsed = sip008Response.getLong("bytes_used") ?: -1
        subscription.bytesRemaining = sip008Response.getLong("bytes_remaining") ?: -1
        subscription.applyDefaultValues()

        val servers = sip008Response.getArray("servers")

        var profiles = mutableListOf<AbstractBean>()

        if (servers != null) {
            for (profile in servers) {
                val bean = parseShadowsocksConfig(profile) ?: continue
                appendExtraInfo(profile, bean)
                profiles.add(bean)
            }
        }

        profiles.forEach { it.applyDefaultValues() }

        if (subscription.nameFilter.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter)
            profiles = profiles.filter { !pattern.containsMatchIn(it.name) }.toMutableList()
        }
        if (subscription.nameFilter1.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter1)
            profiles = profiles.filter { pattern.containsMatchIn(it.name) }.toMutableList()
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (DataStore.subscriptionRemoveDuplicates && subscription.deduplication) {
            val uniqueProfiles = LinkedHashSet<AbstractBean>()
            val uniqueNames = HashMap<AbstractBean, String>()
            for (proxy in profiles) {
                if (!uniqueProfiles.add(proxy)) {
                    val index = uniqueProfiles.indexOf(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotEmpty()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(proxy.displayName() + " ($index)")
                } else {
                    uniqueNames[proxy] = proxy.displayName()
                }
            }
            uniqueProfiles.retainAll(uniqueNames.keys)
            profiles = uniqueProfiles.toMutableList()
        }

        val profileMap = profiles.associateBy { it.profileId }
        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = exists.mapNotNull { entity ->
            val profileId = entity.requireBean().profileId
            if (profileMap.contains(profileId)) profileId to entity else let {
                toDelete.add(entity)
                null
            }
        }.toMap()

        val toUpdate = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((profileId, bean) in profileMap.entries) {
            val name = bean.displayName()
            if (toReplace.contains(profileId)) {
                val entity = toReplace[profileId]!!
                val existsBean = entity.requireBean()
                existsBean.applyFeatureSettings(bean)
                when {
                    existsBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name
                    }
                    entity.userOrder != userOrder -> {
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        entity.userOrder = userOrder
                    }
                }
            } else {
                changed++
                SagerDatabase.proxyDao.addProxy(ProxyEntity(
                    groupId = proxyGroup.id, userOrder = userOrder
                ).apply {
                    putBean(bean)
                })
                added.add(name)
            }
            userOrder++
        }

        SagerDatabase.proxyDao.updateProxy(toUpdate)
        SagerDatabase.proxyDao.deleteProxy(toDelete)

        SubscriptionUpdater.markSuccess(proxyGroup)
        SagerDatabase.groupDao.updateGroup(proxyGroup)
        GroupUpdater.lastResults[proxyGroup.id] = GroupUpdater.UpdateResult(success = true, duplicateCount = duplicate.size)
        finishUpdate(proxyGroup)

        if (byUser && userInterface != null) {
            userInterface.onUpdateSuccess(proxyGroup, changed, added, updated, deleted, duplicate)
        }
    }

    fun appendExtraInfo(profile: JsonObject, bean: AbstractBean) {
        bean.extraType = ExtraType.SIP008
        bean.profileId = profile.getString("id")
    }

}
