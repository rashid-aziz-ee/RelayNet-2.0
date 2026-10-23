package com.example.relaynet.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests the Room DAO using an in-memory database.
 * Must run on a device or emulator (androidTest).
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: RelayNetDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RelayNetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeMessage(
        id: String = "msg-1",
        sender: String = "peer-A",
        name: String = "Alice",
        payload: String = "Hello mesh!",
        isMine: Boolean = false,
        isBroadcast: Boolean = false
    ) = Message(
        messageId = id,
        senderId = sender,
        senderName = name,
        originTimestamp = System.currentTimeMillis(),
        payload = payload,
        hopCount = 1,
        ttl = 2,
        isMine = isMine,
        isBroadcast = isBroadcast,
        deliveryStatus = if (isMine) "SENT" else "RELAYED"
    )

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun insert_and_retrieve_all_messages_via_flow() = runTest {
        val m1 = makeMessage("a", payload = "First")
        val m2 = makeMessage("b", payload = "Second")
        val m3 = makeMessage("c", payload = "Third")

        dao.insert(m1)
        dao.insert(m2)
        dao.insert(m3)

        dao.getAllMessages().test {
            val list = awaitItem()
            assertEquals(3, list.size)
            // Ordered by originTimestamp ASC — all inserted very close in time
            // so just verify all are present
            val ids = list.map { it.messageId }.toSet()
            assertTrue("a" in ids)
            assertTrue("b" in ids)
            assertTrue("c" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun existsById_returns_true_for_inserted_message() = runTest {
        dao.insert(makeMessage("known-id"))
        assertTrue(dao.existsById("known-id"))
    }

    @Test
    fun existsById_returns_false_for_unknown_message() = runTest {
        assertFalse(dao.existsById("ghost-id"))
    }

    @Test
    fun insert_with_same_id_replaces_existing_row() = runTest {
        dao.insert(makeMessage("dup-id", payload = "Original"))
        dao.insert(makeMessage("dup-id", payload = "Replaced"))

        dao.getAllMessages().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Replaced", list[0].payload)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun flow_emits_updated_list_after_new_insert() = runTest {
        dao.getAllMessages().test {
            // Initial state: empty
            val empty = awaitItem()
            assertEquals(0, empty.size)

            // Insert one message
            dao.insert(makeMessage("new"))

            // Flow should emit again with 1 item
            val updated = awaitItem()
            assertEquals(1, updated.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun insert_sos_message_stores_isBroadcast_true() = runTest {
        dao.insert(makeMessage("sos-1", payload = "🆘 Emergency!", isBroadcast = true))
        dao.getAllMessages().test {
            val list = awaitItem()
            assertTrue(list.first().isBroadcast)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun insert_my_message_stores_isMine_true() = runTest {
        dao.insert(makeMessage("mine-1", isMine = true))
        dao.getAllMessages().test {
            val list = awaitItem()
            assertTrue(list.first().isMine)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
