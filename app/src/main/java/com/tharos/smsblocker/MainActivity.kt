package com.tharos.smsblocker

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tharos.smsblocker.ui.theme.SMSBlockerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

data class MessageThread(
    val threadId: String,
    val address: String,
    val contactName: String?,
    val snippet: String,
    val date: Long,
    val read: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SMSBlockerTheme {
                MainNavigation()
            }
        }
    }
}

@Composable
fun MainNavigation() {
    var currentScreen by remember { mutableStateOf("threads") }
    // var selectedThreadId by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                "threads" -> ConversationListScreen(
                    onSettingsClick = { currentScreen = "settings" },
                    onThreadClick = { _ -> 
                        // selectedThreadId = id
                        // currentScreen = "chat" // To be implemented
                    }
                )
                "settings" -> SmsBlockerSettingsScreen(
                    onBack = { currentScreen = "threads" }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(onSettingsClick: () -> Unit, onThreadClick: (String) -> Unit) {
    val context = LocalContext.current
    var threads by remember { mutableStateOf<List<MessageThread>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val hasPermissions = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(hasPermissions.value) {
        if (hasPermissions.value) {
            threads = fetchThreads(context)
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("Messages") },
            actions = {
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        )

        if (!hasPermissions.value) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onSettingsClick) {
                    Text("Grant Permissions in Settings")
                }
            }
        } else if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn {
                items(threads) { thread ->
                    ThreadItem(thread, onClick = { onThreadClick(thread.threadId) })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
fun ThreadItem(thread: MessageThread, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.contactName ?: thread.address,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (!thread.read) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = thread.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (!thread.read) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SmsBlockerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageName = context.packageName
    
    var isDefaultSmsApp by remember {
        mutableStateOf(Telephony.Sms.getDefaultSmsPackage(context) == packageName)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.values.all { it }
    }

    val defaultAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context) == packageName
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Blocker Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        StatusRow(label = "Permissions", status = hasPermission)
        StatusRow(label = "Default SMS App", status = isDefaultSmsApp)
        
        Button(onClick = {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_MMS,
                Manifest.permission.READ_CONTACTS
            ))
        }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Grant Permissions")
        }

        if (!isDefaultSmsApp) {
            Button(onClick = {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                }
                defaultAppLauncher.launch(intent)
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Set as Default SMS App")
            }
        }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Back to Messages")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Blocking Keyword: 'Stop2End'", color = Color.Red)
    }
}

@Composable
fun StatusRow(label: String, status: Boolean) {
    Text("$label: ${if (status) "✅" else "❌"}")
}

private suspend fun fetchThreads(context: Context): List<MessageThread> = withContext(Dispatchers.IO) {
    val threadsMap = mutableMapOf<String, MessageThread>()
    val contentResolver: ContentResolver = context.contentResolver
    
    // Using Telephony.Sms.CONTENT_URI to get messages and group them by thread_id
    // This is more reliable than content://sms/conversations which may not support 'date' column on all devices
    val uri = Telephony.Sms.CONTENT_URI
    val projection = arrayOf(
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.READ
    )
    
    try {
        contentResolver.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            val threadIdIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            
            while (cursor.moveToNext()) {
                val threadId = cursor.getString(threadIdIdx) ?: continue
                
                // The first message we encounter for each threadId is the latest one due to DESC sort
                if (!threadsMap.containsKey(threadId)) {
                    val address = cursor.getString(addressIdx) ?: "Unknown"
                    val snippet = cursor.getString(bodyIdx) ?: ""
                    val date = cursor.getLong(dateIdx)
                    val read = cursor.getInt(readIdx) == 1
                    
                    val contactName = fetchContactName(contentResolver, address)
                    
                    threadsMap[threadId] = MessageThread(threadId, address, contactName, snippet, date, read)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    threadsMap.values.toList().sortedByDescending { it.date }
}

private fun fetchContactName(contentResolver: ContentResolver, phoneNumber: String): String? {
    if (phoneNumber == "Unknown") return null
    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
    val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
    
    return try {
        contentResolver.query(uri, projection, null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
