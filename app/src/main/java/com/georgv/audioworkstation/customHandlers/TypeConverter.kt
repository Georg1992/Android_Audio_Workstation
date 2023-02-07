package com.georgv.audioworkstation.customHandlers

import androidx.room.TypeConverter
import com.georgv.audioworkstation.audioprocessing.Compressor
import com.georgv.audioworkstation.audioprocessing.Effect
import com.georgv.audioworkstation.audioprocessing.Equalizer
import com.georgv.audioworkstation.audioprocessing.Reverb
import java.util.*
import com.google.gson.Gson


object TypeConverter {
    @TypeConverter
    fun fromEffect(effect: Effect?): String? {
        when (effect) {
            is Reverb -> return "Reverb,${effect.delayInMilliSeconds},${effect.decayFactor},${effect.reverbPercent},${effect.feedbackFactor}"
            is Equalizer -> return "Equalizer,${effect.band1},${effect.band2},${effect.band3},${effect.band4},${effect.band5},${effect.band6}"
            is Compressor -> return "Compressor,${effect.threshold},${effect.ratio},${effect.knee},${effect.attackTime},${effect.releaseTime},${effect.makeupGain}"
        }
        return null
    }

    @TypeConverter
    fun toEffect(effectString: String?): Effect? {
        if(effectString != null){
            val values = effectString.split(",")
            return when (values[0]) {
                "Reverb" -> Reverb(values[1].toInt(), values[2].toFloat(), values[3].toInt(), values[4].toFloat())
                "Equalizer" -> Equalizer(values[1].toInt(), values[2].toInt(), values[3].toInt(), values[4].toInt(), values[5].toInt(), values[6].toInt())
                "Compressor" -> Compressor(values[1].toFloat(), values[2].toFloat(), values[3].toFloat(), values[4].toFloat(), values[5].toFloat(), values[6].toFloat())
                else -> null
            }
        }
        return null
    }

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date): Long {
        return date.time
    }


    @TypeConverter
    fun fromArrayList(list: ArrayList<String?>?): String? {
        val gson = Gson()
        return gson.toJson(list)
    }



}