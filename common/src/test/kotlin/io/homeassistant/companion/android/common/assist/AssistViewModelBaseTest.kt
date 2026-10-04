package io.homeassistant.companion.android.common.assist

import android.app.Application
import android.content.pm.PackageManager
import io.homeassistant.companion.android.common.data.servers.ServerConnectionStateProvider
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.common.data.websocket.WebSocketRepository
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineError
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineEvent
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineEventType
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineIntentEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineRunStart
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineTtsEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.ConversationResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.ConversationSpeechPlainResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.ConversationSpeechResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.StreamTtsOutputResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.TtsOutputResponse
import io.homeassistant.companion.android.common.util.AudioUrlPlayer
import io.homeassistant.companion.android.common.util.AudioUsage
import io.homeassistant.companion.android.common.util.PlaybackState
import io.homeassistant.companion.android.common.util.VoiceAudioRecorder
import io.homeassistant.companion.android.common.util.toAudioBytes
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherJUnit5Extension::class)
class AssistViewModelBaseTest {

    private lateinit var serverManager: ServerManager
    private lateinit var voiceAudioRecorder: VoiceAudioRecorder
    private lateinit var audioUrlPlayer: AudioUrlPlayer
    private lateinit var application: Application
    private lateinit var webSocketRepository: WebSocketRepository
    private lateinit var audioDataFlow: MutableSharedFlow<ShortArray>
    private lateinit var pipelineEventsFlow: MutableSharedFlow<AssistPipelineEvent>

    private lateinit var viewModel: TestAssistViewModel

    @BeforeEach
    fun setUp() {
        serverManager = mockk(relaxed = true)
        voiceAudioRecorder = mockk(relaxed = true)
        audioUrlPlayer = mockk(relaxed = true)
        application = mockk(relaxed = true)
        webSocketRepository = mockk(relaxed = true)

        audioDataFlow = MutableSharedFlow(extraBufferCapacity = 10)
        pipelineEventsFlow = MutableSharedFlow(extraBufferCapacity = 10)

        val packageManager = mockk<PackageManager>()
        every { application.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) } returns true

        coEvery { voiceAudioRecorder.audioData() } returns audioDataFlow
        coEvery { serverManager.webSocketRepository(any()) } returns webSocketRepository
        coEvery { webSocketRepository.sendVoiceData(any(), any()) } returns true
        coEvery {
            webSocketRepository.runAssistPipelineForVoice(any(), any(), any(), any(), any())
        } returns pipelineEventsFlow

