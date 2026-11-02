package com.georgv.audioworkstation.core.ui

import com.georgv.audioworkstation.R
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The string to show. A connection problem replaces the action's own failure text. */
internal fun reportedFailure(error: Throwable, otherwise: Int): Int =
    if (isConnectionProblem(error)) R.string.error_no_connection else otherwise

private fun isConnectionProblem(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (hostUnreachable(current)) return true
        current = current.cause
    }
    return false
}

private fun hostUnreachable(error: Throwable): Boolean =
    when (error) {
        is UnknownHostException,
        is NoRouteToHostException,
        is PortUnreachableException,
        -> true
        is SocketTimeoutException -> connectTimedOut(error.message)
        else -> unreachableText(error.message)
    }

private fun connectTimedOut(message: String?): Boolean {
    if (message == null) return true
    val text = message.trim().lowercase()
    if (text == "timeout" || text.contains("read timed out")) return false
    return true
}

private fun unreachableText(message: String?): Boolean {
    if (message == null) return false
    return unreachableMarks.any { mark -> message.contains(mark) }
}

private val unreachableMarks = listOf(
    "ENETUNREACH",
    "EHOSTUNREACH",
    "ENETDOWN",
    "ETIMEDOUT",
    "EAI_NODATA",
    "EAI_AGAIN",
    "EAI_NONAME",
    "EAI_FAIL",
    "Network is unreachable",
    "No route to host",
    "Unable to resolve host",
    "No address associated",
    "Software caused connection abort",
    "Host is unreachable",
)
