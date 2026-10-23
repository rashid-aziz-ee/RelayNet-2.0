package com.example.relaynet

import android.content.Context
import android.util.Base64
import com.example.relaynet.data.CryptoEngine
import com.example.relaynet.data.Message
import com.example.relaynet.data.RelayNetDatabase
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class MeshNetworkManager(
    private val context: Context,
    private val myDeviceId: String,
    private val onMessageReceivedCallback: (MessageEnvelope) -> Unit
) {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.example.relaynet.MESH_SERVICE"
    private val STRATEGY = Strategy.P2P_CLUSTER

    val connectedPeers = mutableSetOf<String>()
    val peerDisplayNames = mutableMapOf<String, String>()
    val endpointToDeviceId = mutableMapOf<String, String>()
    val deviceIdToEndpoint = mutableMapOf<String, String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val pendingConnectingEndpoints = mutableSetOf<String>()

    // Multi-Hop Remote Peers (Discovered via Gossip Announcement through Intermediate Relays)
    val remotePeerNames = mutableMapOf<String, String>()
    val remotePeerNextHopEndpoint = mutableMapOf<String, String>()

    var onPeersMapChangedCallback: ((Map<String, String>) -> Unit)? = null

    fun getActivePeersMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // 1. Direct 1-hop physical neighbors
        for (endpoint in connectedPeers) {
            val devId = endpointToDeviceId[endpoint] ?: endpoint
            val name = peerDisplayNames[devId] ?: peerDisplayNames[endpoint] ?: devId
            result[devId] = name
        }
        // 2. Multi-hop remote peers reachable through relays (only if not already a direct peer)
        for ((remoteDevId, remoteName) in remotePeerNames) {
            if (!result.containsKey(remoteDevId) && remoteDevId != myDeviceId) {
                result[remoteDevId] = remoteName
            }
        }
        return result
    }

    fun getMyPhoneOrId(): String {
        val prefs = context.getSharedPreferences("relaynet_user_prefs", Context.MODE_PRIVATE)
        val savedPhone = prefs.getString("user_phone", "") ?: ""
        return if (savedPhone.isNotEmpty()) savedPhone else "Device_$myDeviceId"
    }

    private fun notifyPeersChanged() {
        val map = getActivePeersMap()
        onPeersMapChangedCallback?.invoke(map)
        onPeersChangedCallback?.invoke(map.keys.toSet())
    }

    private val cryptoEngine = CryptoEngine(context)
    private val messageDao = RelayNetDatabase.getDatabase(context).messageDao()

    // FIX: all Room database access must happen off the main thread. Nearby Connections
    // callbacks (onPayloadReceived, onConnectionResult, etc.) run on the main thread, so any
    // messageDao call made directly inside them was throwing IllegalStateException and being
    // silently swallowed by the surrounding try/catch - this was the real cause of messages
    // appearing to "vanish". This dedicated background scope is used for all DB + relay work.
    private val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isMeshStarted = false

    // --- Core Network Operations ---
    fun startMesh() {
        if (isMeshStarted) return
        isMeshStarted = true
        startAdvertising()
        startDiscovery()
    }

    fun stopMesh() {
        isMeshStarted = false
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedPeers.clear()
        peerDisplayNames.clear()
        endpointToDeviceId.clear()
        deviceIdToEndpoint.clear()
        pendingConnectingEndpoints.clear()
        remotePeerNames.clear()
        remotePeerNextHopEndpoint.clear()
        notifyPeersChanged()
    }

    // Call this from onResume() - refreshes discovery without dropping live peer connections or thrashing
    fun restartMesh() {
        if (!isMeshStarted) {
            startMesh()
        } else {
            // Soft-cycle discovery to clear any stale endpoint caches in Google Play Services
            meshScope.launch {
                try {
                    connectionsClient.stopDiscovery()
                    kotlinx.coroutines.delay(400)
                    if (isMeshStarted) {
                        startDiscovery()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Manual Full Refresh - Cleans stale ghost peers, pings live connections, re-announces mesh topology
    fun fullMeshRefresh() {
        meshScope.launch {
            try {
                android.util.Log.d("RelayNet", "fullMeshRefresh triggered")
                // 1. Clear remote multi-hop peers so stale/ghost relays are pruned
                remotePeerNames.clear()
                remotePeerNextHopEndpoint.clear()
                pendingConnectingEndpoints.clear()

                // 2. Active ping probe to test which direct physical links are actually alive
                val deadEndpoints = mutableListOf<String>()
                val testPingPayload = Payload.fromBytes("PING|$myDeviceId".toByteArray())
                for (endpoint in connectedPeers.toList()) {
                    try {
                        connectionsClient.sendPayload(endpoint, testPingPayload)
                    } catch (e: Exception) {
                        deadEndpoints.add(endpoint)
                    }
                }
                for (dead in deadEndpoints) {
                    connectedPeers.remove(dead)
                    val devId = endpointToDeviceId.remove(dead)
                    if (devId != null) {
                        deviceIdToEndpoint.remove(devId)
                        peerDisplayNames.remove(devId)
                    }
                    peerDisplayNames.remove(dead)
                }

                // 3. Cycle Google Nearby discovery & advertising cleanly with small delay
                connectionsClient.stopDiscovery()
                connectionsClient.stopAdvertising()
                kotlinx.coroutines.delay(400)
                isMeshStarted = true
                startAdvertising()
                startDiscovery()

                // 4. Re-exchange keys with all surviving active direct peers
                val myPubKey = cryptoEngine.getMyPublicKeyBase64()
                val handshakeMsg = "KEY_EXCHANGE|$myDeviceId|${getMyPhoneOrId()}|$myPubKey"
                for (endpoint in connectedPeers.toList()) {
                    sendToPeer(handshakeMsg, endpoint)
                }

                notifyPeersChanged()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        val advertiseName = "${getMyPhoneOrId()}|$myDeviceId"
        connectionsClient.startAdvertising(advertiseName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                android.util.Log.d("RelayNet", "Advertising started as $advertiseName")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("RelayNet", "Advertising failed: ${e.message}")
                // If failed due to transient state and not already advertising, retry shortly
                if (e.message?.contains("8001") != true) {
                    meshScope.launch {
                        kotlinx.coroutines.delay(1500)
                        if (isMeshStarted) startAdvertising()
                    }
                }
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                android.util.Log.d("RelayNet", "Discovery started")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("RelayNet", "Discovery failed: ${e.message}")
                // If failed due to transient state and not already discovering, retry shortly
                if (e.message?.contains("8002") != true) {
                    meshScope.launch {
                        kotlinx.coroutines.delay(1500)
                        if (isMeshStarted) startDiscovery()
                    }
                }
            }
    }

    // Broadcast or Direct Message sending
    fun broadcastNewMessage(text: String, myName: String) {
        broadcastNewMessage(text, myName, "", "")
    }

    fun broadcastNewMessage(
        text: String,
        myName: String,
        recipientId: String,
        recipientName: String,
        explicitMessageId: String? = null
    ) {
        // Encryption + payload building doesn't touch the DB directly here, but keep it off
        // the calling thread too (this can be called from UI click handlers) to stay safe.
        meshScope.launch {
            try {
                val messageId = explicitMessageId ?: UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                val isBroadcast = text.startsWith("🆘")

                val payloadToSend = if (recipientId.isEmpty()) {
                    // group/broadcast chat - no single peer to encrypt for
                    text
                } else {
                    cryptoEngine.encryptForPeer(text, recipientId)
                }

                seenMessageIds.add(messageId)

                // Local cache is handled by ViewModel, but we also ensure over the air delivery string format:
                // messageId|senderId|senderName|payload|timestamp|ttl|recipientId|recipientName
                val messageRawString = "$messageId|$myDeviceId|$myName|$payloadToSend|$timestamp|3|$recipientId|$recipientName"
                sendToAllPeers(messageRawString, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // FIX: this whole function now runs inside meshScope (background thread) instead of
    // directly on the Nearby callback's main thread, so every messageDao call inside it
    // (getAllMessageIds, getMessageById, existsById, insert) is now safe to run.
    private fun handleIncomingRawData(rawData: String, cameFromEndpoint: String) {
        meshScope.launch {
            try {
                val parts = rawData.split("|")
                if (parts.isEmpty()) return@launch

                val type = parts[0]

                // 1. Peer Key Exchange Handshake
                if (type == "KEY_EXCHANGE") {
                    if (parts.size < 4) return@launch
                    val peerId = parts[1]
                    val peerName = parts[2]
                    val peerPublicKey = parts[3]

                    connectedPeers.add(cameFromEndpoint)
                    endpointToDeviceId[cameFromEndpoint] = peerId
                    deviceIdToEndpoint[peerId] = cameFromEndpoint

                    cryptoEngine.savePeerPublicKey(peerId, peerPublicKey)
                    cryptoEngine.savePeerPublicKey(cameFromEndpoint, peerPublicKey)

                    if (peerName.isNotEmpty()) {
                        peerDisplayNames[cameFromEndpoint] = peerName
                        peerDisplayNames[peerId] = peerName
                    }
                    remotePeerNames.remove(peerId)
                    remotePeerNextHopEndpoint.remove(peerId)
                    notifyPeersChanged()

                    // Symmetrical response: Ensure the sender also has my public key & registration!
                    val myPubKey = cryptoEngine.getMyPublicKeyBase64()
                    sendToPeer("KEY_EXCHANGE_ACK|$myDeviceId|${getMyPhoneOrId()}|$myPubKey", cameFromEndpoint)

                    // Multi-hop Gossip Announcement:
                    // A. Announce this newly joined direct peer to all other connected peers
                    val announceMsg = "PEER_ANNOUNCE|$peerId|$peerName|$peerPublicKey|1|2"
                    sendToAllPeers(announceMsg, cameFromEndpoint)

                    // B. Announce all other already connected direct peers to this newly joined peer
                    for (endpoint in connectedPeers) {
                        if (endpoint != cameFromEndpoint) {
                            val otherDevId = endpointToDeviceId[endpoint] ?: endpoint
                            val otherName = peerDisplayNames[otherDevId] ?: peerDisplayNames[endpoint] ?: otherDevId
                            val otherPubKey = cryptoEngine.getPeerPublicKey(otherDevId).ifEmpty { cryptoEngine.getPeerPublicKey(endpoint) }
                            if (otherPubKey.isNotEmpty()) {
                                val existingAnnounceMsg = "PEER_ANNOUNCE|$otherDevId|$otherName|$otherPubKey|1|2"
                                sendToPeer(existingAnnounceMsg, cameFromEndpoint)
                            }
                        }
                    }

                    // C. Announce any multi-hop remote peers I already know to this newly joined peer
                    for ((rId, rName) in remotePeerNames) {
                        val rPubKey = cryptoEngine.getPeerPublicKey(rId)
                        if (rPubKey.isNotEmpty() && rId != peerId) {
                            val cleanName = rName.substringBefore(" (via")
                            val rAnnounceMsg = "PEER_ANNOUNCE|$rId|$cleanName|$rPubKey|2|1"
                            sendToPeer(rAnnounceMsg, cameFromEndpoint)
                        }
                    }

                    // Trigger anti-entropy database sync Catalog
                    val ids = messageDao.getAllMessageIds()
                    val idsCsv = ids.joinToString(",")
                    sendToPeer("SYNC_CATALOG|$myDeviceId|$idsCsv", cameFromEndpoint)
                    return@launch
                }

                // 1a. Symmetrical Key Exchange ACK Received
                if (type == "KEY_EXCHANGE_ACK") {
                    if (parts.size < 4) return@launch
                    val peerId = parts[1]
                    val peerName = parts[2]
                    val peerPublicKey = parts[3]

                    connectedPeers.add(cameFromEndpoint)
                    endpointToDeviceId[cameFromEndpoint] = peerId
                    deviceIdToEndpoint[peerId] = cameFromEndpoint

                    cryptoEngine.savePeerPublicKey(peerId, peerPublicKey)
                    cryptoEngine.savePeerPublicKey(cameFromEndpoint, peerPublicKey)

                    if (peerName.isNotEmpty()) {
                        peerDisplayNames[cameFromEndpoint] = peerName
                        peerDisplayNames[peerId] = peerName
                    }
                    remotePeerNames.remove(peerId)
                    remotePeerNextHopEndpoint.remove(peerId)
                    notifyPeersChanged()
                    return@launch
                }

                // Heartbeat Ping/Pong
                if (type == "PING") {
                    connectedPeers.add(cameFromEndpoint)
                    sendToPeer("PONG|$myDeviceId", cameFromEndpoint)
                    return@launch
                }

                if (type == "PONG") {
                    connectedPeers.add(cameFromEndpoint)
                    return@launch
                }

                // Disconnect propagation: Remove ghost peers dynamically across hops
                if (type == "PEER_LOST") {
                    if (parts.size < 2) return@launch
                    val lostDevId = parts[1]
                    val removed = (remotePeerNames.remove(lostDevId) != null)
                    remotePeerNextHopEndpoint.remove(lostDevId)
                    if (removed) {
                        notifyPeersChanged()
                        sendToAllPeers("PEER_LOST|$lostDevId", cameFromEndpoint)
                    }
                    return@launch
                }

                // 1b. Multi-Hop Peer Gossip Announcement
                if (type == "PEER_ANNOUNCE") {
                    if (parts.size < 6) return@launch
                    val remoteDevId = parts[1]
                    val remoteName = parts[2]
                    val remotePubKey = parts[3]
                    val hopDistance = parts[4].toIntOrNull() ?: 1
                    val ttl = parts[5].toIntOrNull() ?: 1

                    // Ignore if announcing self, or if already a direct physical peer
                    if (remoteDevId == myDeviceId || endpointToDeviceId.values.contains(remoteDevId)) {
                        return@launch
                    }

                    // Save public key for E2EE encryption
                    cryptoEngine.savePeerPublicKey(remoteDevId, remotePubKey)

                    // Track remote peer name and next-hop endpoint
                    val label = if (remoteName.contains("via Relay")) remoteName else "$remoteName (via Relay)"
                    remotePeerNames[remoteDevId] = label
                    remotePeerNextHopEndpoint[remoteDevId] = cameFromEndpoint
                    notifyPeersChanged()

                    // Forward announcement to other peers if TTL allows
                    if (ttl > 1) {
                        val nextTtl = ttl - 1
                        val nextHop = hopDistance + 1
                        val forwardedAnnounce = "PEER_ANNOUNCE|$remoteDevId|$remoteName|$remotePubKey|$nextHop|$nextTtl"
                        sendToAllPeers(forwardedAnnounce, cameFromEndpoint)
                    }
                    return@launch
                }

                // 2. Gossip Sync Catalog Received
                if (type == "SYNC_CATALOG") {
                    if (parts.size < 3) return@launch
                    val peerId = parts[1]
                    val idsCsv = parts[2]
                    if (idsCsv.isEmpty()) return@launch

                    val peerIds = idsCsv.split(",")
                    val myIds = messageDao.getAllMessageIds().toSet()

                    val missingIds = peerIds.filter { !myIds.contains(it) }
                    if (missingIds.isNotEmpty()) {
                        sendToPeer("SYNC_REQUEST|$myDeviceId|${missingIds.joinToString(",")}", cameFromEndpoint)
                    }
                    return@launch
                }

                // 3. Gossip Sync Catalog Request Received
                if (type == "SYNC_REQUEST") {
                    if (parts.size < 3) return@launch
                    val peerId = parts[1]
                    val requestedIdsCsv = parts[2]
                    if (requestedIdsCsv.isEmpty()) return@launch

                    val requestedIds = requestedIdsCsv.split(",")
                    for (id in requestedIds) {
                        val msg = messageDao.getMessageById(id) ?: continue
                        val syncStr = "SYNC_DELIVERY|$myDeviceId|${msg.messageId}|${msg.senderId}|${msg.senderName}|${msg.payload}|${msg.originTimestamp}|${msg.ttl}|${msg.recipientId}|${msg.recipientName}"
                        sendToPeer(syncStr, cameFromEndpoint)
                    }
                    return@launch
                }

                // 4. Gossip Sync Catalog Delivery Received
                if (type == "SYNC_DELIVERY") {
                    if (parts.size < 10) return@launch
                    val msgId = parts[2]
                    if (messageDao.existsById(msgId)) return@launch

                    if (!connectedPeers.contains(cameFromEndpoint)) {
                        connectedPeers.add(cameFromEndpoint)
                        notifyPeersChanged()
                    }

                    val senderId = parts[3]
                    val senderName = parts[4]
                    val payload = parts[5]
                    val timestamp = parts[6].toLong()
                    val ttl = parts[7].toInt()
                    val recipientId = parts[8]
                    val recipientName = parts[9]

                    val decryptedPayload = cryptoEngine.decryptMessage(payload)
                    val isDecryptedSuccessfully = !decryptedPayload.startsWith("ENC:CTR:") && payload.startsWith("ENC:CTR:")
                    val isForMe = isDecryptedSuccessfully || recipientId == myDeviceId || (recipientId.isEmpty() && !payload.startsWith("ENC:CTR:"))

                    val payloadToStore = if (isForMe) decryptedPayload else payload
                    val effectiveRecipientId = if (isForMe && (recipientId.isEmpty() || recipientId != myDeviceId)) myDeviceId else recipientId

                    val message = Message(
                        messageId = msgId,
                        senderId = senderId,
                        senderName = senderName,
                        originTimestamp = timestamp,
                        payload = payloadToStore,
                        hopCount = maxOf(0, 3 - ttl),
                        ttl = ttl,
                        isMine = (senderId == myDeviceId),
                        isBroadcast = decryptedPayload.startsWith("🆘") || (recipientId.isEmpty() && !payload.startsWith("ENC:CTR:")),
                        deliveryStatus = if (senderId == myDeviceId) "SENT" else if (isForMe) "DELIVERED" else "RELAYED",
                        recipientId = effectiveRecipientId,
                        recipientName = recipientName
                    )
                    messageDao.insert(message)

                    // Relay message to other nodes
                    if (ttl > 1) {
                        val nextTtl = ttl - 1
                        val relayedSyncStr = "SYNC_DELIVERY|$myDeviceId|$msgId|$senderId|$senderName|$payload|$timestamp|$nextTtl|$recipientId|$recipientName"
                        sendToAllPeers(relayedSyncStr, cameFromEndpoint)
                    }

                    // If meant for us, alert UI flow
                    if (isForMe || senderId == myDeviceId) {
                        val envelope = MessageEnvelope(
                            messageId = msgId,
                            senderId = senderId,
                            senderName = senderName,
                            payload = decryptedPayload,
                            timestamp = timestamp,
                            ttl = ttl
                        )
                        onMessageReceivedCallback(envelope)
                    }
                    return@launch
                }

                // 5. Standard Real-Time Flood Protocol
                if (parts.size >= 6) {
                    val mId = parts[0]
                    if (seenMessageIds.contains(mId)) return@launch
                    seenMessageIds.add(mId)

                    if (!connectedPeers.contains(cameFromEndpoint)) {
                        connectedPeers.add(cameFromEndpoint)
                        notifyPeersChanged()
                    }

                    val senderId = parts[1]
                    val senderName = parts[2]
                    val encryptedPayload = parts[3]
                    val timestamp = parts[4].toLong()
                    val ttl = parts[5].toInt()
                    val recipientId = if (parts.size > 6) parts[6] else ""
                    val recipientName = if (parts.size > 7) parts[7] else ""

                    val decryptedPayload = cryptoEngine.decryptMessage(encryptedPayload)
                    val isDecryptedSuccessfully = !decryptedPayload.startsWith("ENC:CTR:") && encryptedPayload.startsWith("ENC:CTR:")
                    val isForMe = isDecryptedSuccessfully || recipientId == myDeviceId || (recipientId.isEmpty() && !encryptedPayload.startsWith("ENC:CTR:"))

                    val payloadToStore = if (isForMe) decryptedPayload else encryptedPayload
                    val effectiveRecipientId = if (isForMe && (recipientId.isEmpty() || recipientId != myDeviceId)) myDeviceId else recipientId

                    val envelope = MessageEnvelope(
                        messageId = mId,
                        senderId = senderId,
                        senderName = senderName,
                        payload = decryptedPayload,
                        timestamp = timestamp,
                        ttl = ttl
                    )

                    // Store in database
                    val message = Message(
                        messageId = mId,
                        senderId = senderId,
                        senderName = senderName,
                        originTimestamp = timestamp,
                        payload = payloadToStore,
                        hopCount = maxOf(0, 3 - ttl),
                        ttl = ttl,
                        isMine = (senderId == myDeviceId),
                        isBroadcast = decryptedPayload.startsWith("🆘") || (recipientId.isEmpty() && !encryptedPayload.startsWith("ENC:CTR:")),
                        deliveryStatus = if (senderId == myDeviceId) "SENT" else if (isForMe) "DELIVERED" else "RELAYED",
                        recipientId = effectiveRecipientId,
                        recipientName = recipientName
                    )
                    messageDao.insert(message)

                    // Forward packet to peers
                    if (ttl > 1) {
                        val nextTtl = ttl - 1
                        val relayedRawString = "$mId|$senderId|$senderName|$encryptedPayload|$timestamp|$nextTtl|$recipientId|$recipientName"
                        sendToAllPeers(relayedRawString, cameFromEndpoint)
                    }

                    // If readable locally, notify viewmodel
                    if (isForMe || senderId == myDeviceId) {
                        onMessageReceivedCallback(envelope)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // FIX (bug 3 - silent send failures / stuck dead peers): if the underlying Bluetooth/WiFi
    // socket to a peer dies mid-session (seen in Logcat as "VirtualOutputStream ... closed"),
    // sendPayload throws. Previously that exception was uncaught here and would crash the
    // meshScope coroutine silently. Now we catch it, drop the dead peer from connectedPeers,
    // and notify the UI - so the peer list stays accurate and future sends don't keep
    // retrying a socket that's already gone.
    private fun sendToAllPeers(dataStr: String, excludeEndpoint: String?) {
        val payload = Payload.fromBytes(dataStr.toByteArray())
        val deadPeers = mutableListOf<String>()
        val excludeDevId = if (excludeEndpoint != null) endpointToDeviceId[excludeEndpoint] else null
        for (peerEndpoint in connectedPeers) {
            if (peerEndpoint != excludeEndpoint && endpointToDeviceId[peerEndpoint] != excludeEndpoint && peerEndpoint != excludeDevId) {
                try {
                    connectionsClient.sendPayload(peerEndpoint, payload)
                } catch (e: Exception) {
                    e.printStackTrace()
                    deadPeers.add(peerEndpoint)
                }
            }
        }
        if (deadPeers.isNotEmpty()) {
            for (p in deadPeers) {
                connectedPeers.remove(p)
                val devId = endpointToDeviceId.remove(p)
                if (devId != null) {
                    deviceIdToEndpoint.remove(devId)
                    peerDisplayNames.remove(devId)
                }
                peerDisplayNames.remove(p)
            }
            notifyPeersChanged()
        }
    }

    private fun sendToPeer(dataStr: String, peerIdOrEndpoint: String) {
        val endpoint = deviceIdToEndpoint[peerIdOrEndpoint] ?: peerIdOrEndpoint
        try {
            val payload = Payload.fromBytes(dataStr.toByteArray())
            connectionsClient.sendPayload(endpoint, payload)
        } catch (e: Exception) {
            e.printStackTrace()
            connectedPeers.remove(endpoint)
            val devId = endpointToDeviceId.remove(endpoint)
            if (devId != null) {
                deviceIdToEndpoint.remove(devId)
                peerDisplayNames.remove(devId)
            }
            peerDisplayNames.remove(endpoint)
            notifyPeersChanged()
        }
    }

    // --- Google Nearby Callbacks Boilerplate ---
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val rawData = String(payload.asBytes()!!)
                handleIncomingRawData(rawData, endpointId)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    var onPeersChangedCallback: ((Set<String>) -> Unit)? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            android.util.Log.d("RelayNet", "Connection initiated with $endpointId (${info.endpointName})")
            val peerParts = info.endpointName.split("|")
            val peerName = peerParts.getOrNull(0) ?: info.endpointName
            val peerDeviceId = if (peerParts.size > 1) peerParts[1] else ""

            if (peerDeviceId.isNotEmpty()) {
                endpointToDeviceId[endpointId] = peerDeviceId
                deviceIdToEndpoint[peerDeviceId] = endpointId
            }
            if (peerName.isNotEmpty() && peerName != endpointId) {
                peerDisplayNames[endpointId] = peerName
                if (peerDeviceId.isNotEmpty()) {
                    peerDisplayNames[peerDeviceId] = peerName
                }
            }
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    android.util.Log.e("RelayNet", "acceptConnection to $endpointId failed: ${e.message}")
                }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnectingEndpoints.remove(endpointId)
            val isSuccess = result.status.isSuccess || result.status.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT
            if (isSuccess) {
                android.util.Log.d("RelayNet", "Connection successful with $endpointId (code: ${result.status.statusCode})")
                connectedPeers.add(endpointId)
                notifyPeersChanged()

                // Send key exchange payload instantly with human name/phone
                val myPubKey = cryptoEngine.getMyPublicKeyBase64()
                sendToPeer("KEY_EXCHANGE|$myDeviceId|${getMyPhoneOrId()}|$myPubKey", endpointId)
            } else {
                android.util.Log.w("RelayNet", "Connection result not success: ${result.status.statusCode} ${result.status.statusMessage}")
                val devId = endpointToDeviceId.remove(endpointId)
                if (devId != null) {
                    deviceIdToEndpoint.remove(devId)
                    peerDisplayNames.remove(devId)
                }
                peerDisplayNames.remove(endpointId)
                // If a connection failed (collision or radio busy), recover by soft-cycling discovery after 2s
                meshScope.launch {
                    kotlinx.coroutines.delay(2000)
                    restartMesh()
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            android.util.Log.d("RelayNet", "Peer disconnected: $endpointId")
            pendingConnectingEndpoints.remove(endpointId)
            connectedPeers.remove(endpointId)
            val devId = endpointToDeviceId.remove(endpointId)
            if (devId != null) {
                deviceIdToEndpoint.remove(devId)
                peerDisplayNames.remove(devId)
                // Cascade PEER_LOST to mesh so ghost peers are removed immediately
                sendToAllPeers("PEER_LOST|$devId", endpointId)
            }
            peerDisplayNames.remove(endpointId)

            // Remove any multi-hop remote peers that were reached through this disconnected endpoint
            val remoteToRemove = remotePeerNextHopEndpoint.filter { it.value == endpointId }.keys.toList()
            for (rId in remoteToRemove) {
                remotePeerNextHopEndpoint.remove(rId)
                remotePeerNames.remove(rId)
                sendToAllPeers("PEER_LOST|$rId", endpointId)
            }

            notifyPeersChanged()

            // Refresh discovery so that the peer (or new peers) reconnect quickly
            restartMesh()
        }
    }

    private fun requestConnectionSafe(endpointId: String) {
        if (connectedPeers.contains(endpointId) || pendingConnectingEndpoints.contains(endpointId)) return
        pendingConnectingEndpoints.add(endpointId)
        val myEndpointName = "${getMyPhoneOrId()}|$myDeviceId"
        connectionsClient.requestConnection(myEndpointName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e ->
                android.util.Log.e("RelayNet", "requestConnection to $endpointId failed: ${e.message}")
                pendingConnectingEndpoints.remove(endpointId)
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            android.util.Log.d("RelayNet", "Endpoint discovered: $endpointId (${info.endpointName})")
            val peerParts = info.endpointName.split("|")
            val peerName = peerParts.getOrNull(0) ?: info.endpointName
            val peerDeviceId = if (peerParts.size > 1) peerParts[1] else ""

            if (peerDeviceId.isNotEmpty()) {
                endpointToDeviceId[endpointId] = peerDeviceId
                deviceIdToEndpoint[peerDeviceId] = endpointId
            }
            if (peerName.isNotEmpty() && peerName != endpointId) {
                peerDisplayNames[endpointId] = peerName
                if (peerDeviceId.isNotEmpty()) {
                    peerDisplayNames[peerDeviceId] = peerName
                }
            }

            // 1. If already connected to this peer, skip
            if (connectedPeers.contains(endpointId) || (peerDeviceId.isNotEmpty() && deviceIdToEndpoint.containsKey(peerDeviceId) && connectedPeers.contains(deviceIdToEndpoint[peerDeviceId]))) {
                return
            }

            // 2. If already in-flight connecting, skip
            if (pendingConnectingEndpoints.contains(endpointId)) {
                return
            }

            // 3. Collision-free deterministic tie-breaker:
            // When two phones discover each other simultaneously, both calling requestConnection creates
            // an over-the-air collision that fails or stalls (status 8003/8002).
            // Node with higher deviceId initiates immediately.
            // Node with lower deviceId waits 4.5s to accept the incoming request first.
            if (peerDeviceId.isNotEmpty() && myDeviceId < peerDeviceId) {
                meshScope.launch {
                    kotlinx.coroutines.delay(4500)
                    if (!connectedPeers.contains(endpointId) && !pendingConnectingEndpoints.contains(endpointId)) {
                        requestConnectionSafe(endpointId)
                    }
                }
            } else {
                requestConnectionSafe(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingConnectingEndpoints.remove(endpointId)
        }
    }
}