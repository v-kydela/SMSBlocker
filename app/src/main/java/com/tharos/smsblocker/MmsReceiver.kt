package com.tharos.smsblocker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ACTION_MMS_DOWNLOADED = "com.tharos.smsblocker.ACTION_MMS_DOWNLOADED"

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("MmsReceiver", "Received action: $action")

        if (action == Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) {
            val pushData = intent.getByteArrayExtra("data") ?: return
            val locationUrl = extractLocationUrl(pushData)
            val sender = extractSenderFromPdu(pushData)

            Log.d("MmsReceiver", "Extracted MMS locationUrl: $locationUrl, sender: $sender")
            if (locationUrl.isNullOrBlank()) {
                Log.e("MmsReceiver", "Could not extract locationUrl from MMS notification PDU")
                return
            }

            try {
                val cacheDir = File(context.cacheDir, "mms_downloads").apply { mkdirs() }
                val tempFile = File(cacheDir, "mms_${System.currentTimeMillis()}_${(1000..9999).random()}.dat")
                val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)

                val downloadIntent = Intent(context, MmsReceiver::class.java).apply {
                    this.action = ACTION_MMS_DOWNLOADED
                    putExtra("filePath", tempFile.absolutePath)
                    putExtra("locationUrl", locationUrl)
                    putExtra("sender", sender)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    tempFile.name.hashCode(),
                    downloadIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                context.grantUriPermission(
                    "com.android.providers.telephony",
                    contentUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                val subId = intent.getIntExtra("subscription", -1)
                val smsManager = if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                } else if (subId != -1) {
                    @Suppress("DEPRECATION")
                    SmsManager.getSmsManagerForSubscriptionId(subId)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                Log.d("MmsReceiver", "Triggering downloadMultimediaMessage for $locationUrl")
                smsManager.downloadMultimediaMessage(
                    context,
                    locationUrl,
                    contentUri,
                    null,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e("MmsReceiver", "Error setting up MMS download", e)
            }
        } else if (action == ACTION_MMS_DOWNLOADED) {
            val filePath = intent.getStringExtra("filePath") ?: return
            val notificationSender = intent.getStringExtra("sender")
            val tempFile = File(filePath)

            Log.d("MmsReceiver", "MMS download completed broadcast received for $filePath")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    processDownloadedMms(context, tempFile, notificationSender)
                } catch (e: Exception) {
                    Log.e("MmsReceiver", "Error processing downloaded MMS", e)
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun processDownloadedMms(
        context: Context,
        file: File,
        notificationSender: String?
    ) = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            Log.e("MmsReceiver", "Downloaded MMS file does not exist or is empty")
            return@withContext
        }

        val pduBytes = file.readBytes()
        val parsed = parseRetrieveConfPdu(pduBytes)

        val sender = parsed.sender?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: notificationSender?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: "Unknown"

        val textContent = parsed.textParts.joinToString("\n").trim()
        val keywords = getBlockerKeywords(context)
        val manualSpam = getManualSpam(context)
        val normalizedSender = sender.replace(Regex("[^0-9+]"), "")
        val normalizedManualSpam = manualSpam.map { it.replace(Regex("[^0-9+]"), "") }.filter { it.isNotEmpty() }.toSet()

        val isSpamByKw = isSpamByKeyword(textContent, keywords)
        val isSpamByNum = manualSpam.contains(sender) || (normalizedSender.isNotEmpty() && normalizedSender in normalizedManualSpam)
        val isSpam = isSpamByKw || isSpamByNum

        Log.d("MmsReceiver", "Processing downloaded MMS from $sender. Text: '$textContent', media parts: ${parsed.mediaParts.size}, isSpam: $isSpam")

        if (isSpamByNum) {
            reportSpamToCarrier(context, sender, textContent)
        }

        val threadId = if (sender != "Unknown") {
            try { Telephony.Threads.getOrCreateThreadId(context, sender).toString() } catch (_: Exception) { null }
        } else null

        val timestamp = System.currentTimeMillis()
        val mmsValues = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, if (isSpam) 1 else 0)
            put(Telephony.Mms.SEEN, if (isSpam) 1 else 0)
            put(Telephony.Mms.DATE, timestamp / 1000)
            put(Telephony.Mms.SUBJECT, parsed.subject ?: "")
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.mms-message")
            put(Telephony.Mms.MMS_VERSION, 0x12)
            put(Telephony.Mms.MESSAGE_TYPE, 132) // RetrieveConf
            if (threadId != null && threadId != "-1") {
                put(Telephony.Mms.THREAD_ID, threadId)
            }
        }

        val mmsUri = context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, mmsValues)
        if (mmsUri == null) {
            Log.e("MmsReceiver", "Failed to insert MMS into Telephony.Mms.Inbox")
            return@withContext
        }

        val mmsId = mmsUri.lastPathSegment ?: ""
        val partUri = "content://mms/$mmsId/part".toUri()

        // Save text parts
        if (textContent.isNotBlank()) {
            val textPartValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", "text/plain")
                put("text", textContent)
                put("chset", 106)
            }
            context.contentResolver.insert(partUri, textPartValues)
        }

        // Save media parts (images, videos)
        for ((index, part) in parsed.mediaParts.withIndex()) {
            val ext = when {
                part.mimeType.contains("png") -> "png"
                part.mimeType.contains("gif") -> "gif"
                part.mimeType.contains("mp4") -> "mp4"
                part.mimeType.contains("3gp") -> "3gp"
                else -> "jpg"
            }
            val fileName = part.fileName ?: "attachment_$index.$ext"
            val mediaPartValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", part.mimeType)
                put("cl", fileName)
            }
            val insertedPartUri = context.contentResolver.insert(partUri, mediaPartValues)
            if (insertedPartUri != null) {
                context.contentResolver.openOutputStream(insertedPartUri)?.use { output ->
                    output.write(part.data)
                }
            }
        }

        // Save sender address
        if (sender.isNotBlank()) {
            val addrValues = ContentValues().apply {
                put("msg_id", mmsId)
                put("address", sender)
                put("type", 137) // PduHeaders.FROM
                put("charset", 106)
            }
            context.contentResolver.insert("content://mms/$mmsId/addr".toUri(), addrValues)
        }

        // Notify UI of update
        context.contentResolver.notifyChange(Telephony.Mms.CONTENT_URI, null)
        context.contentResolver.notifyChange("content://mms-sms/conversations/".toUri(), null)

        if (isSpam) {
            if (threadId != null) {
                context.getSharedPreferences("spam_timestamps", Context.MODE_PRIVATE).edit {
                    putLong(threadId, System.currentTimeMillis())
                }
            }
            showBlockedNotification(context, sender)
        } else {
            val notifBody = when {
                textContent.isNotBlank() -> textContent
                parsed.mediaParts.any { it.mimeType.startsWith("video/") } -> "Sent a video"
                parsed.mediaParts.isNotEmpty() -> "Sent a photo"
                else -> "Multimedia message"
            }
            showNotification(context, sender, notifBody, timestamp)
        }
    }
}

