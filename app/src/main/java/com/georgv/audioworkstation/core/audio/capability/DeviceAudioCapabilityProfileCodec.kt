package com.georgv.audioworkstation.core.audio.capability

import org.json.JSONArray
import org.json.JSONObject

@Suppress("TooManyFunctions")
internal object DeviceAudioCapabilityProfileCodec {
    const val SCHEMA_VERSION = 2

    fun encode(profile: DeviceAudioCapabilityProfile): String =
        ProfileJsonWriter.write(profile)

    fun decode(raw: String): DeviceAudioCapabilityProfile? =
        try {
            decodeInternal(raw)
        } catch (_: Exception) {
            null
        }

    private fun decodeInternal(raw: String): DeviceAudioCapabilityProfile? {
        val root = JSONObject(raw)
        val schemaVersion = root.optInt("schemaVersion", 0)
        if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val recentRoundTripMs = decodeDoubleList(root.optJSONArray("recentRoundTripMs"))
        val measurementHistory =
            if (schemaVersion >= SCHEMA_VERSION) {
                decodeMeasurementHistory(root.optJSONObject("measurementHistory"))
            } else {
                CapabilityMeasurementHistory()
            }
        val validation =
            if (schemaVersion >= SCHEMA_VERSION) {
                decodeValidation(root.optJSONObject("validation"))
            } else {
                CapabilityValidationFlags()
            }
        return DeviceAudioCapabilityProfile(
                profileId = root.getString("profileId"),
                deviceManufacturer = root.optString("deviceManufacturer"),
                deviceModel = root.optString("deviceModel"),
                androidVersion = root.optString("androidVersion"),
                sdkInt = root.getIntValue("sdkInt"),
                routeKey = root.getString("routeKey"),
                routeType = AudioRouteType.valueOf(root.getString("routeType")),
                sampleRate = root.getIntValue("sampleRate"),
                output = decodeStreamSide(root.getJSONObject("output")),
                input = decodeStreamSide(root.getJSONObject("input")),
                calibration = decodeCalibration(root.getJSONObject("calibration")),
                startup = decodeStartup(root.getJSONObject("startup")),
                derived = decodeDerived(root.getJSONObject("derived")),
                measurementHistory = measurementHistory,
                validation = validation,
                recentRoundTripMs = recentRoundTripMs,
                createdAt = root.getLongValue("createdAt"),
                updatedAt = root.getLongValue("updatedAt"),
            )
    }

    private fun JSONObject.getLongValue(key: String): Long {
        val value = get(key)
        return when (value) {
            is Number -> value.toLong()
            else -> getLong(key)
        }
    }

    private fun JSONObject.getIntValue(key: String): Int {
        val value = get(key)
        return when (value) {
            is Number -> value.toInt()
            else -> getInt(key)
        }
    }

    private fun decodeStreamSide(json: JSONObject): StreamCapabilitySide =
        StreamCapabilitySide(
            requestedAudioApi = json.optString("requestedAudioApi"),
            actualAudioApi = json.optString("actualAudioApi"),
            requestedPerformanceMode = json.optString("requestedPerformanceMode"),
            actualPerformanceMode = json.optString("actualPerformanceMode"),
            performanceModeGranted = json.optBoolean("performanceModeGranted"),
            requestedSharingMode = json.optString("requestedSharingMode"),
            actualSharingMode = json.optString("actualSharingMode"),
            sharingModeGranted = json.optBoolean("sharingModeGranted"),
            framesPerBurst = json.optInt("framesPerBurst"),
            bufferSizeFrames = json.optInt("bufferSizeFrames"),
            bufferCapacityFrames = json.optInt("bufferCapacityFrames"),
            bufferSizeMs = json.optDouble("bufferSizeMs"),
            burstMs = json.optDouble("burstMs"),
            halReportedLatencyMs = json.optNullableDouble("halReportedLatencyMs"),
            latencyConfidence = json.optDouble("latencyConfidence"),
            lowLatencyPathDenied = json.optBoolean("lowLatencyPathDenied"),
            timestampAvailable = json.optBoolean("timestampAvailable"),
            timestampStable = json.optBoolean("timestampStable"),
            highLatencyRoute = json.optBoolean("highLatencyRoute"),
        )

