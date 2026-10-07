package com.georgv.audioworkstation.online

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.accountSessionDataStore by preferencesDataStore(name = "account_session")

@Singleton
class DataStoreAccountSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) : AccountSessionStore {
    private val dataStore = context.applicationContext.accountSessionDataStore

    override val state: Flow<AccountSession?> =
        dataStore.data.map { prefs ->
            val token = prefs[Keys.TOKEN]
            val accountId = prefs[Keys.ACCOUNT_ID]
            val email = prefs[Keys.EMAIL]
            if (token.isNullOrBlank() || accountId.isNullOrBlank() || email.isNullOrBlank()) {
                null
            } else {
                AccountSession(token = token, accountId = accountId, email = email)
            }
        }

    override suspend fun current(): AccountSession? = state.first()

    override suspend fun save(session: AccountSession) {
        dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = session.token
            prefs[Keys.ACCOUNT_ID] = session.accountId
            prefs[Keys.EMAIL] = session.email
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val ACCOUNT_ID = stringPreferencesKey("account_id")
        val EMAIL = stringPreferencesKey("email")
    }
}
