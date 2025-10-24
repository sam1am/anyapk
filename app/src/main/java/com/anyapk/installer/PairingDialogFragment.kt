package com.anyapk.installer

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PairingDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val codeInput = view.findViewById<EditText>(R.id.pairingCodeInput)
        val portInput = view.findViewById<EditText>(R.id.pairingPortInput)

        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pairing_title))
            .setMessage(getString(R.string.pairing_message))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_pair)) { _, _ ->
                val code = codeInput.text.toString()
                val port = portInput.text.toString().toIntOrNull() ?: 0

                if (code.isNotEmpty() && port > 0) {
                    pairDevice(code, port)
                } else {
                    Toast.makeText(context, getString(R.string.error_invalid_code), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()
    }

    private fun pairDevice(code: String, port: Int) {
        lifecycleScope.launch {
            val result = AdbInstaller.pair(requireContext(), code, port)

            result.onSuccess {
                Toast.makeText(context, getString(R.string.pairing_success), Toast.LENGTH_LONG).show()

                // Show next steps
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Pairing Successful!")
                    .setMessage("Next: You need to authorize the connection.\n\nTap OK, then tap 'Test Connection' to trigger the authorization prompt.\n\nWhen you see 'Allow USB debugging?', check 'Always allow from this computer' and tap 'Allow'.")
                    .setPositiveButton("OK") { _, _ ->
                        // Show test connection button
                        (activity as? MainActivity)?.showTestConnectionButton()
                        dismiss()
                    }
                    .setCancelable(false)
                    .show()
            }

            result.onFailure { error ->
                Toast.makeText(context, getString(R.string.pairing_failed, error.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
