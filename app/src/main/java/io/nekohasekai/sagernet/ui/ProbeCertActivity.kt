/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
 * Copyright (C) 2024  HystericalDragons                                      *
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

package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.graphics.Typeface
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutProbeCertBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import libsagernetcore.Libsagernetcore

class ProbeCertActivity : ThemedActivity() {

    private lateinit var binding: LayoutProbeCertBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutProbeCertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                bottom = ime.bottom,
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_layout)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = if (ime.bottom > bars.bottom) 0 else bars.bottom - ime.bottom,
            )
            insets
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.probe_cert)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.probeCertServer.setText("example.com")
        binding.probeCertServerPort.setText("443")
        binding.probeCertServerName.setText("example.com")
        binding.probeCertServer.doAfterTextChanged { text ->
            binding.probeCertServerName.setText(text)
        }
        binding.probeCertAlpn.setText("h2,http/1.1")
        binding.probeCertProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                when (position) {
                    0 -> binding.probeCertAlpn.setText("h2,http/1.1")
                    1 -> binding.probeCertAlpn.setText("h3")
                    else -> error("unknown protocol")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        binding.probeCert.setOnClickListener {
            probeCert()
        }

        binding.certificate.doAfterTextChanged { text ->
            try {
                binding.certHash.text = when (binding.certHashType.selectedItemPosition) {
                    0 -> Libsagernetcore.calculatePEMCertSHA256Hash(text.toString())
                    1 -> Libsagernetcore.calculatePEMCertPublicKeySHA256Hash(text.toString())
                    2 -> Libsagernetcore.calculatePEMCertChainSHA256Hash(text.toString())
                    else -> error("impossible")
                }
                binding.showCertInfo.isVisible = true
            } catch (_: Exception) {
                binding.certHash.text = ""
                binding.showCertInfo.isVisible = false
            }
        }
        binding.certHashType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                try {
                    val certificate = binding.certificate.text.toString()
                    val certHash = when (position) {
                        0 -> Libsagernetcore.calculatePEMCertSHA256Hash(certificate)
                        1 -> Libsagernetcore.calculatePEMCertPublicKeySHA256Hash(certificate)
                        2 -> Libsagernetcore.calculatePEMCertChainSHA256Hash(certificate)
                        else -> error("impossible")
                    }
                    binding.certHash.text = certHash
                } catch (_: Exception) {
                    binding.certHash.text = ""
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
        binding.certHash.setOnClickListener {
            try {
                val clipData = ClipData.newPlainText("hash", binding.certHash.text)
                SagerNet.clipboard.setPrimaryClip(clipData)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        binding.showCertInfo.text = "?"
        binding.showCertInfo.isVisible = false
        binding.showCertInfo.setOnClickListener {
            try {
                AlertDialog.Builder(this)
                    .setTitle(R.string.certificates)
                    .setView(NestedScrollView(this).apply {
                        setPadding(dp2px(16), dp2px(16), dp2px(16), 0)
                        addView( HorizontalScrollView(this@ProbeCertActivity).apply {
                            addView(TextView(this@ProbeCertActivity).apply {
                                text = Libsagernetcore.certificateToPrettyInfo(binding.certificate.text.toString())
                                isSingleLine = false
                                typeface = Typeface.MONOSPACE
                                setTextIsSelectable(true)
                                setHorizontallyScrolling(false)
                            })
                        })
                    })
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .runCatching { show() }
            } catch (e: Exception) {
                AlertDialog.Builder(this@ProbeCertActivity)
                    .setTitle(R.string.error_title)
                    .setMessage(e.toString())
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .runCatching { show() }
            }
        }
    }

    private fun probeCert() {
        var port: Int
        try {
            port = binding.probeCertServerPort.text.toString().toInt()
        } catch (e: NumberFormatException) {
            binding.waitLayout.isVisible = false
            binding.certificate.setText("")
            runOnMainDispatcher {
                AlertDialog.Builder(this@ProbeCertActivity)
                    .setTitle(R.string.error_title)
                    .setMessage(e.toString())
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .runCatching { show() }
            }
            return
        }
        if (SagerNet.started && DataStore.startedProfile > 0 && !DataStore.requireSocks && binding.probeCertProtocol.selectedItemPosition == 1) {
            runOnMainDispatcher {
                AlertDialog.Builder(this@ProbeCertActivity)
                    .setTitle(R.string.error_title)
                    .setMessage("SOCKS inbound is disabled")
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .runCatching { show() }
            }
            return
        }
        if (SagerNet.started && DataStore.startedProfile > 0 && DataStore.requireSocks && !DataStore.socksUDP && binding.probeCertProtocol.selectedItemPosition == 1) {
            runOnMainDispatcher {
                AlertDialog.Builder(this@ProbeCertActivity)
                    .setTitle(R.string.error_title)
                    .setMessage("SOCKS inbound UDP is disabled")
                    .setPositiveButton(android.R.string.ok) { _, _ -> }
                    .runCatching { show() }
            }
            return
        }
        binding.waitLayout.isVisible = true
        runOnDefaultDispatcher {
            val result = Libsagernetcore.probeCert(
                binding.probeCertServer.text.toString(),
                port,
                binding.probeCertServerName.text.toString(),
                binding.probeCertAlpn.text.toString(),
                when (binding.probeCertProtocol.selectedItemPosition) {
                    0 -> "tls"
                    1 -> "quic"
                    else -> error("impossible")
                },
                SagerNet.started && DataStore.startedProfile > 0 && DataStore.requireSocks,
                if (DataStore.requireSocks) DataStore.socksPort else 0,
                if (DataStore.requireSocks) DataStore.socksUsername else "",
                if (DataStore.requireSocks) DataStore.socksPassword else "",
                SagerNet.started && DataStore.startedProfile > 0 && !DataStore.requireSocks,
                if (!DataStore.requireSocks) SagerNet.deviceStorage.noBackupFilesDir.toString() + "/socks_path" else "",
            )
            onMainDispatcher {
                binding.waitLayout.isVisible = false
                if (result.error.isNotEmpty()) {
                    binding.certificate.setText("")
                    AlertDialog.Builder(this@ProbeCertActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(result.error)
                        .setPositiveButton(android.R.string.ok) { _, _ -> }
                        .runCatching { show() }
                } else {
                    binding.certificate.setText(result.cert)
                    if (result.verifyError.isNotEmpty()) {
                        AlertDialog.Builder(this@ProbeCertActivity)
                            .setTitle(R.string.error_title)
                            .setMessage(result.verifyError)
                            .setPositiveButton(android.R.string.ok) { _, _ -> }
                            .runCatching { show() }
                    }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finishAndRemoveTask()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

}
