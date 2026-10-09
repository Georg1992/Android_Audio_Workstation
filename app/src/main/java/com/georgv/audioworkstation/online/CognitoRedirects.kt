package com.georgv.audioworkstation.online

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel

interface CognitoRedirects {
    fun offer(uri: Uri?)

    suspend fun next(): Uri
}

@Singleton
class ChannelCognitoRedirects @Inject constructor() : CognitoRedirects {
    private val redirects = Channel<Uri>(capacity = Channel.CONFLATED)

    override fun offer(uri: Uri?) {
        if (uri?.scheme == CognitoConfig.SCHEME) redirects.trySend(uri)
    }

    override suspend fun next(): Uri = redirects.receive()
}
