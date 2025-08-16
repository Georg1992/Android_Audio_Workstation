package com.georgv.audioworkstation.engine

class NativeEngine {
	init {
		System.loadLibrary("audioworkstation")
	}

	fun init() = nativeInit()
	fun release() = nativeRelease()
	fun clearTracks() = nativeClearTracks()
	fun addTrack(path: String) = nativeAddTrack(path)
	fun offlineMixToWav(outputPath: String): Boolean = nativeOfflineMixToWav(outputPath)

	private external fun nativeInit()
	private external fun nativeRelease()
	private external fun nativeClearTracks()
	private external fun nativeAddTrack(path: String)
	private external fun nativeOfflineMixToWav(outputPath: String): Boolean
}