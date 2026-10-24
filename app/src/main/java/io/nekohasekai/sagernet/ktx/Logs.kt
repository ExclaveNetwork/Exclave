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

import android.util.Log
import io.nekohasekai.sagernet.utils.DiagnosticLog
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    fun v(message: String) {
        //  if (BuildConfig.DEBUG) {
        Log.v(mkTag(), message)
//        }
    }

    fun v(message: String, exception: Throwable) {
        //  if (BuildConfig.DEBUG) {
        Log.v(mkTag(), message, exception)
//        }
    }

    fun d(message: String) {
        //  if (BuildConfig.DEBUG) {
        Log.d(mkTag(), message)
//        }
    }

    fun d(message: String, exception: Throwable) {
        //  if (BuildConfig.DEBUG) {
        Log.d(mkTag(), message, exception)
//        }
    }

    fun i(message: String) {
        Log.i(mkTag(), message)
    }

    fun i(message: String, exception: Throwable) {
        Log.i(mkTag(), message, exception)
    }

    fun w(message: String) {
        val tag = mkTag()
        Log.w(tag, message)
        DiagnosticLog.warn(tag, message)
    }

    fun w(message: String, exception: Throwable) {
        val tag = mkTag()
        Log.w(tag, message, exception)
        DiagnosticLog.warn(tag, message, exception)
    }

    fun w(exception: Throwable) {
        val tag = mkTag()
        Log.w(tag, exception)
        DiagnosticLog.warn(tag, exception.toString(), exception)
    }

    fun e(message: String) {
        val tag = mkTag()
        Log.e(tag, message)
        DiagnosticLog.error(tag, message)
    }

    fun e(message: String, exception: Throwable) {
        val tag = mkTag()
        Log.e(tag, message, exception)
        DiagnosticLog.error(tag, message, exception)
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}
