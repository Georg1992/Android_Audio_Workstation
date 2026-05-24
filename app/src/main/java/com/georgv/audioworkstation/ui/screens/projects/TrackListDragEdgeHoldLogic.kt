package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.ui.geometry.Rect
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.layout.pageCount
import com.georgv.audioworkstation.ui.layout.pageEndExclusive
import com.georgv.audioworkstation.ui.layout.pageStartIndex
import com.georgv.audioworkstation.ui.layout.swapAdjacentAtBoundaryDown
import com.georgv.audioworkstation.ui.layout.swapAdjacentAtBoundaryUp

internal enum class EdgeHoldZone {
    None,
    Top,
    Bottom,
}

internal data class EdgeHoldSnapshot(
    val fingerYRoot: Float,
    val draggingKey: String?,
    val listBounds: Rect,
    val currentPage: Int,
)

internal fun computeEdgeHoldCandidateZone(
    fingerYRoot: Float,
    listBounds: Rect,
    edgeBandPx: Float,
    globalIndex: Int,
    listSize: Int,
    currentPageIndex: Int,
    pageSize: Int,
): EdgeHoldZone {
    val pageStart = pageStartIndex(currentPageIndex, pageSize)
    val pageEnd = pageEndExclusive(listSize, currentPageIndex, pageSize)
    val inBottomBand = fingerYRoot >= listBounds.bottom - edgeBandPx
    val inTopBand = fingerYRoot <= listBounds.top + edgeBandPx
    val canMoveDown = globalIndex == pageEnd - 1 && pageEnd < listSize
    val canMoveUp = globalIndex == pageStart && pageStart > 0
    return when {
        canMoveDown && inBottomBand -> EdgeHoldZone.Bottom
        canMoveUp && inTopBand -> EdgeHoldZone.Top
        else -> EdgeHoldZone.None
    }
}

internal fun edgeHoldProgressBanner(
    armedZone: EdgeHoldZone,
    heldMs: Long,
    pageEdgeHoldMs: Long,
): EdgeHoldBanner {
    val progress = heldMs / pageEdgeHoldMs.toFloat()
    return when (armedZone) {
        EdgeHoldZone.Bottom -> EdgeHoldBanner.Bottom(progress)
        EdgeHoldZone.Top -> EdgeHoldBanner.Top(progress)
        EdgeHoldZone.None -> EdgeHoldBanner.None
    }
}

internal data class EdgeHoldPageTransition(
    val reordered: List<TrackEntity>,
    val scrollToPage: Int,
)

internal fun edgeHoldScrollToPage(
    armedZone: EdgeHoldZone,
    currentPageIndex: Int,
    trackCount: Int,
    pageSize: Int,
): Int? {
    val lastPage = pageCount(trackCount, pageSize).coerceAtLeast(1) - 1
    return when (armedZone) {
        EdgeHoldZone.Bottom -> (currentPageIndex + 1).coerceAtMost(lastPage)
        EdgeHoldZone.Top -> (currentPageIndex - 1).coerceAtLeast(0)
        EdgeHoldZone.None -> null
    }
}

internal fun edgeHoldPageTransitionIfReady(
    armedZone: EdgeHoldZone,
    tracks: List<TrackEntity>,
    globalIndex: Int,
    currentPageIndex: Int,
    pageSize: Int,
): EdgeHoldPageTransition? {
    val reordered =
        when (armedZone) {
            EdgeHoldZone.Bottom -> swapAdjacentAtBoundaryDown(tracks, globalIndex)
            EdgeHoldZone.Top -> swapAdjacentAtBoundaryUp(tracks, globalIndex)
            EdgeHoldZone.None -> null
        }
    val scrollToPage = reordered?.let { edgeHoldScrollToPage(armedZone, currentPageIndex, it.size, pageSize) }
    return if (reordered != null && scrollToPage != null) {
        EdgeHoldPageTransition(reordered = reordered, scrollToPage = scrollToPage)
    } else {
        null
    }
}

