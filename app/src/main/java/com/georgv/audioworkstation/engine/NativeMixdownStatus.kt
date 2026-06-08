package com.georgv.audioworkstation.engine

enum class NativeMixdownStatus(val code: Int) {
    Success(0),
    Failed(1),
    Cancelled(2),
    ;

    companion object {
        fun fromCode(code: Int): NativeMixdownStatus =
            entries.firstOrNull { it.code == code } ?: Failed
    }
}
