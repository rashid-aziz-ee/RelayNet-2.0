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

    fun selectRecipient(peerId: String, peerName: String) {
        _selectedRecipientId.value = peerId
        _selectedRecipientName.value = peerName
    }

    fun clearRecipient() {
        _selectedRecipientId.value = ""
        _selectedRecipientName.value = ""
    }

    // Networking person will hook into this lambda in MainActivity
    var sendLambda: ((String) -> Unit)? = null
    var sendLambdaWithId: ((text: String, messageId: String) -> Unit)? = null
    var refreshLambda: (() -> Unit)? = null

    fun refreshMesh() {
        refreshLambda?.invoke()
    }

    override fun onMessageReceived(envelope: MessageEnvelope) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val existing = messageDao.getMessageById(envelope.messageId)
            if (existing != null) {
                // If existing message was stored as ciphertext, update it with decrypted plaintext!
                if (existing.payload.startsWith("ENC:CTR:") && !envelope.payload.startsWith("ENC:CTR:")) {
                    messageDao.insert(
                        existing.copy(
                            payload = envelope.payload,
                            recipientId = myDeviceId,
                            deliveryStatus = "DELIVERED"
                        )
                    )
                }
                return@launch
            }

            val isMine = envelope.senderId == myDeviceId
            val isBroadcast = envelope.payload.startsWith("🆘")

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
                recipientId = if (isBroadcast) "" else myDeviceId,
                recipientName = if (isBroadcast) "" else myName
            )
            messageDao.insert(message)

            // Update peer display name if they are in the active peer set
            if (_connectedPeers.value.containsKey(envelope.senderId)) {
                _connectedPeers.update { currentMap ->
                    currentMap.toMutableMap().apply {
                        put(envelope.senderId, envelope.senderName)
                    }
                }
            }
        }
    }

    override fun onSendMessage(text: String) {
        val messageId = UUID.randomUUID().toString()
        val isBroadcast = text.startsWith("🆘")
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