internal data class EdgeHoldMachineState(
    val armedZone: EdgeHoldZone = EdgeHoldZone.None,
    val zoneEnterUptimeMs: Long = 0L,
)

internal data class EdgeHoldCollectResult(
    val banner: EdgeHoldBanner,
    val machine: EdgeHoldMachineState,
    val pageTransition: EdgeHoldPageTransition?,
)

private fun edgeHoldInactiveCollectResult(): EdgeHoldCollectResult =
    EdgeHoldCollectResult(
        banner = EdgeHoldBanner.None,
        machine = EdgeHoldMachineState(),
        pageTransition = null,
    )

private fun armEdgeHoldZone(
    machine: EdgeHoldMachineState,
    candidate: EdgeHoldZone,
    nowUptimeMs: Long,
): EdgeHoldMachineState =
    if (candidate != machine.armedZone) {
        EdgeHoldMachineState(armedZone = candidate, zoneEnterUptimeMs = nowUptimeMs)
    } else {
        machine
    }

private fun advanceEdgeHoldForCandidate(
    machine: EdgeHoldMachineState,
    candidate: EdgeHoldZone,
    tracks: List<TrackEntity>,
    globalIndex: Int,
    currentPageIndex: Int,
    pageSize: Int,
    nowUptimeMs: Long,
    pageEdgeHoldMs: Long,
): EdgeHoldCollectResult {
    val armed = armEdgeHoldZone(machine, candidate, nowUptimeMs)
    val heldMs = nowUptimeMs - armed.zoneEnterUptimeMs
    if (heldMs < pageEdgeHoldMs) {
        return EdgeHoldCollectResult(
            banner = edgeHoldProgressBanner(armed.armedZone, heldMs, pageEdgeHoldMs),
            machine = armed,
            pageTransition = null,
        )
    }
    val transition =
        edgeHoldPageTransitionIfReady(
            armedZone = armed.armedZone,
            tracks = tracks,
            globalIndex = globalIndex,
            currentPageIndex = currentPageIndex.coerceAtLeast(0),
            pageSize = pageSize,
        )
    return EdgeHoldCollectResult(
        banner = EdgeHoldBanner.None,
        machine = EdgeHoldMachineState(zoneEnterUptimeMs = nowUptimeMs),
        pageTransition = transition,
    )
}

internal fun reduceEdgeHoldOnCollect(
    machine: EdgeHoldMachineState,
    fingerYRoot: Float,
    draggingKey: String?,
    tracks: List<TrackEntity>,
    globalIndex: Int,
    listBounds: Rect,
    edgeBandPx: Float,
    currentPageIndex: Int,
    pageSize: Int,
    nowUptimeMs: Long,
    pageEdgeHoldMs: Long,
): EdgeHoldCollectResult {
    val pageIdx = currentPageIndex.coerceAtLeast(0)
    val inactive = draggingKey == null || globalIndex < 0 || listBounds.isEmpty
    val candidate =
        if (inactive) {
            EdgeHoldZone.None
        } else {
            computeEdgeHoldCandidateZone(
                fingerYRoot = fingerYRoot,
                listBounds = listBounds,
                edgeBandPx = edgeBandPx,
                globalIndex = globalIndex,
                listSize = tracks.size,
                currentPageIndex = pageIdx,
                pageSize = pageSize,
            )
        }
    return when {
        inactive || candidate == EdgeHoldZone.None ->
            edgeHoldInactiveCollectResult()
        else ->
            advanceEdgeHoldForCandidate(
                machine = machine,
                candidate = candidate,
                tracks = tracks,
                globalIndex = globalIndex,
                currentPageIndex = pageIdx,
                pageSize = pageSize,
                nowUptimeMs = nowUptimeMs,
                pageEdgeHoldMs = pageEdgeHoldMs,
            )
    }
}
