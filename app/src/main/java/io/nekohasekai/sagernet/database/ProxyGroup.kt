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

import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues

@Entity(tableName = "proxy_groups")
data class ProxyGroup(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var ungrouped: Boolean = false,
    var name: String? = null,
    var type: Int = GroupType.BASIC,
    var subscription: SubscriptionBean? = null,
    var order: Int = GroupOrder.ORIGIN,
    @ColumnInfo(defaultValue = (-1L).toString()) var frontProxy: Long = -1L,
    @ColumnInfo(defaultValue = (-1L).toString()) var landingProxy: Long = -1L,
    @ColumnInfo(defaultValue = "0") var pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") var healthCheckSuccess: Long = 0L,
    @ColumnInfo(defaultValue = "0") var healthCheckTotal: Long = 0L,
    @ColumnInfo(defaultValue = "''") var healthCheckHistory: String = ""
) : Serializable() {

    @Transient
    var export = false

    override fun initializeDefaultValues() {
        subscription?.applyDefaultValues()
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        if (export) {
            output.writeInt(0)
            output.writeString(name)
            output.writeInt(type)
            val subscription = subscription!!
            subscription.serializeForShare(output)
        } else {
            output.writeInt(4)
            output.writeLong(id)
            output.writeLong(userOrder)
            output.writeBoolean(ungrouped)
            output.writeString(name)
            output.writeInt(type)
            if (type == GroupType.SUBSCRIPTION) {
                subscription?.serializeToBuffer(output)
            }
            output.writeInt(order)
            output.writeLong(frontProxy)
            output.writeLong(landingProxy)
            output.writeBoolean(pinned)
            output.writeLong(healthCheckSuccess)
            output.writeLong(healthCheckTotal)
            output.writeString(healthCheckHistory)
        }
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        if (export) {
            val version = input.readInt()
            name = input.readString()
            type = input.readInt()
            val subscription = SubscriptionBean()
            this.subscription = subscription
            subscription.deserializeFromShare(input)
        } else {
            val version = input.readInt()
            id = input.readLong()
            userOrder = input.readLong()
            ungrouped = input.readBoolean()
            name = input.readString()
            type = input.readInt()
            if (type == GroupType.SUBSCRIPTION) {
                val subscription = SubscriptionBean()
                this.subscription = subscription
                subscription.deserializeFromBuffer(input)
            }
            order = input.readInt()
            if (version >= 1) {
                frontProxy = input.readLong()
                landingProxy = input.readLong()
            }
            if (version >= 2) {
                pinned = input.readBoolean()
            }
            if (version >= 3) {
                healthCheckSuccess = input.readLong()
                healthCheckTotal = input.readLong()
            }
            if (version >= 4) {
                healthCheckHistory = input.readString()
            }
        }
    }

    fun parsedHealthCheckHistory(): GroupHealthCheckHistory {
        return GroupHealthCheckHistory.decode(healthCheckHistory)
    }

    fun healthCheckSuccessPercent(): Int? {
        return parsedHealthCheckHistory().successPercent()
    }

    fun healthCheckStatusLabel(): String {
        val history = parsedHealthCheckHistory()
        val percent = history.successPercent() ?: return "-"
        if (percent == 0) return "$percent% (${history.consecutiveFailures})"
        return "$percent%" + if (history.isComplete) " (+)" else ""
    }

    fun recordHealthCheck(success: Boolean) {
        healthCheckHistory = parsedHealthCheckHistory().append(success).encode()
        healthCheckSuccess = 0L
        healthCheckTotal = 0L
    }

    fun resetHealthChecks() {
        healthCheckHistory = ""
        healthCheckSuccess = 0L
        healthCheckTotal = 0L
    }

    fun displayName(): String {
        return name.takeIf { !it.isNullOrEmpty() } ?: app.getString(R.string.group_default)
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM proxy_groups ORDER BY pinned DESC, userOrder")
        fun allGroups(): List<ProxyGroup>

        @Query("SELECT * FROM proxy_groups WHERE type = ${GroupType.SUBSCRIPTION} ORDER BY pinned DESC, userOrder")
        fun subscriptions(): List<ProxyGroup>

        @Query("SELECT MAX(userOrder) + 1 FROM proxy_groups")
        fun nextOrder(): Long?

        @Query("SELECT * FROM proxy_groups WHERE id = :groupId")
        fun getById(groupId: Long): ProxyGroup?

        @Query("DELETE FROM proxy_groups WHERE id = :groupId")
        fun deleteById(groupId: Long): Int

        @Delete
        fun deleteGroup(group: ProxyGroup)

        @Delete
        fun deleteGroup(groupList: List<ProxyGroup>)

        @Insert
        fun createGroup(group: ProxyGroup): Long

        @Update
        fun updateGroup(group: ProxyGroup)

        @Update
        fun updateGroup(groupList: List<ProxyGroup>)

        @Query("DELETE FROM proxy_groups")
        fun reset()

        @Insert
        fun insert(groupList: List<ProxyGroup>)

    }

    companion object {
        @JvmField
        val CREATOR = object : CREATOR<ProxyGroup>() {

            override fun newInstance(): ProxyGroup {
                return ProxyGroup()
            }

            override fun newArray(size: Int): Array<ProxyGroup?> {
                return arrayOfNulls(size)
            }
        }
    }

}
