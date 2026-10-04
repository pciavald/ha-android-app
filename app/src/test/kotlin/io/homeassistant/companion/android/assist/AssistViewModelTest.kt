package io.homeassistant.companion.android.assist

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import io.homeassistant.companion.android.assist.bluetooth.HeadsetVoiceSession
import io.homeassistant.companion.android.common.assist.AssistAudioStrategy
import io.homeassistant.companion.android.common.assist.AssistViewModelBase
import io.homeassistant.companion.android.common.assist.AssistViewModelBase.AssistInputMode
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.servers.ServerConnectionStateProvider
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.common.data.websocket.WebSocketRepository
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistChatLogDelta
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineError
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineEvent
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineEventType
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineIntentEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineIntentProgress
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineListResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineSttEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineTtsEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.ConversationResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.ConversationSpeechPlainResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.ConversationSpeechResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.GetConfigResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.TtsOutputResponse
import io.homeassistant.companion.android.common.util.AudioUrlPlayer
import io.homeassistant.companion.android.common.util.AudioUsage
import io.homeassistant.companion.android.common.util.PlaybackState
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherJUnit5Extension::class)
class AssistViewModelTest {

    private val serverManager: ServerManager = mockk(relaxed = true)
    private val audioUrlPlayer: AudioUrlPlayer = mockk(relaxed = true)
    private val application: Application = mockk(relaxed = true)
    private val webSocketRepository: WebSocketRepository = mockk(relaxed = true)
    private val integrationRepository: IntegrationRepository = mockk(relaxed = true)

    /** Headset session and server calls in the order they happened. */
    private val calls = mutableListOf<String>()
    private val headsetSession = FakeHeadsetVoiceSession(calls)

    private lateinit var viewModel: AssistViewModel

    @BeforeEach
    fun setUp() {
        val packageManager = mockk<PackageManager>()
        every { application.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) } returns true

