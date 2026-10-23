package com.example.relaynet.ui

import app.cash.turbine.test
import com.example.relaynet.MessageEnvelope
import com.example.relaynet.data.Message
import com.example.relaynet.data.MessageDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for RelayViewModel / MeshBridge logic.
 * Runs on the JVM — no device needed. Uses MockK to fake the DAO.
 */
@ExperimentalCoroutinesApi
class RelayViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dao: MessageDao
    private lateinit var viewModel: RelayViewModel

    private val MY_ID = "device-me"
    private val MY_NAME = "My Phone"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dao = mockk(relaxed = true)

        // Default DAO stubs
        every { dao.getAllMessages() } returns flowOf(emptyList())
        coEvery { dao.existsById(any()) } returns false

        viewModel = RelayViewModel(dao, MY_ID, MY_NAME)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeEnvelope(
        id: String = "env-1",
        senderId: String = "peer-X",
        senderName: String = "Peer Phone",
        payload: String = "Hello!",
        ttl: Int = 3
    ) = MessageEnvelope(
        messageId = id,
        senderId = senderId,
        senderName = senderName,
        payload = payload,
        ttl = ttl
    )

    // -----------------------------------------------------------------------
    // Tests — onMessageReceived
    // -----------------------------------------------------------------------

    @Test
    fun onMessageReceived_inserts_message_into_dao() = runTest {
        val envelope = makeEnvelope()

        viewModel.onMessageReceived(envelope)
        advanceUntilIdle()

        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    fun onMessageReceived_skips_insert_when_message_already_exists() = runTest {
        coEvery { dao.existsById("dup-id") } returns true

        viewModel.onMessageReceived(makeEnvelope(id = "dup-id"))
        advanceUntilIdle()

        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun onMessageReceived_marks_isMine_true_when_senderId_matches_myId() = runTest {
        val envelope = makeEnvelope(senderId = MY_ID)

        viewModel.onMessageReceived(envelope)
        advanceUntilIdle()

        coVerify {
            dao.insert(match { it.isMine })
        }
    }

    @Test
    fun onMessageReceived_marks_isMine_false_for_other_sender() = runTest {
        val envelope = makeEnvelope(senderId = "someone-else")

        viewModel.onMessageReceived(envelope)
        advanceUntilIdle()

        coVerify {
            dao.insert(match { !it.isMine })
        }
    }

    @Test
    fun onMessageReceived_marks_isBroadcast_true_for_sos_prefix() = runTest {
        val envelope = makeEnvelope(payload = "🆘 Emergency at block 3!")

        viewModel.onMessageReceived(envelope)
        advanceUntilIdle()

        coVerify {
            dao.insert(match { it.isBroadcast })
        }
    }

    @Test
    fun onMessageReceived_marks_isBroadcast_false_for_normal_message() = runTest {
        val envelope = makeEnvelope(payload = "Normal message")

        viewModel.onMessageReceived(envelope)
        advanceUntilIdle()

        coVerify {
            dao.insert(match { !it.isBroadcast })
        }
    }

    @Test
    fun onMessageReceived_sets_deliveryStatus_RELAYED_for_incoming() = runTest {
        val envelope = makeEnvelope(senderId = "other-device")

        viewModel.onMessageReceived(envelope)
        advanceUntilIdle()

        coVerify {
            dao.insert(match { it.deliveryStatus == "RELAYED" })
        }
    }

    @Test
    fun onMessageReceived_calculates_hopCount_from_ttl() = runTest {
        // ttl=2 means 1 hop used (started at 3)
        val envelope = makeEnvelope(ttl = 2)

        viewModel.onMessageReceived(envelope)
        advanceUntilIdle()

        coVerify {
            dao.insert(match { it.hopCount == 1 })
        }
    }

    // -----------------------------------------------------------------------
    // Tests — onPeersChanged
    // -----------------------------------------------------------------------

    @Test
    fun onPeersChanged_populates_connectedPeers_map() = runTest {
        viewModel.onPeersChanged(setOf("ep-1", "ep-2"))

        val peers = viewModel.connectedPeers.value
        assertEquals(2, peers.size)
        assertTrue(peers.containsKey("ep-1"))
        assertTrue(peers.containsKey("ep-2"))
    }

    @Test
    fun onPeersChanged_uses_short_endpointId_as_default_name() = runTest {
        viewModel.onPeersChanged(setOf("abcdef123456"))

        val name = viewModel.connectedPeers.value["abcdef123456"]
        assertEquals("abcdef", name) // first 6 chars
    }

    @Test
    fun onPeersChanged_preserves_resolved_name_from_received_message() = runTest {
        // Simulate a message received that resolves the peer's name
        viewModel.onPeersChanged(setOf("peer-Z"))                          // initially short ID
        viewModel.onMessageReceived(makeEnvelope(senderId = "peer-Z", senderName = "Zara's Phone"))
        advanceUntilIdle()

        val name = viewModel.connectedPeers.value["peer-Z"]
        assertEquals("Zara's Phone", name)
    }

    @Test
    fun onPeersChanged_removes_disconnected_peers() = runTest {
        viewModel.onPeersChanged(setOf("ep-1", "ep-2"))
        viewModel.onPeersChanged(setOf("ep-1")) // ep-2 disconnected

        val peers = viewModel.connectedPeers.value
        assertEquals(1, peers.size)
        assertFalse(peers.containsKey("ep-2"))
    }

    // -----------------------------------------------------------------------
    // Tests — onSendMessage
    // -----------------------------------------------------------------------

    @Test
    fun onSendMessage_inserts_message_into_dao() = runTest {
        viewModel.onSendMessage("Test message")
        advanceUntilIdle()

        coVerify(atLeast = 1) { dao.insert(any()) }
    }

    @Test
    fun onSendMessage_calls_sendLambda() = runTest {
        var capturedText: String? = null
        viewModel.sendLambda = { text -> capturedText = text }

        viewModel.onSendMessage("Hello network!")
        advanceUntilIdle()

        assertEquals("Hello network!", capturedText)
    }

    @Test
    fun onSendMessage_stores_message_as_isMine_true() = runTest {
        viewModel.onSendMessage("My outbound message")
        advanceUntilIdle()

        coVerify {
            dao.insert(match { it.isMine && it.senderId == MY_ID })
        }
    }

    @Test
    fun onSendMessage_sos_prefix_stores_isBroadcast_true() = runTest {
        viewModel.onSendMessage("🆘 HELP!")
        advanceUntilIdle()

        coVerify {
            dao.insert(match { it.isBroadcast })
        }
    }

    @Test
    fun onSendMessage_stores_with_ttl_3_and_hopCount_0() = runTest {
        viewModel.onSendMessage("outbound")
        advanceUntilIdle()

        coVerify {
            dao.insert(match { it.ttl == 3 && it.hopCount == 0 })
        }
    }
}
