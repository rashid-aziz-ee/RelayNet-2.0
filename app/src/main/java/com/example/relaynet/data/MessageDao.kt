package com.example.relaynet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: Message)

    @Query("SELECT * FROM messages ORDER BY originTimestamp ASC")
    fun getAllMessages(): Flow<List<Message>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE messageId = :messageId)")
    fun existsById(messageId: String): Boolean

    @Query("SELECT messageId FROM messages")
    fun getAllMessageIds(): List<String>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    fun getMessageById(messageId: String): Message?

    // NEW: delete a single message by its ID (used for long-press "delete message")
    @Query("DELETE FROM messages WHERE messageId = :messageId")
    fun deleteById(messageId: String)

    // NEW: wipe the whole local chat history (used for a "Clear Chat" action)
    @Query("DELETE FROM messages")
    fun deleteAll()
}