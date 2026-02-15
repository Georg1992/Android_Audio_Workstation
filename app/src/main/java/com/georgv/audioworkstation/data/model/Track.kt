package com.georgv.audioworkstation.data.model

import java.util.UUID

data class Track(
    val id: String = UUID.randomUUID().toString(),
    val songId: String? = null,
    val name: String? = null,
    val gain: Float = 100f,
    val wavFilePath: String = "",
    val timeStampStart: Long = 0L,
    val timeStampStop: Long? = null,
    val duration: Long? = null
)



