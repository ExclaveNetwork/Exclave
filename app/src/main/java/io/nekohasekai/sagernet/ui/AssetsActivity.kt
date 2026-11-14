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
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libexclavecore.HTTPClient
import libexclavecore.Libexclavecore
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.recycler_view)) { v, insets ->
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
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.adapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.adapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val internalFiles = arrayOf("geoip.dat", "geosite.dat")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            val fileName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
                .substringAfterLast('/')
                .substringAfter(':')

            if (!fileName.endsWith(".dat") && fileName != "index.html" && fileName != "index.js" && fileName != "root_store.certs") {
                runOnMainDispatcher {
                    alert(getString(R.string.route_not_asset, fileName)).show()
                }
                return@registerForActivityResult
            }

            runOnDefaultDispatcher {
                val outFile = File(app.externalAssets, fileName).apply {
                    parentFile?.mkdirs()
                }
                val cacheFile = File(outFile.parentFile, outFile.name + ".tmp")

                try {
                    contentResolver.openInputStream(file)?.use(cacheFile.outputStream())
                    installAsset(cacheFile, outFile)
                } catch (e: Exception) {
                    onMainDispatcher {
                        alert(e.readableMessage).show()
                    }
                    return@runOnDefaultDispatcher
                } finally {
                    cacheFile.delete()
                }

                File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt").apply {
                    if (isFile) delete()
                }

                adapter.reloadAssets()
            }

        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
            R.id.action_import_url -> {
                startActivity(Intent(this, AssetEditActivity::class.java))
                adapter.reloadAssets()
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            assets.clear()
            assets.add(File(app.externalAssets, "geoip.dat"))
            assets.add(File(app.externalAssets, "geosite.dat"))

            val managedAssets = SagerDatabase.assetDao.getAll().associateBy { it.name }
            managedAssets.forEach {
                assets.add(File(app.externalAssets, it.key))
            }

            val unmanagedAssets = app.externalAssets.listFiles()?.filter {
                it.isFile && it.name.endsWith(".dat") && it.name !in internalFiles && it !in assets
            }
            if (unmanagedAssets != null) assets.addAll(unmanagedAssets)

            layout.refreshLayout.post {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                groups.forEach {
                    it.deleteRecursively()
                    SagerDatabase.assetDao.delete(it.name)
                }
            }
        }

    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    versionFile.readText().trim()
                } else {
                    DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                "<unknown>"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

            val assetEntity = SagerDatabase.assetDao.get(file.name)
            binding.rulesUpdate.isInvisible = file.name !in internalFiles && assetEntity == null
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        if (file.name in internalFiles) {
                            updateAsset(file, versionFile, localVersion)
                        } else {
                            updateCustomAsset(file, assetEntity!!.url)
                        }
                    }.onFailure {
                        onMainDispatcher {
                            snackbar(it.readableMessage).show()
                        }
                    }

                    onMainDispatcher {
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

            binding.edit.isVisible = file.name !in internalFiles && assetEntity != null
            binding.edit.setOnClickListener {
                startActivity(Intent(this@AssetsActivity, AssetEditActivity::class.java).apply {
                    putExtra(AssetEditActivity.EXTRA_ASSET_NAME, file.name)
                })
                adapter.reloadAssets()
            }

        }

    }

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String) {
        val repo: String
        var fileName = file.name
        when (DataStore.rulesProvider) {
            3 -> return updateGeoAsset(file, versionFile)
            0 -> {
                if (file.name == internalFiles[0]) {
                    repo = "v2fly/geoip"
                } else {
                    repo = "v2fly/domain-list-community"
                    fileName = "dlc.dat"
                }
            }
            1 -> repo = "Loyalsoldier/v2ray-rules-dat"
            2 -> repo = "Chocolate4U/Iran-v2ray-rules"
            4 -> repo = "runetfreedom/russia-v2ray-rules-dat"
            5 -> {
                repo = "XTLS/Xray-rules-dat"
                if (file.name == internalFiles[0]) {
                    fileName = "geoip-lite.dat"
                }
            }
            else -> error("invalid asset provider")
        }

        val client = Libexclavecore.newHttpClient().apply {
            keepAlive()
            if (SagerNet.started && DataStore.startedProfile > 0) {
                useUDS(app.cacheDir.toString() + "/ipc.sock")
            }
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val release = parseJson(response.contentString).asJsonObject
            val tagName = release.getString("tag_name")?: error("tag_name not found in release ${release["url"]}")

            if (tagName == localVersion) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val releaseAssets = release.getArray("assets")
            val assetToDownload = releaseAssets?.find { it.getString("name") == fileName }
                ?: error("File $fileName not found in release ${release["url"]}")
            val browserDownloadUrl = assetToDownload.getString("browser_download_url")
            // Only the preset providers publish checksums; custom URLs (provider 3) are not checked.
            val expectedSha256 = findExpectedSha256(client, releaseAssets.orEmpty(), assetToDownload)

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()

            try {
                response.writeTo(cacheFile.canonicalPath)
                installAsset(cacheFile, file, expectedSha256)
            } finally {
                cacheFile.delete()
            }

            versionFile.writeText(tagName)

            adapter.reloadAssets()

            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    /**
     * Verifies [cacheFile] and atomically moves it over [file], so that a corrupt or
     * partial download/import can never replace a working asset.
     *
     * - If [expectedSha256] is given (preset route asset providers only), the file must match it.
     * - A `.dat` file must be decoded successfully by the geodata decoder of the core (Go side).
     */
    private fun installAsset(cacheFile: File, file: File, expectedSha256: String? = null) {
        if (expectedSha256 != null) {
            val actual = cacheFile.sha256()
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                error(getString(R.string.route_asset_sha256_mismatch, file.name))
            }
        }
        if (file.name.endsWith(".dat")) {
            try {
                val path = cacheFile.canonicalPath
                when (file.name) {
                    internalFiles[0] -> Libexclavecore.validateGeoIPFile(path)
                    internalFiles[1] -> Libexclavecore.validateGeoSiteFile(path)
                    else -> Libexclavecore.validateGeoDataFile(path)
                }
            } catch (e: Exception) {
                throw IllegalStateException(
                    getString(R.string.route_asset_invalid_file, file.name) + "\n" + e.readableMessage, e
                )
            }
        }
        if (!cacheFile.renameTo(file)) {
            error("failed to rename ${cacheFile.name} to ${file.name}")
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val sha256Regex = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Looks up the SHA256 published by the release for [assetToDownload]: first the
     * `<name>.sha256sum` asset (`<hash>  <name>`), then the `digest` field of the GitHub
     * release API (`sha256:<hash>`). Returns null if the release does not publish one.
     */
    private fun findExpectedSha256(
        client: HTTPClient,
        releaseAssets: List<JsonObject>,
        assetToDownload: JsonObject,
    ): String? {
        val name = assetToDownload.getString("name")
        val sumUrl = releaseAssets.find { it.getString("name") == "$name.sha256sum" }
            ?.getString("browser_download_url")
        if (sumUrl != null) {
            val content = client.newRequest().apply {
                setURL(sumUrl)
            }.execute().contentString
            val hash = content.trim().split(Regex("\\s+")).firstOrNull()
            if (hash == null || !sha256Regex.matches(hash)) {
                error("invalid $name.sha256sum")
            }
            return hash.lowercase()
        }
        val digest = assetToDownload.getString("digest")?.removePrefix("sha256:")
        if (digest != null && sha256Regex.matches(digest)) {
            return digest.lowercase()
        }
        return null
    }

    suspend fun updateGeoAsset(file: File, versionFile: File) {
        try {
            updateCustomAsset(file, if (file.name == internalFiles[0]) DataStore.rulesGeoipUrl else DataStore.rulesGeositeUrl)
        } finally {
            if (versionFile.isFile) {
                versionFile.delete()
            }
        }
    }

    suspend fun updateCustomAsset(file: File, url: String) {
        val client = Libexclavecore.newHttpClient().apply {
            keepAlive()
            if (SagerNet.started && DataStore.startedProfile > 0) {
                useUDS(app.cacheDir.toString() + "/ipc.sock")
            }
        }
        try {
            val response = client.newRequest().apply {
                setURL(url)
            }.execute()
            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            try {
                response.writeTo(cacheFile.canonicalPath)
                installAsset(cacheFile, file)
            } finally {
                cacheFile.delete()
            }
            adapter.reloadAssets()
            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }


}