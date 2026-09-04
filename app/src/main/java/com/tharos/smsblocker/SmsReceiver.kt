package com.tharos.smsblocker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isDefaultApp = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

        // Prevent duplicate notifications and processing:
        // If we are the default app, we only handle SMS_DELIVER_ACTION.
        // If we are NOT the default app, we handle SMS_RECEIVED_ACTION.
        val shouldProcess = if (isDefaultApp) {
            action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
        } else {
            action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        }

        if (shouldProcess) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            // Combine multi-part SMS messages into a single body
            val firstMessage = messages[0]
            val sender = firstMessage.displayOriginatingAddress ?: return
            val body = messages.joinToString("") { it.displayMessageBody ?: "" }
            val timestamp = firstMessage.timestampMillis
            
            Log.d("SmsReceiver", "Intercepted SMS from $sender: $body")
            
            val prefs = context.getSharedPreferences("blocker_prefs", Context.MODE_PRIVATE)
            val keywords = prefs.getStringSet("keywords", setOf("Stop2End")) ?: setOf("Stop2End")
            val isSpam = keywords.any { body.contains(it, ignoreCase = true) }

            if (isSpam) {
                Log.d("SmsReceiver", "Spam keyword detected! Saving to spam folder.")
                blockSender(context, sender)
                
                if (isDefaultApp) {
                    saveToTelephony(context, sender, body, timestamp, isSpam = true)
                }
                
                // Notify the user that a message was blocked so they can find it in the spam folder
                showBlockedNotification(context, sender, body)
            } else {
                if (isDefaultApp) {
                    // If we are the default app and it's NOT spam, 
                    // we MUST manually save it to the system provider as a single combined message.
                    saveToTelephony(context, firstMessage.originatingAddress ?: sender, body, timestamp, isSpam = false)
                }
                // Notify the user about the new legitimate message
                showNotification(context, sender, body)
            }
        }
    }

    private fun showBlockedNotification(context: Context, sender: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_spam", true)
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 
            sender.hashCode() + 1, 
            intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, "sms_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Spam blocked from $sender")
            .setContentText("Message moved to spam folder.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(sender.hashCode(), builder.build())
    }

    private fun showNotification(context: Context, sender: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("address", sender)
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 
            sender.hashCode(), 
            intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, "sms_channel")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New message from $sender")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Extract OTP and add "Copy" action if found
        val otpCode = extractOtp(body)
        if (otpCode != null) {
            val copyIntent = Intent(context, CopyOtpReceiver::class.java).apply {
                putExtra("otp_code", otpCode)
            }
            val copyPendingIntent = PendingIntent.getBroadcast(
                context,
                otpCode.hashCode(),
                copyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_edit, "Copy $otpCode", copyPendingIntent)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun saveToTelephony(context: Context, sender: String, body: String, timestamp: Long, isSpam: Boolean) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.READ, if (isSpam) 1 else 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            
            if (isSpam && uri != null) {
                // Try to mark the thread as spam immediately so it doesn't appear in the main inbox
                val threadId = context.contentResolver.query(uri, arrayOf("thread_id"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                
                if (threadId != null) {
                    val threadValues = ContentValues().apply {
                        put("type", 4) // SPAM
                        put("archived", 1)
                    }
                    val threadUri = "content://mms-sms/conversations/$threadId".toUri()
                    context.contentResolver.update(threadUri, threadValues, null, null)
                    
                    // Record timestamp for auto-deletion after 7 days (handled in MainActivity)
                    context.getSharedPreferences("spam_timestamps", Context.MODE_PRIVATE).edit {
                        putLong(threadId, System.currentTimeMillis())
                    }
                }
            }
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
