package com.georgv.audioworkstation.online

import kotlinx.coroutines.CancellationException

internal suspend fun signOutLocallyThenRevoke(
    sessions: AccountSessionStore,
    accounts: CognitoAccounts,
    onRevokeFailed: (Exception) -> Unit,
) {
    val session = sessions.current() ?: return
    sessions.clear()
    try {
        accounts.signOut(session.refreshToken)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        onRevokeFailed(error)
    }
}
