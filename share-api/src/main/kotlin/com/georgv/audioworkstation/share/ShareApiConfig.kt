package com.georgv.audioworkstation.share

internal data class ShareApiConfig(
    val port: Int,
    val originSecret: String,
    val bucket: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
) {
    companion object {
        fun fromEnvironment(): ShareApiConfig = ShareApiConfig(
            port = required("PORT").toInt(),
            originSecret = required("ORIGIN_SECRET"),
            bucket = required("S3_BUCKET"),
            databaseUrl = required("DATABASE_URL"),
            databaseUser = required("DATABASE_USER"),
            databasePassword = required("DATABASE_PASSWORD"),
        )

        private fun required(name: String): String {
            val value = System.getenv(name)
            if (value.isNullOrBlank()) error("$name is required")
            return value
        }
    }
}
