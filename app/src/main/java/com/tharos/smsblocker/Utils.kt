package com.tharos.smsblocker

import android.content.Context
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
