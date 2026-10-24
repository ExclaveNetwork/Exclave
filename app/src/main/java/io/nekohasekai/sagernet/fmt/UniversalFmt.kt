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
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.zlibCompress
import io.nekohasekai.sagernet.ktx.zlibDecompress
import kotlin.io.encoding.Base64

fun parseBackupLink(link: String): AbstractBean {
    val type = link.substring("exclave://".length).substringBefore("?")
    return ProxyEntity(type = TypeMap[type.lowercase()] ?: error("Type $type not found")).apply {
        putByteArray(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(link.substringAfter("?")).zlibDecompress())
    }.requireBean()
}

fun AbstractBean.exportBackup(): String {
    var link = "exclave://"
    link += TypeMap.reversed[ProxyEntity().putBean(this).type]
    link += "?"
    link += Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(KryoConverters.serialize(this).zlibCompress(9))
    return link
}


fun ProxyGroup.exportBackup(): String {
    var link = "exclave://subscription?"
    export = true
    link += Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(KryoConverters.serialize(this).zlibCompress(9))
    export = false
    return link
}