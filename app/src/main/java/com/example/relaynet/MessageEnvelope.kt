package com.example.relaynet

import java.util.UUID

data class MessageEnvelope(
    val messageId: String = UUID.randomUUID().toString(),
    val senderId: String,       // Phone ka unique network ID
    val senderName: String,     // User ka naam (e.g., "Phone A")
    val payload: String,        // Asli text message
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 3            // Max 3 hops tak aage ja sakta hai
)