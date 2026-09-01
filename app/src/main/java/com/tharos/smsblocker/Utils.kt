package com.tharos.smsblocker

fun extractOtp(body: String): String? {
    // Pattern: Look for 4 to 8 consecutive digits
    val otpPattern = Regex("\\b\\d{4,8}\\b")
    val keywords = listOf("code", "otp", "pin", "verify", "verification", "authentication")
    
    val hasKeyword = keywords.any { body.contains(it, ignoreCase = true) }
    
    return if (hasKeyword) {
        otpPattern.find(body)?.value
    } else null
}