data class ParsedMmsPart(
    val mimeType: String,
    val fileName: String?,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParsedMmsPart

        if (mimeType != other.mimeType) return false
        if (fileName != other.fileName) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class ParsedMmsMessage(
    val sender: String?,
    val subject: String?,
    val textParts: List<String>,
    val mediaParts: List<ParsedMmsPart>
)

fun parseRetrieveConfPdu(pdu: ByteArray): ParsedMmsMessage {
    val sender = extractSenderFromPdu(pdu)
    val subject = extractSubjectFromPdu(pdu)

    val textParts = mutableListOf<String>()
    val mediaParts = mutableListOf<ParsedMmsPart>()

    parseMultipartBody(pdu, textParts, mediaParts)

    if (mediaParts.isEmpty()) {
        scanMediaByMagicBytes(pdu, mediaParts)
    }

    return ParsedMmsMessage(sender, subject, textParts, mediaParts)
}

fun extractLocationUrl(pdu: ByteArray): String? {
    // 1. Check header 0x83
    for (i in pdu.indices) {
        if ((pdu[i].toInt() and 0xFF) == 0x83 && i + 1 < pdu.size) {
            val sb = StringBuilder()
            var j = i + 1
            if (j < pdu.size && (pdu[j].toInt() and 0xFF) < 32 && (pdu[j].toInt() and 0xFF) > 0) {
                j++
            }
            while (j < pdu.size) {
                val b = pdu[j].toInt() and 0xFF
                if (b == 0) break
                if (b in 32..126) {
                    sb.append(b.toChar())
                } else break
                j++
            }
            val str = sb.toString().trim()
            if (str.startsWith("http://", ignoreCase = true) || str.startsWith("https://", ignoreCase = true)) {
                return str
            }
        }
    }

    // 2. Fallback scan for http:// or https://
    val pduStr = String(pdu, Charsets.ISO_8859_1)
    val idx1 = pduStr.indexOf("http://", ignoreCase = true)
    val idx2 = pduStr.indexOf("https://", ignoreCase = true)
    val httpIdx = if (idx1 != -1) idx1 else idx2

    if (httpIdx != -1) {
        val sb = StringBuilder()
        var j = httpIdx
        while (j < pdu.size) {
            val b = pdu[j].toInt() and 0xFF
            if (b == 0 || b < 32 || b > 126) break
            sb.append(b.toChar())
            j++
        }
        val url = sb.toString().trim()
        if (url.isNotBlank()) return url
    }
    return null
}

fun extractSenderFromPdu(pdu: ByteArray): String? {
    for (i in pdu.indices) {
        if ((pdu[i].toInt() and 0xFF) == 0x89 && i + 1 < pdu.size) {
            var j = i + 1
            while (j < pdu.size && (pdu[j].toInt() and 0xFF) < 32) {
                j++
            }
            val sb = StringBuilder()
            while (j < pdu.size) {
                val b = pdu[j].toInt() and 0xFF
                if (b == 0 || b == '/'.code) break
                if (b in 32..126) {
                    sb.append(b.toChar())
                } else break
                j++
            }
            val addr = sb.toString().trim().removeSuffix("/TYPE=PLMN")
            if (addr.isNotBlank() && (addr.any { it.isDigit() } || addr.contains("@"))) {
                return addr
            }
        }
    }
    return null
}

fun extractSubjectFromPdu(pdu: ByteArray): String? {
    for (i in pdu.indices) {
        if ((pdu[i].toInt() and 0xFF) == 0x96 && i + 1 < pdu.size) {
            var j = i + 1
            if (j < pdu.size && (pdu[j].toInt() and 0xFF) < 32) {
                j++
            }
            val sb = StringBuilder()
            while (j < pdu.size) {
                val b = pdu[j].toInt() and 0xFF
                if (b == 0) break
                if (b in 32..126) {
                    sb.append(b.toChar())
                } else break
                j++
            }
            val sub = sb.toString().trim()
            if (sub.isNotBlank()) return sub
        }
    }
    return null
}

fun readUintvar(data: ByteArray, offset: Int): Pair<Int, Int> {
    var value = 0
    var ptr = offset
    while (ptr < data.size) {
        val b = data[ptr].toInt() and 0xFF
        value = (value shl 7) or (b and 0x7F)
        ptr++
        if ((b and 0x80) == 0) break
    }
    return Pair(value, ptr - offset)
}

fun parseMultipartBody(
    pdu: ByteArray,
    textParts: MutableList<String>,
    mediaParts: MutableList<ParsedMmsPart>
) {
    var ptr = 0
    while (ptr < pdu.size - 4) {
        val (nParts, nLen) = readUintvar(pdu, ptr)
        if (nParts in 1..20) {
            val testPtr = ptr + nLen
            if (testPtr < pdu.size) {
                val (hLen, hLenBytes) = readUintvar(pdu, testPtr)
                val (dLen, dLenBytes) = readUintvar(pdu, testPtr + hLenBytes)
                if (hLen in 1..500 && dLen in 1..10000000 && testPtr + hLenBytes + dLenBytes + hLen + dLen <= pdu.size + 100) {
                    ptr = testPtr
                    var partsParsed = 0
                    while (partsParsed < nParts && ptr < pdu.size) {
                        val (headerLen, hlB) = readUintvar(pdu, ptr)
                        ptr += hlB
                        val (dataLen, dlB) = readUintvar(pdu, ptr)
                        ptr += dlB

                        if (ptr + headerLen + dataLen > pdu.size) break

                        val headerBytes = pdu.copyOfRange(ptr, ptr + headerLen)
                        val dataBytes = pdu.copyOfRange(ptr + headerLen, ptr + headerLen + dataLen)
                        ptr += headerLen + dataLen

                        val mimeType = extractMimeTypeFromPartHeader(headerBytes)
                        if (mimeType != null) {
                            if (mimeType == "text/plain") {
                                val text = String(dataBytes, Charsets.UTF_8).trim()
                                if (text.isNotBlank() && text != "null") {
                                    textParts.add(text)
                                }
                            } else if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                                mediaParts.add(ParsedMmsPart(mimeType, null, dataBytes))
                            }
                        }
                        partsParsed++
                    }
                    if (mediaParts.isNotEmpty() || textParts.isNotEmpty()) return
                }
            }
        }
        ptr++
    }
}

