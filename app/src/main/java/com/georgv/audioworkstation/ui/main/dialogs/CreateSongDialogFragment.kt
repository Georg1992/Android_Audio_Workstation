package com.georgv.audioworkstation.ui.main.dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.georgv.audioworkstation.R
import android.text.InputType
import androidx.fragment.app.Fragment
import com.georgv.audioworkstation.ui.main.DialogCaller


class CreateSongDialogFragment(private val caller:DialogCaller) : DialogFragment() {


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // Use the Builder class for convenient dialog construction
            val builder = AlertDialog.Builder(it)
            builder.setMessage("Enter Song Name:")
            val input = EditText(context)
            input.hint = "New Song"
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_CLASS_TEXT
            builder.setView(input)
                .setPositiveButton(R.string.OK,
                    DialogInterface.OnClickListener { _, _ ->
                        val text:String = input.text.toString()
                        caller.delegateFunctionToDialog(text)
                    })
                .setNegativeButton(R.string.CANCEL,
                    DialogInterface.OnClickListener { dialog, id ->
                        dialog.cancel()
                    })
            // Create the AlertDialog object and return it
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}