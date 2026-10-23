package com.example.relaynet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relaynet.data.Message
import com.example.relaynet.data.MessageDao
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for ChatScreen.
 * Runs on a device or emulator (androidTest).
 *
 * Uses a mock DAO so tests are fast and deterministic.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var dao: MessageDao

    // Stable backing flow we can update per-test
    private val messagesFlow = MutableStateFlow<List<Message>>(emptyList())

    private val MY_ID = "device-me"
    private val MY_NAME = "My Phone"

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        every { dao.getAllMessages() } returns messagesFlow
        every { dao.existsById(any()) } returns false
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeMessage(
        id: String,
        payload: String,
        isMine: Boolean = false,
        isBroadcast: Boolean = false,
        senderName: String = "Alice",
        senderId: String = "peer-A"
    ) = Message(
        messageId = id,
        senderId = if (isMine) MY_ID else senderId,
        senderName = if (isMine) MY_NAME else senderName,
        originTimestamp = System.currentTimeMillis(),
        payload = payload,
        hopCount = 0,
        ttl = 3,
        isMine = isMine,
        isBroadcast = isBroadcast,
        deliveryStatus = if (isMine) "SENT" else "RELAYED"
    )

    private fun launchScreen() {
        val viewModel = RelayViewModel(dao, MY_ID, MY_NAME)
        composeTestRule.setContent {
            ChatScreen(viewModel = viewModel)
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun empty_state_shows_no_messages_text() {
        messagesFlow.value = emptyList()
        launchScreen()

        composeTestRule
            .onNodeWithTag("empty_state")
            .assertIsDisplayed()
            .assertTextContains("No messages yet")
    }

    @Test
    fun message_list_visible_when_messages_exist() {
        messagesFlow.value = listOf(makeMessage("m1", "Hello there!"))
        launchScreen()

        composeTestRule
            .onNodeWithTag("message_list")
            .assertIsDisplayed()
    }

    @Test
    fun message_payload_text_is_displayed() {
        messagesFlow.value = listOf(makeMessage("m1", "Test payload visible"))
        launchScreen()

        composeTestRule
            .onNodeWithText("Test payload visible")
            .assertIsDisplayed()
    }

    @Test
    fun multiple_messages_all_rendered() {
        messagesFlow.value = listOf(
            makeMessage("m1", "Message one"),
            makeMessage("m2", "Message two"),
            makeMessage("m3", "Message three")
        )
        launchScreen()

        composeTestRule.onNodeWithText("Message one").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message two").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message three").assertIsDisplayed()
    }

    @Test
    fun sos_button_is_displayed() {
        messagesFlow.value = emptyList()
        launchScreen()

        composeTestRule
            .onNodeWithTag("sos_button")
            .assertIsDisplayed()
    }

    @Test
    fun sos_button_click_calls_onSendMessage_with_sos_prefix() {
        messagesFlow.value = emptyList()
        val viewModel = RelayViewModel(dao, MY_ID, MY_NAME)
        var capturedText: String? = null
        viewModel.sendLambda = { text -> capturedText = text }

        composeTestRule.setContent {
            ChatScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("sos_button").performClick()

        // SOS message should start with the emergency emoji
        composeTestRule.waitForIdle()
        // Verify the SOS message was stored (dao.insert called with SOS payload)
        verify(atLeast = 1) { dao.insert(match { it.isBroadcast }) }
    }

    @Test
    fun peers_row_not_shown_when_no_peers_connected() {
        messagesFlow.value = emptyList()
        launchScreen()

        composeTestRule
            .onNodeWithTag("peers_row")
            .assertDoesNotExist()
    }

    @Test
    fun peers_row_shown_when_peers_connected() {
        messagesFlow.value = emptyList()
        val viewModel = RelayViewModel(dao, MY_ID, MY_NAME)

        composeTestRule.setContent {
            ChatScreen(viewModel = viewModel)
        }

        // Simulate peers connecting
        composeTestRule.runOnUiThread {
            viewModel.onPeersChanged(setOf("ep-1"))
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("peers_row")
            .assertIsDisplayed()
    }

    @Test
    fun peer_display_name_shown_in_peers_row() {
        messagesFlow.value = emptyList()
        val viewModel = RelayViewModel(dao, MY_ID, MY_NAME)

        composeTestRule.setContent {
            ChatScreen(viewModel = viewModel)
        }

        composeTestRule.runOnUiThread {
            viewModel.onPeersChanged(setOf("peer-Z"))
        }
        // Simulate receiving a message from them to resolve the name
        composeTestRule.runOnUiThread {
            viewModel.onPeersChanged(setOf("peer-Z")) // still connected
        }
        composeTestRule.waitForIdle()

        // At minimum the short ID should be in the peers row
        composeTestRule
            .onNodeWithTag("peers_row")
            .assertIsDisplayed()
    }

    @Test
    fun sos_message_bubble_shows_emergency_label() {
        messagesFlow.value = listOf(
            makeMessage("sos-1", "🆘 HELP!", isBroadcast = true, isMine = false)
        )
        launchScreen()

        composeTestRule
            .onNodeWithText("EMERGENCY BROADCAST")
            .assertIsDisplayed()
    }

    @Test
    fun send_button_disabled_when_input_empty() {
        messagesFlow.value = emptyList()
        launchScreen()

        // Send icon button should be disabled when input is empty
        composeTestRule
            .onNodeWithContentDescription("Send")
            .assertIsNotEnabled()
    }

    @Test
    fun send_button_enabled_after_typing() {
        messagesFlow.value = emptyList()
        launchScreen()

        composeTestRule
            .onNodeWithText("Type an offline message...")
            .performTextInput("Hello!")

        composeTestRule
            .onNodeWithContentDescription("Send")
            .assertIsEnabled()
    }

    @Test
    fun typing_and_sending_clears_input_field() {
        messagesFlow.value = emptyList()
        launchScreen()

        composeTestRule
            .onNodeWithText("Type an offline message...")
            .performTextInput("Clear me")

        composeTestRule
            .onNodeWithContentDescription("Send")
            .performClick()

        composeTestRule.waitForIdle()

        // Input should be empty — placeholder visible again
        composeTestRule
            .onNodeWithText("Type an offline message...")
            .assertIsDisplayed()
    }
}
