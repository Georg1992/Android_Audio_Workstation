package com.georgv.audioworkstation.core.validation

const val DefaultNameMaxLength = 40

private val MultiWhitespace = Regex("\\s+")

sealed interface NameValidationResult {
    data class Valid(val normalized: String) : NameValidationResult
    data class Invalid(val error: NameValidationError) : NameValidationResult
}

sealed class NameValidationError {
    data object Blank : NameValidationError()
    data class TooLong(val maxLength: Int) : NameValidationError()
}

/**
 * Normalizes whitespace and validates that [raw] is non-blank and at most [maxLength] characters.
 * Domain-only: returns a typed result so callers (UI / ViewModels) can localize the error message.
 */
fun validateName(raw: String, maxLength: Int = DefaultNameMaxLength): NameValidationResult {
    val normalized = raw.replace(MultiWhitespace, " ").trim()
    if (normalized.isEmpty()) {
        return NameValidationResult.Invalid(NameValidationError.Blank)
    }
    if (normalized.length > maxLength) {
        return NameValidationResult.Invalid(NameValidationError.TooLong(maxLength))
    }
    return NameValidationResult.Valid(normalized)
}
