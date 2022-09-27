package com.georgv.audioworkstation.ui.main

import android.os.Debug
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.data.Track

class TrackListViewModel : ViewModel() {

    private var _tracks: MutableLiveData<ArrayList<Track>> = MutableLiveData<ArrayList<Track>>()
    val currentTracks: LiveData<ArrayList<Track>>
        get() = _tracks


    fun createNewTrackList():ArrayList<Track>{
        val trackList = ArrayList<Track>()
        return trackList
    }

    fun addNewTrack(currentTrackList:ArrayList<Track>) {
        val newTrack = Track("New Instrument")
        currentTrackList.add(newTrack)
        _tracks.value = currentTrackList

    }


}