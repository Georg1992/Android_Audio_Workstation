package com.georgv.audioworkstation.core.ui

/** Whether authoritative screen data has been observed at least once. */
sealed interface DataAvailability {
    /** No first emission yet — content is not authoritative (may hold cached seed). */
    data object Pending : DataAvailability

    /** At least one emission received — content reflects loaded data. */
    data object Ready : DataAvailability
}

/**
 * Generic envelope for screen primary data.
 * [content] is always present; use [availability] to distinguish loading from empty.
 */
data class ScreenState<T>(
    val availability: DataAvailability = DataAvailability.Pending,
    val content: T,
    val isRefreshing: Boolean = false,
    val error: UiMessage? = null,
) {
    val isInitialLoad: Boolean
        get() = availability == DataAvailability.Pending && !isRefreshing

    val isStaleWhileRefreshing: Boolean
        get() = availability == DataAvailability.Ready && isRefreshing
}

/** [DataAvailability.Ready] and [isEmpty] returns true for [content]. */
fun <T> ScreenState<T>.isContentEmpty(isEmpty: (T) -> Boolean): Boolean =
    availability == DataAvailability.Ready && isEmpty(content)
