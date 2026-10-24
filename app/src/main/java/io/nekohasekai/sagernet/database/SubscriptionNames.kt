package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import java.util.Locale

object SubscriptionNames {

    fun next(existingGroups: List<ProxyGroup> = SagerDatabase.groupDao.allGroups()): String {
        return next(existingGroups.mapTo(mutableSetOf()) { it.displayName() })
    }

    fun next(existingNames: MutableSet<String>): String {
        val base = app.getString(R.string.subscription)
            .replaceFirstChar { it.lowercase(Locale.getDefault()) }
        var index = 1
        while (true) {
            val name = "$base ($index)"
            if (existingNames.add(name)) return name
            index++
        }
    }
}
