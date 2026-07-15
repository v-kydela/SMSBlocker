package com.tharos.smsblocker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.tharos.smsblocker.ui.theme.SMSBlockerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit
import kotlinx.parcelize.Parcelize
import kotlin.math.abs

@Parcelize
data class MessageThread(
    val threadId: String,
    val address: String,
    val contactName: String?,
    val snippet: String,
    val date: Long,
    val read: Boolean,
    val isSpam: Boolean = false
) : Parcelable

@Parcelize
data class ChatMessage(
    val id: String,
    val body: String,
    val date: Long,
    val isMe: Boolean,
    val imageUri: Uri? = null,
    val reactions: List<String> = emptyList()
) : Parcelable

@Parcelize
data class MmsData(val text: String, val imageUri: Uri?) : Parcelable

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
    var currentScreen by rememberSaveable { mutableStateOf("threads") }
    var selectedThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedContactName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasRequiredPermissions = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    // Cache threads at the navigation level to avoid re-loading when returning from chat
    var threads by remember { mutableStateOf(loadThreadsFromCache(context)) }
    var isLoading by rememberSaveable { mutableStateOf(hasRequiredPermissions && threads.isEmpty()) }
    var refreshTrigger by rememberSaveable { mutableIntStateOf(0) }

    val contactPrefs = remember { context.getSharedPreferences("contact_names", Context.MODE_PRIVATE) }
    var contactCache by remember { 
        mutableStateOf(contactPrefs.all.mapValues { it.value.toString() }) 
    }

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
            
            // Phase 1: Fast Fetch (Basic thread info + Addresses only)
            // This returns almost instantly.
            val baseThreads = fetchThreadsFast(context, contactCache)
            threads = baseThreads
            isLoading = false
            
            // Phase 2: Background Deep Sync (Snippets + Contact Names)
            // This runs while the user is already looking at the list.
            scope.launch {
                val updatedWithSnippets = resolveMissingSnippets(context, baseThreads)
                threads = updatedWithSnippets
                
                val finalThreads = resolveThreadDetails(context, updatedWithSnippets)
                threads = finalThreads
                
                // Save to cache for next app launch
                saveThreadsToCache(context, finalThreads)
                contactCache = contactPrefs.all.mapValues { it.value.toString() }
            }
        }
    }

    BackHandler(enabled = currentScreen != "threads" || selectedImageUri != null) {
        if (selectedImageUri != null) {
            selectedImageUri = null
        } else {
            currentScreen = "threads"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentScreen) {
                    "threads" -> ConversationListScreen(
                        threads = threads.filter { !it.isSpam },
                        isLoading = isLoading,
                        hasPermissions = hasRequiredPermissions,
                        onSettingsClick = { currentScreen = "settings" },
                        onSpamClick = { currentScreen = "spam" },
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
                    "spam" -> ConversationListScreen(
                        threads = threads.filter { it.isSpam },
                        isLoading = isLoading,
                        hasPermissions = hasRequiredPermissions,
                        isSpamView = true,
                        onSettingsClick = { currentScreen = "settings" },
                        onSpamClick = { }, // already there
                        onBack = { currentScreen = "threads" },
                        onThreadClick = { thread -> 
                            selectedThreadId = thread.threadId
                            selectedContactName = thread.contactName ?: thread.address
                            selectedAddress = thread.address
                            currentScreen = "chat"
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
                        onBack = { currentScreen = "threads" },
                        onImageClick = { selectedImageUri = it }
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
                        onBack = { currentScreen = "threads" },
                        onImageClick = { selectedImageUri = it }
                    )
                    "settings" -> SmsBlockerSettingsScreen(
                        onBack = { currentScreen = "threads" }
                    )
                }
            }
        }

        selectedImageUri?.let { uri ->
            FullScreenImage(uri = uri, onDismiss = { selectedImageUri = null })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    threads: List<MessageThread>,
    isLoading: Boolean,
    hasPermissions: Boolean,
    isSpamView: Boolean = false,
    onSettingsClick: () -> Unit,
    onSpamClick: () -> Unit = {},
    onBack: () -> Unit = {},
    onThreadClick: (MessageThread) -> Unit,
    onNewChat: () -> Unit,
    onDeleteThread: (String) -> Unit
) {
    var threadToDelete by rememberSaveable { mutableStateOf<MessageThread?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }

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
        topBar = {
            if (isSpamView) {
                TopAppBar(
                    title = { Text("Spam & Blocked") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSearchActive && !isSpamView) {
                FloatingActionButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = "New Message")
                }
            }
        }
    ) { p ->
        Column(modifier = Modifier.fillMaxSize().padding(p)) {
            if (!isSpamView) {
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
                                        Row {
                                            IconButton(onClick = onSpamClick) {
                                                Icon(Icons.Default.Delete, contentDescription = "Spam & Blocked", tint = MaterialTheme.colorScheme.outline)
                                            }
                                            IconButton(onClick = onSettingsClick) {
                                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                                            }
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
    var phoneNumber by rememberSaveable { mutableStateOf("") }

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
fun ChatByAddressScreen(address: String, refreshTrigger: Int = 0, onBack: () -> Unit, onImageClick: (Uri) -> Unit) {
    val context = LocalContext.current
    var threadId by rememberSaveable { mutableStateOf<String?>(null) }
    var contactName by rememberSaveable { mutableStateOf(address) }

    LaunchedEffect(address) {
        threadId = fetchThreadIdByAddress(context, address)
        contactName = fetchContactName(context.contentResolver, address) ?: address
    }

    if (threadId != null) {
        ChatScreen(threadId = threadId!!, contactName = contactName, address = address, refreshTrigger = refreshTrigger, onBack = onBack, onImageClick = onImageClick)
    } else {
        ChatScreen(threadId = "-1", contactName = contactName, address = address, refreshTrigger = refreshTrigger, onBack = onBack, onImageClick = onImageClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(threadId: String, contactName: String, address: String, refreshTrigger: Int = 0, onBack: () -> Unit, onImageClick: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by rememberSaveable { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var textValue by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var currentThreadId by rememberSaveable { mutableStateOf(threadId) }

    LaunchedEffect(currentThreadId, refreshTrigger) {
        if (currentThreadId != "-1") {
            if (refreshTrigger > 0) {
                markThreadAsRead(context, currentThreadId)
            }
            messages = fetchMessagesForThread(context, currentThreadId)
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
            state = listState,
            reverseLayout = true
        ) {
            items(messages.asReversed()) { message ->
                MessageBubble(message, onImageClick = onImageClick)
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
fun MessageBubble(message: ChatMessage, onImageClick: (Uri) -> Unit) {
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
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(message.imageUri) },
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
            
            if (message.reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    message.reactions.forEach { emoji ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = emoji,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context) == packageName
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val keywords = remember { 
        val saved = prefs.getStringSet("keywords", setOf("Stop2End")) ?: setOf("Stop2End")
        mutableStateListOf<String>().apply { addAll(saved) }
    }
    var newKeyword by rememberSaveable { mutableStateOf("") }

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
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Manage Permissions in Settings")
            }

            if (!isDefaultSmsApp) {
                Button(onClick = {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val roleManager = context.getSystemService(RoleManager::class.java)
                            if (roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true) {
                                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                                defaultAppLauncher.launch(intent)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                            }
                            defaultAppLauncher.launch(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("SMSBlocker", "Failed to request default SMS app", e)
                    }
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

private fun saveThreadsToCache(context: Context, threads: List<MessageThread>) {
    val prefs = context.getSharedPreferences("threads_cache", Context.MODE_PRIVATE)
    val serialized = threads.take(30).joinToString("||") { 
        "${it.threadId}|${it.address}|${it.contactName ?: ""}|${it.snippet.replace("\n", " ")}|${it.date}|${it.read}|${it.isSpam}" 
    }
    prefs.edit { putString("cached_list", serialized) }
}

private fun loadThreadsFromCache(context: Context): List<MessageThread> {
    val prefs = context.getSharedPreferences("threads_cache", Context.MODE_PRIVATE)
    val serialized = prefs.getString("cached_list", null) ?: return emptyList()
    return try {
        serialized.split("||").map {
            val parts = it.split("|")
            MessageThread(
                parts[0], 
                parts[1], 
                parts[2].ifBlank { null }, 
                parts[3], 
                parts[4].toLong(), 
                parts[5].toBoolean(),
                if (parts.size > 6) parts[6].toBoolean() else false
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun fetchThreadsFast(context: Context, contactCache: Map<String, String>): List<MessageThread> = withContext(Dispatchers.IO) {
    val contentResolver: ContentResolver = context.contentResolver
    val uri = "content://mms-sms/conversations?simple=true".toUri()
    // Include 'archived' and 'type'
    val projection = arrayOf("_id", "snippet", "date", "read", "recipient_ids", "type", "archived")
    val sortOrder = "date DESC LIMIT 100" // Fetch more to find spam

    val baseThreads = mutableListOf<MessageThread>()
    val recipientIdSet = mutableSetOf<String>()

    try {
        contentResolver.query(uri, projection, null, null, sortOrder)?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val snippetIdx = c.getColumnIndex("snippet")
            val dateIdx = c.getColumnIndex("date")
            val readIdx = c.getColumnIndex("read")
            val recipientIdsIdx = c.getColumnIndex("recipient_ids")
            val typeIdx = c.getColumnIndex("type")
            val archivedIdx = c.getColumnIndex("archived")
            
            while (c.moveToNext()) {
                val threadId = c.getString(idIdx) ?: continue
                val snippet = c.getString(snippetIdx) ?: ""
                val date = c.getLong(dateIdx)
                val read = c.getInt(readIdx) == 1
                val recipientIds = c.getString(recipientIdsIdx) ?: ""
                val type = if (typeIdx != -1) c.getInt(typeIdx) else 0
                val archived = if (archivedIdx != -1) c.getInt(archivedIdx) == 1 else false
                
                val firstId = recipientIds.split(" ").firstOrNull() ?: ""
                if (firstId.isNotBlank()) recipientIdSet.add(firstId)
                
                baseThreads.add(MessageThread(threadId, firstId, null, snippet, date, read, isSpam = archived || type == 4))
            }
        }
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Failed to query conversations", e)
        return@withContext fetchThreadsFastLegacy(context, contactCache)
    }

    if (baseThreads.isEmpty()) return@withContext emptyList()

    val addrMap = mutableMapOf<String, String>()
    if (recipientIdSet.isNotEmpty()) {
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
            Log.e("SMSBlocker", "Error fetching addresses", e)
        }
    }

    return@withContext baseThreads.map { thread ->
        val addr = addrMap[thread.address] ?: "Unknown"
        val normalized = addr.replace(Regex("[^0-9+]"), "")
        thread.copy(
            address = addr,
            contactName = contactCache[normalized]
        )
    }
}

private suspend fun fetchThreadsFastLegacy(context: Context, contactCache: Map<String, String>): List<MessageThread> = withContext(Dispatchers.IO) {
    val contentResolver: ContentResolver = context.contentResolver
    val uri = "content://mms-sms/conversations?simple=true".toUri()
    val projection = arrayOf("_id", "snippet", "date", "read", "recipient_ids")
    val sortOrder = "date DESC LIMIT 30"

    val baseThreads = mutableListOf<MessageThread>()
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
                
                baseThreads.add(MessageThread(threadId, firstId, null, snippet, date, read))
            }
        }
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Failed to query conversations legacy", e)
    }

    if (baseThreads.isEmpty()) return@withContext emptyList()

    val addrMap = mutableMapOf<String, String>()
    if (recipientIdSet.isNotEmpty()) {
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
            Log.e("SMSBlocker", "Error fetching addresses", e)
        }
    }

    return@withContext baseThreads.map { thread ->
        val addr = addrMap[thread.address] ?: "Unknown"
        val normalized = addr.replace(Regex("[^0-9+]"), "")
        thread.copy(
            address = addr,
            contactName = contactCache[normalized]
        )
    }
}

private suspend fun resolveMissingSnippets(context: Context, threads: List<MessageThread>): List<MessageThread> = withContext(Dispatchers.IO) {
    val contentResolver = context.contentResolver
    return@withContext kotlinx.coroutines.coroutineScope {
        threads.map { thread ->
            async {
                if (thread.snippet.isBlank()) {
                    thread.copy(snippet = fetchFallbackSnippet(contentResolver, thread.threadId))
                } else thread
            }
        }.awaitAll()
    }
}

private fun fetchFallbackSnippet(contentResolver: ContentResolver, threadId: String): String {
    try {
        val smsCursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId),
            "date DESC LIMIT 1"
        )
        var smsBody = ""
        var smsDate = 0L
        smsCursor?.use {
            if (it.moveToFirst()) {
                smsBody = it.getString(0) ?: ""
                smsDate = it.getLong(1)
            }
        }

        val mmsCursor = contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId),
            "date DESC LIMIT 1"
        )
        var mmsId: String? = null
        var mmsDate = 0L
        mmsCursor?.use {
            if (it.moveToFirst()) {
                mmsId = it.getString(0)
                mmsDate = it.getLong(1) * 1000
            }
        }

        return if (mmsId != null && mmsDate > smsDate) {
            val data = fetchMmsData(contentResolver, mmsId)
            if (data.text.isNotBlank()) data.text 
            else if (data.imageUri != null) "Multimedia message" 
            else smsBody
        } else {
            smsBody
        }
    } catch (_: Exception) {
        return ""
    }
}

private suspend fun resolveThreadDetails(context: Context, threads: List<MessageThread>): List<MessageThread> = withContext(Dispatchers.IO) {
    val contentResolver = context.contentResolver
    val uniqueAddresses = threads.map { it.address }.filter { it != "Unknown" }.distinct()
    
    // 1. Bulk resolve contact names
    val contactMap = mutableMapOf<String, String>()
    if (uniqueAddresses.isNotEmpty()) {
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

        // Update persistent cache
        context.getSharedPreferences("contact_names", Context.MODE_PRIVATE).edit {
            contactMap.forEach { (num, name) -> putString(num, name) }
        }
    }

    // 2. Resolve blocked numbers (Only if we are the default app or have system access)
    val blockedNumbers = mutableSetOf<String>()
    if (Telephony.Sms.getDefaultSmsPackage(context) == context.packageName) {
        try {
            contentResolver.query(android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, arrayOf(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { cursor ->
                val numIdx = cursor.getColumnIndex(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
                while (cursor.moveToNext()) {
                    cursor.getString(numIdx)?.let { blockedNumbers.add(it.replace(Regex("[^0-9+]"), "")) }
                }
            }
        } catch (e: Exception) {
            Log.e("SMSBlocker", "Error fetching blocked numbers", e)
        }
    } else {
        Log.d("SMSBlocker", "Not default app: Skipping BlockedNumberContract query")
    }

    // 3. Resolve missing snippets and apply names/spam status
    threads.map { thread ->
        val normalized = thread.address.replace(Regex("[^0-9+]"), "")
        val name = contactMap[normalized] ?: fetchContactName(contentResolver, thread.address)
        val isBlocked = blockedNumbers.contains(normalized)
        thread.copy(contactName = name, isSpam = thread.isSpam || isBlocked)
    }
}

private suspend fun fetchMessagesForThread(context: Context, threadId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
    val contentResolver = context.contentResolver
    val selectionArgs = arrayOf(threadId)

    kotlinx.coroutines.coroutineScope {
        val smsDeferred = async {
            val smsMessages = mutableListOf<ChatMessage>()
            try {
                val smsUri = Telephony.Sms.CONTENT_URI
                val smsProjection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
                val smsSelection = "${Telephony.Sms.THREAD_ID} = ?"
                
                contentResolver.query(smsUri, smsProjection, smsSelection, selectionArgs, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                    val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                    val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)
                    
                    while (cursor.moveToNext()) {
                        if (idIdx != -1 && bodyIdx != -1 && dateIdx != -1 && typeIdx != -1) {
                            smsMessages.add(ChatMessage(
                                id = "sms_${cursor.getString(idIdx)}",
                                body = cursor.getString(bodyIdx) ?: "",
                                date = cursor.getLong(dateIdx),
                                isMe = cursor.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SMSBlocker", "Error fetching SMS for thread $threadId", e)
            }
            smsMessages
        }

        val mmsDeferred = async {
            val mmsMessages = mutableListOf<ChatMessage>()
            try {
                val mmsUri = Telephony.Mms.CONTENT_URI
                val mmsProjection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.SUBJECT)
                val mmsSelection = "thread_id = ?"
                
                contentResolver.query(mmsUri, mmsProjection, mmsSelection, selectionArgs, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(Telephony.Mms._ID)
                    val dateIdx = cursor.getColumnIndex(Telephony.Mms.DATE)
                    val boxIdx = cursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX)
                    val subIdx = cursor.getColumnIndex(Telephony.Mms.SUBJECT)
                    
                    while (cursor.moveToNext()) {
                        if (idIdx != -1 && dateIdx != -1 && boxIdx != -1) {
                            val mmsId = cursor.getString(idIdx)
                            val date = cursor.getLong(dateIdx) * 1000 
                            val isMe = cursor.getInt(boxIdx) == Telephony.Mms.MESSAGE_BOX_SENT
                            val subject = if (subIdx != -1) cursor.getString(subIdx) ?: "" else ""
                            
                            val mmsData = fetchMmsData(contentResolver, mmsId)
                            val bodyText = mmsData.text.ifBlank { subject }
                            
                            if (bodyText.isNotBlank() || mmsData.imageUri != null) {
                                mmsMessages.add(ChatMessage(
                                    id = "mms_$mmsId",
                                    body = bodyText,
                                    date = date,
                                    isMe = isMe,
                                    imageUri = mmsData.imageUri
                                ))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SMSBlocker", "Error fetching MMS for thread $threadId", e)
            }
            mmsMessages
        }

        val allMessages = smsDeferred.await() + mmsDeferred.await()
        processReactions(allMessages.sortedBy { it.date })
    }
}

private fun processReactions(messages: List<ChatMessage>): List<ChatMessage> {
    val reactionMap = mapOf(
        "Liked" to "👍", "Loved" to "❤️", "Disliked" to "👎",
        "Laughed at" to "😂", "Emphasized" to "‼️", "Questioned" to "❓"
    )
    
    val patterns = listOf(
        Regex("^(Liked|Loved|Disliked|Laughed at|Emphasized|Questioned) [“\"'](.*)[”\"']", RegexOption.IGNORE_CASE),
        Regex("^(\\S+) to [“\"'](.*)[”\"']", RegexOption.IGNORE_CASE),
        Regex("^Reacted (\\S+) to [“\"'](.*)[”\"']", RegexOption.IGNORE_CASE),
        Regex("^Removed (?:a |an )?(.+?) from [“\"'](.*)[”\"']", RegexOption.IGNORE_CASE)
    )
    
    val reactionMessages = mutableSetOf<String>()
    val resultMessages = messages.map { it.copy() }.toMutableList()
    
    fun normalize(text: String): String {
        val n = text.lowercase().replace(Regex("[^a-z0-9]"), "")
        return n.ifBlank { text.trim().lowercase() } // Don't return empty for emoji-only
    }

    for (i in messages.indices) {
        val msg = messages[i]
        val body = msg.body.trim()
        if (body.isBlank()) continue
        
        var emoji: String? = null
        var snippet: String? = null
        var isRemoval = false

        for (pattern in patterns) {
            val match = pattern.find(body)
            if (match != null) {
                val val1 = match.groupValues[1]
                val val2 = match.groupValues[2]
                
                if (pattern.pattern.contains("Removed")) {
                    isRemoval = true
                    emoji = reactionMap.entries.find { it.key.equals(val1, ignoreCase = true) }?.value ?: val1
                    snippet = val2
                } else if (pattern.pattern.startsWith("^(\\S+)")) { // emoji first style
                    emoji = val1
                    snippet = val2
                } else {
                    emoji = reactionMap.entries.find { it.key.equals(val1, ignoreCase = true) }?.value ?: val1
                    snippet = val2
                }
                break
            }
        }

        if (emoji != null && snippet != null && snippet.length >= 2) {
            val normalizedSnippet = normalize(snippet)
            var targetIndex = -1
            var minTimeDiff = Long.MAX_VALUE
            
            for (j in messages.indices) {
                if (i == j) continue
                val target = messages[j]
                val normalizedTarget = normalize(target.body)
                
                val bodyMatch = normalizedSnippet.isNotEmpty() && normalizedTarget.contains(normalizedSnippet)
                val imageMatch = snippet.lowercase().contains("image") && target.imageUri != null
                
                if (bodyMatch || imageMatch) {
                    val timeDiff = abs(msg.date - target.date)
                    if (timeDiff < minTimeDiff && timeDiff < 600000) { // Max 10 mins apart
                        minTimeDiff = timeDiff
                        targetIndex = j
                    }
                }
            }
            
            if (targetIndex != -1) {
                val target = resultMessages[targetIndex]
                val currentReactions = target.reactions.toMutableList()
                if (isRemoval) {
                    currentReactions.remove(emoji)
                } else if (!currentReactions.contains(emoji)) {
                    currentReactions.add(emoji)
                }
                resultMessages[targetIndex] = target.copy(reactions = currentReactions)
                reactionMessages.add(msg.id)
            }
        }
    }
    
    return resultMessages.filter { it.id !in reactionMessages }
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
                val ct = if (ctIdx != -1) cursor.getString(ctIdx) else null
                if (ct == "text/plain") {
                    if (textIdx != -1) text += (cursor.getString(textIdx) ?: "")
                } else if (ct != null && ct.startsWith("image/")) {
                    if (idIdx != -1) {
                        val partId = cursor.getString(idIdx)
                        imageUri = "content://mms/part/$partId".toUri()
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("SMSBlocker", "Error fetching MMS data for part $mmsId", e)
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

@Composable
fun FullScreenImage(uri: Uri, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.9f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
                .clickable(
                    enabled = scale == 1f,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = uri,
                contentDescription = "Full Screen Image",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
