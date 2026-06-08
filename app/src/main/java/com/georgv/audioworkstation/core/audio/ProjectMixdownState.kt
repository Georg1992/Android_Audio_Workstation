package com.georgv.audioworkstation.core.audio

data class ProjectMixdownState(
    val isMixing: Boolean = false,
    val progress: Float = 0f,
    val mixdownWavPath: String? = null,
    val errorMessageResId: Int? = null,
) {
    val hasMixPreview: Boolean
        get() = !mixdownWavPath.isNullOrBlank()
}
