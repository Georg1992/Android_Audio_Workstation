package com.georgv.audioworkstation.ui.screens.projects.reorder

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/**
 * Pure merge of an optimistic reorder session onto the visible “live” track rows.
 *
 * Returns **null** when the session should produce no UI update (same order, empty merge path, etc.).
 */
object OptimisticTrackOrder {

    fun applySession(
        liveTracks: List<TrackEntity>,
        proposedOrder: List<TrackEntity>
    ): List<TrackEntity>? {
        if (proposedOrder.isEmpty()) return null
        if (trackIdsEqualInOrder(liveTracks, proposedOrder)) return null

        val swapLower = singleAdjacentSwapLowerIndexIfAny(liveTracks, proposedOrder)
        if (swapLower != null) {
            val i = swapLower
            val j = i + 1
            val mut = liveTracks.toMutableList()
            val atI = mut[i]
            val atJ = mut[j]
            mut[i] = atJ.copy(position = i)
            mut[j] = atI.copy(position = j)
            if (trackIdsEqualInOrder(mut, proposedOrder)) {
                return mut
            }
        }

        val live = liveTracks.associateBy { it.id }
        val merged =
            proposedOrder
                .filter { it.id in live }
                .mapIndexed { index, row ->
                    live.getValue(row.id).copy(position = index)
                }
        if (merged.isEmpty()) return null
        val presentIds = merged.map { it.id }.toSet()
        val trailing =
            liveTracks
                .filter { it.id !in presentIds }
                .sortedBy { it.position }
                .mapIndexed { i, t ->
                    t.copy(position = merged.size + i)
                }
        val next = merged + trailing
        if (trackIdsEqualInOrder(liveTracks, next)) return null
        return next
    }
}

private fun trackIdsEqualInOrder(a: List<TrackEntity>, b: List<TrackEntity>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i].id != b[i].id) return false
    }
    return true
}

/**
 * If [proposed] is [current] with exactly one adjacent pair of tracks swapped by id, returns the
 * lower index of that pair.
 */
private fun singleAdjacentSwapLowerIndexIfAny(
    current: List<TrackEntity>,
    proposed: List<TrackEntity>,
): Int? {
    if (current.size != proposed.size || current.isEmpty()) return null
    val n = current.size
    val firstMismatch =
        current.indices.firstOrNull { current[it].id != proposed[it].id } ?: return null
    if (firstMismatch == n - 1) return null
    if (proposed[firstMismatch].id != current[firstMismatch + 1].id) return null
    if (proposed[firstMismatch + 1].id != current[firstMismatch].id) return null
    for (k in firstMismatch + 2 until n) {
        if (current[k].id != proposed[k].id) return null
    }
    return firstMismatch
}
