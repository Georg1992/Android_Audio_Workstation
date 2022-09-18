package com.georgv.audioworkstation.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.georgv.audioworkstation.data.Track

class TrackListViewModel : ViewModel() {
    // TODO: Implement the ViewModel
    private val tracks: MutableLiveData<List<Track>> by lazy {
        MutableLiveData<List<Track>>().also {
            //loadTracks()
        }
    }
}