        viewModel = TestAssistViewModel(
            serverManager = serverManager,
            audioStrategy = DefaultAssistAudioStrategy(voiceAudioRecorder),
            audioUrlPlayer = audioUrlPlayer,
            application = application,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `Given recorder setup When pipeline sends RUN_START and STT_START Then buffered audio is forwarded`() = runTest {
        val samples1 = shortArrayOf(1, 2, 3)
        val samples2 = shortArrayOf(4, 5, 6)
        val handlerId = 42

        // Setup recorder and start pipeline
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        // Emit audio data (should be buffered)
        audioDataFlow.emit(samples1)
        audioDataFlow.emit(samples2)
        advanceUntilIdle()

        // No data should be sent yet
        coVerify(exactly = 0) { webSocketRepository.sendVoiceData(any(), any()) }

        // Send RUN_START event with handler ID
        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        advanceUntilIdle()

        // Still no data - waiting for STT_START
        coVerify(exactly = 0) { webSocketRepository.sendVoiceData(any(), any()) }

        // Send STT_START event
        pipelineEventsFlow.emit(createSttStartEvent())
        advanceUntilIdle()

        // Now buffered data should be forwarded (converted to bytes via toAudioBytes)
        coVerifyOrder {
            webSocketRepository.sendVoiceData(handlerId, samples1.toAudioBytes())
            webSocketRepository.sendVoiceData(handlerId, samples2.toAudioBytes())
        }
    }

    @Test
    fun `Given pipeline started When new audio arrives after STT_START Then it is forwarded immediately`() = runTest {
        val handlerId = 42
        val samples = shortArrayOf(1, 2, 3)

        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        // Start pipeline with RUN_START and STT_START
        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        pipelineEventsFlow.emit(createSttStartEvent())
        advanceUntilIdle()

        // Emit new audio
        audioDataFlow.emit(samples)
        advanceUntilIdle()

        // Should be forwarded immediately (converted to bytes)
        coVerify { webSocketRepository.sendVoiceData(handlerId, samples.toAudioBytes()) }
    }

    @Test
    fun `Given active recording When stopRecording with sendRecorded true Then buffer is drained and empty byte array is sent`() = runTest {
        val handlerId = 42
        val samples = shortArrayOf(1, 2, 3)

        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        // Buffer some data
        audioDataFlow.emit(samples)
        advanceUntilIdle()

        // Start STT via pipeline events
        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        pipelineEventsFlow.emit(createSttStartEvent())
        advanceUntilIdle()

        // Stop recording
        viewModel.callStopRecording(sendRecorded = true)
        advanceUntilIdle()

        // Verify order: buffered data (as bytes), then empty byte array
        coVerifyOrder {
            webSocketRepository.sendVoiceData(handlerId, samples.toAudioBytes())
            webSocketRepository.sendVoiceData(handlerId, byteArrayOf())
        }
    }

    @Test
    fun `Given active recording When stopRecording with sendRecorded false Then no data is sent`() = runTest {
        val handlerId = 42
        val samples = shortArrayOf(1, 2, 3)

        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        // Buffer some data before STT starts
        audioDataFlow.emit(samples)
        advanceUntilIdle()

        // Send RUN_START to set handler ID but not STT_START (simulating early cancellation)
        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        advanceUntilIdle()

        // Stop recording without sending
        viewModel.callStopRecording(sendRecorded = false)
        advanceUntilIdle()

        // No voice data should be sent
        coVerify(exactly = 0) { webSocketRepository.sendVoiceData(any(), any()) }
    }

    @Test
    fun `Given no binaryHandlerId When stopRecording Then jobs are cancelled without sending`() = runTest {
        viewModel.setupRecorder()
        advanceUntilIdle()

        audioDataFlow.emit(shortArrayOf(1, 2, 3))
        advanceUntilIdle()

        // Stop without ever receiving RUN_START (no handler ID)
        viewModel.callStopRecording(sendRecorded = true)
        advanceUntilIdle()

        // No voice data should be sent since there's no handler
        coVerify(exactly = 0) { webSocketRepository.sendVoiceData(any(), any()) }
    }

    @Test
    fun `Given multiple audio chunks When STT starts Then all chunks are forwarded in order`() = runTest {
        val handlerId = 42
        val chunks = (1..10).map { shortArrayOf(it.toShort()) }

        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        // Buffer multiple chunks
        chunks.forEach { audioDataFlow.emit(it) }
        advanceUntilIdle()

        // Start STT via pipeline events
        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        pipelineEventsFlow.emit(createSttStartEvent())
        advanceUntilIdle()

        // Verify all chunks were sent in order (converted to bytes)
        coVerifyOrder {
            chunks.forEach { chunk ->
                webSocketRepository.sendVoiceData(handlerId, chunk.toAudioBytes())
            }
        }
    }

    @Test
    fun `Given voice pipeline When RUN_START received Then PipelineStarted event is emitted`() = runTest {
        val handlerId = 42
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        advanceUntilIdle()

        assertEquals(listOf(AssistEvent.PipelineStarted), viewModel.receivedEvents)
    }

    @Test
    fun `Given voice pipeline When duplicate wake-up error received Then Dismiss event is emitted`() = runTest {
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createErrorEvent(code = "duplicate_wake_up_detected"))
        advanceUntilIdle()

        assertEquals(listOf(AssistEvent.Dismiss), viewModel.receivedEvents)
    }

