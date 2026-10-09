package com.georgv.audioworkstation.ui.screens.projects

internal object QuickRecordStop {
    fun tracksToMix(
        quickRecord: Boolean,
        takeStopped: Boolean,
        playableTrackIds: Set<String>,
    ): Set<String>? {
        if (!quickRecord || !takeStopped) return null
        if (playableTrackIds.isEmpty()) return null
        return playableTrackIds
    }
}