fun extractMimeTypeFromPartHeader(headerBytes: ByteArray): String? {
    for (i in headerBytes.indices) {
        val b = headerBytes[i].toInt() and 0xFF
        when (b) {
            0x83 -> return "text/plain"
            0x84 -> return "text/html"
            0x9D -> return "image/jpeg"
            0x9E -> return "image/gif"
            0x9F -> return "image/png"
            0xA8 -> return "video/3gpp"
            0xA9 -> return "video/mp4"
            0xBA -> return "image/heic"
        }
    }
    val str = String(headerBytes, Charsets.ISO_8859_1)
    return when {
        str.contains("image/jpeg", ignoreCase = true) -> "image/jpeg"
        str.contains("image/png", ignoreCase = true) -> "image/png"
        str.contains("image/gif", ignoreCase = true) -> "image/gif"
        str.contains("image/webp", ignoreCase = true) -> "image/webp"
        str.contains("image/heic", ignoreCase = true) -> "image/heic"
        str.contains("video/mp4", ignoreCase = true) -> "video/mp4"
        str.contains("video/3gpp", ignoreCase = true) || str.contains("video/3gp", ignoreCase = true) -> "video/3gpp"
        str.contains("text/plain", ignoreCase = true) -> "text/plain"
        else -> null
    }
}