    @Test
    fun `Given wake word phrase When voice pipeline started Then phrase is passed to WebSocket`() = runTest {
        val wakePhrase = "Hey Jarvis"

        viewModel.setupRecorder()
        viewModel.runVoicePipeline(wakeWordPhrase = wakePhrase)
        advanceUntilIdle()

        coVerify {
            webSocketRepository.runAssistPipelineForVoice(
                sampleRate = any(),
                outputTts = any(),
                pipelineId = any(),
                conversationId = any(),
                wakeWordPhrase = wakePhrase,
            )
        }
    }

    @Test
    fun `Given no wake word phrase When voice pipeline started Then phrase is null in WebSocket call`() = runTest {
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        coVerify {
            webSocketRepository.runAssistPipelineForVoice(
                sampleRate = any(),
                outputTts = any(),
                pipelineId = any(),
                conversationId = any(),
                wakeWordPhrase = null,
            )
        }
    }

    @Test
    fun `Given external audio strategy When pipeline runs Then external audio is forwarded`() = runTest {
        val externalAudioFlow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 10)
        val externalStrategy = object : AssistAudioStrategy {
            override suspend fun audioData() = externalAudioFlow
            override val wakeWordDetected: Flow<String> = emptyFlow()
            override fun requestFocus() {}
            override fun abandonFocus() {}
        }
        val externalVm = TestAssistViewModel(
            serverManager = serverManager,
            audioStrategy = externalStrategy,
            audioUrlPlayer = audioUrlPlayer,
            application = application,
        )

        val handlerId = 42
        val samples = shortArrayOf(10, 20, 30)

        externalVm.setupRecorder()
        externalVm.runVoicePipeline()
        advanceUntilIdle()

        // Emit via external audio source
        externalAudioFlow.emit(samples)
        advanceUntilIdle()

        // Start STT
        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        pipelineEventsFlow.emit(createSttStartEvent())
        advanceUntilIdle()

