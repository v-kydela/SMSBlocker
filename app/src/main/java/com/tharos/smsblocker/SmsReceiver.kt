package com.tharos.smsblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.provider.BlockedNumberContract
import android.provider.Telephony
import android.util.Log
import android.widget.Toast

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val sender = message.displayOriginatingAddress ?: continue
                val body = message.displayMessageBody ?: ""
                
                Log.d("SmsReceiver", "Received SMS from $sender: $body")
                
                if (body.contains("Stop2End", ignoreCase = true)) {
                    Log.d("SmsReceiver", "Keyword 'Stop2End' detected! Blocking sender $sender")
                    blockSender(context, sender)
                    Toast.makeText(context, "Blocked spam from $sender", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun blockSender(context: Context, phoneNumber: String) {
        try {
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phoneNumber)
            }
            context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
            Log.i("SmsReceiver", "Successfully blocked $phoneNumber via system.")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Could not block number. App might not be the default SMS/Dialer app.", e)
            // Fallback: save to internal database (not implemented here)
        }
    }
}
