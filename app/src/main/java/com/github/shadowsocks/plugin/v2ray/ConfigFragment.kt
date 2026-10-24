/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.plugin.v2ray

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.PluginOptions
import io.nekohasekai.sagernet.R

class ConfigFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
    private val mode by lazy { findPreference<ListPreference>("mode")!! }
    private val host by lazy { findPreference<EditTextPreference>("host")!! }
    private val path by lazy { findPreference<EditTextPreference>("path")!! }
    private val serviceName by lazy { findPreference<EditTextPreference>("serviceName")!! }
    private val mux by lazy { findPreference<EditTextPreference>("mux")!! }
    private val certRaw by lazy { findPreference<EditTextPreference>("certRaw")!! }
    private val loglevel by lazy { findPreference<ListPreference>("loglevel")!! }

    private fun readMode(value: String = mode.value) = when (value) {
        "websocket-http" -> Pair(null, false)
        "websocket-tls" -> Pair(null, true)
        "quic-tls" -> Pair("quic", false)
        "grpc" -> Pair("grpc", false)
        "grpc-tls" -> Pair("grpc", true)
        else -> {
            check(false)
            Pair(null, false)
        }
    }

    val options get() = PluginOptions().apply {
        val (mode, tls) = readMode()
        putWithDefault("mode", mode)
        if (tls) this["tls"] = null
        putWithDefault("host", host.text, "cloudfront.com")
        putWithDefault("path", path.text, "/")
        putWithDefault("mux", mux.text, "1")
        if (mode == "grpc") {
            putWithDefault("serviceName", serviceName.text, "")
        }
        putWithDefault("certRaw", certRaw.text?.replace("\n", ""), "")
        putWithDefault("loglevel", loglevel.value, "warning")
    }

    fun onInitializePluginOptions(options: PluginOptions) {
        mode.value = when (options["mode"] ?: "websocket") {
            "quic" -> "quic-tls"
            "websocket" if "tls" in options -> "websocket-tls"
            "grpc" if "tls" !in options -> "grpc"
            "grpc" if "tls" in options -> "grpc-tls"
            else -> "websocket-http"
        }.also { onModeChange(it) }
        host.text = options["host"] ?: "cloudfront.com"
        path.text = options["path"] ?: "/"
        mux.text = options["mux"] ?: "1"
        certRaw.text = options["certRaw"]
        serviceName.text = options["serviceName"]
        loglevel.value = options["loglevel"] ?: "warning"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.v2ray_plugin_config)
        mode.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            onModeChange(newValue as String)
            if ((preference as ListPreference).value != newValue) {
                (requireActivity() as? ConfigActivity)?.let {
                    it.dirty = true
                    it.onBackPressedCallback.isEnabled = true
                }
            }
            true
        }
        host.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_TEXT_VARIATION_URI
            it.setSelection(it.text.length)
        }
        host.onPreferenceChangeListener = this
        path.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_TEXT_VARIATION_URI
            it.setSelection(it.text.length)
        }
        path.onPreferenceChangeListener = this
        mux.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.filters = arrayOf(InputFilter.LengthFilter(4))
            it.setSelection(it.text.length)
        }
        mux.onPreferenceChangeListener = this
        certRaw.setOnBindEditTextListener {
            it.setSelection(it.text.length)
        }
        certRaw.onPreferenceChangeListener = this
        serviceName.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_TEXT_VARIATION_URI
            it.setSelection(it.text.length)
        }
        serviceName.onPreferenceChangeListener = this
        loglevel.onPreferenceChangeListener = this
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
    }

    private fun onModeChange(modeValue: String) {
        val (mode, tls) = readMode(modeValue)
        path.isEnabled = mode == null
        mux.isEnabled = mode == null
        serviceName.isVisible = mode == "grpc"
        certRaw.isEnabled = (mode == null && tls) || (mode == "quic") || (mode == "grpc" && tls)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val oldValue = when (preference) {
            is EditTextPreference -> preference.text
            is ListPreference -> preference.value
            else -> null
        }
        if (oldValue != newValue) {
            (requireActivity() as? ConfigActivity)?.let {
                it.dirty = true
                it.onBackPressedCallback.isEnabled = true
            }
        }
        return true
    }
}