        // External audio should be forwarded to pipeline (converted to bytes)
        coVerify { webSocketRepository.sendVoiceData(handlerId, samples.toAudioBytes()) }
    }

    @Test
    fun `Given external audio strategy When stopRecording called Then strategy abandonFocus is called`() = runTest {
        var focusAbandoned = false
        val externalStrategy = object : AssistAudioStrategy {
            override suspend fun audioData() = MutableSharedFlow<ShortArray>()
            override val wakeWordDetected: Flow<String> = emptyFlow()
            override fun requestFocus() {}
            override fun abandonFocus() {
                focusAbandoned = true
            }
        }
        val externalVm = TestAssistViewModel(
            serverManager = serverManager,
            audioStrategy = externalStrategy,
            audioUrlPlayer = audioUrlPlayer,
            application = application,
        )

        externalVm.setupRecorder()
        advanceUntilIdle()

        externalVm.callStopRecording(sendRecorded = false)
        advanceUntilIdle()

        // The external strategy's abandonFocus should be called
        assertTrue(focusAbandoned, "External strategy abandonFocus should be called")
    }

    @Test
    fun `Given audio collection fails When recorder is running Then onError is called`() = runTest {
        val audioError = RuntimeException("Microphone access lost")
        val errorStrategy = object : AssistAudioStrategy {
            override suspend fun audioData() = kotlinx.coroutines.flow.flow<ShortArray> { throw audioError }
            override val wakeWordDetected: Flow<String> = emptyFlow()
            override fun requestFocus() {}
            override fun abandonFocus() {}
        }
        val viewModel = TestAssistViewModel(
            serverManager = serverManager,
            audioStrategy = errorStrategy,
            audioUrlPlayer = audioUrlPlayer,
            application = application,
        )

        viewModel.setupRecorder()
        advanceUntilIdle()

        assertEquals(1, viewModel.receivedErrors.size)
        assertEquals("Microphone access lost", viewModel.receivedErrors.first().message)
    }

    @Test
    fun `Given sending voice data fails When consumer forwards audio Then onError is called`() = runTest {
        val handlerId = 42
        val sendError = RuntimeException("WebSocket disconnected")
        coEvery { webSocketRepository.sendVoiceData(any(), any()) } throws sendError

        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        audioDataFlow.emit(shortArrayOf(1, 2, 3))
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(handlerId))
        pipelineEventsFlow.emit(createSttStartEvent())
        advanceUntilIdle()

        assertEquals(1, viewModel.receivedErrors.size)
        assertEquals("WebSocket disconnected", viewModel.receivedErrors.first().message)
    }

    @Test
    fun `Given voice pipeline without TTS When RUN_END received Then TurnFinished is emitted once after PipelineEnded`() = runTest {
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42))
        pipelineEventsFlow.emit(createRunEndEvent())
        advanceUntilIdle()

        assertEquals(
            listOf(AssistEvent.PipelineStarted, AssistEvent.PipelineEnded, AssistEvent.TurnFinished),
            viewModel.receivedEvents,
        )
    }

    @Test
    fun `Given streamed TTS still playing When RUN_END received Then TurnFinished is emitted only after STOP_PLAYING`() = runTest {
        val playbackStates = MutableSharedFlow<PlaybackState>()
        setupPlayback(playbackStates)
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42, ttsUrl = "/api/tts_proxy/stream.mp3"))
        advanceUntilIdle()
        playbackStates.emit(PlaybackState.PLAYING)
        pipelineEventsFlow.emit(createRunEndEvent())
        advanceUntilIdle()

        assertFalse(AssistEvent.TurnFinished in viewModel.receivedEvents)

        playbackStates.emit(PlaybackState.STOP_PLAYING)
        advanceUntilIdle()

        assertEquals(
            listOf(
                AssistEvent.PipelineStarted,
                AssistEvent.PipelineEnded,
                AssistEvent.PlaybackFinished,
                AssistEvent.TurnFinished,
            ),
            viewModel.receivedEvents,
        )
    }

    @Test
    fun `Given continue conversation When playback finishes after RUN_END Then ContinueConversation is emitted without TurnFinished`() = runTest {
        val playbackStates = MutableSharedFlow<PlaybackState>()
        setupPlayback(playbackStates)
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42))
        pipelineEventsFlow.emit(createIntentEndEvent(continueConversation = true))
        pipelineEventsFlow.emit(createTtsEndEvent())
        advanceUntilIdle()
        pipelineEventsFlow.emit(createRunEndEvent())
        advanceUntilIdle()
        playbackStates.emit(PlaybackState.STOP_PLAYING)
        advanceUntilIdle()

        assertTrue(AssistEvent.ContinueConversation in viewModel.receivedEvents)
        assertFalse(AssistEvent.TurnFinished in viewModel.receivedEvents)
    }

    @Test
    fun `Given voice pipeline When error with message received Then TurnFinished is emitted after the error`() = runTest {
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createErrorEvent(code = "stt-no-text-recognized", message = "No text recognized"))
        advanceUntilIdle()

        assertEquals(2, viewModel.receivedEvents.size)
        assertTrue(viewModel.receivedEvents[0] is AssistEvent.Message.Error)
        assertEquals(AssistEvent.TurnFinished, viewModel.receivedEvents[1])
    }

    @Test
    fun `Given pipeline fails to start When running the pipeline Then TurnFinished is emitted`() = runTest {
        coEvery {
            webSocketRepository.runAssistPipelineForVoice(any(), any(), any(), any(), any())
        } throws IllegalStateException("Not connected")

        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        assertEquals(AssistEvent.TurnFinished, viewModel.receivedEvents.last())
    }

    @Test
    fun `Given player completes without STOP_PLAYING When TTS_END received Then PlaybackFinished and TurnFinished are emitted`() = runTest {
        setupPlayback(emptyFlow())
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42))
        pipelineEventsFlow.emit(createTtsEndEvent())
        advanceUntilIdle()
        pipelineEventsFlow.emit(createRunEndEvent())
        advanceUntilIdle()

        assertEquals(
            listOf(
                AssistEvent.PipelineStarted,
                AssistEvent.PlaybackFinished,
                AssistEvent.PipelineEnded,
                AssistEvent.TurnFinished,
            ),
            viewModel.receivedEvents,
        )
    }

    @Test
    fun `Given input route not ready When recorder is set up Then audio is collected only once the route is ready`() = runTest {
        val inputRoute = CompletableDeferred<Unit>()
        val routedViewModel = TestAssistViewModel(
            serverManager = serverManager,
            audioStrategy = DefaultAssistAudioStrategy(voiceAudioRecorder),
            audioUrlPlayer = audioUrlPlayer,
            application = application,
            inputRoute = inputRoute,
        )

        routedViewModel.setupRecorder()
        advanceUntilIdle()

        coVerify(exactly = 0) { voiceAudioRecorder.audioData() }

        inputRoute.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { voiceAudioRecorder.audioData() }
    }

    @Test
    fun `Given TTS playback usage overridden When TTS is played Then the usage is passed to the player`() = runTest {
        setupPlayback(flowOf(PlaybackState.PLAYING, PlaybackState.STOP_PLAYING))
        viewModel.playbackUsage = AudioUsage.VOICE_COMMUNICATION
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42, ttsUrl = "/api/tts_proxy/stream.mp3"))
        advanceUntilIdle()

        verify { audioUrlPlayer.playAudio(any(), AudioUsage.VOICE_COMMUNICATION) }
    }

    @Test
    fun `Given TTS playback tail When playback ends after RUN_END Then PlaybackFinished and TurnFinished wait for the tail`() = runTest {
        val playbackStates = MutableSharedFlow<PlaybackState>()
        setupPlayback(playbackStates)
        viewModel.playbackTail = 1.seconds
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42, ttsUrl = "/api/tts_proxy/stream.mp3"))
        advanceUntilIdle()
        playbackStates.emit(PlaybackState.PLAYING)
        pipelineEventsFlow.emit(createRunEndEvent())
        advanceUntilIdle()
        playbackStates.emit(PlaybackState.STOP_PLAYING)
        runCurrent()
        advanceTimeBy(999.milliseconds)

        assertEquals(listOf(AssistEvent.PipelineStarted, AssistEvent.PipelineEnded), viewModel.receivedEvents)
        assertTrue(viewModel.isPlaying)

        advanceTimeBy(2.milliseconds)

        assertEquals(
            listOf(
                AssistEvent.PipelineStarted,
                AssistEvent.PipelineEnded,
                AssistEvent.PlaybackFinished,
                AssistEvent.TurnFinished,
            ),
            viewModel.receivedEvents,
        )
        assertFalse(viewModel.isPlaying)
    }

    @Test
    fun `Given TTS playback tail When the player ends without playing Then the turn finishes without waiting`() = runTest {
        setupPlayback(flowOf(PlaybackState.STOP_PLAYING))
        viewModel.playbackTail = 1.seconds
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42, ttsUrl = "/api/tts_proxy/stream.mp3"))
        pipelineEventsFlow.emit(createRunEndEvent())
        runCurrent()

        assertEquals(AssistEvent.TurnFinished, viewModel.receivedEvents.last())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `Given TTS playback tail When playback is stopped during the tail Then PlaybackFinished is not emitted`() = runTest {
        setupPlayback(flowOf(PlaybackState.PLAYING, PlaybackState.STOP_PLAYING))
        viewModel.playbackTail = 1.seconds
        viewModel.setupRecorder()
        viewModel.runVoicePipeline()
        advanceUntilIdle()

        pipelineEventsFlow.emit(createRunStartEvent(42, ttsUrl = "/api/tts_proxy/stream.mp3"))
        runCurrent()
        viewModel.callStopPlayback()
        advanceUntilIdle()

        assertFalse(AssistEvent.PlaybackFinished in viewModel.receivedEvents)
        assertFalse(viewModel.isPlaying)
    }

    private fun setupPlayback(playbackStates: Flow<PlaybackState>) {
        val connectionStateProvider = mockk<ServerConnectionStateProvider>()
        coEvery { serverManager.connectionStateProvider(any()) } returns connectionStateProvider
        every { connectionStateProvider.urlFlow(anyNullable()) } returns flowOf(UrlState.HasUrl(URL("http://ha.local")))
        every { audioUrlPlayer.playAudio(any(), any()) } returns playbackStates
    }

    private fun createRunStartEvent(handlerId: Int): AssistPipelineEvent {
        return AssistPipelineEvent(
            type = AssistPipelineEventType.RUN_START,
            data = AssistPipelineRunStart(
                pipeline = "test-pipeline",
                language = "en",
                runnerData = mapOf("stt_binary_handler_id" to handlerId),
            ),
        )
    }

    private fun createRunStartEvent(handlerId: Int, ttsUrl: String): AssistPipelineEvent {
        return AssistPipelineEvent(
            type = AssistPipelineEventType.RUN_START,
            data = AssistPipelineRunStart(
                pipeline = "test-pipeline",
                language = "en",
                runnerData = mapOf("stt_binary_handler_id" to handlerId),
                ttsOutput = StreamTtsOutputResponse(mimeType = "audio/mpeg", url = ttsUrl, streamResponse = true),
            ),
        )
    }

    private fun createIntentEndEvent(continueConversation: Boolean): AssistPipelineEvent {
        return AssistPipelineEvent(
            type = AssistPipelineEventType.INTENT_END,
            data = AssistPipelineIntentEnd(
                intentOutput = ConversationResponse(
                    response = ConversationSpeechResponse(
                        speech = ConversationSpeechPlainResponse(plain = mapOf("speech" to "Which room?")),
                    ),
                    conversationId = "conversation",
                    continueConversation = continueConversation,
                ),
            ),
        )
    }

    private fun createTtsEndEvent(): AssistPipelineEvent {
        return AssistPipelineEvent(
            type = AssistPipelineEventType.TTS_END,
            data = AssistPipelineTtsEnd(
                ttsOutput = TtsOutputResponse(mimeType = "audio/mpeg", url = "/api/tts_proxy/answer.mp3"),
            ),
        )
    }

    private fun createRunEndEvent(): AssistPipelineEvent {
        return AssistPipelineEvent(
            type = AssistPipelineEventType.RUN_END,
            data = null,
        )
    }

    private fun createSttStartEvent(): AssistPipelineEvent {
        return AssistPipelineEvent(
            type = AssistPipelineEventType.STT_START,
            data = null,
        )
    }

    private fun createErrorEvent(code: String, message: String? = null): AssistPipelineEvent {
        return AssistPipelineEvent(
            type = AssistPipelineEventType.ERROR,
            data = AssistPipelineError(code = code, message = message),
        )
    }

    /**
     * Test implementation of AssistViewModelBase for testing purposes.
     */
    private class TestAssistViewModel(
        serverManager: ServerManager,
        audioStrategy: AssistAudioStrategy,
        audioUrlPlayer: AudioUrlPlayer,
        application: Application,
        private val inputRoute: CompletableDeferred<Unit>? = null,
    ) : AssistViewModelBase(serverManager, audioStrategy, audioUrlPlayer, application) {

        private var inputMode: AssistInputMode? = null

        var playbackUsage = AudioUsage.ASSISTANT
        var playbackTail = Duration.ZERO

        val isPlaying: Boolean
            get() = isPlayingAudio

        override val ttsPlaybackUsage: AudioUsage
            get() = playbackUsage

        override val ttsPlaybackTail: Duration
            get() = playbackTail

        override suspend fun awaitInputRoute() {
            inputRoute?.await()
        }

        override fun getInput(): AssistInputMode? = inputMode

        override fun setInput(inputMode: AssistInputMode) {
            this.inputMode = inputMode
        }

        val receivedEvents = mutableListOf<AssistEvent>()
        val receivedErrors = mutableListOf<Throwable>()

        fun setupRecorder() {
            setupRecorder(onError = { receivedErrors += it })
        }

        fun runVoicePipeline(
            pipeline: AssistPipelineResponse? = null,
            wakeWordPhrase: String? = null,
        ) {
            runAssistPipelineInternal(
                text = null, // null means voice pipeline
                pipeline = pipeline,
                wakeWordPhrase = wakeWordPhrase,
                onEvent = { receivedEvents += it },
            )
        }

        fun callStopRecording(sendRecorded: Boolean = true) {
            stopRecording(sendRecorded)
        }

        fun callStopPlayback() {
            stopPlayback()
        }
    }
}
