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

package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import kotlinx.parcelize.Parcelize

@Entity(tableName = "rules")
@Parcelize
@TypeConverters(ListConverter::class, StringCollectionConverter::class)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var name: String = "",
    var userOrder: Long = 0L,
    var enabled: Boolean = false,
    var domains: String = "",
    var ip: String = "",
    var port: String = "",
    var sourcePort: String = "",
    var network: String = "",
    var source: String = "",
    var protocol: String = "",
    var attrs: String = "",
    var outbound: Long = 0,
    var reverse: Boolean = false,
    var redirect: String = "",
    var packages: List<String> = listOf(),
    @ColumnInfo(defaultValue = "") var ssid: String = "",
    @ColumnInfo(defaultValue = "") var networkType: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "") var customPackageNames: List<String> = listOf(),
) : Parcelable {

    fun isBypassRule(): Boolean {
        return (domains.isNotEmpty() && ip.isEmpty() || ip.isNotEmpty() && domains.isEmpty()) && port.isEmpty() && sourcePort.isEmpty() && network.isEmpty() && source.isEmpty() && protocol.isEmpty() && attrs.isEmpty() && !reverse && redirect.isEmpty() && outbound == -1L && packages.isEmpty() && customPackageNames.isEmpty()  && ssid.isEmpty() && networkType.isEmpty()
    }

    fun isProxyRule(): Boolean {
        return !(domains.isNotEmpty() && ip.isNotEmpty()) && outbound == 0L
    }

    fun displayName(): String {
        return name.takeIf { it.isNotEmpty() } ?: "Rule $id"
    }

    fun mkSummary(): String {
        var summary = ""
        if (domains.isNotEmpty()) summary += "$domains\n"
        if (ip.isNotEmpty()) summary += "$ip\n"
        if (port.isNotEmpty()) summary += "$port\n"
        if (sourcePort.isNotEmpty()) summary += "$sourcePort\n"
        if (network.isNotEmpty()) summary += "$network\n"
        if (source.isNotEmpty()) summary += "$source\n"
        if (protocol.isNotEmpty()) summary += "$protocol\n"
        if (attrs.isNotEmpty()) summary += "$attrs\n"
        if (reverse) summary += "$redirect\n"
        if (packages.isNotEmpty()) summary += app.resources.getQuantityString(
            R.plurals.apps_message, packages.size, packages.size
        ) + "\n"
        if (customPackageNames.isNotEmpty()) summary += "${customPackageNames.joinToString("\n")}\n"
        if (ssid.isNotEmpty()) summary += "$ssid\n"
        if (networkType.isNotEmpty()) {
            val types = mutableListOf<String>()
            if (networkType.contains("data")) types.add(app.getString(R.string.network_data))
            if (networkType.contains("wifi")) types.add(app.getString(R.string.network_wifi))
            if (networkType.contains("bluetooth")) types.add(app.getString(R.string.network_bt))
            if (networkType.contains("ethernet")) types.add(app.getString(R.string.network_eth))
            if (networkType.contains("usb")) types.add(app.getString(R.string.network_usb))
            if (networkType.contains("satellite")) types.add(app.getString(R.string.network_satellite))
            summary += types.joinToString("\n") + "\n"
        }
        val lines = summary.trim().split("\n")
        return if (lines.size > 3) {
            lines.subList(0, 3).joinToString("\n", postfix = "\n...")
        } else {
            summary.trim()
        }
    }

    fun displayOutbound(): String {
        if (reverse) {
            return app.getString(R.string.route_reverse)
        }
        return when (outbound) {
            0L -> app.getString(R.string.route_proxy)
            -1L -> app.getString(R.string.route_bypass)
            -2L -> app.getString(R.string.route_block)
            else -> ProfileManager.getProfile(outbound)?.displayName()
                ?: app.getString(R.string.route_proxy)
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM rules ORDER BY userOrder")
        fun allRules(): List<RuleEntity>

        @Query("SELECT * FROM rules WHERE enabled = :enabled ORDER BY userOrder")
        fun enabledRules(enabled: Boolean = true): List<RuleEntity>

        @Query("SELECT MAX(userOrder) + 1 FROM rules")
        fun nextOrder(): Long?

        @Query("SELECT * FROM rules WHERE id = :ruleId")
        fun getById(ruleId: Long): RuleEntity?

        @Query("DELETE FROM rules WHERE id = :ruleId")
        fun deleteById(ruleId: Long): Int

        @Delete
        fun deleteRule(rule: RuleEntity)

        @Delete
        fun deleteRules(rules: List<RuleEntity>)

        @Insert
        fun createRule(rule: RuleEntity): Long

        @Update
        fun updateRule(rule: RuleEntity)

        @Update
        fun updateRules(rules: List<RuleEntity>)

        @Query("DELETE FROM rules")
        fun reset()

        @Insert
        fun insert(rules: List<RuleEntity>)

        @Query("UPDATE rules SET enabled = :enabled")
        fun enableAll(enabled: Boolean = true)

    }


}