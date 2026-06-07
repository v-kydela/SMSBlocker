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
        val action = intent.action
        if (action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION || 
            action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val sender = message.displayOriginatingAddress ?: continue
                val body = message.displayMessageBody ?: ""
                
                Log.d("SmsReceiver", "Intercepted SMS from $sender: $body")
                
                if (body.contains("Stop2End", ignoreCase = true)) {
                    Log.d("SmsReceiver", "Spam keyword detected! Blocking.")
                    blockSender(context, sender)
                    
                    // If we are the default app (receiving SMS_DELIVER), 
                    // NOT saving it to the database effectively blocks it.
                    Toast.makeText(context, "Spam blocked from $sender", Toast.LENGTH_LONG).show()
                    
                    // In SMS_RECEIVED (non-default), we can't stop the message 
                    // from reaching the default app, but we've already blocked the number.
                } else if (action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
                    // If we are the default app and it's NOT spam, 
                    // we SHOULD save it to the system provider.
                    // (This is a simplified example; a full SMS app would do more here)
                    saveToTelephony(context, message)
                }
            }
        }
    }

    private fun saveToTelephony(context: Context, message: android.telephony.SmsMessage) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, message.originatingAddress)
                put(Telephony.Sms.BODY, message.messageBody)
                put(Telephony.Sms.DATE, message.timestampMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error saving message to provider", e)
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
