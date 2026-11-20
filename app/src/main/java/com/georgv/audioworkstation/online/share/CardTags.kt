package com.georgv.audioworkstation.online.share

internal const val MaxCardTags = 12
internal const val MaxCardTagLength = 24
internal const val MaxCardDescriptionLength = 240

internal fun cardTagList(stored: String): List<String> {
    if (stored.isBlank()) return emptyList()
    val tags = ArrayList<String>()
    for (part in stored.split(',')) {
        val tag = part.trim()
        if (tag.isNotEmpty()) tags += tag
    }
    return tags
}

internal fun checkedCardDescription(raw: String): String {
    val text = raw.replace(Regex("\\s+"), " ").trim()
    if (text.length > MaxCardDescriptionLength) error("description is too long")
    return text
}

internal fun checkedCardTags(stored: String): List<String> {
    val tags = cardTagList(stored)
    if (tags.size > MaxCardTags) error("too many tags")
    if (tags.any { tag -> tag.length > MaxCardTagLength }) error("tag is invalid")
    return tags
}
