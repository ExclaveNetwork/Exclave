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

package io.nekohasekai.sagernet.plugin

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.os.bundleOf
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import io.nekohasekai.sagernet.plugin.PluginContract.METADATA_KEY_ID
import java.io.File
import java.io.FileNotFoundException

object PluginManager {

    class PluginNotFoundException(val plugin: String) : FileNotFoundException(plugin),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() =
            SagerNet.application.getString(R.string.plugin_unknown, plugin)
    }

    private var receiver: BroadcastReceiver? = null
    private var cachedPlugins: PluginList? = null
    fun fetchPlugins() = synchronized(this) {
        if (receiver == null) receiver = SagerNet.application.listenForPackageChanges {
            synchronized(this) {
                receiver = null
                cachedPlugins = null
            }
        }
        if (cachedPlugins == null) cachedPlugins = PluginList()
        cachedPlugins!!
    }

    private fun buildUri(id: String, authority: String) = Uri.Builder()
        .scheme(PluginContract.SCHEME)
        .authority(authority)
        .path("/$id")
        .build()

    data class InitResult(
        val path: String,
    )

    @Throws(Throwable::class)
    fun init(pluginId: String): InitResult? {
        if (pluginId.isEmpty()) return null
        var throwable: Throwable? = null

        try {
            val result = initNative(pluginId)
            if (result != null) return result
        } catch (t: Throwable) {
            throwable = t
        }

        throw throwable ?: PluginNotFoundException(pluginId)
    }

    private fun initNative(pluginId: String): InitResult? {
        val flags = PackageManager.GET_META_DATA
        var providers = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(PluginContract.ACTION_NATIVE_PLUGIN, buildUri(pluginId, "com.github.dyhkwong.sagernet")), flags)
            .filter { it.providerInfo.exported }
        if (providers.isEmpty()) {
            try {
                initNativeInternal(pluginId)?.also { return InitResult(it) }
            } catch (_: Throwable) {
            }
        }
        if (providers.isEmpty()) {
            providers = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(PluginContract.ACTION_NATIVE_PLUGIN, buildUri(pluginId, "fr.husi")), flags)
            .filter { it.providerInfo.exported }
        }
        if (providers.isEmpty()) {
            providers = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(PluginContract.ACTION_NATIVE_PLUGIN, buildUri(pluginId, "io.nekohasekai.sagernet")), flags)
            .filter { it.providerInfo.exported }
        }
        if (providers.isEmpty()) {
            providers = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(PluginContract.ACTION_NATIVE_PLUGIN, buildUri(pluginId, "moe.matsuri.lite")), flags)
            .filter { it.providerInfo.exported }
        }
        if (providers.isEmpty()) {
            providers = SagerNet.application.packageManager.queryIntentContentProviders(
                Intent(PluginContract.ACTION_NATIVE_PLUGIN), PackageManager.GET_META_DATA
            ).filter {
                it.providerInfo.exported &&
                it.providerInfo.metaData.containsKey(METADATA_KEY_ID) &&
                it.providerInfo.metaData.getString(METADATA_KEY_ID) == pluginId
            }
            if (providers.size > 1) {
                providers = listOf(providers[0]) // What if there is more than one?
            }
        }
        if (providers.isEmpty()) return null
        if (providers.size > 1) {
            val message =
                "Conflicting plugins found from: ${providers.joinToString { it.providerInfo.packageName }}"
            Toast.makeText(SagerNet.application, message, Toast.LENGTH_LONG).show()
            throw IllegalStateException(message)
        }
        val provider = providers.single().providerInfo
        var failure: Throwable? = null
        try {
            initNativeFaster(provider)?.also { return InitResult(it) }
        } catch (t: Throwable) {
            Logs.w("Initializing native plugin faster mode failed")
            failure = t
        }

        val uri = Uri.Builder().apply {
            scheme(ContentResolver.SCHEME_CONTENT)
            authority(provider.authority)
        }.build()
        try {
            return initNativeFast(SagerNet.application.contentResolver,
                pluginId,
                uri)?.let { InitResult(it) }
        } catch (t: Throwable) {
            Logs.w("Initializing native plugin fast mode failed")
            failure?.also { t.addSuppressed(it) }
            failure = t
            throw failure
        }
    }

    private fun initNativeInternal(pluginId: String): String? {
        return when (pluginId) {
            "naive-plugin" -> "libnaive.so"
            else -> return null
        }.let {
            File(SagerNet.application.applicationInfo.nativeLibraryDir, it).apply {
                check(canExecute())
            }.absolutePath
        }
    }

    private fun initNativeFaster(provider: ProviderInfo): String? {
        return provider.loadString(PluginContract.METADATA_KEY_EXECUTABLE_PATH)
            ?.let { relativePath ->
                File(provider.applicationInfo.nativeLibraryDir).resolve(relativePath).apply {
                    check(canExecute())
                }.absolutePath
            }
    }

    private fun initNativeFast(cr: ContentResolver, pluginId: String, uri: Uri): String? {
        return cr.call(uri, PluginContract.METHOD_GET_EXECUTABLE, null,bundleOf())
            ?.getString(PluginContract.EXTRA_ENTRY)?.also {
                check(File(it).canExecute())
            }
    }

    fun ComponentInfo.loadString(key: String) = when (val value = metaData.get(key)) {
        is String -> value
        is Int -> SagerNet.application.packageManager.getResourcesForApplication(applicationInfo)
            .getString(value)
        null -> null
        else -> error("meta-data $key has invalid type ${value.javaClass}")
    }
}
