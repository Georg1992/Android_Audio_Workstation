package com.georgv.audioworkstation.online

import kotlinx.coroutines.flow.Flow

interface AccountSessionStore {
    val state: Flow<AccountSession?>

    suspend fun current(): AccountSession?

    suspend fun save(session: AccountSession)

    suspend fun clear()
}
