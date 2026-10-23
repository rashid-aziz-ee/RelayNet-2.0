package com.example.relaynet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relaynet.data.Message
import com.example.relaynet.data.MessageDao
import com.example.relaynet.data.RelayNetDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RoomDatabaseTest {
    private lateinit var messageDao: MessageDao
    private lateinit var db: RelayNetDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RelayNetDatabase::class.java).build()
        messageDao = db.messageDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun insertAndReadMessage() = runBlocking {
        val message = Message(
            messageId = "test-uuid-123",
            senderId = "sender-1",
            senderName = "Sender One",
            originTimestamp = System.currentTimeMillis(),
            payload = "Hello Mesh!",
            hopCount = 0,
            ttl = 3,
            isMine = true,
            isBroadcast = false,
            deliveryStatus = "SENT"
        )
        messageDao.insert(message)
        
        val exists = messageDao.existsById("test-uuid-123")
        assertTrue(exists)

        val allMessages = messageDao.getAllMessages().first()
        assertEquals(1, allMessages.size)
        assertEquals("Hello Mesh!", allMessages[0].payload)
    }
}
