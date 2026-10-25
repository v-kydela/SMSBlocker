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

fun extractOtp(body: String): String? {
    // Pattern: Look for 4 to 8 consecutive digits
    val otpPattern = Regex("\\b\\d{4,8}\\b")
    val keywords = listOf("code", "otp", "pin", "verify", "verification", "authentication")
    
    val hasKeyword = keywords.any { body.contains(it, ignoreCase = true) }
    
    return if (hasKeyword) {
        otpPattern.find(body)?.value
    } else null
}
