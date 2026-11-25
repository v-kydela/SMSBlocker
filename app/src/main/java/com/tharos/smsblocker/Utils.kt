package com.tharos.smsblocker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.edit

val DEFAULT_KEYWORDS = setOf(
    "Stop2end",
    "Stop to end",
    "Stop to quit",
    "End2end",
    "End to end",
)

private const val PREFS_BLOCKER = "blocker_prefs"
private const val KEY_KEYWORDS = "keywords"

private const val PREFS_MANUAL_SPAM = "manual_spam"
private const val KEY_ADDRESSES = "addresses"

private const val PREFS_MANUAL_ARCHIVE = "manual_archive"
private const val PREFS_MANUAL_UNARCHIVE = "manual_unarchive"
private const val KEY_IDS = "ids"

fun getStringSetPref(
    context: Context,
    prefName: String,
    key: String,
    default: Set<String> = emptySet()
): Set<String> {
    val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
    return prefs.getStringSet(key, default) ?: default
}

fun saveStringSetPref(
    context: Context,
    prefName: String,
    key: String,
    value: Set<String>
) {
    val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
    prefs.edit { putStringSet(key, value) }
}

fun getBlockerKeywords(context: Context): Set<String> =
    getStringSetPref(context, PREFS_BLOCKER, KEY_KEYWORDS, DEFAULT_KEYWORDS)

fun saveBlockerKeywords(context: Context, keywords: Set<String>) =
    saveStringSetPref(context, PREFS_BLOCKER, KEY_KEYWORDS, keywords)

fun getManualSpam(context: Context): Set<String> =
    getStringSetPref(context, PREFS_MANUAL_SPAM, KEY_ADDRESSES)

fun saveManualSpam(context: Context, addresses: Set<String>) =
    saveStringSetPref(context, PREFS_MANUAL_SPAM, KEY_ADDRESSES, addresses)

fun getManualArchive(context: Context): Set<String> =
    getStringSetPref(context, PREFS_MANUAL_ARCHIVE, KEY_IDS)

fun saveManualArchive(context: Context, ids: Set<String>) =
    saveStringSetPref(context, PREFS_MANUAL_ARCHIVE, KEY_IDS, ids)

fun getManualUnarchive(context: Context): Set<String> =
    getStringSetPref(context, PREFS_MANUAL_UNARCHIVE, KEY_IDS)

fun saveManualUnarchive(context: Context, ids: Set<String>) =
    saveStringSetPref(context, PREFS_MANUAL_UNARCHIVE, KEY_IDS, ids)

private const val PREFS_SETTINGS = "app_settings"
private const val KEY_CARRIER_REPORTING = "carrier_reporting_7726"

fun isCarrierReportingEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_CARRIER_REPORTING, true)
}

fun setCarrierReportingEnabled(context: Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    prefs.edit { putBoolean(KEY_CARRIER_REPORTING, enabled) }
}

fun reportSpamToCarrier(context: Context, senderNumber: String, messageBody: String? = null) {
    if (!isCarrierReportingEnabled(context) || senderNumber.isBlank() || senderNumber.startsWith("Recipient ID")) return
    try {
        val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
        val text = if (!messageBody.isNullOrBlank()) {
            "$senderNumber: $messageBody"
        } else {
            senderNumber
        }
        val parts = smsManager.divideMessage(text)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage("7726", null, parts, null, null)
        } else {
            smsManager.sendTextMessage("7726", null, text, null, null)
        }
        android.util.Log.i("SMSBlocker", "Reported spam from $senderNumber to carrier shortcode 7726")
    } catch (e: Exception) {
        android.util.Log.e("SMSBlocker", "Failed to send carrier spam report to 7726", e)
    }
}

fun is7726Shortcode(sender: String): Boolean {
    val digits = sender.replace(Regex("[^0-9]"), "")
    return digits == "7726" || sender.equals("7726", ignoreCase = true)
}

