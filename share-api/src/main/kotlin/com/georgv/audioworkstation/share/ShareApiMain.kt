package com.georgv.audioworkstation.share

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

fun main() {
    val config = ShareApiConfig.fromEnvironment()
    val http = ShareHttp(
        config = config,
        tokens = CognitoAccessTokens(),
        manifest = ShareManifest(config),
        objects = SharedObjects(config),
    )
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", config.port), 0)
    server.createContext("/") { exchange ->
        try {
            http.handle(exchange)
        } finally {
            exchange.close()
        }
    }
    server.executor = Executors.newFixedThreadPool(8)
    server.start()
}
