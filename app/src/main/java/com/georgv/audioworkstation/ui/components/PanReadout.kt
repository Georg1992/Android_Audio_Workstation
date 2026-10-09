package com.georgv.audioworkstation.ui.components

/** Maps [com.georgv.audioworkstation.core.audio.PanRange] readout tokens onto localized side letters. */
fun localizedPanReadout(
    raw: String,
    left: String,
    right: String,
    center: String,
): String = when (raw) {
    "C" -> center
    "L" -> left
    "R" -> right
    else -> localizedPanTenths(raw, left, right)
}

private fun localizedPanTenths(raw: String, left: String, right: String): String {
    require(raw.length >= 2) { "Pan readout is too short: $raw" }
    val number = raw.dropLast(1)
    return when (raw.last()) {
        'L' -> number + left
        'R' -> number + right
        else -> error("Pan readout must end with L or R: $raw")
    }
}