private fun isPunctuationOrSpace(c: Char): Boolean =
    c.isWhitespace() || c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';'

fun isKeywordAtEnd(text: String, keyword: String): Boolean {
    if (text.isBlank() || keyword.isBlank()) return false
    val cleanedText = text.trimEnd(::isPunctuationOrSpace)
    val cleanedKeyword = keyword.trim()
    return cleanedText.endsWith(cleanedKeyword, ignoreCase = true)
}

fun findSpamKeyword(text: String, keywords: Set<String>): String? {
    if (text.isBlank() || keywords.isEmpty()) return null
    return keywords.find { isKeywordAtEnd(text, it) }
}

fun isSpamByKeyword(text: String, keywords: Set<String>): Boolean {
    return findSpamKeyword(text, keywords) != null
}

fun extractOtp(body: String): String? {
    // Pattern: Look for 4 to 8 consecutive digits
    val otpPattern = Regex("\\b\\d{4,8}\\b")
    val keywords = listOf("code", "otp", "pin", "verify", "verification", "authentication")
    
    val hasKeyword = keywords.any { body.contains(it, ignoreCase = true) }
    
    return if (hasKeyword) {
        otpPattern.find(body)?.value
    } else null
}

fun getContactName(context: Context, phoneNumber: String): String {
    if (phoneNumber.isBlank() || phoneNumber == "Unknown") return phoneNumber

    val normalized = phoneNumber.replace(Regex("[^0-9+]"), "")
    if (normalized.isNotBlank()) {
        val cached = context.getSharedPreferences("contact_names", Context.MODE_PRIVATE)
            .getString(normalized, null)
        if (!cached.isNullOrBlank()) return cached
    }

    val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(phoneNumber))
    val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
    try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIdx != -1) {
                    val name = cursor.getString(nameIdx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("Utils", "Error fetching contact name", e)
    }
    return phoneNumber
}

data class NotifMessage(val timestamp: Long, val body: String)

