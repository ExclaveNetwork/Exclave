/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  lingyicute                                             *
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

import java.io.File
import java.io.RandomAccessFile

/**
 * Checks that the file is structurally a GeoIPList / GeoSiteList, i.e. a sequence of
 * `entry = 1` length-delimited messages, each starting with a `country_code = 1` string,
 * that exactly fills the file. Truncated files, HTML error pages, etc. are rejected.
 * Throws [IllegalArgumentException] if the file is malformed.
 */
fun File.validateGeoData() {
    RandomAccessFile(this, "r").use { raf ->
        val size = raf.length()
        require(size > 0) { "empty file" }
        var pos = 0L
        while (pos < size) {
            raf.seek(pos)
            require(raf.read() == 0x0A) { "unexpected tag at offset $pos" }
            var length = 0L
            var shift = 0
            var headerSize = 1L
            while (true) {
                val b = raf.read()
                require(b >= 0) { "unexpected end of file" }
                headerSize++
                length = length or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
                require(shift <= 28) { "invalid length at offset $pos" }
            }
            val end = pos + headerSize + length
            require(length > 0 && end <= size) { "truncated entry at offset $pos" }
            require(raf.read() == 0x0A) { "invalid entry at offset $pos" }
            pos = end
        }
    }
}