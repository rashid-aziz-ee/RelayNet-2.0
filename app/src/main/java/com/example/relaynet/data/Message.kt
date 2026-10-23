package com.example.relaynet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val originTimestamp: Long,
    val payload: String,
    val hopCount: Int,
    val ttl: Int,
    val isMine: Boolean,
    val isBroadcast: Boolean,
    val deliveryStatus: String, // SENDING, SENT, RELAYED, DELIVERED
    val recipientId: String = "",
    val recipientName: String = ""
)
