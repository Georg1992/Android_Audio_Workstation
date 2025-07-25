package com.georgv.audioworkstation.ui.main.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.FragmentEffectBinding
import com.georgv.audioworkstation.ui.main.SongViewModel
import com.google.android.material.slider.Slider


class EffectFragment : Fragment() {


    private lateinit var binding: FragmentEffectBinding
    private lateinit var track: Track
    private val viewModel: SongViewModel by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentEffectBinding.inflate(inflater, container, false)
        //track = args.selectedTrack
        Log.d("TRACK EQ IN EFFECT FRAG", "${track}")

        val applyAllButton: ImageButton = binding.applyAllEffect
        val trackName = binding.trackName
        trackName.text = track.name

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

            //setSliders(sliders, switches, track.equalizer, track.compressor, track.reverb)


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
            val action = EffectFragmentDirections.actionEffectFragmentToTrackListFragment()
            NavHostFragment.findNavController(this).navigate(action)
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

//            if (rev != null && rev is Reverb) {
//                binding.sliderDelay.value = rev.delayInMilliSeconds.toFloat()
//                binding.sliderDryWet.value = rev.reverbPercent.toFloat()
//                binding.sliderFeedback.value = rev.feedbackFactor
//                binding.sliderDecay.value = rev.decayFactor
//                binding.switchReverb.isChecked = true
//            }
//            if (eq != null && eq is Equalizer) {
//                binding.sldBand1.value = eq.band1.toFloat()
//                binding.sldBand2.value = eq.band2.toFloat()
//                binding.sldBand3.value = eq.band3.toFloat()
//                binding.sldBand4.value = eq.band4.toFloat()
//                binding.sldBand5.value = eq.band5.toFloat()
//                binding.sldBand6.value = eq.band6.toFloat()
//                binding.switchEq.isChecked = true
//            }
//            if (comp != null && comp is Compressor) {
//                binding.sliderThreshold.value = comp.threshold
//                binding.sliderRatio.value = comp.ratio
//                binding.sliderKnee.value = comp.knee
//                binding.sliderAttack.value = comp.attackTime
//                binding.sliderRelease.value = comp.releaseTime
//                binding.sliderGain.value = comp.makeupGain
//                binding.switchCompressor.isChecked = true
//            }
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