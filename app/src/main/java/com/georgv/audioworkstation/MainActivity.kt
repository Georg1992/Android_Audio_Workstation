package com.georgv.audioworkstation

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.georgv.audioworkstation.databinding.MainFragmentBinding
import com.georgv.audioworkstation.ui.main.MainFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: MainFragmentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainFragmentBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
    }

}