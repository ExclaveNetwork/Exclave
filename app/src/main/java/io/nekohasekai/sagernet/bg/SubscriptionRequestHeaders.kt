/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026                                                       *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.os.Build
import io.nekohasekai.sagernet.database.DataStore

object SubscriptionRequestHeaders {

    fun applyTo(setHeader: (String, String) -> Unit) {
        if (!DataStore.subscriptionSendHwid) return

        val version = Build.VERSION.RELEASE.takeIf { it.isNotBlank() }
            ?: Build.VERSION.SDK_INT.toString()
        setHeader("X-Hwid", DataStore.getSubscriptionHwid())
        setHeader("X-Android-Version", "Android $version")
    }
}