    private fun decodeCalibration(json: JSONObject): MeasuredCalibrationData =
        MeasuredCalibrationData(
            measuredRoundTripMs = json.optNullableDouble("measuredRoundTripMs"),
            measuredJitterMs = json.optNullableDouble("measuredJitterMs"),
            estimatedOutputLatencyMs = json.optNullableDouble("estimatedOutputLatencyMs"),
            estimatedTrueCaptureDelayMs = json.optNullableDouble("estimatedTrueCaptureDelayMs"),
            calibrationConfidence = json.optDouble("calibrationConfidence"),
            calibratedAt = json.getLongValue("calibratedAt"),
        )

    private fun decodeStartup(json: JSONObject): StartupMetricsData =
        StartupMetricsData(
            armToFirstInputMs = json.optNullableLongFromDouble("armToFirstInputMs"),
            armToFirstAudibleMs = json.optNullableLongFromDouble("armToFirstAudibleMs"),
            firstInputToFirstAudibleMs = json.optNullableLongFromDouble("firstInputToFirstAudibleMs"),
            startupMetricsUpdatedAt = json.optLong("startupMetricsUpdatedAt"),
        )

    private fun decodeDerived(json: JSONObject): DerivedCapabilityData =
        DerivedCapabilityData(
            outputTier = LatencyTier.valueOf(json.optString("outputTier", LatencyTier.UNKNOWN.name)),
            inputTier = LatencyTier.valueOf(json.optString("inputTier", LatencyTier.UNKNOWN.name)),
            overallLiveLatencyTier =
                LatencyTier.valueOf(
                    json.optString("overallLiveLatencyTier", LatencyTier.UNKNOWN.name),
                ),
            recommendedBackend = json.optString("recommendedBackend"),
            profileState =
                runCatching {
                    CapabilityProfileState.valueOf(
                        json.optString("profileState", CapabilityProfileState.EMPTY.name),
                    )
                }.getOrDefault(CapabilityProfileState.EMPTY),
        )

    private fun decodeValidation(json: JSONObject?): CapabilityValidationFlags {
        if (json == null) {
            return CapabilityValidationFlags()
        }
        return CapabilityValidationFlags(
            measurementInconsistent = json.optBoolean("measurementInconsistent"),
            captureDelayInvalid = json.optBoolean("captureDelayInvalid"),
            captureDelayUnknown = json.optBoolean("captureDelayUnknown"),
            outputLatencyInvalid = json.optBoolean("outputLatencyInvalid"),
            roundTripInvalid = json.optBoolean("roundTripInvalid"),
        )
    }

    private fun decodeMeasurementHistory(json: JSONObject?): CapabilityMeasurementHistory {
        if (json == null) {
            return CapabilityMeasurementHistory()
        }
        return CapabilityMeasurementHistory(
            outputFloorMs = decodeSampleList(json.optJSONArray("outputFloorMs")),
            roundTripMs = decodeSampleList(json.optJSONArray("roundTripMs")),
            captureDelayMs = decodeSampleList(json.optJSONArray("captureDelayMs")),
            jitterMs = decodeSampleList(json.optJSONArray("jitterMs")),
            appOutputCostP95Us = decodeSampleList(json.optJSONArray("appOutputCostP95Us")),
            appInputProcessingP95Us = decodeSampleList(json.optJSONArray("appInputProcessingP95Us")),
            backends = decodeBackendList(json.optJSONArray("backends")),
        )
    }

    private fun decodeSampleList(array: JSONArray?): List<LatencyMeasurementSample> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    LatencyMeasurementSample(
                        valueMs = item.optDouble("valueMs"),
                        measuredAt = item.optLong("measuredAt"),
                    ),
                )
            }
        }
    }

    private fun decodeBackendList(array: JSONArray?): List<BackendCapabilitySnapshot> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    BackendCapabilitySnapshot(
                        audioApi = item.optString("audioApi"),
                        direction = item.optString("direction"),
                        performanceMode = item.optString("performanceMode"),
                        performanceModeGranted = item.optBoolean("performanceModeGranted"),
                        sharingMode = item.optString("sharingMode"),
                        sharingModeGranted = item.optBoolean("sharingModeGranted"),
                        framesPerBurst = item.optInt("framesPerBurst"),
                        bufferSizeFrames = item.optInt("bufferSizeFrames"),
                        halReportedLatencyMs = item.optNullableDouble("halReportedLatencyMs"),
                        latencyAvailable = item.optBoolean("latencyAvailable"),
                        measuredLatencyMs = item.optNullableDouble("measuredLatencyMs"),
                        testedAt = item.optLong("testedAt"),
                    ),
                )
            }
        }
    }

    private fun decodeDoubleList(array: JSONArray?): List<Double> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optDouble(index))
            }
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return optDouble(key)
    }

    private fun JSONObject.optNullableLongFromDouble(key: String): Long? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return optDouble(key).toLong()
    }
}

