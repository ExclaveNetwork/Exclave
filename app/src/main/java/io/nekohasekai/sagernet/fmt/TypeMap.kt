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

package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity

object TypeMap : HashMap<String, Int>() {
    init {
        this["socks"] = ProxyEntity.TYPE_SOCKS
        this["http"] = ProxyEntity.TYPE_HTTP
        this["ss"] = ProxyEntity.TYPE_SS
        this["ssr"] = ProxyEntity.TYPE_SSR
        this["vmess"] = ProxyEntity.TYPE_VMESS
        this["vless"] = ProxyEntity.TYPE_VLESS
        this["trojan"] = ProxyEntity.TYPE_TROJAN
        this["naive"] = ProxyEntity.TYPE_NAIVE
        this["config"] = ProxyEntity.TYPE_CONFIG
        this["hysteria2"] = ProxyEntity.TYPE_HYSTERIA2
        this["ssh"] = ProxyEntity.TYPE_SSH
        this["wg"] = ProxyEntity.TYPE_WG
        this["mieru"] = ProxyEntity.TYPE_MIERU
        this["tuic5"] = ProxyEntity.TYPE_TUIC5
        this["shadowtls"] = ProxyEntity.TYPE_SHADOWTLS
        this["juicity"] = ProxyEntity.TYPE_JUICITY
        this["http3"] = ProxyEntity.TYPE_HTTP3
        this["anytls"] = ProxyEntity.TYPE_ANYTLS
        this["shadowquic"] = ProxyEntity.TYPE_SHADOWQUIC
        this["trusttunnel"] = ProxyEntity.TYPE_TRUSTTUNNEL
    }

    val reversed = HashMap<Int, String>()

    init {
        TypeMap.forEach { (key, type) ->
            reversed[type] = key
        }
    }

}