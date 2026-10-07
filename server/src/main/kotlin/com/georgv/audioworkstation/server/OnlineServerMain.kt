package com.georgv.audioworkstation.server

import java.io.File

fun main() {
    val server = OnlineServer.start(port = 8080, root = File("online-data"))
    println("online api http://127.0.0.1:${server.port}")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