        coEvery { serverManager.isRegistered() } answers {
            calls += "isRegistered"
            true
        }
        coEvery { serverManager.webSocketRepository(any()) } returns webSocketRepository
        coEvery { serverManager.integrationRepository(any()) } returns integrationRepository
        coEvery { integrationRepository.isHomeAssistantVersionAtLeast(any(), any(), any()) } returns true
        coEvery { webSocketRepository.getConfig() } returns GetConfigResponse(
            latitude = 0.0,
            longitude = 0.0,
            elevation = 0.0,
            unitSystem = emptyMap(),
            locationName = "Test",
            timeZone = "UTC",
            components = listOf("assist_pipeline"),
            version = "2025.1.0",
        )
        coEvery { webSocketRepository.getAssistPipeline(anyNullable()) } returns AssistPipelineResponse(
            id = "test-pipeline",
            name = "Test Pipeline",
            language = "en",
            conversationEngine = "conversation",
            conversationLanguage = "en",
            sttEngine = "stt",
            sttLanguage = "en",
            ttsEngine = "tts",
            ttsLanguage = "en",
            ttsVoice = null,
        )
        coEvery { webSocketRepository.getAssistPipelines() } returns AssistPipelineListResponse(
            pipelines = listOf(),
            preferredPipeline = "test-pipeline",
        )
        coEvery { integrationRepository.getLastUsedPipelineId() } returns null
        coEvery { integrationRepository.getLastUsedPipelineSttSupport() } returns false
        coEvery { integrationRepository.setLastUsedPipeline(any(), any()) } returns Unit
        coEvery { serverManager.getServer(any<Int>()) } returns mockk(relaxed = true)
        coEvery { serverManager.servers() } returns listOf()
    }

    private fun createViewModel(): AssistViewModel {
        return AssistViewModel(
            serverManager = serverManager,
            audioUrlPlayer = audioUrlPlayer,
            application = application,
            headsetSession = headsetSession,
            initialAudioStrategy = object : AssistAudioStrategy {
                override suspend fun audioData(): Flow<ShortArray> = emptyFlow()

                override val wakeWordDetected: Flow<String> = emptyFlow()

                override fun requestFocus() {
                    // No-op for testing
                }

                override fun abandonFocus() {
                    // No-op for testing
                }
            },
        )
    }

    private fun createAndInitialize(
        hasPermission: Boolean = false,
        startedWithWakeWord: Boolean = false,
        fromHeadset: Boolean = false,
    ): AssistViewModel {
        val vm = createViewModel()
        vm.onCreate(
            hasPermission = hasPermission,
            serverId = null,
            pipelineId = null,
            startListening = if (fromHeadset) true else null,
            wakeWordPhrase = if (startedWithWakeWord) "Okay Nabu" else null,
            fromHeadset = fromHeadset,
        )
        return vm
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private val pipelineEvents = MutableSharedFlow<AssistPipelineEvent>()

    /**
     * Sets up mocks for a voice pipeline backed by [pipelineEvents].
     *
     * Configures the audio recorder to start successfully and wires
     * [pipelineEvents] as the pipeline event source for voice input.
     */
    private fun setupVoicePipeline() {
        coEvery {
            webSocketRepository.runAssistPipelineForVoice(any(), any(), anyNullable(), anyNullable(), anyNullable())
        } returns pipelineEvents
    }

    /**
     * Sets up mocks for a voice pipeline that supports TTS playback.
     *
     * Extends [setupVoicePipeline] with connection state and [AudioUrlPlayer]
     * mocks so that emitting [AssistPipelineEventType.TTS_END] triggers audio
     * playback through [playbackStates].
     */
    private fun setupVoicePipelineWithTts(playbackStates: MutableSharedFlow<PlaybackState>) {
        setupVoicePipeline()

        val connectionStateProvider = mockk<ServerConnectionStateProvider>()
        coEvery { serverManager.connectionStateProvider(any()) } returns connectionStateProvider
        every { connectionStateProvider.urlFlow(anyNullable()) } returns flowOf(
            UrlState.HasUrl(URL("http://test-ha.local")),
        )
        every { audioUrlPlayer.playAudio(any(), any()) } returns playbackStates
    }

    private suspend fun emitIntentEnd(continueConversation: Boolean = false) {
        pipelineEvents.emit(
            AssistPipelineEvent(
                type = AssistPipelineEventType.INTENT_END,
                data = AssistPipelineIntentEnd(
                    intentOutput = ConversationResponse(
                        response = ConversationSpeechResponse(
                            speech = ConversationSpeechPlainResponse(
                                plain = mapOf("speech" to "Hello there"),
                            ),
                        ),
                        conversationId = "test-conv",
                        continueConversation = continueConversation,
                    ),
                ),
            ),
        )
    }

    private suspend fun emitTtsEnd() {
        pipelineEvents.emit(
            AssistPipelineEvent(
                type = AssistPipelineEventType.TTS_END,
                data = AssistPipelineTtsEnd(
                    ttsOutput = TtsOutputResponse(
                        mimeType = "audio/mpeg",
                        url = "/api/tts_proxy/test.mp3",
                    ),
                ),
            ),
        )
    }

    private suspend fun emitRunEnd() {
        pipelineEvents.emit(AssistPipelineEvent(type = AssistPipelineEventType.RUN_END, data = null))
    }

    @Nested
    inner class InactivityTimerTest {

        @Test
        fun `Given started with wake word and voice inactive mode and non-placeholder message when CLOSE_INACTIVE elapses then shouldFinish is true`() = runTest {
            viewModel = createAndInitialize(startedWithWakeWord = true)
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE)
            runCurrent()

            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given voice inactive mode and non-placeholder message when less than CLOSE_INACTIVE elapses then shouldFinish is false`() = runTest {
            viewModel = createAndInitialize()
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE - 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given text input mode when CLOSE_INACTIVE elapses then shouldFinish is false`() = runTest {
            coEvery { webSocketRepository.getAssistPipeline(anyNullable()) } returns AssistPipelineResponse(
                id = "test-pipeline",
                name = "Test Pipeline",
                language = "en",
                conversationEngine = "conversation",
                conversationLanguage = "en",
                sttEngine = null,
                sttLanguage = null,
                ttsEngine = null,
                ttsLanguage = null,
                ttsVoice = null,
            )
            viewModel = createAndInitialize()
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given blocked input mode when CLOSE_INACTIVE elapses then shouldFinish is false`() = runTest {
            coEvery { serverManager.isRegistered() } returns false
            viewModel = createAndInitialize()
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given voice inactive mode when onPause is called then timer is cancelled`() = runTest {
            viewModel = createAndInitialize()
            runCurrent()

            advanceTimeBy(1.seconds)
            runCurrent()

            viewModel.onPause()
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE - 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given voice inactive mode when onDestroy is called then timer is cancelled`() = runTest {
            viewModel = createAndInitialize()
            runCurrent()

            advanceTimeBy(1.seconds)
            runCurrent()

            viewModel.onDestroy()
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE - 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given voice inactive mode when input mode changes to TEXT then timer is cancelled`() = runTest {
            viewModel = createAndInitialize()
            runCurrent()

            advanceTimeBy(1.seconds)
            runCurrent()

            viewModel.onChangeInput() // VOICE_INACTIVE -> TEXT
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE - 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given started with wake word and TEXT mode when switching back to VOICE_INACTIVE then timer restarts`() = runTest {
            viewModel = createAndInitialize(startedWithWakeWord = true)
            runCurrent()

            viewModel.onChangeInput() // VOICE_INACTIVE -> TEXT
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            viewModel.onChangeInput() // TEXT -> VOICE_INACTIVE
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE - 1.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given voice active mode when CLOSE_INACTIVE elapses then shouldFinish is false`() = runTest {
            setupVoicePipeline()
            coEvery {
                webSocketRepository.runAssistPipelineForVoice(any(), any(), anyNullable(), anyNullable(), anyNullable())
            } returns flow { awaitCancellation() }

            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given started with wake word and voice active mode when recording stops and response arrives then timer fires and shouldFinish is true`() = runTest {
            setupVoicePipeline()

            viewModel = createAndInitialize(hasPermission = true, startedWithWakeWord = true)
            runCurrent()

            // VM is now VOICE_ACTIVE. Stop recording to transition to VOICE_INACTIVE.
            viewModel.onMicrophoneInput()
            runCurrent()

            // Last message is still a placeholder, so the timer should not start yet.
            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            // Pipeline emits a response, replacing the placeholder with a real message.
            emitIntentEnd()
            runCurrent()

            // Timer is now running. Verify it hasn't fired yet just before expiry.
            advanceTimeBy(CLOSE_INACTIVE - 1.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            // Timer fires.
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given voice inactive mode with placeholder message when CLOSE_INACTIVE elapses then shouldFinish is false`() = runTest {
            setupVoicePipeline()
            coEvery {
                webSocketRepository.runAssistPipelineForVoice(any(), any(), anyNullable(), anyNullable(), anyNullable())
            } returns flow { awaitCancellation() }

            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            // Stop recording: transitions to VOICE_INACTIVE while last message is still a placeholder
            viewModel.onMicrophoneInput()
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given voice inactive mode and audio playing when CLOSE_INACTIVE elapses then shouldFinish is false`() = runTest {
            val playbackStates = MutableSharedFlow<PlaybackState>()
            setupVoicePipelineWithTts(playbackStates)

            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            // Stop recording to transition to VOICE_INACTIVE
            viewModel.onMicrophoneInput()
            runCurrent()

            // Emit INTENT_END to replace placeholder with a real message
            emitIntentEnd()
            runCurrent()

            // Emit TTS_END which triggers audio playback
            emitTtsEnd()
            runCurrent()

            // Audio starts playing
            playbackStates.emit(PlaybackState.PLAYING)
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given started with wake word and voice inactive mode and audio playing when playback stops then timer starts`() = runTest {
            val playbackStates = MutableSharedFlow<PlaybackState>()
            setupVoicePipelineWithTts(playbackStates)

            viewModel = createAndInitialize(hasPermission = true, startedWithWakeWord = true)
            runCurrent()

            // Stop recording to transition to VOICE_INACTIVE
            viewModel.onMicrophoneInput()
            runCurrent()

            // Emit INTENT_END to replace placeholder with a real message
            emitIntentEnd()
            runCurrent()

            // Emit TTS_END and start playback
            emitTtsEnd()
            runCurrent()
            playbackStates.emit(PlaybackState.PLAYING)
            runCurrent()

            // Timer should not fire while audio is playing
            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            // Audio playback finishes → triggers PlaybackFinished → restartInactivityTimer()
            playbackStates.emit(PlaybackState.STOP_PLAYING)
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE - 1.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given started with wake word and voice inactive mode when text input triggers a response then timer restarts`() = runTest {
            val textPipelineEvents = MutableSharedFlow<AssistPipelineEvent>()
            coEvery {
                integrationRepository.getAssistResponse(any(), anyNullable(), anyNullable())
            } returns textPipelineEvents

            viewModel = createAndInitialize(startedWithWakeWord = true)
            runCurrent()

            // Timer is running. Advance partway through.
            advanceTimeBy(CLOSE_INACTIVE - 5.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            // Send text input which starts a pipeline run
            viewModel.onTextInput("hello")
            runCurrent()

            // Emit a response message from the pipeline, which calls restartInactivityTimer()
            textPipelineEvents.emit(
                AssistPipelineEvent(
                    type = AssistPipelineEventType.INTENT_END,
                    data = AssistPipelineIntentEnd(
                        intentOutput = ConversationResponse(
                            response = ConversationSpeechResponse(
                                speech = ConversationSpeechPlainResponse(
                                    plain = mapOf("speech" to "Hello there"),
                                ),
                            ),
                            conversationId = "test-conv",
                        ),
                    ),
                ),
            )
            runCurrent()

            // Original CLOSE_INACTIVE has now elapsed since initialization, but timer was restarted
            advanceTimeBy(5.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            // Wait for the restarted timer to almost expire
            advanceTimeBy(CLOSE_INACTIVE - 6.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            // Now the restarted timer expires
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given not a wake word session when CLOSE_INACTIVE elapses then shouldFinish is false`() = runTest {
            viewModel = createViewModel()
            viewModel.onCreate(
                hasPermission = false,
                serverId = null,
                pipelineId = null,
                startListening = null,
                wakeWordPhrase = null,
            )
            runCurrent()

            advanceTimeBy(CLOSE_INACTIVE + 1.seconds)
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }
    }

    @Nested
    inner class VoiceRunTest {

        // A single flow for every run, as identical runs share one server subscription
        private val pipelineEvents = MutableSharedFlow<AssistPipelineEvent>()

        @BeforeEach
        fun setUpVoicePipeline() {
            every { application.getString(any<Int>()) } returns GREETING
            coEvery {
                webSocketRepository.runAssistPipelineForVoice(any(), any(), anyNullable(), anyNullable(), anyNullable())
            } returns pipelineEvents
        }

        private fun voiceCommandIntent(): Intent = mockk {
            every { flags } returns 0
            every { action } returns Intent.ACTION_VOICE_COMMAND
        }

        private fun AssistViewModel.messages(): List<Pair<String, Boolean>> = conversation.map { it.message to it.isInput }

        private fun verifyVoiceRuns(count: Int) {
            coVerify(exactly = count) {
                webSocketRepository.runAssistPipelineForVoice(any(), any(), anyNullable(), anyNullable(), anyNullable())
            }
        }

        private suspend fun emitSttEnd(text: String) {
            pipelineEvents.emit(
                AssistPipelineEvent(
                    type = AssistPipelineEventType.STT_END,
                    data = AssistPipelineSttEnd(sttOutput = mapOf("text" to text)),
                ),
            )
        }

        private suspend fun emitIntentEnd(speech: String) {
            pipelineEvents.emit(
                AssistPipelineEvent(
                    type = AssistPipelineEventType.INTENT_END,
                    data = AssistPipelineIntentEnd(
                        intentOutput = ConversationResponse(
                            response = ConversationSpeechResponse(
                                speech = ConversationSpeechPlainResponse(plain = mapOf("speech" to speech)),
                            ),
                            conversationId = "test-conv",
                        ),
                    ),
                ),
            )
        }

        private suspend fun emitIntentProgress(delta: String) {
            pipelineEvents.emit(
                AssistPipelineEvent(
                    type = AssistPipelineEventType.INTENT_PROGRESS,
                    data = AssistPipelineIntentProgress(chatLogDelta = AssistChatLogDelta(content = delta)),
                ),
            )
        }

        private suspend fun emitRunEnd() {
            pipelineEvents.emit(AssistPipelineEvent(type = AssistPipelineEventType.RUN_END, data = null))
        }

        @Test
        fun `Given a voice run in progress when paused and triggered again then the run continues and each message is shown once`() = runTest {
            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            viewModel.onPause()
            viewModel.onNewIntent(voiceCommandIntent(), lockedMatches = true)
            runCurrent()

            verifyVoiceRuns(1)

            emitSttEnd("Turn on the lights")
            emitIntentEnd("Turned on the lights")
            runCurrent()

            assertEquals(
                listOf(GREETING to false, "Turn on the lights" to true, "Turned on the lights" to false),
                viewModel.messages(),
            )
        }

        @Test
        fun `Given voice active mode when triggered again then recording continues and no run is started`() = runTest {
            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()
            assertEquals(AssistInputMode.VOICE_ACTIVE, viewModel.inputMode)

            viewModel.onNewIntent(voiceCommandIntent(), lockedMatches = true)
            runCurrent()

            assertEquals(AssistInputMode.VOICE_ACTIVE, viewModel.inputMode)
            verifyVoiceRuns(1)
        }

        @Test
        fun `Given a voice run waiting for its answer when microphone is pressed then no run is started`() = runTest {
            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            viewModel.onMicrophoneInput() // Stops recording, the run waits for its answer
            runCurrent()
            viewModel.onMicrophoneInput()
            runCurrent()

            assertEquals(AssistInputMode.VOICE_INACTIVE, viewModel.inputMode)
            verifyVoiceRuns(1)
        }

        @Test
        fun `Given a streamed response when deltas arrive then they replace the placeholder of the response`() = runTest {
            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            emitSttEnd("Say hello")
            emitIntentProgress("Hel")
            emitIntentProgress("lo")
            runCurrent()

            assertEquals(
                listOf(GREETING to false, "Say hello" to true, "Hello" to false),
                viewModel.messages(),
            )
            assertFalse(viewModel.conversation.any { it.isPlaceholder })
        }

        @Test
        fun `Given a voice run that ended when triggered again then a new run starts`() = runTest {
            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            emitSttEnd("Turn on the lights")
            emitIntentEnd("Turned on the lights")
            emitRunEnd()
            runCurrent()

            viewModel.onNewIntent(voiceCommandIntent(), lockedMatches = true)
            runCurrent()

            assertEquals(AssistInputMode.VOICE_ACTIVE, viewModel.inputMode)
            verifyVoiceRuns(2)
            assertEquals(
                listOf(GREETING to false, "Turn on the lights" to true, "Turned on the lights" to false),
                viewModel.messages(),
            )
        }
    }

    @Nested
    inner class HeadsetSessionTest {

        @Test
        fun `Given voice command from a headset when onCreate then the headset session starts before any server call`() = runTest {
            setupVoicePipeline()

            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            assertEquals("start", calls.first())
            assertTrue("isRegistered" in calls)
            assertTrue(headsetSession.isActive)
        }

        @Test
        fun `Given launch not from a headset when onCreate then no headset session is started`() = runTest {
            viewModel = createAndInitialize()
            runCurrent()

            assertFalse("start" in calls)
        }

        @Test
        fun `Given active headset session when recording starts then the headset audio route is awaited`() = runTest {
            setupVoicePipeline()

            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            assertTrue("awaitAudioRoute" in calls)
        }

        @Test
        fun `Given active headset session when the turn finishes then the session stops and Assist closes`() = runTest {
            setupVoicePipeline()
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            emitRunEnd()
            runCurrent()

            assertEquals(1, headsetSession.stopCount)
            assertFalse(headsetSession.isActive)
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given headset session failed to start when the turn finishes then Assist stays open`() = runTest {
            setupVoicePipeline()
            headsetSession.startResult = false
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            emitRunEnd()
            runCurrent()

            assertFalse(viewModel.shouldFinish)
        }

        @Test
        fun `Given active headset session when the answer was played then the session stops only after the playback tail`() = runTest {
            val playbackStates = MutableSharedFlow<PlaybackState>()
            setupVoicePipelineWithTts(playbackStates)
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            emitTtsEnd()
            runCurrent()
            playbackStates.emit(PlaybackState.PLAYING)
            emitRunEnd()
            runCurrent()
            playbackStates.emit(PlaybackState.STOP_PLAYING)
            runCurrent()

            advanceTimeBy(HEADSET_PLAYBACK_TAIL - 1.milliseconds)
            runCurrent()
            assertEquals(0, headsetSession.stopCount)
            assertFalse(viewModel.shouldFinish)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(1, headsetSession.stopCount)
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given active headset session when the pipeline asks to dismiss then the session stops`() = runTest {
            setupVoicePipeline()
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            pipelineEvents.emit(
                AssistPipelineEvent(
                    type = AssistPipelineEventType.ERROR,
                    data = AssistPipelineError(code = "duplicate_wake_up_detected"),
                ),
            )
            runCurrent()

            assertEquals(1, headsetSession.stopCount)
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given active headset session when onDestroy then the session stops`() = runTest {
            setupVoicePipeline()
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            viewModel.onDestroy()

            assertEquals(1, headsetSession.stopCount)
        }

        @Test
        fun `Given active headset session when the conversation continues then the session stays active`() = runTest {
            val playbackStates = MutableSharedFlow<PlaybackState>()
            setupVoicePipelineWithTts(playbackStates)
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            emitIntentEnd(continueConversation = true)
            emitTtsEnd()
            runCurrent()
            emitRunEnd()
            runCurrent()
            playbackStates.emit(PlaybackState.STOP_PLAYING)
            runCurrent()

            assertEquals(0, headsetSession.stopCount)
            assertTrue(headsetSession.isActive)
            assertFalse(viewModel.shouldFinish)
            assertEquals(AssistViewModelBase.AssistInputMode.VOICE_ACTIVE, viewModel.inputMode)
        }

        @Test
        fun `Given active headset session when the headset ends it then Assist closes`() = runTest {
            setupVoicePipeline()
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            headsetSession.ended.emit(Unit)
            runCurrent()

            assertEquals(1, headsetSession.stopCount)
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given active headset session when nothing ends the turn then the session times out`() = runTest {
            setupVoicePipeline()
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            advanceTimeBy(HEADSET_SESSION_TIMEOUT - 1.seconds)
            runCurrent()
            assertFalse(viewModel.shouldFinish)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(1, headsetSession.stopCount)
            assertTrue(viewModel.shouldFinish)
        }

        @Test
        fun `Given active headset session when TTS plays then it uses the voice communication usage`() = runTest {
            setupVoicePipelineWithTts(MutableSharedFlow())
            viewModel = createAndInitialize(hasPermission = true, fromHeadset = true)
            runCurrent()

            emitTtsEnd()
            runCurrent()

            verify { audioUrlPlayer.playAudio(any(), AudioUsage.VOICE_COMMUNICATION) }
        }

        @Test
        fun `Given no headset session when TTS plays then it uses the assistant usage`() = runTest {
            setupVoicePipelineWithTts(MutableSharedFlow())
            viewModel = createAndInitialize(hasPermission = true)
            runCurrent()

            emitTtsEnd()
            runCurrent()

            verify { audioUrlPlayer.playAudio(any(), AudioUsage.ASSISTANT) }
        }
    }

    private class FakeHeadsetVoiceSession(private val calls: MutableList<String>) : HeadsetVoiceSession {
        var startResult = true
        var stopCount = 0
        val ended = MutableSharedFlow<Unit>()

        private var active = false

        override val isActive: Boolean
            get() = active

        override val endedByHeadset: Flow<Unit> = ended

        override suspend fun start(): Boolean {
            calls += "start"
            active = startResult
            return startResult
        }

        override suspend fun awaitAudioRoute(timeout: Duration): Boolean {
            calls += "awaitAudioRoute"
            return true
        }

        override fun stop() {
            if (active) stopCount++
            active = false
        }
    }
}

private const val GREETING = "How can I assist?"
