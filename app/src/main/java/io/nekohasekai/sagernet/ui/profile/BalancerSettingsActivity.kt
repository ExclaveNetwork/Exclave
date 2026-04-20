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

package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.takisoft.preferencex.EditTextPreference
import com.takisoft.preferencex.PreferenceFragmentCompat
import com.takisoft.preferencex.SimpleMenuPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import io.nekohasekai.sagernet.widget.GroupPreference

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_chain_settings) {

    companion object {
        const val KEY_PROXY_LIST = "proxyList"
    }

    override fun createEntity() = BalancerBean()

    val proxyList = ArrayList<ProxyEntity>()
    var proxyListInSavedInstance = ""

    override fun BalancerBean.init() {
        DataStore.profileName = name
        DataStore.balancerType = type
        DataStore.balancerStrategy = strategy
        DataStore.balancerGroup = groupId
        DataStore.serverProtocol = proxies.joinToString(",")
        DataStore.balancerProbeUrl = probeUrl
        DataStore.balancerProbeInterval = probeInterval
        DataStore.balancerNameFilter = nameFilter
        DataStore.balancerNameFilter1 = nameFilter1
        DataStore.balancerUseLandingProxy = useLandingProxy
        DataStore.balancerUseFrontProxy = useFrontProxy
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        type = DataStore.balancerType
        strategy = DataStore.balancerStrategy
        groupId = DataStore.balancerGroup
        proxies = proxyList.map { it.id }
        probeUrl = DataStore.balancerProbeUrl
        probeInterval = DataStore.balancerProbeInterval
        nameFilter = DataStore.balancerNameFilter
        nameFilter1 = DataStore.balancerNameFilter1
        useLandingProxy = DataStore.balancerUseLandingProxy
        useFrontProxy = DataStore.balancerUseFrontProxy
    }

    lateinit var balancerType: SimpleMenuPreference
    lateinit var balancerGroup: GroupPreference
    lateinit var balancerNameFilter: EditTextPreference
    lateinit var balancerNameFilter1: EditTextPreference
    lateinit var balancerUseLandingProxy: SwitchPreference
    lateinit var balancerUseFrontProxy: SwitchPreference
    lateinit var probeInterval: EditTextPreference

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.balancer_preferences)

        balancerType = findPreference(Key.BALANCER_TYPE)!!
        balancerGroup = findPreference(Key.BALANCER_GROUP)!!
        balancerNameFilter = findPreference(Key.BALANCER_NAME_FILTER)!!
        balancerNameFilter1 = findPreference(Key.BALANCER_NAME_FILTER1)!!
        balancerUseLandingProxy = findPreference(Key.BALANCER_USE_LANDING_PROXY)!!
        balancerUseFrontProxy = findPreference(Key.BALANCER_USE_FRONT_PROXY)!!
        probeInterval = findPreference(Key.PROBE_INTERVAL)!!
        probeInterval.onBindEditTextListener = EditTextPreferenceModifiers.Number

        itemView = findViewById(R.id.list_cell)

        balancerType.setOnPreferenceChangeListener { _, newValue ->
            updateType(newValue.toString().toInt())
            true
        }
    }

    fun updateType(type: Int = DataStore.balancerType) {
        when (type) {
            BalancerBean.TYPE_LIST -> {
                balancerGroup.isVisible = false
                balancerNameFilter.isVisible = false
                balancerNameFilter1.isVisible = false
                balancerUseLandingProxy.isVisible = false
                balancerUseFrontProxy.isVisible = false
                configurationList.isVisible = true
                itemView.isVisible = true
            }
            BalancerBean.TYPE_GROUP -> {
                balancerGroup.isVisible = true
                balancerNameFilter.isVisible = true
                balancerNameFilter1.isVisible = true
                balancerUseLandingProxy.isVisible = true
                balancerUseFrontProxy.isVisible = true
                configurationList.isVisible = false
                itemView.isVisible = false
            }
        }
    }

    lateinit var itemView: LinearLayout
    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.balancer_settings)
        configurationList = findViewById(R.id.configuration_list)
        ViewCompat.setOnApplyWindowInsetsListener(configurationList) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left + dp2px(4),
                right = bars.right + dp2px(4),
                bottom = bars.bottom + dp2px(4),
            )
            insets
        }
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.adapterPosition, target.adapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.adapterPosition)
            }

        }).attachToRecyclerView(configurationList)
        savedInstanceState?.getString(KEY_PROXY_LIST)?.let {
            proxyListInSavedInstance = it
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ChainSettingsActivity.Companion.KEY_PROXY_LIST, proxyList.map { it.id }.joinToString(","))
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        // override the padding in ProfileSettingsActivity
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
            )
            insets
        }

        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }

        updateType()
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            var idList = DataStore.serverProtocol.split(",")
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLong() }
            proxyListInSavedInstance.takeIf { it.isNotEmpty() }?.let {
                idList = it.split(",")
                    .mapNotNull { it.takeIf { it.isNotBlank() }?.toLong() }
            }
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    proxyList.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                notifyDataSetChanged()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            dirty = true
            onBackPressedCallback.isEnabled = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            dirty = true
            onBackPressedCallback.isEnabled = true
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        return profile.id != DataStore.editingId
    }

    /*fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false

        for (entity in proxyList) {
            if (testProfileContains(entity, profile)) return false
        }

        return true
    }

    fun testProfileContains(profile: ProxyEntity, anotherProfile: ProxyEntity): Boolean {
        if (profile.type != ProxyEntity.TYPE_BALANCER || anotherProfile.type != ProxyEntity.TYPE_BALANCER) return false
        if (profile.id == anotherProfile.id) return true
        val proxies = profile.balancerBean!!.proxies
        if (proxies.contains(anotherProfile.id)) return true
        if (proxies.isNotEmpty()) {
            for (entity in ProfileManager.getProfiles(proxies)) {
                if (testProfileContains(entity, anotherProfile)) {
                    return true
                }
            }
        }
        return false
    }*/

    var replacing = 0

    val selectProfileForAdd = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            dirty = true
            onBackPressedCallback.isEnabled = true

            val profile = ProfileManager.getProfile(
                data!!.getLongExtra(
                    ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                )
            )!!

            if (!testProfileAllowed(profile)) {
                onMainDispatcher {
                    MaterialAlertDialogBuilder(this@BalancerSettingsActivity).setTitle(R.string.circular_reference)
                        .setMessage(R.string.circular_reference_sum)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } else {
                configurationList.post {
                    if (replacing != 0) {
                        proxyList[replacing - 1] = profile
                        configurationAdapter.notifyItemChanged(replacing)
                    } else {
                        proxyList.add(profile)
                        configurationAdapter.notifyItemInserted(proxyList.size)
                    }
                }
            }
        }
    }

    inner class AddHolder(val binding: LayoutAddEntityBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
        }
    }

    inner class ProfileHolder(binding: LayoutProfileBinding) : RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val deleteButton = binding.deleteIcon
        val shareLayout = binding.share

        fun bind(proxyEntity: ProxyEntity) {

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()

            var rx = proxyEntity.rx
            var tx = proxyEntity.tx

            val stats = proxyEntity.stats
            if (stats != null) {
                rx += stats.rxTotal
                tx += stats.txTotal
            }

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    FormatFileSizeCompat.formatFileSize(itemView.context, tx, DataStore.useIECUnit),
                    FormatFileSizeCompat.formatFileSize(itemView.context, rx, DataStore.useIECUnit)
                )
            }

            editButton.setOnClickListener {
                replacing = adapterPosition
                selectProfileForAdd.launch(Intent(
                    this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                })
            }

            deleteButton.setOnClickListener {
                MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                    .setTitle(getString(R.string.delete_confirm_prompt))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        configurationAdapter.remove(adapterPosition)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            shareLayout.isVisible = false

        }

    }

}