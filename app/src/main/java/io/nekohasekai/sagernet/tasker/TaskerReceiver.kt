/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.tasker

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.app

class TaskerReceiver : BroadcastReceiver() {

    companion object {
        private val componentName by lazy { ComponentName(app, TaskerReceiver::class.java) }
        var enabled: Boolean
            get() = when (app.packageManager.getComponentEnabledSetting(componentName)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> {
                    app.packageManager.getReceiverInfo(componentName, 0).enabled
                }
                else -> true
            }
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName,
                if (value) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (DataStore.disableTaskerIntegration) {
            enabled = false
            return
        }
        val settings = TaskerBundle.fromIntent(intent)
        when (settings.action) {
            TaskerBundle.ACTION_START -> {
                var reload = false
                if (settings.profileId > 0 && DataStore.selectedProxy != settings.profileId) {
                    if (ProfileManager.getProfile(settings.profileId) != null) {
                        DataStore.selectedProxy = settings.profileId
                        reload = DataStore.startedProfile != 0L
                    }
                }
                if (reload) SagerNet.reloadService() else SagerNet.startService()
            }
            TaskerBundle.ACTION_STOP -> {
                SagerNet.stopService()
            }
        }
    }
}