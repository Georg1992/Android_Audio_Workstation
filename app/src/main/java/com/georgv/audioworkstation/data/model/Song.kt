package com.georgv.audioworkstation.data.model

import java.util.UUID

data class Song(
    val id: String = UUID.randomUUID().toString(),
    val name: String? = null,
    val wavFilePath: String? = null
)







