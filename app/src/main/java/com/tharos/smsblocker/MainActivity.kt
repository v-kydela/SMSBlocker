package com.tharos.smsblocker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

data class ChatMessage(
    val id: String,
    val body: String,
    val date: Long,
    val isMe: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel(this)
        setContent {
            SMSBlockerTheme {
                MainNavigation()
            }
        }
    }
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "SMS Messages"
        val descriptionText = "Notifications for new SMS messages"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("sms_channel", name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

@Composable
fun MainNavigation() {
    var currentScreen by remember { mutableStateOf("threads") }
    var selectedThreadId by remember { mutableStateOf<String?>(null) }
    var selectedContactName by remember { mutableStateOf<String?>(null) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasRequiredPermissions = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    // Cache threads at the navigation level to avoid re-loading when returning from chat
    var threads by remember { mutableStateOf<List<MessageThread>>(emptyList()) }
    var isLoading by remember { mutableStateOf(hasRequiredPermissions && threads.isEmpty()) }

    // Refresh threads whenever we are on the threads screen
    LaunchedEffect(hasRequiredPermissions, currentScreen) {
        if (hasRequiredPermissions && currentScreen == "threads") {
            if (threads.isEmpty()) isLoading = true
            threads = fetchThreads(context)
            isLoading = false
        }
    }

    BackHandler(enabled = currentScreen != "threads") {
        currentScreen = "threads"
    }

    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                "threads" -> ConversationListScreen(
                    threads = threads,
                    isLoading = isLoading,
                    hasPermissions = hasRequiredPermissions,
                    onSettingsClick = { currentScreen = "settings" },
                    onThreadClick = { thread -> 
                        selectedThreadId = thread.threadId
                        selectedContactName = thread.contactName ?: thread.address
                        selectedAddress = thread.address
                        currentScreen = "chat"
                        
                        // Mark as read in DB and update local state
                        if (!thread.read) {
                            scope.launch {
                                markThreadAsRead(context, thread.threadId)
                                threads = threads.map { 
                                    if (it.threadId == thread.threadId) it.copy(read = true) else it 
                                }
                            }
                        }
                    },
                    onNewChat = { currentScreen = "new_chat" }
                )
                "chat" -> ChatScreen(
                    threadId = selectedThreadId!!,
                    contactName = selectedContactName ?: "Unknown",
                    address = selectedAddress!!,
                    onBack = { currentScreen = "threads" }
                )
                "new_chat" -> NewChatScreen(
                    onBack = { currentScreen = "threads" },
                    onStartChat = { address ->
                        selectedAddress = address
                        selectedContactName = null
                        currentScreen = "chat_by_address"
                    }
                )
                "chat_by_address" -> ChatByAddressScreen(
                    address = selectedAddress!!,
                    onBack = { currentScreen = "threads" }
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
fun ConversationListScreen(
    threads: List<MessageThread>,
    isLoading: Boolean,
    hasPermissions: Boolean,
    onSettingsClick: () -> Unit, 
    onThreadClick: (MessageThread) -> Unit,
    onNewChat: () -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = "New Message")
            }
        }
    ) { p ->
        Column(modifier = Modifier.fillMaxSize().padding(p)) {
            CenterAlignedTopAppBar(
                title = { Text("Messages") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )

            if (!hasPermissions) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = onSettingsClick) {
                        Text("Grant Permissions in Settings")
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (threads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages found", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn {
                    items(threads) { thread ->
                        ThreadItem(thread, onClick = { onThreadClick(thread) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(onBack: () -> Unit, onStartChat: (String) -> Unit) {
    var phoneNumber by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("New Message") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            TextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Recipient number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { if (phoneNumber.isNotBlank()) onStartChat(phoneNumber) },
                modifier = Modifier.fillMaxWidth(),
                enabled = phoneNumber.isNotBlank()
            ) {
                Text("Start Chat")
            }
        }
    }
}

@Composable
fun ChatByAddressScreen(address: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var threadId by remember { mutableStateOf<String?>(null) }
    var contactName by remember { mutableStateOf(address) }

    LaunchedEffect(address) {
        threadId = fetchThreadIdByAddress(context, address)
        contactName = fetchContactName(context.contentResolver, address) ?: address
    }

    if (threadId != null) {
        ChatScreen(threadId = threadId!!, contactName = contactName, address = address, onBack = onBack)
    } else {
        ChatScreen(threadId = "-1", contactName = contactName, address = address, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(threadId: String, contactName: String, address: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var textValue by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var currentThreadId by remember { mutableStateOf(threadId) }

    LaunchedEffect(currentThreadId) {
        if (currentThreadId != "-1") {
            markThreadAsRead(context, currentThreadId)
            messages = fetchMessagesForThread(context, currentThreadId)
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(contactName) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            state = listState
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = textValue,
                onValueChange = { textValue = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Text message") },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (textValue.isNotBlank()) {
                        val body = textValue
                        textValue = ""
                        scope.launch {
                            sendMessage(context, address, body, if (currentThreadId == "-1") null else currentThreadId)
                            if (currentThreadId == "-1") {
                                currentThreadId = fetchThreadIdByAddress(context, address) ?: "-1"
                            }
                            messages = if (currentThreadId != "-1") {
                                fetchMessagesForThread(context, currentThreadId)
                            } else {
                                messages + ChatMessage("temp", body, System.currentTimeMillis(), true)
                            }
                        }
                    }
                },
                enabled = textValue.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isMe) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (message.isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (message.isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
        Surface(
            color = color,
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = message.body,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = textColor
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
                Manifest.permission.READ_CONTACTS,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 
                    Manifest.permission.POST_NOTIFICATIONS else Manifest.permission.RECEIVE_SMS
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

private suspend fun fetchThreads(context: Context): List<MessageThread> = coroutineScope {
    val threads = mutableListOf<MessageThread>()
    val contentResolver: ContentResolver = context.contentResolver
    
    // Using mms-sms/conversations is the standard way to get threads
    val uri = "content://mms-sms/conversations?simple=true".toUri()
    val projection = arrayOf(
        "_id",
        "snippet",
        "date",
        "read",
        "recipient_ids"
    )
    
    val sortOrder = "date DESC"
    
    withContext(Dispatchers.IO) {
        Log.d("SMSBlocker", "Fetching threads from $uri")
        val cursor = try {
            contentResolver.query(uri, projection, null, null, sortOrder)
        } catch (e: Exception) {
            Log.e("SMSBlocker", "Failed to query conversations", e)
            null
        }

        cursor?.use { c ->
            Log.d("SMSBlocker", "Cursor count: ${c.count}")
            val idIdx = c.getColumnIndex("_id")
            val snippetIdx = c.getColumnIndex("snippet")
            val dateIdx = c.getColumnIndex("date")
            val readIdx = c.getColumnIndex("read")
            val recipientIdsIdx = c.getColumnIndex("recipient_ids")
            
            while (c.moveToNext()) {
                val threadId = c.getString(idIdx) ?: continue
                val snippet = c.getString(snippetIdx) ?: ""
                val date = c.getLong(dateIdx)
                val read = c.getInt(readIdx) == 1
                val recipientIds = c.getString(recipientIdsIdx) ?: ""
                
                // We'll resolve the address from recipientIds later
                threads.add(MessageThread(threadId, recipientIds, null, snippet, date, read))
            }
        }
    }
    
    // Parallel resolution of addresses and contact names
    threads.map { thread ->
        async(Dispatchers.IO) {
            val address = fetchAddressForRecipientId(contentResolver, thread.address) ?: "Unknown"
            val name = fetchContactName(contentResolver, address)
            thread.copy(address = address, contactName = name)
        }
    }.awaitAll()
}

private fun fetchAddressForRecipientId(contentResolver: ContentResolver, recipientId: String): String? {
    if (recipientId.isBlank()) return null
    // recipientId can be multiple IDs separated by space, we just take the first one for now
    val firstId = recipientId.split(" ").firstOrNull() ?: return null
    
    val uri = "content://mms-sms/canonical-address/$firstId".toUri()
    return try {
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getString(0) // The address is in the first column
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun fetchMessagesForThread(context: Context, threadId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
    val messages = mutableListOf<ChatMessage>()
    val uri = Telephony.Sms.CONTENT_URI
    val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
    val selection = "${Telephony.Sms.THREAD_ID} = ?"
    val selectionArgs = arrayOf(threadId)
    
    context.contentResolver.query(uri, projection, selection, selectionArgs, "${Telephony.Sms.DATE} ASC")?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        
        while (cursor.moveToNext()) {
            val id = cursor.getString(idIdx)
            val body = cursor.getString(bodyIdx) ?: ""
            val date = cursor.getLong(dateIdx)
            val type = cursor.getInt(typeIdx)
            val isMe = type == Telephony.Sms.MESSAGE_TYPE_SENT
            messages.add(ChatMessage(id, body, date, isMe))
        }
    }
    messages
}

private suspend fun markThreadAsRead(context: Context, threadId: String) = withContext(Dispatchers.IO) {
    try {
        val values = android.content.ContentValues().apply {
            put("read", 1)
        }
        val uri = "content://sms/".toUri()
        context.contentResolver.update(uri, values, "thread_id = ? AND read = 0", arrayOf(threadId))
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Failed to mark thread as read", e)
    }
}

private suspend fun fetchThreadIdByAddress(context: Context, address: String): String? = withContext(Dispatchers.IO) {
    val uri = Telephony.Sms.CONTENT_URI
    val projection = arrayOf(Telephony.Sms.THREAD_ID)
    val selection = "${Telephony.Sms.ADDRESS} = ?"
    val selectionArgs = arrayOf(address)
    
    context.contentResolver.query(uri, projection, selection, selectionArgs, "${Telephony.Sms.DATE} DESC LIMIT 1")?.use { cursor ->
        if (cursor.moveToFirst()) {
            return@withContext cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
        }
    }
    null
}

private suspend fun sendMessage(context: Context, address: String, body: String, threadId: String?) = withContext(Dispatchers.IO) {
    try {
        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(address, null, body, null, null)
        
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            if (threadId != null && threadId != "-1") put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
        }
        context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
    } catch (e: Exception) {
        e.printStackTrace()
    }
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
