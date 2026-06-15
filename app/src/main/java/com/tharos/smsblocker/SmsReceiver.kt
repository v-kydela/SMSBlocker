package com.tharos.smsblocker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.BlockedNumberContract
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

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
                    // NOT saving it to the database effectively blocks it from appearing.
                    Toast.makeText(context, "Spam blocked from $sender", Toast.LENGTH_LONG).show()
                    
                    // In SMS_RECEIVED (non-default), we can't stop the message 
                    // from reaching the default app, but we've already blocked the number for the future.
                } else {
                    if (action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
                        // If we are the default app and it's NOT spam, 
                        // we MUST manually save it to the system provider.
                        saveToTelephony(context, message)
                    }
                    // Notify the user about the new legitimate message
                    showNotification(context, sender, body)
                }
            }
        }
    }

    private fun showNotification(context: Context, sender: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, "sms_channel")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New message from $sender")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
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
        }
    }
}
