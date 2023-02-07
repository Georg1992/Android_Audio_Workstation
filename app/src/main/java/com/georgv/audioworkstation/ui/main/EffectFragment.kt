package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.navArgs
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.customHandlers.TypeConverter
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.FragmentEffectBinding
import com.google.android.material.slider.Slider


class EffectFragment : Fragment() {


    private lateinit var binding: FragmentEffectBinding
    private lateinit var track: Track
    private val viewModel: SongViewModel by activityViewModels()
    private val args: EffectFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentEffectBinding.inflate(inflater, container, false)
        track = args.selectedTrack
        Log.d("TRACK EQ IN EFFECT FRAG", "${track.equalizer}")

        val applyAllButton: ImageButton = binding.applyAllEffect
        val trackName = binding.trackName
        trackName.text = track.trackName

        val sliders = arrayOf(
            Pair(binding.sldBand1, binding.band1Value),
            Pair(binding.sldBand2, binding.band2Value),
            Pair(binding.sldBand3, binding.band3Value),
            Pair(binding.sldBand4, binding.band4Value),
            Pair(binding.sldBand5, binding.band5Value),
            Pair(binding.sldBand6, binding.band6Value),

            Pair(binding.sliderThreshold,binding.thresholdValue),
            Pair(binding.sliderRatio,binding.ratioValue),
            Pair(binding.sliderKnee,binding.kneeValue),
            Pair(binding.sliderAttack,binding.attackValue),
            Pair(binding.sliderRelease,binding.releaseValue),
            Pair(binding.sliderGain,binding.compGainValue),

            Pair(binding.sliderDelay, binding.delayValue),
            Pair(binding.sliderDryWet, binding.dryWetValue),
            Pair(binding.sliderDecay, binding.decayValue),
            Pair(binding.sliderFeedback, binding.feedbackValue),
        )
        val switches = arrayOf(
            binding.switchEq,binding.switchReverb,binding.switchCompressor
        )

        setSliders(sliders, switches, track.equalizer, track.compressor, track.reverb)


        for(switch in switches){
            switch.setOnCheckedChangeListener{sw, isChecked ->
                if (isChecked) {
                    updateEffect(sw.tag.toString())
                } else {
                    removeEffect(sw.tag.toString())
                }

            }
        }




        applyAllButton.setOnClickListener {
            val song = viewModel.currentSong
            if (song != null) {
                val action = EffectFragmentDirections.actionEffectFragmentToTrackListFragment(song)
                NavHostFragment.findNavController(this).navigate(action)
            }
        }
        // Inflate the layout for this fragment
        return binding.root
    }

    private fun updateEffect(tag: String) {
        Log.d("UPDATING EFFECT", "TO DATA BASE")
        when (tag) {
            "rev" -> {
                val effectToUpdate = Reverb(
                    binding.sliderDelay.value.toInt(), binding.sliderDecay.value,
                    binding.sliderDryWet.value.toInt(), binding.sliderFeedback.value
                )
                viewModel.updateEffectToDb(effectToUpdate, track.id)
            }
            "eq" -> {
                val effectToUpdate = Equalizer(
                    binding.sldBand1.value.toInt(), binding.sldBand2.value.toInt(),
                    binding.sldBand3.value.toInt(), binding.sldBand4.value.toInt(),
                    binding.sldBand5.value.toInt(), binding.sldBand6.value.toInt()
                )
                viewModel.updateEffectToDb(effectToUpdate, track.id)
            }
            "comp" -> {
                val effectToUpdate = Compressor(binding.sliderThreshold.value, binding.sliderRatio.value,
                binding.sliderKnee.value, binding.sliderAttack.value,binding.sliderRelease.value, binding.sliderGain.value
                )
                viewModel.updateEffectToDb(effectToUpdate, track.id)
            }
        }
    }

    private fun removeEffect(tag: String) {
        viewModel.deleteEffectFromDb(tag, track.id)
    }

    private fun setSliders(
        arraySl: Array<Pair<Slider, TextView>>,
        arraySw: Array<SwitchCompat>,
        eq: String?,
        comp: String?,
        rev: String?
    ) {
        setSliderSteps()
        val equalizer = TypeConverter.toEffect(eq)
        val compressor = TypeConverter.toEffect(comp)
        val reverb = TypeConverter.toEffect(rev)

        for (pair in arraySl) {
            val slider = pair.first
            val textView = pair.second

            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    val tag = slider.tag
                    for (switch in arraySw){
                        if(switch.isChecked && switch.tag == tag){
                            updateEffect(tag.toString())
                        }
                    }
                }
            })

            slider.addOnChangeListener { sl: Slider, fl: Float, _: Boolean ->
                setTextView(sl, textView, fl)
            }

            if (reverb != null && reverb is Reverb) {
                binding.sliderDelay.value = reverb.delayInMilliSeconds.toFloat()
                binding.sliderDryWet.value = reverb.reverbPercent.toFloat()
                binding.sliderFeedback.value = reverb.feedbackFactor
                binding.sliderDecay.value = reverb.decayFactor
                binding.switchReverb.isChecked = true
            }
            if (equalizer != null && equalizer is Equalizer) {
                binding.sldBand1.value = equalizer.band1.toFloat()
                binding.sldBand2.value = equalizer.band2.toFloat()
                binding.sldBand3.value = equalizer.band3.toFloat()
                binding.sldBand4.value = equalizer.band4.toFloat()
                binding.sldBand5.value = equalizer.band5.toFloat()
                binding.sldBand6.value = equalizer.band6.toFloat()
                binding.switchEq.isChecked = true
            }
            if (compressor != null && compressor is Compressor) {
                binding.sliderThreshold.value = compressor.threshold
                binding.sliderRatio.value = compressor.ratio
                binding.sliderKnee.value = compressor.knee
                binding.sliderAttack.value = compressor.attackTime
                binding.sliderRelease.value = compressor.releaseTime
                binding.sliderGain.value = compressor.makeupGain
                binding.switchCompressor.isChecked = true
            }
            setTextView(slider,textView,slider.value)
        }
    }

    private fun setSliderSteps() {
        binding.sliderDelay.stepSize = 1f
        binding.sliderDryWet.stepSize = 1f
        binding.sliderDecay.stepSize = 0.1f
        binding.sliderFeedback.stepSize = 0.1f

        binding.sldBand1.stepSize = 1f
        binding.sldBand2.stepSize = 1f
        binding.sldBand3.stepSize = 1f
        binding.sldBand4.stepSize = 1f
        binding.sldBand5.stepSize = 1f
        binding.sldBand6.stepSize = 1f

        binding.sliderThreshold.stepSize = 1f
        binding.sliderRatio.stepSize = 0.25f
        binding.sliderKnee.stepSize = 1f
        binding.sliderAttack.stepSize = 1f
        binding.sliderRelease.stepSize = 1f
        binding.sliderGain.stepSize = 1f
    }


    private fun setTextView(slider: Slider, textView: TextView, value: Float) {
        if (slider.stepSize >= 1) {
            textView.text = value.toInt().toString()
        } else {
            textView.text = value.toString()
        }
    }

}