fun getActiveNotificationMessages(context: Context, sender: String): List<NotifMessage> {
    val key = sender.replace(Regex("[^0-9+]"), "")
    if (key.isBlank()) return emptyList()
    val prefs = context.getSharedPreferences("active_notifications", Context.MODE_PRIVATE)
    val serialized = prefs.getString(key, null) ?: return emptyList()
    val now = System.currentTimeMillis()
    return try {
        serialized.split("\n\n").mapNotNull { line ->
            val parts = line.split("||", limit = 2)
            if (parts.size == 2) {
                val time = parts[0].toLongOrNull() ?: 0L
                if (now - time < 3 * 3600 * 1000) {
                    NotifMessage(time, parts[1])
                } else null
            } else null
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun addActiveNotificationMessage(context: Context, sender: String, body: String, timestamp: Long): List<NotifMessage> {
    val key = sender.replace(Regex("[^0-9+]"), "")
    if (key.isBlank()) return listOf(NotifMessage(timestamp, body))
    val current = getActiveNotificationMessages(context, sender).toMutableList()
    current.add(NotifMessage(timestamp, body))
    val trimmed = if (current.size > 10) current.takeLast(10) else current

    val serialized = trimmed.joinToString("\n\n") { "${it.timestamp}||${it.body}" }
    context.getSharedPreferences("active_notifications", Context.MODE_PRIVATE).edit {
        putString(key, serialized)
    }
    return trimmed
}

fun clearNotificationMessages(context: Context, sender: String) {
    val key = sender.replace(Regex("[^0-9+]"), "")
    if (key.isBlank()) return
    context.getSharedPreferences("active_notifications", Context.MODE_PRIVATE).edit {
        remove(key)
    }
}

private const val PREFS_MANUAL_READ = "manual_read_timestamps"
private const val PREFS_MANUAL_UNREAD = "manual_unread"

fun getManualReadTimestamps(context: Context): Map<String, Long> {
    val prefs = context.getSharedPreferences(PREFS_MANUAL_READ, Context.MODE_PRIVATE)
    return prefs.all.mapValues { 
        (it.value as? Long) ?: (it.value as? String)?.toLongOrNull() ?: 0L 
    }
}

fun saveManualReadTimestamp(context: Context, threadId: String, timestamp: Long) {
    val prefs = context.getSharedPreferences(PREFS_MANUAL_READ, Context.MODE_PRIVATE)
    prefs.edit { putLong(threadId, timestamp) }
}

fun saveManualReadTimestampsBulk(context: Context, timestamps: Map<String, Long>) {
    val prefs = context.getSharedPreferences(PREFS_MANUAL_READ, Context.MODE_PRIVATE)
    prefs.edit {
        timestamps.forEach { (threadId, timestamp) ->
            putLong(threadId, timestamp)
        }
    }
}

fun removeManualReadTimestampsBulk(context: Context, threadIds: Collection<String>) {
    val prefs = context.getSharedPreferences(PREFS_MANUAL_READ, Context.MODE_PRIVATE)
    prefs.edit {
        threadIds.forEach { remove(it) }
    }
}

fun getManualUnread(context: Context): Set<String> =
    getStringSetPref(context, PREFS_MANUAL_UNREAD, KEY_IDS)

fun saveManualUnread(context: Context, ids: Set<String>) =
    saveStringSetPref(context, PREFS_MANUAL_UNREAD, KEY_IDS, ids)

private const val PREFS_REACTIONS = "local_reactions"

fun getLocalReactions(context: Context, threadId: String): Map<String, List<String>> {
    val prefs = context.getSharedPreferences("${PREFS_REACTIONS}_$threadId", Context.MODE_PRIVATE)
    val map = mutableMapOf<String, List<String>>()
    prefs.all.forEach { (msgId, value) ->
        val list = (value as? String)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        map[msgId] = list
    }
    return map
}

fun saveLocalReaction(context: Context, threadId: String, msgId: String, reactions: List<String>) {
    val prefs = context.getSharedPreferences("${PREFS_REACTIONS}_$threadId", Context.MODE_PRIVATE)
    prefs.edit {
        putString(msgId, reactions.joinToString(","))
    }
}

fun clearLocalReactions(context: Context, threadId: String) {
    context.getSharedPreferences("${PREFS_REACTIONS}_$threadId", Context.MODE_PRIVATE).edit { clear() }
}

fun showBlockedNotification(context: Context, sender: String) {
    createNotificationChannel(context)
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

    val senderName = getContactName(context, sender)
    val builder = NotificationCompat.Builder(context, "spam_channel")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(senderName)
        .setContentText("Spam message blocked")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSound(null)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(sender.hashCode() + 1, builder.build())
}

fun showNotification(context: Context, sender: String, body: String, timestamp: Long) {
    createNotificationChannel(context)

    val activeMessages = addActiveNotificationMessage(context, sender, body, timestamp)
    val senderName = getContactName(context, sender)

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

    val userPerson = Person.Builder().setName("Me").build()
    val senderPerson = Person.Builder().setName(senderName).build()

    val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
        .setConversationTitle(null)

    for (msg in activeMessages) {
        messagingStyle.addMessage(msg.body, msg.timestamp, senderPerson)
    }

    val builder = NotificationCompat.Builder(context, "sms_channel")
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setStyle(messagingStyle)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)

    val latestOtpCode = activeMessages.mapNotNull { extractOtp(it.body) }.lastOrNull()
    if (latestOtpCode != null) {
        val copyIntent = Intent(context, CopyOtpReceiver::class.java).apply {
            putExtra("otp_code", latestOtpCode)
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            context,
            latestOtpCode.hashCode(),
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(android.R.drawable.ic_menu_edit, "Copy $latestOtpCode", copyPendingIntent)
    }

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(sender.hashCode(), builder.build())
}