fun scanMediaByMagicBytes(pdu: ByteArray, mediaParts: MutableList<ParsedMmsPart>) {
    var i = 0
    while (i < pdu.size - 4) {
        if ((pdu[i].toInt() and 0xFF) == 0xFF &&
            (pdu[i + 1].toInt() and 0xFF) == 0xD8 &&
            (pdu[i + 2].toInt() and 0xFF) == 0xFF
        ) {
            val start = i
            var end = -1
            for (j in start + 3 until pdu.size - 1) {
                if ((pdu[j].toInt() and 0xFF) == 0xFF && (pdu[j + 1].toInt() and 0xFF) == 0xD9) {
                    end = j + 2
                    break
                }
            }
            if (end != -1 && end - start > 100) {
                val imgData = pdu.copyOfRange(start, end)
                mediaParts.add(ParsedMmsPart("image/jpeg", "image_${mediaParts.size + 1}.jpg", imgData))
                i = end
                continue
            }
        }

        if (i + 8 < pdu.size &&
            (pdu[i].toInt() and 0xFF) == 0x89 &&
            (pdu[i + 1].toInt() and 0xFF) == 0x50 &&
            (pdu[i + 2].toInt() and 0xFF) == 0x4E &&
            (pdu[i + 3].toInt() and 0xFF) == 0x47
        ) {
            val start = i
            var end = -1
            for (j in start + 8 until pdu.size - 7) {
                if ((pdu[j].toInt() and 0xFF) == 0x49 &&
                    (pdu[j + 1].toInt() and 0xFF) == 0x45 &&
                    (pdu[j + 2].toInt() and 0xFF) == 0x4E &&
                    (pdu[j + 3].toInt() and 0xFF) == 0x44
                ) {
                    end = j + 8
                    break
                }
            }
            val imgData = pdu.copyOfRange(start, if (end != -1) end else minOf(start + 5000000, pdu.size))
            if (imgData.size > 100) {
                mediaParts.add(ParsedMmsPart("image/png", "image_${mediaParts.size + 1}.png", imgData))
                i = if (end != -1) end else start + imgData.size
                continue
            }
        }

        if (i + 6 < pdu.size &&
            (pdu[i].toInt() and 0xFF) == 0x47 &&
            (pdu[i + 1].toInt() and 0xFF) == 0x49 &&
            (pdu[i + 2].toInt() and 0xFF) == 0x46 &&
            (pdu[i + 3].toInt() and 0xFF) == 0x38
        ) {
            val start = i
            var end = -1
            for (j in start + 6 until pdu.size) {
                if ((pdu[j].toInt() and 0xFF) == 0x3B) {
                    end = j + 1
                    break
                }
            }
            if (end != -1 && end - start > 100) {
                val imgData = pdu.copyOfRange(start, end)
                mediaParts.add(ParsedMmsPart("image/gif", "image_${mediaParts.size + 1}.gif", imgData))
                i = end
                continue
            }
        }

        if (i + 8 < pdu.size &&
            (pdu[i + 4].toInt() and 0xFF) == 0x66 &&
            (pdu[i + 5].toInt() and 0xFF) == 0x74 &&
            (pdu[i + 6].toInt() and 0xFF) == 0x79 &&
            (pdu[i + 7].toInt() and 0xFF) == 0x70
        ) {
            val start = i
            val videoData = pdu.copyOfRange(start, pdu.size)
            if (videoData.size > 500) {
                mediaParts.add(ParsedMmsPart("video/mp4", "video_${mediaParts.size + 1}.mp4", videoData))
                break
            }
        }

        i++
    }
}
