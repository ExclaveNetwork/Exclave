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

package com.github.shadowsocks.plugin

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.widget.Toast
import androidx.core.os.bundleOf
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import java.io.File
import java.io.FileNotFoundException

object PluginManager {
    class PluginNotFoundException(val plugin: String) : FileNotFoundException(plugin),
            BaseService.ExpectedException {
        override fun getLocalizedMessage() = SagerNet.application.getString(R.string.plugin_unknown, plugin)
    }

    private var receiver: BroadcastReceiver? = null
    private var cachedPlugins: PluginList? = null
    private var cachedPluginsSkipInternal: PluginList? = null
    fun fetchPlugins(skipInternal: Boolean) = synchronized(this) {
        if (receiver == null) receiver = SagerNet.application.listenForPackageChanges {
            synchronized(this) {
                receiver = null
                cachedPlugins = null
                cachedPluginsSkipInternal = null
            }
        }
        if (skipInternal) {
            if (cachedPlugins == null) cachedPlugins = PluginList(skipInternal = true)
            cachedPlugins!!
        } else {
            if (cachedPluginsSkipInternal == null) cachedPluginsSkipInternal = PluginList(skipInternal = false)
            cachedPluginsSkipInternal!!
        }
    }

    private fun buildUri(id: String) = Uri.Builder()
            .scheme(PluginContract.SCHEME)
            .authority(PluginContract.AUTHORITY)
            .path("/$id")
            .build()
    fun buildIntent(id: String, action: String): Intent = Intent(action, buildUri(id))

    data class InitResult(
            val path: String,
            val options: PluginOptions,
            val isV2: Boolean = false,
    )

    // the following parts are meant to be used by :bg
    @Throws(Throwable::class)
    fun init(configuration: PluginConfiguration): InitResult? {
        if (configuration.selected.isEmpty()) return null
        var throwable: Throwable? = null

        try {
            val result = initNative(configuration)
            if (result != null) return result
        } catch (t: Throwable) {
            throwable = t
        }

        // add other plugin types here

        throw throwable ?: PluginNotFoundException(configuration.selected)
    }

    private fun initNative(configuration: PluginConfiguration): InitResult? {
        val flags = PackageManager.GET_META_DATA
        val providers = SagerNet.application.packageManager.queryIntentContentProviders(
                Intent(PluginContract.ACTION_NATIVE_PLUGIN, buildUri(configuration.selected)), flags)
                .filter { it.providerInfo.exported }
        if (providers.isEmpty()) return null
        if (providers.size > 1) {
            val message = "Conflicting plugins found from: ${providers.joinToString { it.providerInfo.packageName }}"
            Toast.makeText(SagerNet.application, message, Toast.LENGTH_LONG).show()
            throw IllegalStateException(message)
        }
        val provider = providers.single().providerInfo
        val options = configuration.getOptions { provider.loadString(PluginContract.METADATA_KEY_DEFAULT_CONFIG) }
        val isV2 = (provider.applicationInfo.metaData?.getString(PluginContract.METADATA_KEY_VERSION)
            ?.substringBefore('.')
            ?.toIntOrNull() ?: 0) >= 2
        var failure: Throwable? = null
        try {
            initNativeFaster(provider)?.also { return InitResult(it, options, isV2) }
        } catch (t: Throwable) {
            Logs.w("Initializing native plugin faster mode failed")
            failure = t
        }

        val uri = Uri.Builder().apply {
            scheme(ContentResolver.SCHEME_CONTENT)
            authority(provider.authority)
        }.build()
        try {
            return initNativeFast(SagerNet.application.contentResolver, options, uri)?.let { InitResult(it, options, isV2) }
        } catch (t: Throwable) {
            Logs.w("Initializing native plugin fast mode failed")
            failure?.also { t.addSuppressed(it) }
            failure = t
            throw failure
        }
    }

    private fun initNativeFaster(provider: ProviderInfo): String? {
        return provider.loadString(PluginContract.METADATA_KEY_EXECUTABLE_PATH)?.let { relativePath ->
            File(provider.applicationInfo.nativeLibraryDir).resolve(relativePath).apply {
                check(canExecute())
            }.absolutePath
        }
    }

    private fun initNativeFast(cr: ContentResolver, options: PluginOptions, uri: Uri): String? {
        return cr.call(uri, PluginContract.METHOD_GET_EXECUTABLE, null,
                bundleOf(PluginContract.EXTRA_OPTIONS to options.id))?.getString(PluginContract.EXTRA_ENTRY)?.also {
            check(File(it).canExecute())
        }
    }

    fun ComponentInfo.loadString(key: String) = when (val value = metaData.get(key)) {
        is String -> value
        is Int -> SagerNet.application.packageManager.getResourcesForApplication(applicationInfo).getString(value)
        null -> null
        else -> error("meta-data $key has invalid type ${value.javaClass}")
    }
}
