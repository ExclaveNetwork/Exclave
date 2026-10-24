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

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.sql.SQLException


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onAdded(profiles: List<ProxyEntity>) {
            profiles.forEach { onAdd(it) }
        }
        suspend fun onUpdated(profileId: Long, trafficStats: TrafficStats)
        suspend fun onUpdated(profile: ProxyEntity)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()
    private val displayCacheLock = Mutex()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
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

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun createProfiles(groupId: Long, beans: List<AbstractBean>): List<ProxyEntity> {
        if (beans.isEmpty()) return emptyList()

        var userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        val profiles = beans.map { bean ->
            bean.applyDefaultValues()
            ProxyEntity(groupId = groupId).apply {
                id = 0
                putBean(bean)
                this.userOrder = userOrder++
            }
        }
        val ids = SagerDatabase.proxyDao.insert(profiles)
        profiles.forEachIndexed { index, profile ->
            profile.id = ids.getOrNull(index) ?: profile.id
        }
        iterator { onAdded(profiles) }
        return profiles
    }

    suspend fun rebuildDisplayCacheIfNeeded() {
        if (SagerDatabase.proxyDao.countMissingDisplayCache(ProxyEntity.DISPLAY_CACHE_VERSION) == 0L) return
        displayCacheLock.withLock {
            while (true) {
                val profiles = SagerDatabase.proxyDao.getMissingDisplayCache(
                    ProxyEntity.DISPLAY_CACHE_VERSION,
                    300
                )
                if (profiles.isEmpty()) break
                profiles.forEach { profile ->
                    try {
                        profile.refreshCache()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w("Failed to refresh profile display cache: ${profile.id}", e)
                        profile.profileNameCache = profile.profileNameCache.ifBlank { "" }
                        profile.displayNameCache = profile.displayNameCache.ifBlank {
                            profile.profileNameCache.ifBlank { "Profile ${profile.id}" }
                        }
                        profile.displayTypeCache = profile.displayTypeCache.ifBlank { "Invalid" }
                        profile.displayAddressCache = profile.displayAddressCache.ifBlank { "" }
                        profile.importKeyCache = ""
                        profile.searchCache = listOf(
                            profile.displayNameCache,
                            profile.displayTypeCache,
                            profile.displayAddressCache
                        ).joinToString("\n").lowercase()
                        profile.displayCacheVersion = ProxyEntity.DISPLAY_CACHE_VERSION
                    }
                }
                SagerDatabase.proxyDao.updateProxy(profiles)
            }
        }
    }

    suspend fun importKeys(): Set<String> {
        rebuildDisplayCacheIfNeeded()
        return SagerDatabase.proxyDao.getImportKeys().toSet()
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        profile.refreshCache()
        SagerDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile) }
    }

    suspend fun updateProfileTestResult(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateTestResult(profile.id, profile.status, profile.ping, profile.error)
        iterator { onUpdated(profile) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        profiles.forEach { it.refreshCache() }
        SagerDatabase.proxyDao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it) }
        }
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    suspend fun postUpdate(profileId: Long) {
        postUpdate(getProfile(profileId) ?: return)
    }

    suspend fun postUpdate(profile: ProxyEntity) {
        iterator { onUpdated(profile) }
    }

    suspend fun postTrafficUpdated(profileId: Long, stats: TrafficStats) {
        iterator { onUpdated(profileId, stats) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            DataStore.rulesFirstCreate = true
            defaultRules().forEach { createRule(it, false) }
            rules = SagerDatabase.rulesDao.allRules()
        }
        return rules
    }

    private fun defaultRules(): List<RuleEntity> {
        return listOf(
            RuleEntity(
                name = "Правило домена для Ru",
                domains = "geosite:category-ru\ngeosite:tld-ru",
                outbound = -1L,
            ),
            RuleEntity(
                name = "IP правило для Ru",
                ip = "geoip:ru",
                outbound = -1L,
            ),
            RuleEntity(
                enabled = true,
                name = "QUIC",
                port = "443",
                network = "udp",
                outbound = -2L,
            ),
            RuleEntity(
                enabled = true,
                name = "Аналитика и телеметрия",
                domains = "geosite:category-ads-all",
                outbound = -2L,
            ),
            RuleEntity(
                enabled = true,
                name = "Реклама",
                domains = "category-ads-all",
                outbound = -2L,
            ),
            RuleEntity(
                enabled = true,
                name = "Белый список",
                domains = defaultWhitelistDomains.joinToString("\n"),
                outbound = -1L,
            ),
            RuleEntity(
                enabled = true,
                name = "Обход LAN",
                ip = "geoip:private",
                outbound = -1L,
            ),
        )
    }

    private val defaultWhitelistDomains = listOf(
        "domain:avito.ru",
        "domain:*.avito.ru",
        "domain:avito.st",
        "domain:*.avito.st",
        "domain:mail.ru",
        "domain:*.mail.ru",
        "domain:2gis.com",
        "domain:*.2gis.com",
        "domain:2gis.ru",
        "domain:*.2gis.ru",
        "domain:ya.ru",
        "domain:*.ya.ru",
        "domain:yandex.ru",
        "domain:*.yandex.ru",
        "domain:yandex.com",
        "domain:*.yandex.com",
        "domain:yastatic.net",
        "domain:*.yastatic.net",
        "domain:yandex.net",
        "domain:*.yandex.net",
        "domain:ok.ru",
        "domain:*.ok.ru",
        "domain:vk.ru",
        "domain:*.vk.ru",
        "domain:vk.com",
        "domain:*.vk.com",
        "domain:vk-portal.net",
        "domain:*.vk-portal.net",
        "domain:userapi.com",
        "domain:*.userapi.com",
        "domain:rutube.ru",
        "domain:*.rutube.ru",
        "domain:rutubelist.ru",
        "domain:*.rutubelist.ru",
        "domain:wb.ru",
        "domain:*.wb.ru",
        "domain:wildberries.ru",
        "domain:*.wildberries.ru",
        "domain:ozon.ru",
        "domain:*.ozon.ru",
        "domain:ozone.ru",
        "domain:*.ozone.ru",
        "domain:rzd.ru",
        "domain:*.rzd.ru",
        "domain:alfabank.ru",
        "domain:*.alfabank.ru",
        "domain:vtb.ru",
        "domain:*.vtb.ru",
        "domain:gosuslugi.ru",
        "domain:*.gosuslugi.ru",
        "domain:gu-st.ru",
        "domain:*.gu-st.ru",
        "domain:gov.ru",
        "domain:*.gov.ru",
        "domain:kremlin.ru",
        "domain:*.kremlin.ru",
        "domain:government.ru",
        "domain:*.government.ru",
        "domain:izbirkom.ru",
        "domain:*.izbirkom.ru",
        "domain:cikrf.ru",
        "domain:*.cikrf.ru",
        "domain:nalog.ru",
        "domain:*.nalog.ru",
        "domain:dobro.ru",
        "domain:*.dobro.ru",
        "domain:hrlink.ru",
        "domain:*.hrlink.ru",
        "domain:lizaalert.org",
        "domain:*.lizaalert.org",
        "domain:evotor.ru",
        "domain:*.evotor.ru",
        "domain:roskachestvo.gov.ru",
        "domain:sirius.online",
        "domain:*.sirius.online",
        "domain:sochisirius.ru",
        "domain:*.sochisirius.ru",
        "domain:max.ru",
        "domain:*.max.ru",
        "domain:auto.ru",
        "domain:*.auto.ru",
        "domain:dzen.ru",
        "domain:*.dzen.ru",
        "domain:kinopoisk.ru",
        "domain:*.kinopoisk.ru",
        "domain:t2.ru",
        "domain:*.t2.ru",
        "domain:taximaxim.ru",
        "domain:*.taximaxim.ru",
        "domain:lemanapro.ru",
        "domain:*.lemanapro.ru",
        "domain:lmru.tech",
        "domain:*.lmru.tech",
        "domain:oneme.ru",
        "domain:*.oneme.ru",
        "domain:ofd.ru",
        "domain:*.ofd.ru",
        "domain:pochta.ru",
        "domain:*.pochta.ru",
        "domain:rambler.ru",
        "domain:*.rambler.ru",
        "domain:rbc.ru",
        "domain:*.rbc.ru",
        "domain:gazeta.ru",
        "domain:*.gazeta.ru",
        "domain:lenta.ru",
        "domain:*.lenta.ru",
        "domain:kp.ru",
        "domain:*.kp.ru",
        "domain:tutu.ru",
        "domain:*.tutu.ru",
        "domain:47news.ru",
        "domain:*.47news.ru",
        "domain:onf.ru",
        "domain:*.onf.ru",
        "domain:res-nsdi.ru",
        "domain:*.res-nsdi.ru",
        "domain:auth-nsdi.ru",
        "domain:*.auth-nsdi.ru",
        "domain:xn--80ajghhoc2aj1c8b.xn--p1ai",
    )

}