@Suppress("TooManyFunctions")
private object ProfileJsonWriter {
    fun write(profile: DeviceAudioCapabilityProfile): String =
        buildString {
            append('{')
            writeField("schemaVersion", DeviceAudioCapabilityProfileCodec.SCHEMA_VERSION)
            writeField("profileId", profile.profileId)
            writeField("deviceManufacturer", profile.deviceManufacturer)
            writeField("deviceModel", profile.deviceModel)
            writeField("androidVersion", profile.androidVersion)
            writeField("sdkInt", profile.sdkInt)
            writeField("routeKey", profile.routeKey)
            writeField("routeType", profile.routeType.name)
            writeField("sampleRate", profile.sampleRate)
            writeRawField("output", writeStreamSide(profile.output))
            writeRawField("input", writeStreamSide(profile.input))
            writeRawField("calibration", writeCalibration(profile.calibration))
            writeRawField("startup", writeStartup(profile.startup))
            writeRawField("derived", writeDerived(profile.derived))
            writeRawField("measurementHistory", writeMeasurementHistory(profile.measurementHistory))
            writeRawField("validation", writeValidation(profile.validation))
            writeRawField("recentRoundTripMs", writeDoubleArray(profile.recentRoundTripMs))
            writeField("createdAt", profile.createdAt)
            writeField("updatedAt", profile.updatedAt, last = true)
            append('}')
        }

    private fun StringBuilder.writeField(
        key: String,
        value: String,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        append('"')
        append(escape(value))
        append('"')
        if (!last) {
            append(',')
        }
    }

    private fun StringBuilder.writeField(
        key: String,
        value: Int,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        append(value)
        if (!last) {
            append(',')
        }
    }

    private fun StringBuilder.writeField(
        key: String,
        value: Long,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        append(value)
        if (!last) {
            append(',')
        }
    }

    private fun StringBuilder.writeField(
        key: String,
        value: Boolean,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        append(value)
        if (!last) {
            append(',')
        }
    }

    private fun StringBuilder.writeField(
        key: String,
        value: Double,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        append(value)
        if (!last) {
            append(',')
        }
    }

    private fun StringBuilder.writeNullableField(
        key: String,
        value: Double?,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        if (value == null) {
            append("null")
        } else {
            append(value)
        }
        if (!last) {
            append(',')
        }
    }

    private fun StringBuilder.writeNullableField(
        key: String,
        value: Long?,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        if (value == null) {
            append("null")
        } else {
            append(value)
        }
        if (!last) {
            append(',')
        }
    }

    private fun StringBuilder.writeRawField(
        key: String,
        rawObject: String,
        last: Boolean = false,
    ) {
        append('"')
        append(escape(key))
        append("\":")
        append(rawObject)
        if (!last) {
            append(',')
        }
    }

    private fun writeStreamSide(side: StreamCapabilitySide): String =
        buildString {
            append('{')
            writeField("requestedAudioApi", side.requestedAudioApi)
            writeField("actualAudioApi", side.actualAudioApi)
            writeField("requestedPerformanceMode", side.requestedPerformanceMode)
            writeField("actualPerformanceMode", side.actualPerformanceMode)
            writeField("performanceModeGranted", side.performanceModeGranted)
            writeField("requestedSharingMode", side.requestedSharingMode)
            writeField("actualSharingMode", side.actualSharingMode)
            writeField("sharingModeGranted", side.sharingModeGranted)
            writeField("framesPerBurst", side.framesPerBurst)
            writeField("bufferSizeFrames", side.bufferSizeFrames)
            writeField("bufferCapacityFrames", side.bufferCapacityFrames)
            writeField("bufferSizeMs", side.bufferSizeMs)
            writeField("burstMs", side.burstMs)
            writeNullableField("halReportedLatencyMs", side.halReportedLatencyMs)
            writeField("latencyConfidence", side.latencyConfidence)
            writeField("lowLatencyPathDenied", side.lowLatencyPathDenied)
            writeField("timestampAvailable", side.timestampAvailable)
            writeField("timestampStable", side.timestampStable)
            writeField("highLatencyRoute", side.highLatencyRoute, last = true)
            append('}')
        }

