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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.tharos.smsblocker.ui.theme.SMSBlockerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit

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
    val isMe: Boolean,
    val imageUri: Uri? = null
)

data class MmsData(val text: String, val imageUri: Uri?)

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
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Register a ContentObserver to listen for SMS database changes
    val observer = remember {
        object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                Log.d("SMSBlocker", "SMS Database changed, triggering refresh")
                refreshTrigger++
            }
        }
    }

    DisposableEffect(Unit) {
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer
        )
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    // Refresh threads whenever we are on the threads screen OR the database changes
    LaunchedEffect(hasRequiredPermissions, currentScreen, refreshTrigger) {
        if (hasRequiredPermissions && currentScreen == "threads") {
            if (threads.isEmpty()) isLoading = true
            
            // Phase 1: Quick fetch of threads and addresses
            val baseThreads = fetchBaseThreads(context)
            threads = baseThreads
            isLoading = false
            
            // Phase 2: Resolve contact names in background
            if (baseThreads.isNotEmpty()) {
                threads = resolveThreadNames(context, baseThreads)
            }
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
                    onNewChat = { currentScreen = "new_chat" },
                    onDeleteThread = { threadId ->
                        scope.launch {
                            deleteThread(context, threadId)
                            threads = threads.filter { it.threadId != threadId }
                        }
                    }
                )
                "chat" -> ChatScreen(
                    threadId = selectedThreadId!!,
                    contactName = selectedContactName ?: "Unknown",
                    address = selectedAddress!!,
                    refreshTrigger = refreshTrigger,
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
                    refreshTrigger = refreshTrigger,
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
    onNewChat: () -> Unit,
    onDeleteThread: (String) -> Unit
) {
    var threadToDelete by remember { mutableStateOf<MessageThread?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val filteredThreads = remember(threads, searchQuery) {
        if (searchQuery.isBlank()) {
            threads
        } else {
            threads.filter { thread ->
                (thread.contactName?.contains(searchQuery, ignoreCase = true) ?: false) ||
                thread.address.contains(searchQuery, ignoreCase = true) ||
                thread.snippet.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (!isSearchActive) {
                FloatingActionButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = "New Message")
                }
            }
        }
    ) { p ->
        Column(modifier = Modifier.fillMaxSize().padding(p)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onSearch = { isSearchActive = false },
                            expanded = isSearchActive,
                            onExpandedChange = { isSearchActive = it },
                            placeholder = { Text("Search messages...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (isSearchActive || searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = ""
                                        } else {
                                            isSearchActive = false
                                        }
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                                    }
                                } else {
                                    IconButton(onClick = onSettingsClick) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                            }
                        )
                    },
                    expanded = isSearchActive,
                    onExpandedChange = { isSearchActive = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredThreads, key = { it.threadId }) { thread ->
                            ThreadItem(
                                thread,
                                onClick = {
                                    isSearchActive = false
                                    onThreadClick(thread)
                                },
                                onDelete = { threadToDelete = thread }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            if (!isSearchActive) {
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
                } else if (filteredThreads.isEmpty() && searchQuery.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results for '$searchQuery'", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn {
                        items(filteredThreads, key = { it.threadId }) { thread ->
                            ThreadItem(
                                thread, 
                                onClick = { onThreadClick(thread) },
                                onDelete = { threadToDelete = thread }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }

    if (threadToDelete != null) {
        AlertDialog(
            onDismissRequest = { threadToDelete = null },
            title = { Text("Delete Conversation?") },
            text = { Text("Are you sure you want to delete the conversation with ${threadToDelete?.contactName ?: threadToDelete?.address}?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteThread(threadToDelete!!.threadId)
                    threadToDelete = null
                }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { threadToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ThreadItem(thread: MessageThread, onClick: () -> Unit, onDelete: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.contactName ?: thread.address,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (!thread.read) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = timeFormat.format(Date(thread.date)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                text = thread.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (!thread.read) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete, 
                contentDescription = "Delete Thread",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
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
fun ChatByAddressScreen(address: String, refreshTrigger: Int = 0, onBack: () -> Unit) {
    val context = LocalContext.current
    var threadId by remember { mutableStateOf<String?>(null) }
    var contactName by remember { mutableStateOf(address) }

    LaunchedEffect(address) {
        threadId = fetchThreadIdByAddress(context, address)
        contactName = fetchContactName(context.contentResolver, address) ?: address
    }

    if (threadId != null) {
        ChatScreen(threadId = threadId!!, contactName = contactName, address = address, refreshTrigger = refreshTrigger, onBack = onBack)
    } else {
        ChatScreen(threadId = "-1", contactName = contactName, address = address, refreshTrigger = refreshTrigger, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(threadId: String, contactName: String, address: String, refreshTrigger: Int = 0, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var textValue by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var currentThreadId by remember { mutableStateOf(threadId) }

    LaunchedEffect(currentThreadId, refreshTrigger) {
        if (currentThreadId != "-1") {
            if (refreshTrigger > 0) {
                markThreadAsRead(context, currentThreadId)
            }
            messages = fetchMessagesForThread(context, currentThreadId)
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
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
    val color = if (message.isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (message.isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    
    val bubbleShape = if (message.isMe) {
        RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    }

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
        Column(horizontalAlignment = if (message.isMe) Alignment.End else Alignment.Start) {
            Surface(
                color = color,
                shape = bubbleShape,
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (message.imageUri != null) {
                        AsyncImage(
                            model = message.imageUri,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .sizeIn(maxWidth = 240.dp, maxHeight = 320.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (message.body.isNotBlank()) {
                        Text(
                            text = message.body,
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        text = timeFormat.format(Date(message.date)),
                        color = textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SmsBlockerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageName = context.packageName
    val prefs = remember { context.getSharedPreferences("blocker_prefs", Context.MODE_PRIVATE) }
    
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

    val keywords = remember { 
        val saved = prefs.getStringSet("keywords", setOf("Stop2End")) ?: setOf("Stop2End")
        mutableStateListOf<String>().apply { addAll(saved) }
    }
    var newKeyword by remember { mutableStateOf("") }

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

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
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
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
            
            Text("Blocking Keywords", style = MaterialTheme.typography.titleLarge)
            Text("Messages containing these words will be blocked", style = MaterialTheme.typography.bodySmall)
            
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    label = { Text("Add keyword") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newKeyword.isNotBlank() && !keywords.contains(newKeyword)) {
                            keywords.add(newKeyword)
                            prefs.edit { putStringSet("keywords", keywords.toSet()) }
                            newKeyword = ""
                        }
                    },
                    enabled = newKeyword.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                keywords.forEach { keyword ->
                    AssistChip(
                        onClick = { },
                        label = { Text(keyword) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.clickable {
                                    keywords.remove(keyword)
                                    prefs.edit { putStringSet("keywords", keywords.toSet()) }
                                }.padding(4.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Messages")
            }
        }
    }
}

@Composable
fun StatusRow(label: String, status: Boolean) {
    Text("$label: ${if (status) "✅" else "❌"}")
}

private suspend fun fetchBaseThreads(context: Context): List<MessageThread> = withContext(Dispatchers.IO) {
    val contentResolver: ContentResolver = context.contentResolver
    val uri = "content://mms-sms/conversations?simple=true".toUri()
    val projection = arrayOf("_id", "snippet", "date", "read", "recipient_ids")
    val sortOrder = "date DESC LIMIT 50"

    val threads = mutableListOf<MessageThread>()
    val recipientIdSet = mutableSetOf<String>()

    try {
        contentResolver.query(uri, projection, null, null, sortOrder)?.use { c ->
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
                
                val firstId = recipientIds.split(" ").firstOrNull() ?: ""
                if (firstId.isNotBlank()) recipientIdSet.add(firstId)
                
                // Store recipientId in address field temporarily for resolution
                threads.add(MessageThread(threadId, firstId, null, snippet, date, read))
            }
        }
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Failed to query conversations", e)
    }

    // Bulk fetch ONLY the required canonical addresses
    if (recipientIdSet.isNotEmpty()) {
        val addrMap = mutableMapOf<String, String>()
        val selection = "_id IN (${recipientIdSet.joinToString(",")})"
        try {
            contentResolver.query("content://mms-sms/canonical-addresses".toUri(), arrayOf("_id", "address"), selection, null, null)?.use { c ->
                val idIdx = c.getColumnIndex("_id")
                val addrIdx = c.getColumnIndex("address")
                while (c.moveToNext()) {
                    addrMap[c.getString(idIdx)] = c.getString(addrIdx)
                }
            }
        } catch (e: Exception) {
            Log.e("SMSBlocker", "Error fetching canonical addresses", e)
        }
        
        return@withContext threads.map { it.copy(address = addrMap[it.address] ?: "Unknown") }
    }

    threads
}

private suspend fun resolveThreadNames(context: Context, threads: List<MessageThread>): List<MessageThread> = withContext(Dispatchers.IO) {
    val contentResolver = context.contentResolver
    val uniqueAddresses = threads.map { it.address }.filter { it != "Unknown" }.distinct()
    if (uniqueAddresses.isEmpty()) return@withContext threads

    // Optimized: Fetch all relevant contacts in one pass instead of 50 separate queries
    val contactMap = mutableMapOf<String, String>()
    val contactUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
    
    try {
        contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "") ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                contactMap[number] = name
            }
        }
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Error fetching contacts bulk", e)
    }

    threads.map { thread ->
        val normalized = thread.address.replace(Regex("[^0-9+]"), "")
        val name = contactMap[normalized] ?: fetchContactName(contentResolver, thread.address)
        thread.copy(contactName = name)
    }
}

private suspend fun fetchMessagesForThread(context: Context, threadId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
    val messages = mutableListOf<ChatMessage>()
    val contentResolver = context.contentResolver

    // 1. Fetch SMS
    val smsUri = Telephony.Sms.CONTENT_URI
    val smsProjection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
    val smsSelection = "${Telephony.Sms.THREAD_ID} = ?"
    val selectionArgs = arrayOf(threadId)
    
    contentResolver.query(smsUri, smsProjection, smsSelection, selectionArgs, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        
        while (cursor.moveToNext()) {
            messages.add(ChatMessage(
                id = "sms_${cursor.getString(idIdx)}",
                body = cursor.getString(bodyIdx) ?: "",
                date = cursor.getLong(dateIdx),
                isMe = cursor.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT
            ))
        }
    }

    // 2. Fetch MMS
    val mmsUri = Telephony.Mms.CONTENT_URI
    val mmsProjection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
    val mmsSelection = "${Telephony.Mms.THREAD_ID} = ?"
    
    contentResolver.query(mmsUri, mmsProjection, mmsSelection, selectionArgs, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
        val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
        val boxIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
        
        while (cursor.moveToNext()) {
            val mmsId = cursor.getString(idIdx)
            // MMS date is in seconds, SMS is in milliseconds
            val date = cursor.getLong(dateIdx) * 1000 
            val isMe = cursor.getInt(boxIdx) == Telephony.Mms.MESSAGE_BOX_SENT
            
            val mmsData = fetchMmsData(contentResolver, mmsId)
            if (mmsData.text.isNotBlank() || mmsData.imageUri != null) {
                messages.add(ChatMessage(
                    id = "mms_$mmsId",
                    body = mmsData.text,
                    date = date,
                    isMe = isMe,
                    imageUri = mmsData.imageUri
                ))
            }
        }
    }

    messages.sortedBy { it.date }
}

private fun fetchMmsData(contentResolver: ContentResolver, mmsId: String): MmsData {
    val selection = "mid = ?"
    val selectionArgs = arrayOf(mmsId)
    val uri = "content://mms/part".toUri()
    var text = ""
    var imageUri: Uri? = null
    
    try {
        contentResolver.query(uri, null, selection, selectionArgs, null)?.use { cursor ->
            val ctIdx = cursor.getColumnIndex("ct")
            val textIdx = cursor.getColumnIndex("text")
            val idIdx = cursor.getColumnIndex("_id")
            
            while (cursor.moveToNext()) {
                val ct = cursor.getString(ctIdx)
                if (ct == "text/plain") {
                    text += (cursor.getString(textIdx) ?: "")
                } else if (ct != null && ct.startsWith("image/")) {
                    val partId = cursor.getString(idIdx)
                    imageUri = "content://mms/part/$partId".toUri()
                }
            }
        }
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Error fetching MMS data", e)
    }
    return MmsData(text, imageUri)
}

private suspend fun markThreadAsRead(context: Context, threadId: String) = withContext(Dispatchers.IO) {
    val isDefault = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    if (!isDefault) {
        Log.w("SMSBlocker", "Not default SMS app: markThreadAsRead will likely be ignored by the system.")
    }

    try {
        val values = android.content.ContentValues().apply {
            put("read", 1)
            put("seen", 1)
        }
        
        val contentResolver = context.contentResolver
        val selection = "thread_id = ?"
        val selectionArgs = arrayOf(threadId)

        // 1. Mark SMS as read and seen
        contentResolver.update(Telephony.Sms.CONTENT_URI, values, selection, selectionArgs)
        
        // 2. Mark MMS as read and seen
        val mmsUri = "content://mms/".toUri()
        contentResolver.update(mmsUri, values, selection, selectionArgs)
        
        // 3. Update the conversation thread itself
        val threadUri = "content://mms-sms/conversations/$threadId".toUri()
        contentResolver.update(threadUri, values, null, null)
        
        Log.d("SMSBlocker", "Thread $threadId marked as read (Success: $isDefault)")
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Failed to mark thread as read", e)
    }
}

private suspend fun deleteThread(context: Context, threadId: String) = withContext(Dispatchers.IO) {
    try {
        val uri = "content://mms-sms/conversations/$threadId".toUri()
        context.contentResolver.delete(uri, null, null)
        Log.d("SMSBlocker", "Thread $threadId deleted")
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Failed to delete thread", e)
    }
}

private suspend fun fetchThreadIdByAddress(context: Context, address: String): String? = withContext(Dispatchers.IO) {
    try {
        val uri = "content://mms-sms/threadID".toUri()
        val projection = arrayOf("_id")
        val selection = "address = ?"
        val selectionArgs = arrayOf(address)
        
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return@withContext cursor.getString(0)
            }
        }
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Error getting thread ID by address", e)
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
