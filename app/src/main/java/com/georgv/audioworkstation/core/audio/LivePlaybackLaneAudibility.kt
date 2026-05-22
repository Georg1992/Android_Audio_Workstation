package com.georgv.audioworkstation.core.audio

/**
 * Maps UI selection to per fixed lane-slot audibility (HJ.1 + HJ.2).
 *
 * Indices with null [sessionLaneTrackIds] entries are inaudible in the bool array.
 * See [audibleTrackIds] for the derived set of audible track IDs.
 */
fun laneAudibilityFromSelection(
    sessionLaneTrackIds: Array<String?>,
    selectedTrackIds: Set<String>,
): BooleanArray =
    BooleanArray(sessionLaneTrackIds.size) { index ->
        val trackId = sessionLaneTrackIds[index] ?: return@BooleanArray false
        trackId in selectedTrackIds
    }

/** Legacy helper for transport-start lane lists (initial arm order). */
fun armedLaneAudibilityFromSelection(
    armedTrackIdsInLaneOrder: List<String>,
    selectedTrackIds: Set<String>,
): BooleanArray =
    BooleanArray(armedTrackIdsInLaneOrder.size) { index ->
        armedTrackIdsInLaneOrder[index] in selectedTrackIds
    }
