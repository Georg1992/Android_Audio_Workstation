package com.georgv.audioworkstation.core.session

internal class StreamPosition {
    var read: () -> Long = { 0L }
}
