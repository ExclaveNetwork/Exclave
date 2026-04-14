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
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.takisoft.preferencex.PreferenceFragmentCompat
import com.takisoft.preferencex.SimpleMenuPreference
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.Key.MODE_VPN
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ColorPickerPreference
import io.nekohasekai.sagernet.widget.LinkOrContentPreference
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var isProxyApps: SwitchPreference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        listView.setPadding(0,0,0,dp2px(64))
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom + dp2px(64),
            )
            insets
        }
    }

    val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        // common
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val requireHttp = findPreference<SwitchPreference>(Key.REQUIRE_HTTP)!!
        val httpUsername = findPreference<EditTextPreference>(Key.HTTP_USERNAME)!!
        val httpPassword = findPreference<EditTextPreference>(Key.HTTP_PASSWORD)!!
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)!!
        val httpProxyException = findPreference<EditTextPreference>(Key.HTTP_PROXY_EXCEPTION)!!

        // app settings
        findPreference<ColorPickerPreference>(Key.APP_THEME)!!.setOnPreferenceChangeListener { _, newTheme ->
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                ActivityCompat.recreate(this)
            }
            true
        }

        findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            requireActivity().apply {
                ActivityCompat.recreate(this)
            }
            true
        }

        fun getLanguageDisplayName(code: String): String = run {
            return when (code) {
                "" -> getString(R.string.language_system_default)
                "ar" -> getString(R.string.language_ar_display_name)
                "en-US" -> getString(R.string.language_en_display_name)
                "es" -> getString(R.string.language_es_display_name)
                "fa" -> getString(R.string.language_fa_display_name)
                "fr" -> getString(R.string.language_fr_display_name)
                "id" -> getString(R.string.language_id_display_name)
                "it" -> getString(R.string.language_it_display_name)
                "ja" -> getString(R.string.language_ja_display_name)
                "nb-NO" -> getString(R.string.language_nb_NO_display_name)
                "ru" -> getString(R.string.language_ru_display_name)
                "tr" -> getString(R.string.language_tr_display_name)
                "zh-Hans-CN" -> getString(R.string.language_zh_Hans_CN_display_name)
                "zh-Hant-TW" -> getString(R.string.language_zh_Hant_TW_display_name)
                else -> Locale.forLanguageTag(code).displayName // just a fallback name from Java
            }
        }
        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)!!
        val locale = when (val value = AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
            // https://stackoverflow.com/questions/13291578/how-to-localize-an-android-app-in-indonesian-language
            // Some old Android versions still return "in".
            "in" -> "id"
            else -> value
        }
        appLanguage.summary = getLanguageDisplayName(locale)
        appLanguage.value = if (locale in resources.getStringArray(R.array.language_value)) locale else ""
        appLanguage.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newValue)) // "id" always works
            appLanguage.summary = getLanguageDisplayName(newValue)
            appLanguage.value = newValue
            true
        }

        val serviceMode = findPreference<SimpleMenuPreference>(Key.SERVICE_MODE)!!
        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val mtu = findPreference<EditTextPreference>(Key.MTU)!!
        val enableVPNInterfaceIPv6Address = findPreference<SwitchPreference>(Key.ENABLE_VPN_INTERFACE_IPV6_ADDRESS)!!
        val allowAppsBypassVpn = findPreference<SwitchPreference>(Key.ALLOW_APPS_BYPASS_VPN)!!
        val meteredNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        val enablePcap = findPreference<SwitchPreference>(Key.ENABLE_PCAP)!!
        val discardICMP = findPreference<SwitchPreference>(Key.DISCARD_ICMP)!!
        val appTrafficStatistics = findPreference<SwitchPreference>(Key.APP_TRAFFIC_STATISTICS)!!
        serviceMode.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            tunImplementation.isEnabled = newValue == MODE_VPN
            mtu.isEnabled = newValue == MODE_VPN
            enableVPNInterfaceIPv6Address.isEnabled = newValue == MODE_VPN
            allowAppsBypassVpn.isEnabled = newValue == MODE_VPN
            discardICMP.isEnabled = newValue == MODE_VPN
            meteredNetwork.isEnabled = newValue == MODE_VPN
            enablePcap.isEnabled = newValue == MODE_VPN && tunImplementation.value.toInt() == TunImplementation.GVISOR
            appTrafficStatistics.isEnabled = newValue == MODE_VPN
            isProxyApps.isEnabled = newValue == MODE_VPN
            bypassLan.isEnabled = newValue == MODE_VPN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendHttpProxy.isVisible = requireHttp.isChecked && newValue == MODE_VPN
                        && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                        && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                httpProxyException.isVisible = requireHttp.isChecked && newValue == MODE_VPN
                        && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                        && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                        && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
            }
            if (SagerNet.started) {
                SagerNet.stopService()
                runOnMainDispatcher {
                    delay(300)
                    SagerNet.startService()
                }
            }
            true
        }
        tunImplementation.isEnabled = serviceMode.value == MODE_VPN
        tunImplementation.setOnPreferenceChangeListener { _, newValue ->
            enablePcap.isEnabled = serviceMode.value == MODE_VPN && (newValue as String).toInt() == TunImplementation.GVISOR
            if (SagerNet.started) {
                SagerNet.stopService()
                runOnMainDispatcher {
                    SagerNet.startService()
                }
            }
            true
        }
        mtu.isEnabled = serviceMode.value == MODE_VPN
        mtu.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        mtu.onPreferenceChangeListener = reloadListener
        enableVPNInterfaceIPv6Address.isEnabled = serviceMode.value == MODE_VPN
        enableVPNInterfaceIPv6Address.onPreferenceChangeListener = reloadListener
        allowAppsBypassVpn.isEnabled = serviceMode.value == MODE_VPN
        allowAppsBypassVpn.onPreferenceChangeListener = reloadListener
        if (Build.VERSION.SDK_INT < 28) {
            meteredNetwork.remove()
        }
        meteredNetwork.isEnabled = serviceMode.value == MODE_VPN
        meteredNetwork.onPreferenceChangeListener = reloadListener
        enablePcap.isEnabled = serviceMode.value == MODE_VPN && tunImplementation.value.toInt() == TunImplementation.GVISOR
        enablePcap.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                val path = File(
                    app.getExternalFilesDir(null)?.apply { mkdirs() } ?: app.filesDir,
                    "pcap"
                ).absolutePath
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.pcap)
                    setMessage(resources.getString(R.string.pcap_notice, path))
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        needReload()
                    }
                    setNegativeButton(android.R.string.copy) { _, _ ->
                        SagerNet.trySetPrimaryClip(path)
                        snackbar(R.string.copy_success).show()
                    }
                }.show()
            }
            needReload()
            true
        }
        discardICMP.isEnabled = serviceMode.value == MODE_VPN
        discardICMP.onPreferenceChangeListener = reloadListener
        appTrafficStatistics.isEnabled = serviceMode.value == MODE_VPN
        appTrafficStatistics.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            if (newValue) {
                PackageCache.awaitLoadSync()
            }
            needReload()
            true
        }

        val profileTrafficStatistics = findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<Preference>(Key.SPEED_INTERVAL)!!
        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        profileTrafficStatistics.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            speedInterval.isEnabled = newValue
            showDirectSpeed.isEnabled = newValue
            needReload()
            true
        }
        speedInterval.isEnabled = profileTrafficStatistics.isChecked
        speedInterval.onPreferenceChangeListener = reloadListener
        showDirectSpeed.isEnabled = profileTrafficStatistics.isChecked
        showDirectSpeed.onPreferenceChangeListener = reloadListener

        findPreference<SimpleMenuPreference>(Key.LOG_LEVEL)!!.setOnPreferenceChangeListener { _, newValue ->
            if ((newValue as String).toInt() == LogLevel.DEBUG && !DataStore.logLevelDebugWarningDisable) {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setMessage(R.string.debug_log_sum)
                    setPositiveButton(android.R.string.ok, null)
                    setNeutralButton(R.string.do_not_show_again, { _, _ ->
                        DataStore.logLevelDebugWarningDisable = true
                    })
                }.show()
            }
            needReload()
            true
        }

        findPreference<SimpleMenuPreference>(Key.PROVIDER_ROOT_CA)!!.setOnPreferenceChangeListener { _, newValue ->
           if ((newValue as String).toInt() == RootCAProvider.CUSTOM) {
                runOnMainDispatcher {
                    val context = requireContext()
                    MaterialAlertDialogBuilder(context)
                        .setMessage(getString(R.string.custom_root_ca_hint, context.packageName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            true
        }

        // route settings
        findPreference<Preference>(Key.ROUTE_MODE)!!.onPreferenceChangeListener = reloadListener
        isProxyApps.isEnabled = serviceMode.value == MODE_VPN
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            newValue as Boolean
        }

        bypassLan.isEnabled = serviceMode.value == MODE_VPN
        bypassLan.setOnPreferenceChangeListener { _, _ ->
            needReload()
            true
        }

        findPreference<Preference>(Key.DOMAIN_STRATEGY)!!.onPreferenceChangeListener = reloadListener

        val trafficSniffing = findPreference<SwitchPreference>(Key.TRAFFIC_SNIFFING)!!
        val destinationOverride = findPreference<SwitchPreference>(Key.DESTINATION_OVERRIDE)!!
        val hijackDns = findPreference<SwitchPreference>(Key.HIJACK_DNS)!!
        trafficSniffing.setOnPreferenceChangeListener { _, newValue ->
            destinationOverride.isEnabled = newValue as Boolean
            hijackDns.isEnabled = newValue
            needReload()
            true
        }
        destinationOverride.isEnabled = trafficSniffing.isChecked
        destinationOverride.onPreferenceChangeListener = reloadListener
        hijackDns.isEnabled = trafficSniffing.isChecked
        hijackDns.onPreferenceChangeListener = reloadListener

        findPreference<SimpleMenuPreference>(Key.OUTBOUND_DOMAIN_STRATEGY)!!.onPreferenceChangeListener = reloadListener
        findPreference<SimpleMenuPreference>(Key.OUTBOUND_DOMAIN_STRATEGY_FOR_DIRECT)!!.onPreferenceChangeListener = reloadListener
        findPreference<SimpleMenuPreference>(Key.OUTBOUND_DOMAIN_STRATEGY_FOR_SERVER)!!.onPreferenceChangeListener = reloadListener

        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)!!
        val rulesGeositeUrl = findPreference<LinkOrContentPreference>(Key.RULES_GEOSITE_URL)!!
        val rulesGeoipUrl = findPreference<LinkOrContentPreference>(Key.RULES_GEOIP_URL)!!
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            rulesGeositeUrl.isVisible = provider == 3
            rulesGeoipUrl.isVisible = provider == 3
            true
        }
        rulesGeositeUrl.isVisible = DataStore.rulesProvider == 3
        rulesGeoipUrl.isVisible = DataStore.rulesProvider == 3

        // protocol settings
        val enableFragment = findPreference<SwitchPreference>(Key.ENABLE_FRAGMENT)!!
        val enableFragmentForDirect = findPreference<SwitchPreference>(Key.ENABLE_FRAGMENT_FOR_DIRECT)!!
        val fragmentMethod = findPreference<SimpleMenuPreference>(Key.FRAGMENT_METHOD)!!
        enableFragment.setOnPreferenceChangeListener { _, newValue ->
            newValue as Boolean
            enableFragmentForDirect.isVisible = newValue
            fragmentMethod.isVisible = newValue
            true
        }
        enableFragmentForDirect.isVisible = enableFragment.isChecked
        fragmentMethod.isVisible = enableFragment.isChecked

        // DNS settings
        findPreference<EditTextPreference>(Key.REMOTE_DNS)!!.onPreferenceChangeListener = reloadListener
        findPreference<SimpleMenuPreference>(Key.REMOTE_DNS_QUERY_STRATEGY)!!.onPreferenceChangeListener = reloadListener
        findPreference<EditTextPreference>(Key.EDNS_CLIENT_IP)!!.onPreferenceChangeListener = reloadListener

        val useLocalDnsAsDirectDns = findPreference<SwitchPreference>(Key.USE_LOCAL_DNS_AS_DIRECT_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        directDns.onPreferenceChangeListener = reloadListener
        useLocalDnsAsDirectDns.setOnPreferenceChangeListener { _, newValue ->
            directDns.isEnabled = newValue == false
            needReload()
            true
        }
        directDns.isEnabled = !useLocalDnsAsDirectDns.isChecked
        findPreference<SimpleMenuPreference>(Key.DIRECT_DNS_QUERY_STRATEGY)!!.onPreferenceChangeListener = reloadListener

        val useLocalDnsAsBootstrapDns = findPreference<SwitchPreference>(Key.USE_LOCAL_DNS_AS_BOOTSTRAP_DNS)!!
        val bootstrapDns = findPreference<EditTextPreference>(Key.BOOTSTRAP_DNS)!!
        bootstrapDns.onPreferenceChangeListener = reloadListener
        useLocalDnsAsBootstrapDns.setOnPreferenceChangeListener { _, newValue ->
            bootstrapDns.isEnabled = newValue == false
            needReload()
            true
        }
        bootstrapDns.isEnabled = !useLocalDnsAsBootstrapDns.isChecked

        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)!!
        dnsHosts.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts.dialogMessage = getString(R.string.one_per_line_format, "example.com 127.0.0.1\nwww.example.com 127.0.0.1 127.0.0.2")
        dnsHosts.onPreferenceChangeListener = reloadListener

        findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!.onPreferenceChangeListener = reloadListener

        // inbound settings
        findPreference<SwitchPreference>(Key.ALLOW_ACCESS)!!.onPreferenceChangeListener = reloadListener

        val allowAccess = findPreference<SwitchPreference>(Key.ALLOW_ACCESS)!!
        allowAccess.onPreferenceChangeListener = reloadListener
        val requireSocks = findPreference<SwitchPreference>(Key.REQUIRE_SOCKS)!!
        val requireTransproxy = findPreference<SwitchPreference>(Key.REQUIRE_TRANSPROXY)!!
        val requireDns = findPreference<SwitchPreference>(Key.REQUIRE_DNS_INBOUND)!!
        allowAccess.isVisible = requireSocks.isChecked || requireHttp.isChecked || requireTransproxy.isChecked || requireDns.isChecked

        val portSocks5 = findPreference<EditTextPreference>(Key.SOCKS_PORT)!!
        portSocks5.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        portSocks5.isVisible = requireSocks.isChecked
        portSocks5.onPreferenceChangeListener = reloadListener
        val socks5Username = findPreference<EditTextPreference>(Key.SOCKS_USERNAME)!!
        socks5Username.isVisible = requireSocks.isChecked
        socks5Username.onPreferenceChangeListener = reloadListener
        val socks5Password = findPreference<EditTextPreference>(Key.SOCKS_PASSWORD)!!
        socks5Password.summaryProvider = ProfileSettingsActivity.PasswordSummaryProvider
        socks5Password.isVisible = requireSocks.isChecked
        socks5Password.onPreferenceChangeListener = reloadListener
        val socks5UDP = findPreference<SwitchPreference>(Key.SOCKS_UDP)!!
        socks5UDP.isVisible = requireSocks.isChecked
        socks5UDP.onPreferenceChangeListener = reloadListener
        requireSocks.setOnPreferenceChangeListener { _, newValue ->
            portSocks5.isVisible = newValue as Boolean
            socks5Username.isVisible = newValue
            socks5Password.isVisible = newValue
            socks5UDP.isVisible = newValue
            allowAccess.isVisible = newValue || requireHttp.isChecked || requireTransproxy.isChecked || requireDns.isChecked
            needReload()
            true
        }

        val portHttp = findPreference<EditTextPreference>(Key.HTTP_PORT)!!
        portHttp.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        portHttp.isVisible = requireHttp.isChecked
        portHttp.onPreferenceChangeListener = reloadListener
        httpUsername.isVisible = requireHttp.isChecked
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            httpUsername.onPreferenceChangeListener = reloadListener
        } else {
            httpUsername.onPreferenceChangeListener = { _, newValue ->
                newValue as String
                appendHttpProxy.isVisible = serviceMode.value == MODE_VPN && newValue.isEmpty() && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                httpProxyException.isVisible = serviceMode.value == MODE_VPN && newValue.isEmpty()
                        && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                        && newValue.isEmpty() && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                needReload()
                true
            }
        }
        httpPassword.summaryProvider = ProfileSettingsActivity.PasswordSummaryProvider
        httpPassword.isVisible = requireHttp.isChecked
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            httpPassword.onPreferenceChangeListener = reloadListener
        } else {
            httpPassword.onPreferenceChangeListener = { _, newValue ->
                newValue as String
                appendHttpProxy.isVisible = serviceMode.value == MODE_VPN && newValue.isEmpty() && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                httpProxyException.isVisible = serviceMode.value == MODE_VPN && newValue.isEmpty()
                        && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                        && newValue.isEmpty() && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                needReload()
                true
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requireHttp.setOnPreferenceChangeListener { _, newValue ->
                portHttp.isVisible = newValue as Boolean
                httpUsername.isVisible = newValue
                httpPassword.isVisible = newValue
                allowAccess.isVisible = requireSocks.isChecked || newValue || requireTransproxy.isChecked || requireDns.isChecked
                needReload()
                true
            }
            appendHttpProxy.remove()
            httpProxyException.remove()
        } else {
            requireHttp.setOnPreferenceChangeListener { _, newValue ->
                portHttp.isVisible = newValue as Boolean
                httpUsername.isVisible = newValue
                httpPassword.isVisible = newValue
                appendHttpProxy.isVisible = newValue && serviceMode.value == MODE_VPN
                        && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                        && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                httpProxyException.isVisible = newValue && serviceMode.value == MODE_VPN
                        && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                        && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                        && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
                allowAccess.isVisible = requireSocks.isChecked || newValue || requireTransproxy.isChecked || requireDns.isChecked
                needReload()
                true
            }
            appendHttpProxy.isVisible = requireHttp.isChecked && serviceMode.value == MODE_VPN
                    && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                    && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
            appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
                httpProxyException.isVisible = newValue as Boolean
                needReload()
                true
            }
            httpProxyException.isVisible = requireHttp.isChecked && serviceMode.value == MODE_VPN
                    && appendHttpProxy.isVisible && appendHttpProxy.isChecked
                    && httpUsername.isVisible && httpUsername.text.isNullOrEmpty()
                    && httpPassword.isVisible && httpPassword.text.isNullOrEmpty()
            httpProxyException.onPreferenceChangeListener = reloadListener
        }

        val transproxyPort = findPreference<EditTextPreference>(Key.TRANSPROXY_PORT)!!
        requireTransproxy.setOnPreferenceChangeListener { _, newValue ->
            transproxyPort.isVisible = newValue as Boolean
            allowAccess.isVisible = requireSocks.isChecked || requireHttp.isChecked || newValue || requireDns.isChecked
            needReload()
            true
        }
        transproxyPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        transproxyPort.isVisible = requireTransproxy.isChecked
        transproxyPort.onPreferenceChangeListener = reloadListener

        val portLocalDns = findPreference<EditTextPreference>(Key.LOCAL_DNS_PORT)!!
        requireDns.setOnPreferenceChangeListener { _, newValue ->
            portLocalDns.isVisible = newValue as Boolean
            allowAccess.isVisible = requireSocks.isChecked || requireHttp.isChecked || requireTransproxy.isChecked || newValue
            needReload()
            true
        }
        portLocalDns.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        portLocalDns.isVisible = requireDns.isChecked
        portLocalDns.onPreferenceChangeListener = reloadListener

        findPreference<EditTextPreference>(Key.PPROF_SERVER)!!.apply {
            isVisible = DataStore.enableDebug
            onPreferenceChangeListener = reloadListener
        }

        findPreference<EditTextPreference>(Key.EXPERIMENTAL_FLAGS)!!.isVisible = DataStore.enableDebug

        // misc settings
        findPreference<SwitchPreference>(Key.SHOW_GROUP_NAME)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!.onPreferenceChangeListener = reloadListener
        findPreference<SimpleMenuPreference>(Key.FAB_STYLE)!!.setOnPreferenceChangeListener { _, _ ->
            requireActivity().apply {
                this.finish()
                startActivity(intent)
            }
            true
        }
    }


    override fun onResume() {
        super.onResume()

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
    }

}