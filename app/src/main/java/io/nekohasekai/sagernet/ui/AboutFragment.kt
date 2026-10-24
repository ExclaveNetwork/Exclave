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

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.util.Linkify
import android.view.View
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.items.MaterialAboutTitleItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.dp2pxf
import io.nekohasekai.sagernet.ktx.snackbar
import libsagernetcore.Libsagernetcore

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.menu_about)

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.layout_about)) { v, insets ->
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

        parentFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = true
    }

    class AboutContent : MaterialAboutFragment() {

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            parentFragmentManager.beginTransaction()
                .replace(R.id.about_fragment_holder, AboutContent())
                .commitAllowingStateLoss()
        }

        override fun getMaterialAboutList(activityContext: Context?): MaterialAboutList {
            try {
                if (activityContext == null) {
                    return MaterialAboutList.Builder().build()
                }
                return MaterialAboutList.Builder()
                    .addCard(MaterialAboutCard.Builder()
                        .outline(false)
                        .addItem(MaterialAboutTitleItem.Builder()
                            .icon(R.mipmap.ic_launcher)
                            .text(R.string.app_name)
                            .setOnLongClickAction {
                                AlertDialog.Builder(activityContext).apply {
                                    setView(NestedScrollView(activityContext).apply {
                                        setPadding(dp2px(16), dp2px(16), dp2px(16), 0)
                                        addView( HorizontalScrollView(activityContext).apply {
                                            addView(TextView(activityContext).apply {
                                                text = resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
                                                textSize = dp2pxf(4)
                                                typeface = Typeface.MONOSPACE
                                                isSingleLine = false
                                                setTextIsSelectable(true)
                                                setHorizontallyScrolling(false)
                                            })
                                        })
                                    })
                                    setPositiveButton(android.R.string.ok, null)
                                }.show()
                            }
                            .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .icon(R.drawable.ic_baseline_update_24)
                            .text(R.string.app_version)
                            .subText(BuildConfig.VERSION_NAME)
                            .setOnClickAction {
                                startActivity(Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/dyhkwong/Exclave/releases".toUri()
                                ))
                            }
                            .setOnLongClickAction {
                                DataStore.enableDebug = !DataStore.enableDebug
                                snackbar(if (DataStore.enableDebug) "developer mode enabled" else "developer mode disabled").show()
                            }
                            .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .icon(R.drawable.ic_baseline_airplanemode_active_24)
                            .text(getString(R.string.version_x, "v2ray-core"))
                            .subText(Libsagernetcore.getV2RayVersion() + "-fork")
                            .setOnClickAction {
                                startActivity(Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/dyhkwong/v2ray-core/tree/dev-sagernet".toUri()
                                ))
                            }
                            .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .icon(R.drawable.ic_baseline_info_24)
                            .text(getString(R.string.version_x, "Go"))
                            .subText(Libsagernetcore.getGoVersion())
                            .setOnLongClickAction {
                                AlertDialog.Builder(activityContext).apply {
                                    setView(NestedScrollView(activityContext).apply {
                                        setPadding(dp2px(16), dp2px(16), dp2px(16), 0)
                                        addView( HorizontalScrollView(activityContext).apply {
                                            addView(TextView(activityContext).apply {
                                                text = Libsagernetcore.getDepInfo()
                                                textSize = dp2pxf(4)
                                                typeface = Typeface.MONOSPACE
                                                isSingleLine = false
                                                setTextIsSelectable(true)
                                                setHorizontallyScrolling(false)
                                            })
                                        })
                                    })
                                    setPositiveButton(android.R.string.ok, null)
                                }.show()
                            }
                            .build())
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = activityContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(activityContext.packageName)) {
                                    addItem(MaterialAboutActionItem.Builder()
                                        .icon(R.drawable.ic_baseline_running_with_errors_24)
                                        .text(R.string.ignore_battery_optimizations)
                                        .subText(R.string.ignore_battery_optimizations_sum)
                                        .setOnClickAction {
                                            requestIgnoreBatteryOptimizations.launch(
                                                Intent(
                                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                    "package:${activityContext.packageName}".toUri()
                                                )
                                            )
                                        }.build())
                                }
                            }
                        }
                        .build())
                    .addCard(MaterialAboutCard.Builder()
                        .outline(false)
                        .title(R.string.project)
                        .addItem(MaterialAboutActionItem.Builder()
                            .icon(R.drawable.ic_baseline_sanitizer_24)
                            .text(R.string.github)
                            .setOnClickAction {
                                startActivity(Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/dyhkwong/Exclave".toUri()
                                ))
                            }
                            .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .icon(R.drawable.baseline_translate_24)
                            .text(R.string.translation_platform)
                            .setOnClickAction {
                                startActivity(Intent(
                                    Intent.ACTION_VIEW,
                                    "https://hosted.weblate.org/projects/exclave/".toUri()
                                ))
                            }
                            .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .icon(R.drawable.ic_action_copyleft)
                            .text(R.string.license)
                            .setOnClickAction {
                                AlertDialog.Builder(activityContext).apply {
                                    setView(
                                        TextView(activityContext).apply {
                                            setPadding(dp2px(16))
                                            text = getString(
                                                if (Libsagernetcore.buildWithClash()) {
                                                    R.string.license_gpl_v3_only
                                                } else {
                                                    R.string.license_gpl_v3_or_later
                                                }
                                            )
                                            setTextIsSelectable(true)
                                            Linkify.addLinks(this, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
                                        }
                                    )
                                    setOnCancelListener { _ ->
                                        showLicenseAlertDialogFromAssets(activityContext, "license/GPL-3.0.txt")
                                    }
                                    setPositiveButton(android.R.string.ok) { _, _ ->
                                        showLicenseAlertDialogFromAssets(activityContext, "license/GPL-3.0.txt")
                                    }
                                }.show()
                            }
                            .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .icon(R.drawable.ic_action_description)
                            .text(R.string.third_party_notices)
                            .setOnClickAction {
                                AlertDialog.Builder(activityContext).apply {
                                    setView(ListView(activityContext).apply {
                                        adapter = ArrayAdapter(activityContext, android.R.layout.simple_list_item_1,
                                            arrayOf(
                                                getString(R.string.third_party_notices),
                                                "Apache License Version 2.0",
                                                "MIT License",
                                                "BSD 3-Clause License",
                                                "BSD 2-Clause License",
                                                "GNU GENERAL PUBLIC LICENSE Version 3",
                                                "Mozilla Public License Version 2.0",
                                                "Creative Commons Attribution 4.0 International Public License",
                                                "Community Data License Agreement - Permissive - Version 2.0"
                                            )
                                        )
                                        setOnItemClickListener { _, _, position, _ ->
                                            showLicenseAlertDialogFromAssets(activityContext, when (position) {
                                                0 -> "license/notices.txt"
                                                1 -> "license/Apache-2.0.txt"
                                                2 -> "license/MIT.txt"
                                                3 -> "license/BSD-3-Clause.txt"
                                                4 -> "license/BSD-2-Clause.txt"
                                                5 -> "license/GPL-3.0.txt"
                                                6 -> "license/MPL-2.0.txt"
                                                7 -> "license/CC-BY-4.0.txt"
                                                8 -> "license/CDLA-Permissive-2.0.txt"
                                                else -> error("index out of bound")
                                            })
                                        }
                                    })
                                    setPositiveButton(android.R.string.ok, null)
                                }.show()
                            }
                            .build())
                        .build())
                    .build()
            } catch (_: IllegalStateException) {
                return MaterialAboutList.Builder().build()
            }
        }

        private fun showLicenseAlertDialogFromAssets(context: Context, asset: String) {
            AlertDialog.Builder(context).apply {
                setView(NestedScrollView(context).apply {
                    setPadding(dp2px(16), dp2px(16), dp2px(16), 0)
                    addView( HorizontalScrollView(context).apply {
                        addView(TextView(context).apply {
                            text = context.assets.open(asset).use { it.reader().readText() }
                            textSize = dp2pxf(4)
                            typeface = Typeface.MONOSPACE
                            isSingleLine = false
                            setTextIsSelectable(true)
                            setHorizontallyScrolling(false)
                        })
                    })
                })
                setPositiveButton(android.R.string.ok, null)
            }.show()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<RecyclerView>(com.danielstone.materialaboutlibrary.R.id.mal_recyclerview).apply {
                overScrollMode = RecyclerView.OVER_SCROLL_ALWAYS
            }
        }

    }

}