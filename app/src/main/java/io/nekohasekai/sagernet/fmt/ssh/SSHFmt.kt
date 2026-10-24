/******************************************************************************
 *                                                                            *
 * Copyright (C) 2025  dyhkwong                                               *
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.ssh

import libsagernetcore.Libsagernetcore

fun parseSSH(link: String): SSHBean {
    // Warning: no public key pinning is insecure!
    val url = Libsagernetcore.parseURL(link)
    return SSHBean().apply {
        serverAddress = url.host.ifEmpty { error("empty host") }
        serverPort = url.port.takeIf { it > 0 } ?: 22
        username = url.username
        password = url.password
        name = url.fragment
        if (url.password.isNotEmpty()) {
            authType = SSHBean.AUTH_TYPE_PASSWORD
        }
    }
}
