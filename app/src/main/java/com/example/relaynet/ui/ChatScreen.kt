package com.example.relaynet.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.relaynet.data.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: RelayViewModel) {
    val messages by viewModel.messages.collectAsState()
    val peers by viewModel.connectedPeers.collectAsState()
    val selectedRecipientId by viewModel.selectedRecipientId.collectAsState()
    val selectedRecipientName by viewModel.selectedRecipientName.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showPhoneDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    val userPrefs = remember { context.getSharedPreferences("relaynet_user_prefs", android.content.Context.MODE_PRIVATE) }
    var currentPhone by remember { mutableStateOf(userPrefs.getString("user_phone", "") ?: "") }
    var phoneInput by remember { mutableStateOf(currentPhone) }

    // Filter messages to show ONLY ours, broadcasts, or directed to us
    val visibleMessages = remember(messages, selectedRecipientId, selectedRecipientName) {
        messages.filter { message ->
            // Never show intermediate encrypted ciphertext blobs on screen
            if (message.payload.startsWith("ENC:CTR:") && !message.isMine) {
                return@filter false
            }

            if (selectedRecipientId.isEmpty()) {
                // Open mesh feed:
                // Show broadcasts, open chat messages, sent by me, or messages received by me
                message.isBroadcast ||
                message.recipientId.isEmpty() ||
                message.isMine ||
                message.recipientId == viewModel.myDeviceId
            } else {
                // 1-on-1 private E2EE chat with selected peer:
                // 1. Always show emergency SOS broadcasts
                // 2. Messages sent by me to this recipient (by ID or clean name)
                // 3. Messages received by me from this sender (by ID or clean name)
                val cleanSelectedName = selectedRecipientName.substringBefore(" (via").trim()
                message.isBroadcast ||
                (message.isMine && (
                    message.recipientId == selectedRecipientId ||
                    (cleanSelectedName.isNotEmpty() && (
                        message.recipientName.equals(cleanSelectedName, ignoreCase = true) ||
                        message.recipientName.equals(selectedRecipientName, ignoreCase = true)
                    ))
                )) ||
                (!message.isMine && (
                    message.senderId == selectedRecipientId ||
                    (cleanSelectedName.isNotEmpty() && (
                        message.senderName.equals(cleanSelectedName, ignoreCase = true) ||
                        message.senderName.equals(selectedRecipientName, ignoreCase = true)
                    ))
                ))
            }
        }
    }

    // Auto scroll to bottom when new messages arrive
    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(visibleMessages.size - 1)
        }
    }

    // Connectivity indicator pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "connectivityPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "RelayNet",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "//MESH",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                        }
                        Text(
                            text = if (peers.isNotEmpty()) "LINK ACTIVE" else "SCANNING...",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                color = if (peers.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            ),
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    // Identity / Phone Setup button
                    IconButton(
                        onClick = { showPhoneDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "My Emergency Phone ID",
                            tint = if (currentPhone.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Share App Offline (Sideload APK / ZIP)
                    IconButton(
                        onClick = { showShareDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share App Offline",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Clear Chat button - only shown when there's actually something to clear
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearAllMessages() },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("clear_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear Chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Manual Mesh Refresh button
                    var isRefreshing by remember { mutableStateOf(false) }
                    val rotationAngle by animateFloatAsState(
                        targetValue = if (isRefreshing) 360f else 0f,
                        animationSpec = tween(durationMillis = 600, easing = LinearEasing),
                        label = "refreshRotation"
                    )
                    IconButton(
                        onClick = {
                            isRefreshing = true
                            viewModel.refreshMesh()
                            android.widget.Toast.makeText(context, "Scanning & refreshing mesh connections...", android.widget.Toast.LENGTH_SHORT).show()
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(800)
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Mesh Connections",
                            tint = if (isRefreshing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer(rotationZ = rotationAngle)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Connected peers count badge (tap to refresh)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(
                            1.dp,
                            if (peers.isNotEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable {
                                isRefreshing = true
                                viewModel.refreshMesh()
                                android.widget.Toast.makeText(context, "Scanning & refreshing mesh connections...", android.widget.Toast.LENGTH_SHORT).show()
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(800)
                                    isRefreshing = false
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            // Pulsing Dot
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
                                if (peers.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .graphicsLayer(
                                                scaleX = pulseScale,
                                                scaleY = pulseScale,
                                                alpha = pulseAlpha
                                            )
                                            .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            color = if (peers.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            shape = CircleShape
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${peers.size} ${if (peers.size == 1) "PEER" else "PEERS"}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (peers.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(12.dp)
                ) {
                    // Directed recipient alert banner
                    if (selectedRecipientId.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "🔒", fontSize = 12.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "AES-CTR E2EE CHAT WITH: ${selectedRecipientName.uppercase()}",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.clearRecipient() },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Text(
                                        text = "✕",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else if (peers.isEmpty()) {
                        // Warning if no peers are connected - Warm Amber style
                        Surface(
                            color = Color(0xFF1E150B),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Text(
                                    text = "⚠️ NO PEERS DETECTED. Messages will be saved in local storage and relayed automatically upon node connection.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Red SOS button for Emergency Broadcast
                        Button(
                            onClick = {
                                viewModel.onSendMessage("🆘 SOS — EMERGENCY BROADCAST FROM ${viewModel.myName}!")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(16.dp),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 1.dp
                            ),
                            modifier = Modifier
                                .height(56.dp)
                                .padding(end = 8.dp)
                                .testTag("sos_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Emergency SOS",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SOS",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp
                                )
                            )
                        }

                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    text = "Type an offline message...",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        val isInputEmpty = inputText.trim().isEmpty()

                        IconButton(
                            onClick = {
                                if (!isInputEmpty) {
                                    viewModel.onSendMessage(inputText.trim())
                                    inputText = ""
                                }
                            },
                            enabled = !isInputEmpty,
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    color = if (!isInputEmpty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = if (!isInputEmpty) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Live Peers indicator horizontal row
            if (peers.isNotEmpty()) {
                PeersRow(
                    peers = peers,
                    selectedRecipientId = selectedRecipientId,
                    onPeerClick = { id, name ->
                        if (selectedRecipientId == id) {
                            viewModel.clearRecipient()
                        } else {
                            viewModel.selectRecipient(id, name)
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            // Message list or empty state
            if (visibleMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "📡",
                                    fontSize = 32.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No messages yet",
                            modifier = Modifier.testTag("empty_state"),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Your device is scanning for nearby nodes. Send an SOS or type a message to cache it for mesh relay.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("message_list"),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleMessages, key = { it.messageId }) { message ->
                        MessageBubble(
                            message = message,
                            myDeviceId = viewModel.myDeviceId,
                            // NEW: long-press a bubble to delete just that message
                            onLongPress = { viewModel.deleteMessage(message.messageId) }
                        )
                    }
                }
            }
        }
        if (showPhoneDialog) {
            AlertDialog(
                onDismissRequest = { showPhoneDialog = false },
                title = {
                    Text(
                        text = "Emergency Mesh Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Set your Mobile Number. When you send messages or SOS offline, nearby friends will see your saved name from their phonebook, preventing impersonation attacks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("Phone Number (e.g. 03001234567)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            userPrefs.edit().putString("user_phone", phoneInput.trim()).apply()
                            currentPhone = phoneInput.trim()
                            showPhoneDialog = false
                        }
                    ) {
                        Text("Save Identity")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPhoneDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showShareDialog) {
            AlertDialog(
                onDismissRequest = { showShareDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SHARE RELAYNET OFFLINE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Send RelayNet to nearby devices without internet. Choose transfer method:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Option 1: Fast Quick Share (Recommended)
                        Surface(
                            onClick = {
                                showShareDialog = false
                                com.example.relaynet.util.ApkShareUtil.shareAppFast(context)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "⚡", fontSize = 22.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Quick Share / Nearby",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "FAST",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                ),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Wi-Fi Direct (1-2 sec) • Direct 1-tap install",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Option 2: Compressed ZIP (.zip)
                        Surface(
                            onClick = {
                                showShareDialog = false
                                com.example.relaynet.util.ApkShareUtil.shareAppAsZip(context)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "📦", fontSize = 22.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Compressed ZIP (.zip)",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    Text(
                                        text = "Deflate compressed archive • For extraction & backup",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Option 3: Classic Bluetooth (.apk)
                        Surface(
                            onClick = {
                                showShareDialog = false
                                com.example.relaynet.util.ApkShareUtil.shareAppViaBluetooth(context)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "📶", fontSize = 22.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Classic Bluetooth (.apk)",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                    Text(
                                        text = "Standard Bluetooth OPP (150-250 KB/s - slower)",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showShareDialog = false }) {
                        Text("CLOSE", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    }
}

@Composable
fun PeersRow(
    peers: Map<String, String>,
    selectedRecipientId: String,
    onPeerClick: (String, String) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "peerDot")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "peerPulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp, horizontal = 12.dp)
            .testTag("peers_row")
    ) {
        Text(
            text = "CONNECTED MESH PEERS (TAP TO START DIRECT E2EE CHAT)",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.5.sp
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(peers.entries.toList(), key = { it.key }) { (endpointId, displayName) ->
                val isSelected = selectedRecipientId == endpointId
                val context = LocalContext.current
                val resolvedName = remember(displayName) {
                    com.example.relaynet.util.ContactHelper.resolveContactName(context, displayName)
                }

                Surface(
                    onClick = { onPeerClick(endpointId, resolvedName) },
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isSelected) 1f else pulseAlpha),
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = resolvedName,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "(${endpointId.take(4)})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            }
        }
    }
}

// NEW: onLongPress parameter added - triggers deletion of this single message.
// @OptIn(ExperimentalFoundationApi::class) is required for combinedClickable (onLongClick).
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: Message, myDeviceId: String, onLongPress: () -> Unit = {}) {
    val isMe = message.isMine

    val bubbleColor = if (message.isBroadcast) {
        MaterialTheme.colorScheme.tertiaryContainer // Alert red container
    } else if (isMe) {
        MaterialTheme.colorScheme.primaryContainer // Signal green container
    } else {
        MaterialTheme.colorScheme.surfaceVariant // Slate grey container
    }

    val bubbleBorder = if (message.isBroadcast) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary) // Alert red border
    } else if (isMe) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
    }

    val textColor = if (message.isBroadcast) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else if (isMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val alignment = if (isMe) Alignment.End else Alignment.Start
    val shape = if (isMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        // Sender Name (if not me)
        if (!isMe) {
            val context = LocalContext.current
            val resolvedSender = remember(message.senderName) {
                com.example.relaynet.util.ContactHelper.resolveContactName(context, message.senderName)
            }
            Text(
                text = resolvedSender.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp
                ),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
            )
        }

        // Message body - NEW: combinedClickable added so long-press triggers deletion.
        // onClick is a no-op (required by combinedClickable) so normal taps still behave
        // exactly as before; only a long-press does anything new.
        Surface(
            color = bubbleColor,
            shape = shape,
            border = bubbleBorder,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = { /* no-op: tapping a bubble does nothing, same as before */ },
                    onLongClick = onLongPress
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Emergency broadcast label
                if (message.isBroadcast && message.payload.startsWith("🆘")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Emergency",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "EMERGENCY BROADCAST",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.tertiary,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                } else if (!message.isBroadcast && message.recipientId.isNotEmpty()) {
                    // E2EE directed message label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(text = "🔒", fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isMe) {
                                val targetName = message.recipientName.ifEmpty { message.recipientId.take(4) }.substringBefore(" (via")
                                "DIRECT E2EE TO: ${targetName.uppercase()}"
                            } else {
                                "DIRECT E2EE PRIVATE MESSAGE"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }

                // Edge AI Triage Classification Badge
                val triage = remember(message.payload) { com.example.relaynet.ai.EmergencyTriageEngine.classifyEmergency(message.payload) }
                if (triage.category != "CHAT") {
                    Surface(
                        color = if (triage.urgencyLevel == "CRITICAL") MaterialTheme.colorScheme.error.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (triage.urgencyLevel == "CRITICAL") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "${triage.badgeIcon} ${triage.summary}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp,
                                color = if (triage.urgencyLevel == "CRITICAL") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                // Message payload
                Text(
                    text = message.payload,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = textColor,
                        lineHeight = 20.sp
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Metadata block (Hop count, Delivery Status, Time)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                    val timeString = remember(message.originTimestamp) {
                        timeFormat.format(Date(message.originTimestamp))
                    }

                    // Hop Count Indicator
                    Surface(
                        color = Color.Black.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        val hopText = if (isMe) {
                            "ORIGIN"
                        } else {
                            when (message.hopCount) {
                                0 -> "⚡ DIRECT LINK"
                                1 -> "🔄 1 RELAY HOP"
                                else -> "🌐 ${message.hopCount} MESH HOPS"
                            }
                        }
                        Text(
                            text = hopText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor.copy(alpha = 0.85f)
                            ),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            color = textColor.copy(alpha = 0.4f)
                        )
                    )

                    // Delivery Status
                    val statusColor = when (message.deliveryStatus) {
                        "SENDING" -> MaterialTheme.colorScheme.secondary // Amber
                        "SENT" -> MaterialTheme.colorScheme.primary // Signal Green
                        "RELAYED" -> MaterialTheme.colorScheme.primary // Signal Green
                        else -> textColor.copy(alpha = 0.8f)
                    }
                    Text(
                        text = message.deliveryStatus,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    )

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            color = textColor.copy(alpha = 0.4f)
                        )
                    )

                    // Time
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    )
                }
            }
        }
    }
}