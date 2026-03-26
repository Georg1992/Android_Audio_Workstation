package com.georgv.audioworkstation.ui.screens.projects

private const val TrackNameMaxLength = 40
private val MultiWhitespace = Regex("\\s+")

sealed interface TrackNameValidationResult {
    data class Valid(val normalizedName: String) : TrackNameValidationResult
    data class Invalid(val message: String) : TrackNameValidationResult
}

fun validateTrackName(rawName: String): TrackNameValidationResult {
    val normalized = rawName
        .replace(MultiWhitespace, " ")
        .trim()

    if (normalized.isEmpty()) {
        return TrackNameValidationResult.Invalid("Track name cannot be blank.")
    }

    if (normalized.length > TrackNameMaxLength) {
        return TrackNameValidationResult.Invalid("Track name must be $TrackNameMaxLength characters or less.")
    }

    return TrackNameValidationResult.Valid(normalized)
}
