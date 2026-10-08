package com.georgv.audioworkstation.server

import java.io.File

fun main() {
    val cognito = CognitoSignedAccessTokens(CognitoConfig.CLIENT_ID, UrlCognitoJwks())
    val server = OnlineServer.start(port = 8080, root = File("online-data"), cognito = cognito)
    println("online api http://127.0.0.1:${server.port}")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
