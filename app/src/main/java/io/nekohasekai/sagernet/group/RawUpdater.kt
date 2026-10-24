/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.SubscriptionRequestHeaders
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocksConfig
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardConfig
import io.nekohasekai.sagernet.ktx.*
import libsagernetcore.Libsagernetcore
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.util.regex.Pattern

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://", ignoreCase = true)) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.bufferedReader()
                ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
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

            proxies = parseRaw(response.contentString)
                ?: error(app.getString(R.string.no_proxies_found))

            val subscriptionUserinfo = response.getHeader("Subscription-Userinfo")
            if (subscriptionUserinfo.isNotEmpty()) {
                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }
                var used = 0L
                try {
                    val upload = get("upload=([0-9]+)")?.toLong() ?: -1L
                    if (upload > 0L) {
                        used += upload
                    }
                    val download = get("download=([0-9]+)")?.toLong() ?: -1L
                    if (download > 0L) {
                        used += download
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: -1L
                    subscription.apply {
                        if (upload > 0L || download > 0L) {
                            bytesUsed = used
                            bytesRemaining = if (total > 0L) total - used else -1L
                        } else {
                            bytesUsed = -1L
                            bytesRemaining = -1L
                        }
                        expiryDate = get("expire=([0-9]+)")?.toLong() ?: -1L
                    }
                } catch (_: Exception) {
                }
            } else {
                subscription.apply {
                    bytesUsed = -1L
                    bytesRemaining = -1L
                    expiryDate = -1L
                }
            }
        }

        proxies.forEach { it.applyDefaultValues() }

        if (subscription.nameFilter.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter)
            proxies = proxies.filter { !pattern.containsMatchIn(it.name) }
        }
        if (subscription.nameFilter1.isNotEmpty()) {
            val pattern = Regex(subscription.nameFilter1)
            proxies = proxies.filter { pattern.containsMatchIn(it.name) }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (DataStore.subscriptionRemoveDuplicates && subscription.deduplication) {
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val uniqueNames = HashMap<Protocols.Deduplication, String>()
            for (p in proxies) {
                val proxy = Protocols.Deduplication(p, p.javaClass.toString())
                if (!uniqueProxies.add(proxy)) {
                    val index = uniqueProxies.indexOf(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotEmpty()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(p.displayName() + " ($index)")
                } else {
                    uniqueNames[proxy] = p.displayName()
                }
            }
            uniqueProxies.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.toList().map { it.bean }
        }

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = exists.mapNotNull { entity ->
            val name = entity.displayName()
            if (nameMap.contains(name)) name to entity else let {
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
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
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

    @Suppress("UNCHECKED_CAST")
    fun parseRaw(text: String): List<AbstractBean>? {
        val parsed = ArrayList<AbstractBean>()
        fun addParsed(proxies: List<AbstractBean>) {
            if (proxies.isNotEmpty()) parsed.addAll(proxies)
        }

        try {
            val options = DumperOptions()
            val yaml = Yaml(YAMLConstructor(LoaderOptions()), Representer(options), options, object : Resolver() {
                override fun addImplicitResolver(tag: Tag, regexp: Pattern, first: String?, limit: Int) {
                    when (tag) {
                        Tag.FLOAT -> {}
                        Tag.BOOL -> super.addImplicitResolver(tag, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF", limit)
                        else -> super.addImplicitResolver(tag, regexp, first, limit)
                    }
                }
            }).apply {
                // https://github.com/SagerNet/SagerNet/blob/70e684bae81d4bb4203e860ab88c4319e88f944d/app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt#L229
                // IDK why but `!<str>` is obviously widely used in Clash ecology
                // https://github.com/search?q=!%3Cstr%3E&type=code
                // addTypeDescription(TypeDescription(String::class.java, "str"))
            }.loadAs(text, Map::class.java)
            (yaml["proxies"] as? List<Map<String, Any?>>)?.let { proxies ->
                parseClashProxies(proxies).takeIf { it.isNotEmpty() }?.let {
                    addParsed(it)
                }
            }
        } catch (_: Exception) {}
        var parsedJson = false
        try {
            parseJSONConfig(text).takeIf { it.isNotEmpty() }?.let {
                addParsed(it)
                parsedJson = true
            }
        } catch (_: Exception) {}
        if (!parsedJson) {
            try {
                parseCustomJSONConfig(text).takeIf { it.isNotEmpty() }?.let {
                    addParsed(it)
                }
            } catch (_: Exception) {}
        }
        val normalizedText = normalizeImportText(text)
        try {
            parseShareLinks(text.decodeBase64()).takeIf { it.isNotEmpty() }?.let {
                addParsed(it)
            }
        } catch (_: Exception) {}
        try {
            parseShareLinks(text).takeIf { it.isNotEmpty() }?.let {
                addParsed(it)
            }
        } catch (_: Exception) {}
        if (normalizedText != text) {
            try {
                parseShareLinks(normalizedText).takeIf { it.isNotEmpty() }?.let {
                    addParsed(it)
                }
            } catch (_: Exception) {}
            try {
                parseShareLinks(normalizedText.decodeBase64()).takeIf { it.isNotEmpty() }?.let {
                    addParsed(it)
                }
            } catch (_: Exception) {}
        }
        try {
            parseWireGuardConfig(text).takeIf { it.isNotEmpty() }?.let {
                addParsed(it)
            }
        } catch (_: Exception) {}
        if (normalizedText != text) {
            try {
                parseWireGuardConfig(normalizedText).takeIf { it.isNotEmpty() }?.let {
                    addParsed(it)
                }
            } catch (_: Exception) {}
        }
        if (parsed.isEmpty()) return null

        val unique = ArrayList<AbstractBean>(parsed.size)
        val seen = LinkedHashSet<String>()
        parsed.forEach { bean ->
            val key = listOf(
                bean.javaClass.name,
                bean.serverAddress,
                bean.serverPort,
                bean.displayName(),
            ).joinToString("\u0000")
            if (seen.add(key)) {
                unique.add(bean)
            }
        }
        return unique
    }

    private fun parseCustomJSONConfig(text: String): List<AbstractBean> {
        val content = stripJson(text, stripTrailingCommas = true)
        val jsonElement = parseJson(content, lenient = true)
        if (!jsonElement.isJsonObject) return listOf()

        val jsonObject = jsonElement.asJsonObject
        val type = when {
            jsonObject.contains("outbounds", ignoreCase = true) ||
                    jsonObject.contains("inbounds", ignoreCase = true) -> "v2ray"
            jsonObject.contains("protocol", ignoreCase = true) -> "v2ray_outbound"
            else -> return listOf()
        }

        val name = jsonObject.getString("remarks", ignoreCase = true)
            ?: jsonObject.getString("tag", ignoreCase = true)
            ?: "JSON Config"
        return listOf(ConfigBean().applyDefaultValues().apply {
            this.name = name
            this.type = type
            this.content = content
        })
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJSONConfig(text: String): List<AbstractBean> {
        val jsonElement = parseJson(stripJson(text, stripTrailingCommas = true))
        if (jsonElement.isJsonArray) {
            // https://github.com/XTLS/Xray-core/discussions/3765 WTF
            val beans = ArrayList<AbstractBean>()
            jsonElement.asJsonArray.forEach {
                if (!it.isJsonObject) {
                    return listOf()
                }
                val prefix = it.asJsonObject.getString("remarks", ignoreCase = true)
                val outbounds = it.asJsonObject.getArray("outbounds", ignoreCase = true)
                outbounds?.forEach { outbound ->
                    val parsed = parseV2RayOutbound(outbound)
                    if (!prefix.isNullOrEmpty()) {
                        parsed.forEach { bean ->
                            bean.initializeDefaultValues()
                            bean.name = "$prefix ${bean.displayName()}"
                        }
                    }
                    beans.addAll(parsed)
                }
            }
            return beans
        }
        if (!jsonElement.isJsonObject) {
            return listOf()
        }
        val jsonObject = jsonElement.asJsonObject
        val beans = ArrayList<AbstractBean>()
        when {
            jsonObject.contains("protocol", ignoreCase = true) -> {
                // V2Ray JSONv4 outbound or V2Ray JSONv5 outbound
                return parseV2Ray5Outbound(jsonObject).takeIf { it.isNotEmpty() }
                    ?: parseV2RayOutbound(jsonObject)
            }
            jsonObject.contains("proxies", ignoreCase = true) -> {
                // Clash YAML
                return listOf()
            }
            jsonObject.getInt("version") != null && jsonObject.contains("servers") -> {
                // SIP008
                val element = parseJson(text)
                if (!element.isJsonObject) {
                    return listOf()
                }
                element.asJsonObject.getArray("servers")?.forEach { server ->
                    parseShadowsocksConfig(server)?.let {
                        beans.add(it)
                    }
                }
                return beans
            }
            jsonObject.contains("type") -> {
                // sing-box outbound/endpoint
                return parseSingBoxEndpoint(jsonObject).takeIf { it.isNotEmpty() }
                    ?: parseSingBoxOutbound(jsonObject)
            }
            else -> {
                val outbounds = jsonObject.getArray("outbounds", ignoreCase = true)
                val endpoints = jsonObject.getArray("endpoints", ignoreCase = true)
                val isV2Ray = !outbounds.isNullOrEmpty() && outbounds[0].contains("protocol", ignoreCase = true)
                if (isV2Ray) {
                    // V2Ray JSONv4 or V2Ray JSONv5
                    outbounds.forEach {
                        beans.addAll(parseV2Ray5Outbound(it).takeIf { it.isNotEmpty() }
                            ?: parseV2RayOutbound(it))
                    }
                    return beans
                }
                val isSingBox = !endpoints.isNullOrEmpty() || (!outbounds.isNullOrEmpty() && outbounds[0].contains("type"))
                if (isSingBox) {
                    // sing-box
                    outbounds?.forEach {
                        beans.addAll(parseSingBoxOutbound(it))
                    }
                    endpoints?.forEach {
                        beans.addAll(parseSingBoxEndpoint(it))
                    }
                    return beans
                }
                return listOf()
            }
        }
    }
}

private class YAMLConstructor(
    options: LoaderOptions,
) : Constructor(options) {
    override fun constructObject(node: Node): Any? {
        when (node.tag) {
            Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.STR, Tag.SEQ, Tag.MAP, Tag.BINARY,
            Tag.TIMESTAMP, Tag.SET, Tag.OMAP, Tag.PAIRS, Tag.YAML, Tag.MERGE -> {
                return super.constructObject(node)
            }
        }
        // ignore unknown tags
        return when (node) {
            is MappingNode -> constructMapping(node)
            is SequenceNode -> constructSequence(node)
            is ScalarNode -> constructScalar(node)
            else -> null
        }
    }
}
