package com.georgv.audioworkstation.ui.screens.projects

private const val ProjectNameMaxLength = 40
private val ProjectMultiWhitespace = Regex("\\s+")

sealed interface ProjectNameValidationResult {
    data class Valid(val normalizedName: String) : ProjectNameValidationResult
    data class Invalid(val message: String) : ProjectNameValidationResult
}

fun validateProjectName(rawName: String): ProjectNameValidationResult {
    val normalized = rawName
        .replace(ProjectMultiWhitespace, " ")
        .trim()

    if (normalized.isEmpty()) {
        return ProjectNameValidationResult.Invalid("Project name cannot be blank.")
    }

    if (normalized.length > ProjectNameMaxLength) {
        return ProjectNameValidationResult.Invalid("Project name must be $ProjectNameMaxLength characters or less.")
    }

    return ProjectNameValidationResult.Valid(normalized)
}
