package com.georgv.audioworkstation.server

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OnlineServer(
    private val http: HttpServer,
    private val executor: ExecutorService,
    private val database: OnlineDatabase,
) : AutoCloseable {
    val port: Int get() = http.address.port

    override fun close() {
        http.stop(0)
        executor.shutdownNow()
        database.close()
    }

    companion object {
        const val STORAGE_DIRECTORY = "storage"
        const val DATABASE_FILE = "online.db"

        fun start(port: Int, root: File, cognito: CognitoAccessTokens): OnlineServer {
            check(root.mkdirs() || root.isDirectory) { "Online data directory was not created." }
            val storage = ContentAddressedStorage(File(root, STORAGE_DIRECTORY))
            val database = OnlineDatabase(File(root, DATABASE_FILE))
            val accounts = AccountService(database, cognito)
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            val executor = Executors.newCachedThreadPool()
            http.executor = executor
            http.createContext("/", OnlineHandler(accounts, OnlineService(accounts, database, storage)))
            http.start()
            return OnlineServer(http, executor, database)
        }
    }
}
