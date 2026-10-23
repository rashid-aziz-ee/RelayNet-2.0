package com.example.relaynet.mesh

import com.example.relaynet.MessageEnvelope

interface MeshBridge {
    // Called by the networking layer when a message is received
    fun onMessageReceived(envelope: MessageEnvelope)

    // Called by the UI when sending a message
    fun onSendMessage(text: String)

    // Called by the networking layer when peer connections change
    fun onPeersChanged(peers: Set<String>)
}