    private fun writeCalibration(data: MeasuredCalibrationData): String =
        buildString {
            append('{')
            writeNullableField("measuredRoundTripMs", data.measuredRoundTripMs)
            writeNullableField("measuredJitterMs", data.measuredJitterMs)
            writeNullableField("estimatedOutputLatencyMs", data.estimatedOutputLatencyMs)
            writeNullableField("estimatedTrueCaptureDelayMs", data.estimatedTrueCaptureDelayMs)
            writeField("calibrationConfidence", data.calibrationConfidence)
            writeField("calibratedAt", data.calibratedAt, last = true)
            append('}')
        }

    private fun writeStartup(data: StartupMetricsData): String =
        buildString {
            append('{')
            writeNullableField("armToFirstInputMs", data.armToFirstInputMs)
            writeNullableField("armToFirstAudibleMs", data.armToFirstAudibleMs)
            writeNullableField("firstInputToFirstAudibleMs", data.firstInputToFirstAudibleMs)
            writeField("startupMetricsUpdatedAt", data.startupMetricsUpdatedAt, last = true)
            append('}')
        }

    private fun writeDerived(data: DerivedCapabilityData): String =
        buildString {
            append('{')
            writeField("outputTier", data.outputTier.name)
            writeField("inputTier", data.inputTier.name)
            writeField("overallLiveLatencyTier", data.overallLiveLatencyTier.name)
            writeField("recommendedBackend", data.recommendedBackend)
            writeField("profileState", data.profileState.name, last = true)
            append('}')
        }

    private fun writeMeasurementHistory(history: CapabilityMeasurementHistory): String =
        buildString {
            append('{')
            writeRawField("outputFloorMs", writeSampleArray(history.outputFloorMs))
            writeRawField("roundTripMs", writeSampleArray(history.roundTripMs))
            writeRawField("captureDelayMs", writeSampleArray(history.captureDelayMs))
            writeRawField("jitterMs", writeSampleArray(history.jitterMs))
            writeRawField("appOutputCostP95Us", writeSampleArray(history.appOutputCostP95Us))
            writeRawField("appInputProcessingP95Us", writeSampleArray(history.appInputProcessingP95Us))
            writeRawField("backends", writeBackendArray(history.backends), last = true)
            append('}')
        }

    private fun writeValidation(flags: CapabilityValidationFlags): String =
        buildString {
            append('{')
            writeField("measurementInconsistent", flags.measurementInconsistent)
            writeField("captureDelayInvalid", flags.captureDelayInvalid)
            writeField("captureDelayUnknown", flags.captureDelayUnknown)
            writeField("outputLatencyInvalid", flags.outputLatencyInvalid)
            writeField("roundTripInvalid", flags.roundTripInvalid, last = true)
            append('}')
        }

    private fun writeSampleArray(samples: List<LatencyMeasurementSample>): String =
        buildString {
            append('[')
            samples.forEachIndexed { index, sample ->
                append('{')
                writeField("valueMs", sample.valueMs)
                writeField("measuredAt", sample.measuredAt, last = true)
                append('}')
                if (index < samples.lastIndex) {
                    append(',')
                }
            }
            append(']')
        }

    private fun writeBackendArray(backends: List<BackendCapabilitySnapshot>): String =
        buildString {
            append('[')
            backends.forEachIndexed { index, backend ->
                append('{')
                writeField("audioApi", backend.audioApi)
                writeField("direction", backend.direction)
                writeField("performanceMode", backend.performanceMode)
                writeField("performanceModeGranted", backend.performanceModeGranted)
                writeField("sharingMode", backend.sharingMode)
                writeField("sharingModeGranted", backend.sharingModeGranted)
                writeField("framesPerBurst", backend.framesPerBurst)
                writeField("bufferSizeFrames", backend.bufferSizeFrames)
                writeNullableField("halReportedLatencyMs", backend.halReportedLatencyMs)
                writeField("latencyAvailable", backend.latencyAvailable)
                writeNullableField("measuredLatencyMs", backend.measuredLatencyMs)
                writeField("testedAt", backend.testedAt, last = true)
                append('}')
                if (index < backends.lastIndex) {
                    append(',')
                }
            }
            append(']')
        }

    private fun writeDoubleArray(values: List<Double>): String =
        buildString {
            append('[')
            values.forEachIndexed { index, value ->
                append(value)
                if (index < values.lastIndex) {
                    append(',')
                }
            }
            append(']')
        }

    private fun escape(value: String): String =
        buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
}
