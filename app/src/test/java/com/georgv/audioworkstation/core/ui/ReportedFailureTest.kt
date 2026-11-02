package com.georgv.audioworkstation.core.ui

import com.georgv.audioworkstation.R
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportedFailureTest {
    @Test
    fun offlineHostIsAConnectionProblem() {
        val offline = UnknownHostException("Unable to resolve host \"api.example.com\"")
        assertEquals(R.string.error_no_connection, reportedFailure(offline, R.string.error_sign_in_failed))
    }

    @Test
    fun unreachableRouteIsAConnectionProblem() {
        val failed = ConnectException("Failed to connect to api.example.com/203.0.113.5:443")
        failed.initCause(Exception("connect failed: ENETUNREACH (Network is unreachable)"))
        assertEquals(R.string.error_no_connection, reportedFailure(failed, R.string.error_share_project_failed))
    }

    @Test
    fun connectTimeoutIsAConnectionProblem() {
        val timedOut = SocketTimeoutException("failed to connect to /203.0.113.5 (port 443) after 15000ms")
        assertEquals(R.string.error_no_connection, reportedFailure(timedOut, R.string.error_unshare_project_failed))
    }

    @Test
    fun noRouteAndNetworkDownAreConnectionProblems() {
        assertEquals(
            R.string.error_no_connection,
            reportedFailure(NoRouteToHostException("No route to host"), R.string.error_shared_projects_failed),
        )
        assertEquals(
            R.string.error_no_connection,
            reportedFailure(SocketException("Network is unreachable"), R.string.error_card_save_failed),
        )
    }

    @Test
    fun refusedPortStaysTheActionFailure() {
        val refused = ConnectException("Failed to connect to api.example.com/203.0.113.5:443")
        refused.initCause(Exception("connect failed: ECONNREFUSED (Connection refused)"))
        assertEquals(R.string.error_share_project_failed, reportedFailure(refused, R.string.error_share_project_failed))
    }

    @Test
    fun serverStatusStaysTheActionFailure() {
        val status = IllegalStateException("share api status 500")
        assertEquals(R.string.error_shared_projects_failed, reportedFailure(status, R.string.error_shared_projects_failed))
    }

    @Test
    fun readTimeoutStaysTheActionFailure() {
        val read = SocketTimeoutException("Read timed out")
        val idle = SocketTimeoutException("timeout")
        assertEquals(R.string.error_sign_in_failed, reportedFailure(read, R.string.error_sign_in_failed))
        assertEquals(R.string.error_card_save_failed, reportedFailure(idle, R.string.error_card_save_failed))
    }
}
