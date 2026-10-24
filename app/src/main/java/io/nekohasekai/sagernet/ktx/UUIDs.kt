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

package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun uuid5(text: String): String {
    val data = ByteArrayOutputStream()
    data.write(ByteArray(16))
    data.write(text.toByteArray())
    val hash = MessageDigest.getInstance("SHA-1").digest(data.toByteArray())
    val result = hash.copyOfRange(0, 16)
    result[6] = result[6] and 0x0F.toByte()
    result[6] = result[6] or 0x50.toByte()
    result[8] = result[8] and 0x3F.toByte()
    result[8] = result[8] or 0x80.toByte()
    return Uuid.fromByteArray(result).toHexDashString()
}

@OptIn(ExperimentalUuidApi::class)
fun uuidOrGenerate(text: String): String {
    if (text.length != 36) {
        return uuid5(text)
    }
    try {
        Uuid.parseHexDash(text)
        return text
    } catch (_: Exception) {
        return uuid5(text)
    }
}