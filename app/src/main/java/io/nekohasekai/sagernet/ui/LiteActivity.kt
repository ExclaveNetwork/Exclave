package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.AutoVpnManager
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.FloatingSwitchButton
import io.nekohasekai.sagernet.bg.GroupSourceSyncer
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutLiteMainBinding
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A focused front door for the Lite product flavor. */
class LiteActivity : ThemedActivity(), SagerConnection.Callback, AutoVpnManager.Listener {

    private lateinit var binding: LayoutLiteMainBinding
    private val connection = SagerConnection(true)
    private var serviceState = BaseService.State.Idle
    private var activeProfileName = ""
    private var syncingFloatingToggle = false
    private var syncingModeToggle = false
    private var selectedAutoMode = AutoVpnManager.MODE_ANY
    private var startRequestedFromUi = false
    private var trafficTxRate = 0L
    private var trafficRxRate = 0L
    private var pingTesting = false
    private var manualPingStatus: String? = null
    private var initialGroupSourceSyncStarted = false
    private var releaseUpdateCheckScheduled = false

    private val statusPoll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || !::binding.isInitialized) return
            updateUi()
            binding.liteRoot.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) { denied ->
        if (denied) {
            startRequestedFromUi = false
            snackbar(R.string.vpn_permission_denied).show()
            updateUi()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutLiteMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyLiteDefaultSettingsOnce()

        binding.liteToolbar.setOnMenuItemClickListener(::onToolbarItemSelected)
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !syncingModeToggle) {
                selectedAutoMode = modeForButton(checkedId)
                AutoVpnManager.selectMode(selectedAutoMode)
                updateUi()
            }
        }
        selectedAutoMode = storedMode()
        syncingModeToggle = true
        binding.modeGroup.check(buttonForMode(selectedAutoMode))
        syncingModeToggle = false
        binding.liteConnect.setOnClickListener(::onConnectClicked)
        binding.liteCheckPing.setOnClickListener {
            checkPingNow()
        }
        binding.liteSwitchProfile.setOnClickListener {
            AutoVpnManager.switchNowFromApp()
        }
        binding.liteSwitchGeminiOnce.setOnClickListener {
            AutoVpnManager.switchGeminiSoftFromApp()
        }
        binding.liteSwitchGeminiRemember.setOnClickListener {
            AutoVpnManager.switchGeminiAndRememberFromApp()
        }
        binding.liteFloatingSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!syncingFloatingToggle) setFloatingSwitchEnabled(enabled)
        }

        connection.connect(this, this)
        AutoVpnManager.addListener(this)
        updateUi()
        syncGroupSourceIfProfilesEmpty()
    }

    override fun onResume() {
        super.onResume()
        syncFloatingToggle()
        if (DataStore.autoVpnFloatingSwitchEnabled) {
            FloatingSwitchButton.sync(applicationContext)
        }
        updateUi()
        binding.liteRoot.removeCallbacks(statusPoll)
        binding.liteRoot.post(statusPoll)
        scheduleReleaseUpdateCheck()
    }

    private fun scheduleReleaseUpdateCheck() {
        if (releaseUpdateCheckScheduled) return
        releaseUpdateCheckScheduled = true
        lifecycleScope.launch {
            delay(1200)
            if (isFinishing || isDestroyed) return@launch
            val update = ReleaseUpdateChecker.findAvailableUpdate() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            MaterialAlertDialogBuilder(this@LiteActivity)
                .setTitle(getString(R.string.app_update_available_title, update.version))
                .setMessage(
                    getString(
                        R.string.app_update_available_message,
                        BuildConfig.VERSION_NAME,
                        update.version,
                        getString(if (update.prerelease) R.string.app_update_prerelease else R.string.app_update_stable)
                    )
                )
                .setNegativeButton(R.string.app_update_later, null)
                .setPositiveButton(R.string.app_update_download) { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }.onFailure {
                        snackbar(R.string.action_export_err).show()
                    }
                }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bandwidthTimeout = TRAFFIC_UPDATE_INTERVAL_MS
    }

    override fun onPause() {
        binding.liteRoot.removeCallbacks(statusPoll)
        super.onPause()
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0L
        super.onStop()
    }

    override fun onDestroy() {
        binding.liteRoot.removeCallbacks(statusPoll)
        AutoVpnManager.removeListener(this)
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun onAutoVpnStateChanged() {
        if (!isFinishing && !isDestroyed) updateUi()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        serviceState = state
        when {
            !profileName.isNullOrBlank() -> activeProfileName = profileName
            !state.canStop -> {
                activeProfileName = ""
                trafficTxRate = 0L
                trafficRxRate = 0L
            }
        }
        if (state.canStop && activeProfileName.isBlank()) refreshActiveProfileName()
        updateUi()
        if (!msg.isNullOrBlank()) snackbar(msg).show()
    }

    override fun onServiceConnected(service: ISagerNetService) {
        serviceState = try {
            BaseService.State.entries[service.state].also { SagerNet.started = it.canStop }
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
        if (serviceState.canStop) refreshActiveProfileName()
        updateUi()
    }

    override fun onServiceDisconnected() {
        serviceState = BaseService.State.Idle
        activeProfileName = ""
        trafficTxRate = 0L
        trafficRxRate = 0L
        updateUi()
    }

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        snackbar(getString(R.string.plugin_unknown, pluginName)).show()
    }

    override fun routeAlert(type: Int, routeName: String) {
        snackbar(getString(R.string.lite_route_alert, routeName)).show()
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats, isCurrent: Boolean) {
        if (!isCurrent) return
        trafficTxRate = stats.txRateProxy
        trafficRxRate = stats.rxRateProxy
        updateUi()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.liteRoot, text, Snackbar.LENGTH_LONG)

    private fun onToolbarItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_lite_settings -> {
                startActivity(Intent(this, LiteSettingsActivity::class.java))
                true
            }
            R.id.action_lite_groups -> {
                startActivity(LiteContentActivity.intentFor(this, LiteContentActivity.DESTINATION_GROUPS))
                true
            }
            R.id.action_lite_routes -> {
                startActivity(LiteContentActivity.intentFor(this, LiteContentActivity.DESTINATION_ROUTES))
                true
            }
            R.id.action_lite_tools -> {
                startActivity(LiteContentActivity.intentFor(this, LiteContentActivity.DESTINATION_TOOLS))
                true
            }
            R.id.action_lite_logs -> {
                startActivity(LiteContentActivity.intentFor(this, LiteContentActivity.DESTINATION_LOGS))
                true
            }
            else -> false
        }
    }

    private fun checkPingNow() {
        val service = connection.service
        if (!serviceState.canStop || service == null) {
            snackbar(R.string.lite_not_connected).show()
            return
        }
        pingTesting = true
        manualPingStatus = getString(R.string.connection_test_testing)
        updateUi()
        runOnDefaultDispatcher {
            val result = runCatching { service.urlTest() }
            runOnMainDispatcher {
                if (isFinishing || isDestroyed) return@runOnMainDispatcher
                pingTesting = false
                manualPingStatus = result.fold(
                    onSuccess = { elapsed ->
                        getString(
                            if (DataStore.connectionTestURL.startsWith("https://", ignoreCase = true)) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            },
                            elapsed,
                        )
                    },
                    onFailure = { error ->
                        getString(R.string.connection_test_error, readablePingError(error))
                    },
                )
                snackbar(manualPingStatus.orEmpty()).show()
                updateUi()
            }
        }
    }

    private fun readablePingError(error: Throwable): String {
        val raw = error.localizedMessage ?: error.readableMessage
        return when {
            raw.contains("timeout", ignoreCase = true) ||
                    raw.contains("deadline", ignoreCase = true) -> getString(R.string.connection_test_timeout)
            raw.contains("refused", ignoreCase = true) ||
                    raw.contains("closed pipe", ignoreCase = true) -> getString(R.string.connection_test_refused)
            else -> raw
        }
    }

    private fun syncGroupSourceIfProfilesEmpty() {
        if (initialGroupSourceSyncStarted) return
        initialGroupSourceSyncStarted = true
        runOnDefaultDispatcher {
            val groupIds = SagerDatabase.groupDao.allGroups().map { it.id }
            val profileCount = if (groupIds.isEmpty()) {
                0L
            } else {
                SagerDatabase.proxyDao.countByGroups(groupIds)
            }
            if (profileCount > 0L ||
                !DataStore.groupSourceSyncEnabled ||
                DataStore.groupSourceUrl.trim().isBlank()
            ) return@runOnDefaultDispatcher

            runOnMainDispatcher {
                if (!isFinishing && !isDestroyed) {
                    snackbar(R.string.lite_initial_sync_start).show()
                }
            }
            val result = runCatching { GroupSourceSyncer.syncNowAndUpdate() }
            runOnMainDispatcher {
                if (isFinishing || isDestroyed) return@runOnMainDispatcher
                result.fold(
                    onSuccess = { summary ->
                        if (summary.disabled) {
                            snackbar(R.string.group_source_sync_disabled).show()
                        } else {
                            snackbar(
                                getString(
                                    R.string.group_source_sync_result,
                                    summary.added,
                                    summary.kept,
                                    summary.removed,
                                )
                            ).show()
                        }
                    },
                    onFailure = { error ->
                        snackbar(getString(R.string.group_source_sync_error, error.readableMessage)).show()
                    },
                )
            }
        }
    }

    private fun applyLiteDefaultSettingsOnce() {
        if (DataStore.configurationStore.getBoolean(LITE_DEFAULTS_APPLIED_KEY) == true) return
        DataStore.subscriptionSendHwid = true
        DataStore.subscriptionAutoSortGroups = true
        DataStore.configurationStore.putBoolean(LITE_DEFAULTS_APPLIED_KEY, true)
    }

    private fun onConnectClicked(@Suppress("UNUSED_PARAMETER") view: View) {
        when {
            serviceState.canStop -> SagerNet.stopService()
            AutoVpnManager.isAutoRunning() || DataStore.autoVpnServiceStartRequested -> {
                AutoVpnManager.stopScanFromApp()
            }
            else -> {
                requestNotificationPermissionIfNeeded()
                AutoVpnManager.selectMode(selectedMode())
                startRequestedFromUi = true
                updateUi()
                // Match the main application's path: keep VpnService alive while Auto
                // searches directly, then let it receive the selected profile command.
                // Starting VpnService from the separate search process can be rejected
                // by Android as a background foreground-service start.
                connect.launch(null)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setFloatingSwitchEnabled(enabled: Boolean) {
        if (enabled && !FloatingSwitchButton.canDrawOverlays(this)) {
            syncingFloatingToggle = true
            binding.liteFloatingSwitch.isChecked = false
            syncingFloatingToggle = false
            Toast.makeText(this, R.string.auto_vpn_floating_switch_permission_required, Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ))
                }
            }
            return
        }

        DataStore.autoVpnFloatingSwitchEnabled = enabled
        if (enabled) FloatingSwitchButton.sync(applicationContext) else FloatingSwitchButton.hide()
    }

    private fun syncFloatingToggle() {
        syncingFloatingToggle = true
        binding.liteFloatingSwitch.isChecked = DataStore.autoVpnFloatingSwitchEnabled
        syncingFloatingToggle = false
    }

    private fun refreshActiveProfileName() {
        val profileId = DataStore.startedProfile.takeIf { it > 0L } ?: DataStore.selectedProxy
        if (profileId <= 0L) return
        runOnDefaultDispatcher {
            val name = SagerDatabase.proxyDao.getById(profileId)?.displayName().orEmpty()
            runOnMainDispatcher {
                if (!isFinishing && !isDestroyed && name.isNotBlank()) {
                    activeProfileName = name
                    updateUi()
                }
            }
        }
    }

    private fun updateUi() {
        if (!::binding.isInitialized) return
        AutoVpnManager.syncVisibleStateFromStore()
        val mode = storedMode()
        selectedAutoMode = mode
        val autoSearchRunning = AutoVpnManager.isAutoRunning() || DataStore.autoVpnServiceStartRequested
        val isConnected = serviceState.canStop
        if (startRequestedFromUi && (autoSearchRunning || isConnected || serviceState == BaseService.State.Connecting)) {
            startRequestedFromUi = false
        }
        val isSearching = startRequestedFromUi || autoSearchRunning
        val isGemini = mode == AutoVpnManager.MODE_GEMINI

        val modeButton = buttonForMode(mode)
        if (binding.modeGroup.checkedButtonId != modeButton) {
            syncingModeToggle = true
            binding.modeGroup.check(modeButton)
            syncingModeToggle = false
        }
        binding.modeGroup.isEnabled = !isConnected && !isSearching

        binding.liteProgress.isVisible = isSearching || serviceState == BaseService.State.Connecting
        binding.liteConnect.apply {
            text = when {
                isConnected -> getString(R.string.lite_stop_vpn)
                isSearching -> getString(R.string.lite_cancel_selection)
                else -> getString(R.string.lite_start_vpn)
            }
            setIconResource(if (isConnected) R.drawable.ic_baseline_stop_24 else R.drawable.ic_baseline_vpn_key_24)
        }
        binding.liteCheckPing.apply {
            text = if (pingTesting) {
                getString(R.string.connection_test_testing)
            } else {
                getString(R.string.lite_check_ping)
            }
            isEnabled = isConnected && !pingTesting
        }

        val canSwitch = isConnected && DataStore.autoVpnEnabled
        binding.liteSwitchProfile.isVisible = !isGemini
        binding.liteSwitchProfile.isEnabled = canSwitch
        binding.liteSwitchGeminiOnce.isVisible = isGemini
        binding.liteSwitchGeminiRemember.isVisible = isGemini
        binding.liteSwitchGeminiOnce.isEnabled = canSwitch
        binding.liteSwitchGeminiRemember.isEnabled = canSwitch

        val primaryStatus = when {
            startRequestedFromUi -> getString(R.string.lite_starting_auto)
            isSearching -> AutoVpnManager.cardStatusText(mode)
            isConnected && activeProfileName.isNotBlank() -> getString(
                R.string.lite_connected_profile,
                activeProfileName,
            )
            isConnected -> getString(R.string.lite_connected)
            else -> getString(R.string.lite_not_connected)
        }
        val detail = AutoVpnManager.cardStatusText(mode).takeIf {
            DataStore.autoVpnEnabled && (isConnected || isSearching) && it != primaryStatus
        }
        binding.liteStatus.text = listOfNotNull(primaryStatus, detail, manualPingStatus)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        binding.liteStatus.setTextColor(
            MaterialColors.getColor(
                binding.liteStatus,
                if (isConnected || isSearching) {
                    androidx.appcompat.R.attr.colorPrimary
                } else {
                    android.R.attr.textColorSecondary
                },
            ),
        )

        val progress = AutoVpnManager.progressSummary(mode)
        binding.liteMetrics.text = listOf(
            getString(R.string.lite_auto_progress, progress.finished, progress.total, progress.working),
            getString(R.string.lite_auto_reserve, progress.reserve, progress.reserveTarget),
            getString(
                R.string.lite_auto_traffic,
                FormatFileSizeCompat.formatFileSize(this, trafficTxRate, DataStore.useIECUnit),
                FormatFileSizeCompat.formatFileSize(this, trafficRxRate, DataStore.useIECUnit),
            ),
        ).joinToString("\n")
    }

    private fun selectedMode(): Int = selectedAutoMode

    private fun storedMode(): Int = DataStore.autoVpnMode.takeIf {
        it == AutoVpnManager.MODE_ANY ||
                it == AutoVpnManager.MODE_NON_RU ||
                it == AutoVpnManager.MODE_GEMINI
    } ?: AutoVpnManager.MODE_ANY

    private fun modeForButton(buttonId: Int): Int = when (buttonId) {
        R.id.mode_non_ru -> AutoVpnManager.MODE_NON_RU
        R.id.mode_gemini -> AutoVpnManager.MODE_GEMINI
        else -> AutoVpnManager.MODE_ANY
    }

    private fun buttonForMode(mode: Int): Int = when (mode) {
        AutoVpnManager.MODE_NON_RU -> R.id.mode_non_ru
        AutoVpnManager.MODE_GEMINI -> R.id.mode_gemini
        else -> R.id.mode_auto
    }

    private companion object {
        const val STATUS_POLL_INTERVAL_MS = 1_000L
        const val TRAFFIC_UPDATE_INTERVAL_MS = 1_000L
        const val LITE_DEFAULTS_APPLIED_KEY = "liteDefaultsApplied_0_17_98"
    }

}
