package com.example.relaynet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relaynet.MessageEnvelope
import com.example.relaynet.data.Message
import com.example.relaynet.data.MessageDao
import com.example.relaynet.data.CryptoEngine
import com.example.relaynet.mesh.MeshBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PrivateMessageNotice(
    val senderId: String,
    val senderName: String,
    val previewText: String,
    val timestamp: Long = System.currentTimeMillis()
)

class RelayViewModel(
    private val messageDao: MessageDao,
    val myDeviceId: String,
    val myName: String,
    private val cryptoEngine: CryptoEngine? = null
) : ViewModel(), MeshBridge {

    val messages: StateFlow<List<Message>> = messageDao.getAllMessages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _connectedPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectedPeers: StateFlow<Map<String, String>> = _connectedPeers.asStateFlow()

    // Targeted / Private directed recipient states
    private val _selectedRecipientId = MutableStateFlow("")
    val selectedRecipientId = _selectedRecipientId.asStateFlow()

    private val _selectedRecipientName = MutableStateFlow("")
    val selectedRecipientName = _selectedRecipientName.asStateFlow()

    // NEW: Unread private E2EE senders indicator state (shows glowing green badge on Peer chips)
    private val _unreadPrivateSenders = MutableStateFlow<Set<String>>(emptySet())
    val unreadPrivateSenders: StateFlow<Set<String>> = _unreadPrivateSenders.asStateFlow()

    private val _latestPrivateNotice = MutableStateFlow<PrivateMessageNotice?>(null)
    val latestPrivateNotice: StateFlow<PrivateMessageNotice?> = _latestPrivateNotice.asStateFlow()

    fun clearPrivateNotice() {
        _latestPrivateNotice.value = null
    }

    fun selectRecipient(peerId: String, peerName: String) {
        _selectedRecipientId.value = peerId
        _selectedRecipientName.value = peerName
        // Auto-clear unread badge on tap
        _unreadPrivateSenders.update { it - peerId }
    }

    fun clearRecipient() {
        _selectedRecipientId.value = ""
        _selectedRecipientName.value = ""
    }

    fun markPeerAsRead(peerId: String) {
        _unreadPrivateSenders.update { it - peerId }
    }

    // NEW: Benchmark Telemetry Manager hook
    var benchmarkManager: com.example.relaynet.util.BenchmarkManager? = null

    fun startBenchmark(totalPackets: Int = 30, intervalMs: Long = 500L, targetPeerId: String = "") {
        benchmarkManager?.startBenchmark(
            com.example.relaynet.util.BenchmarkConfig(
                totalPackets = totalPackets,
                intervalMs = intervalMs,
                targetPeerId = targetPeerId
            )
        )
    }

    fun cancelBenchmark() {
        benchmarkManager?.cancelBenchmark()
    }

    // Networking person will hook into this lambda in MainActivity
    var sendLambda: ((String) -> Unit)? = null
    var sendLambdaWithId: ((text: String, messageId: String) -> Unit)? = null
    var refreshLambda: (() -> Unit)? = null

    fun refreshMesh() {
        refreshLambda?.invoke()
    }

    // NEW: Walkie-Talkie Push-to-Talk voice note transmission (colon delimiter)
    fun sendVoiceNote(durationSeconds: Int, base64Audio: String) {
        val payload = "🎤 VOICE:$durationSeconds:$base64Audio"
        onSendMessage(payload)
    }

    override fun onMessageReceived(envelope: MessageEnvelope) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val isMine = envelope.senderId == myDeviceId
            // A message is a broadcast/group message if recipientId is empty/blank/null OR if it starts with SOS
            val isBroadcast = envelope.recipientId.isBlank() || envelope.recipientId == "null" || envelope.payload.startsWith("🆘")

            // A message is ONLY private if it was explicitly directed to a non-empty recipient,
            // targeted to myDeviceId, and marked as E2EE
            val isPrivateDirectedToMe = !isMine && !isBroadcast && envelope.recipientId.isNotBlank() && envelope.recipientId == myDeviceId && envelope.isE2EE

            // Trigger unread notification badge on Peer chip ONLY when a targeted private 1-on-1 message arrives
            // and the user is NOT actively viewing the 1-on-1 chat with that sender
            if (isPrivateDirectedToMe && envelope.senderId.isNotBlank() && _selectedRecipientId.value != envelope.senderId) {
                _unreadPrivateSenders.update { it + envelope.senderId }
            }

            // Update peer display name if known
            if (envelope.senderId.isNotEmpty() && envelope.senderName.isNotEmpty()) {
                _connectedPeers.update { currentMap ->
                    if (currentMap.containsKey(envelope.senderId) && currentMap[envelope.senderId] != envelope.senderName) {
                        currentMap.toMutableMap().apply {
                            put(envelope.senderId, envelope.senderName)
                        }
                    } else {
                        currentMap
                    }
                }
            }

            val existing = messageDao.getMessageById(envelope.messageId)
            if (existing != null) {
                // If existing message was stored as ciphertext, update it with decrypted plaintext!
                if (existing.payload.startsWith("ENC:CTR:") && !envelope.payload.startsWith("ENC:CTR:")) {
                    messageDao.insert(
                        existing.copy(
                            payload = envelope.payload,
                            recipientId = if (isBroadcast) "" else envelope.recipientId,
                            deliveryStatus = "DELIVERED"
                        )
                    )
                }
                return@launch
            }

            val hopCount = maxOf(0, 3 - envelope.ttl)

            val message = Message(
                messageId = envelope.messageId,
                senderId = envelope.senderId,
                senderName = envelope.senderName,
                originTimestamp = envelope.timestamp,
                payload = envelope.payload,
                hopCount = hopCount,
                ttl = envelope.ttl,
                isMine = isMine,
                isBroadcast = isBroadcast,
                deliveryStatus = if (isMine) "SENT" else "DELIVERED",
                recipientId = if (isBroadcast) "" else envelope.recipientId,
                recipientName = if (isBroadcast) "" else myName
            )
            messageDao.insert(message)
        }
    }

    override fun onSendMessage(text: String) {
        val messageId = UUID.randomUUID().toString()
        val isBroadcast = text.startsWith("🆘") || _selectedRecipientId.value.isBlank()
        val timestamp = System.currentTimeMillis()
        val recipientId = if (isBroadcast) "" else _selectedRecipientId.value
        val recipientName = if (isBroadcast) "" else _selectedRecipientName.value

        // 1. Store locally immediately as SENDING
        val message = Message(
            messageId = messageId,
            senderId = myDeviceId,
            senderName = myName,
            originTimestamp = timestamp,
            payload = text, // Plain text saved locally for the sender
            hopCount = 0,
            ttl = 3,
            isMine = true,
            isBroadcast = isBroadcast,
            deliveryStatus = "SENDING",
            recipientId = recipientId,
            recipientName = recipientName
        )

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            messageDao.insert(message)

            // 2. Broadcast via network
            try {
                if (sendLambdaWithId != null) {
                    sendLambdaWithId?.invoke(text, messageId)
                } else {
                    sendLambda?.invoke(text)
                }
                // 3. Mark as SENT once sent to broadcast lambda
                messageDao.insert(message.copy(deliveryStatus = "SENT"))
            } catch (e: Exception) {
                // If it fails, in offline mesh we assume SENT or at least processed for local broadcast
                messageDao.insert(message.copy(deliveryStatus = "SENT"))
            }
        }
    }

    fun onPeersMapChanged(peersMap: Map<String, String>) {
        _connectedPeers.value = peersMap
    }

    override fun onPeersChanged(peers: Set<String>) {
        _connectedPeers.update { currentMap ->
            val newMap = mutableMapOf<String, String>()
            for (peer in peers) {
                // Keep existing display name if we already resolved it, otherwise default to short ID
                newMap[peer] = currentMap[peer] ?: peer.take(6)
            }
            newMap
        }
    }

    // NEW: delete a single message (e.g. long-press a bubble in ChatScreen)
    fun deleteMessage(messageId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            messageDao.deleteById(messageId)
        }
    }

    // NEW: clear the entire local chat history (e.g. a "Clear Chat" button in the header)
    fun clearAllMessages() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            messageDao.deleteAll()
        }
    }
}

class RelayViewModelFactory(
    private val messageDao: MessageDao,
    private val myDeviceId: String,
    private val myName: String,
    private val context: android.content.Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RelayViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RelayViewModel(messageDao, myDeviceId, myName, CryptoEngine(context)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
