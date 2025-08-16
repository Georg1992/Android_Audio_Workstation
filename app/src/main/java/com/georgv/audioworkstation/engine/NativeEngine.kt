package com.georgv.audioworkstation.engine

import java.io.File
import java.io.FileOutputStream
import android.util.Log

class NativeEngine {
	private val TAG = "NativeEngine"
	
	init {
		try {
			System.loadLibrary("audioworkstation")
		} catch (e: UnsatisfiedLinkError) {
			Log.w(TAG, "Native library not available, using stub implementation")
		}
	}

	fun init() = try { nativeInit() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native init not available") }
	fun release() = try { nativeRelease() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native release not available") }
	fun clearTracks() = try { nativeClearTracks() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native clearTracks not available") }
	fun addTrack(path: String) = try { nativeAddTrack(path) } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native addTrack not available") }
	fun addTrack(path: String, volume: Float) = try { nativeAddTrackWithVolume(path, volume) } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native addTrackWithVolume not available") }
	fun loadTracks() = try { nativeLoadTracks() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native loadTracks not available") }
	fun offlineMixToWav(outputPath: String): Boolean = try { nativeOfflineMixToWav(outputPath) } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native offlineMixToWav not available"); false }
	fun start(): Boolean = try { nativeStart() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native start not available"); false }
	fun stop() = try { nativeStop() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native stop not available") }
	fun reset() = try { nativeReset() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native reset not available") }
	
	// Recording functions
	fun startRecording(outputPath: String): Boolean = try { nativeStartRecording(outputPath) } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native startRecording not available"); false }
	fun stopRecording() = try { nativeStopRecording() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native stopRecording not available") }
	fun isRecording(): Boolean = try { nativeIsRecording() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Native isRecording not available"); false }
	
	/**
	 * Create a simple test tone WAV file for testing
	 * Returns the path to the created file, or null if failed
	 */
	fun createTestTone(outputDir: File, frequency: Float = 440f, durationSeconds: Float = 2f): String? {
		try {
			val fileName = "test_tone_${frequency.toInt()}hz.wav"
			val outputFile = File(outputDir, fileName)
			
			val sampleRate = 44100
			val channels = 2
			val duration = (durationSeconds * sampleRate).toInt()
			val dataSize = duration * channels * 2 // 16-bit samples
			
			FileOutputStream(outputFile).use { fos ->
				// WAV header
				fos.write("RIFF".toByteArray())
				writeInt32LE(fos, 36 + dataSize) // file size - 8
				fos.write("WAVE".toByteArray())
				fos.write("fmt ".toByteArray())
				writeInt32LE(fos, 16) // fmt chunk size
				writeInt16LE(fos, 1) // audio format (PCM)
				writeInt16LE(fos, channels.toShort()) // num channels
				writeInt32LE(fos, sampleRate) // sample rate
				writeInt32LE(fos, sampleRate * channels * 2) // byte rate
				writeInt16LE(fos, (channels * 2).toShort()) // block align
				writeInt16LE(fos, 16) // bits per sample
				fos.write("data".toByteArray())
				writeInt32LE(fos, dataSize)
				
				// Generate sine wave data
				for (i in 0 until duration) {
					val time = i.toFloat() / sampleRate
					val sample = (Math.sin(2.0 * Math.PI * frequency * time) * 0.3 * 32767).toInt().toShort()
					
					// Write for both channels
					writeInt16LE(fos, sample) // left
					writeInt16LE(fos, sample) // right
				}
			}
			
			Log.i(TAG, "Created test tone: ${outputFile.absolutePath}")
			return outputFile.absolutePath
		} catch (e: Exception) {
			Log.e(TAG, "Failed to create test tone", e)
			return null
		}
	}
	
	private fun writeInt32LE(fos: FileOutputStream, value: Int) {
		fos.write(value and 0xFF)
		fos.write((value shr 8) and 0xFF)
		fos.write((value shr 16) and 0xFF)
		fos.write((value shr 24) and 0xFF)
	}
	
	private fun writeInt16LE(fos: FileOutputStream, value: Short) {
		fos.write((value.toInt() and 0xFF))
		fos.write((value.toInt() shr 8) and 0xFF)
	}

	private external fun nativeInit()
	private external fun nativeRelease()
	private external fun nativeClearTracks()
	private external fun nativeAddTrack(path: String)
	private external fun nativeAddTrackWithVolume(path: String, volume: Float)
	private external fun nativeLoadTracks()
	private external fun nativeOfflineMixToWav(outputPath: String): Boolean
	private external fun nativeStart(): Boolean
	private external fun nativeStop()
	private external fun nativeReset()
	
	// Recording JNI methods
	private external fun nativeStartRecording(outputPath: String): Boolean
	private external fun nativeStopRecording()
	private external fun nativeIsRecording(): Boolean
}