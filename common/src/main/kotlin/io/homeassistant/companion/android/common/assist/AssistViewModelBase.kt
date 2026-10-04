package io.homeassistant.companion.android.common.assist

import android.app.Application
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.Companion.PROTECTED
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineError
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineEventType
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineIntentEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineIntentProgress
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineRunStart
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineSttEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineTtsEnd
import io.homeassistant.companion.android.common.util.AudioUrlPlayer
import io.homeassistant.companion.android.common.util.AudioUsage
import io.homeassistant.companion.android.common.util.FailFast
import io.homeassistant.companion.android.common.util.PlaybackState
import io.homeassistant.companion.android.common.util.VOICE_SAMPLE_RATE
import io.homeassistant.companion.android.common.util.toAudioBytes
import io.homeassistant.companion.android.util.UrlUtil
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface AssistEvent {
    sealed class Message(val message: String) : AssistEvent {
        class Input(message: String) : Message(message)
        class Output(message: String) : Message(message)
        class Error(message: String) : Message(message)
    }

    class MessageChunk(val chunk: String) : AssistEvent
    data object ContinueConversation : AssistEvent

    /** Signals that the pipeline has started processing and the UI can be shown */
    data object PipelineStarted : AssistEvent
    data object PipelineEnded : AssistEvent

    /** Signals that the Assist UI should be dismissed without showing an error */
    data object Dismiss : AssistEvent

    /** Signals that TTS audio playback has finished */
    data object PlaybackFinished : AssistEvent

    /**
     * Signals that the run is over and nothing else follows it: the pipeline ended, no TTS playback
     * is pending and the conversation does not continue. Emitted at most once per run.
     */
    data object TurnFinished : AssistEvent
}

abstract class AssistViewModelBase(
    protected val serverManager: ServerManager,
    protected val audioStrategy: AssistAudioStrategy,
    private val audioUrlPlayer: AudioUrlPlayer,
    application: Application,
) : AndroidViewModel(application) {

    companion object {
        const val PIPELINE_PREFERRED = "preferred"
        const val PIPELINE_LAST_USED = "last_used"
    }

    enum class AssistInputMode {
        TEXT,
        TEXT_ONLY,
        VOICE_INACTIVE,
        VOICE_ACTIVE,
        BLOCKED,
    }

    protected val app = application

    protected var selectedServerId = ServerManager.SERVER_ID_ACTIVE

    protected var recorderProactive = false

    /**
     * Parent job managing the audio recording pipeline. Contains both the producer
     * (collecting from [AssistAudioStrategy.audioData]) and consumer (forwarding to server).
     * Joining this job ensures all buffered audio has been sent before cleanup.
     */
    private var recorderJob: Job? = null

    /**
     * Child job that collects audio data from [AssistAudioStrategy.audioData] and
     * buffers them in a channel. Cancelled separately from [recorderJob] to stop
     * collecting while still allowing the consumer to drain buffered data.
     */
    private var producerJob: Job? = null

    /**
     * Signals when the server is ready to receive audio data. Completed with the
     * binary handler ID when [handleSttStart] is called. The consumer coroutine
     * awaits this before forwarding buffered audio to the server.
     */
    private var sttReady: CompletableDeferred<Int>? = null
    protected val hasMicrophone = app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    protected var hasPermission = false

    @VisibleForTesting
    var binaryHandlerId: Int? = null
    private var conversationId: String? = null
    private var continueConversation = AtomicBoolean(false)

    suspend fun isRegistered(): Boolean = serverManager.isRegistered()

    abstract fun getInput(): AssistInputMode?
    abstract fun setInput(inputMode: AssistInputMode)

    protected fun clearPipelineData() {
        binaryHandlerId = null
        conversationId = null
    }

    private var currentPlayAudioJob: Job? = null

    private var currentPathBeingPlayed: String? = null

    /** Whether TTS audio is currently being played back. Updated by playback handlers. */
    protected var isPlayingAudio = false

    /** Audio usage of the TTS playback, read when each playback starts. */
    protected open val ttsPlaybackUsage: AudioUsage
        get() = AudioUsage.ASSISTANT

    /**
     * Suspends until the audio input is routed to the expected device, before the recorder starts
     * capturing. Audio is buffered until the server is ready for it, so waiting here costs nothing
     * as long as it returns before STT starts.
     */
    protected open suspend fun awaitInputRoute() = Unit

    /**
     * Tracks the end of a single pipeline run so that [AssistEvent.TurnFinished] is emitted once,
     * only when the run ended, no playback is pending and the conversation does not continue.
     * All its state is touched from the main thread.
     */
    private class RunTurn(private val onEvent: (AssistEvent) -> Unit) {
        var runEnded = false
        var continued = false
        var playbackJob: Job? = null
        private var finished = false

        fun finish() {
            if (finished) return
            finished = true
            onEvent(AssistEvent.TurnFinished)
        }
    }

    /**
     * @param text input to run an intent pipeline with, or `null` to run a STT pipeline (check if
     * STT is supported _before_ calling this function)
     * @param pipeline information about the pipeline, or `null` to use the server's default
     * @param onEvent callback for events that should be use to update the UI
     */
    protected fun runAssistPipelineInternal(
        text: String?,
        pipeline: AssistPipelineResponse?,
        wakeWordPhrase: String? = null,
        onEvent: (AssistEvent) -> Unit,
    ) {
        val isVoice = text == null
        val turn = RunTurn(onEvent)
        var job: Job? = null
        job = viewModelScope.launch {
            val flow = try {
                if (isVoice) {
                    serverManager.webSocketRepository(selectedServerId).runAssistPipelineForVoice(
                        sampleRate = VOICE_SAMPLE_RATE,
                        outputTts = pipeline?.ttsEngine?.isNotBlank() == true,
                        pipelineId = pipeline?.id,
                        conversationId = conversationId,
                        wakeWordPhrase = wakeWordPhrase,
                    )
                } else {
                    serverManager.integrationRepository(selectedServerId).getAssistResponse(
                        text = text,
                        pipelineId = pipeline?.id,
                        conversationId = conversationId,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to start assist pipeline")
                null
            }

            flow?.collect { event ->
                when (event.type) {
                    AssistPipelineEventType.RUN_START -> {
                        handleRunStart(
                            event.data as? AssistPipelineRunStart,
                            isVoice,
                            turn,
                            onEvent,
                        )
                        onEvent(AssistEvent.PipelineStarted)
                    }

                    AssistPipelineEventType.STT_START -> handleSttStart()
                    AssistPipelineEventType.STT_END -> handleSttEnd(event.data as? AssistPipelineSttEnd, onEvent)
                    AssistPipelineEventType.INTENT_PROGRESS -> handleIntentProgress(
                        event.data as? AssistPipelineIntentProgress,
                        onEvent,
                    )

                    AssistPipelineEventType.INTENT_END -> handleIntentEnd(
                        event.data as? AssistPipelineIntentEnd,
                        onEvent,
                    )

                    AssistPipelineEventType.TTS_END -> handleTtsEnd(
                        event.data as? AssistPipelineTtsEnd,
                        isVoice,
                        turn,
                        onEvent,
                    )

                    AssistPipelineEventType.RUN_END -> {
                        stopRecording()
                        job?.cancel()
                        onEvent(AssistEvent.PipelineEnded)
                        handleRunEnd(turn)
                    }

                    AssistPipelineEventType.ERROR -> {
                        if (handleError(event.data as? AssistPipelineError, turn, onEvent)) job?.cancel()
                    }

                    else -> {
                        /*No op*/
                    }
                }
            } ?: run {
                onEvent(AssistEvent.Message.Output(app.getString(R.string.assist_error)))
                turn.finish()
            }
        }
    }

    private fun handleRunStart(
        data: AssistPipelineRunStart?,
        isVoice: Boolean,
        turn: RunTurn,
        onEvent: (AssistEvent) -> Unit,
    ) {
        if (!isVoice) return

        data?.ttsOutput?.let { ttsOutput ->
            val audioPath = ttsOutput.url
            val shouldPlay = currentPathBeingPlayed != audioPath || currentPlayAudioJob?.isActive != true
            if (audioPath.isNotBlank() && shouldPlay) {
                currentPathBeingPlayed = audioPath
                stopPlayback()
                launchPlayback(audioPath, markPlayingOnStart = false, turn, onEvent)
            }
        }

        binaryHandlerId = data?.runnerData?.get("stt_binary_handler_id") as? Int
    }

    private fun handleSttStart() {
        binaryHandlerId?.let { id ->
            sttReady?.complete(id)
        }
    }

    private fun handleSttEnd(data: AssistPipelineSttEnd?, onEvent: (AssistEvent) -> Unit) {
        stopRecording()
        data?.sttOutput?.get("text")?.let { text ->
            onEvent(AssistEvent.Message.Input(text as String))
        }
    }

    private fun handleIntentProgress(data: AssistPipelineIntentProgress?, onEvent: (AssistEvent) -> Unit) {
        data?.chatLogDelta?.content?.let { delta ->
            onEvent(AssistEvent.MessageChunk(delta))
        }
    }

    private fun handleIntentEnd(data: AssistPipelineIntentEnd?, onEvent: (AssistEvent) -> Unit) {
        val intentOutput = data?.intentOutput ?: return
        conversationId = intentOutput.conversationId
        continueConversation.set(intentOutput.continueConversation)
        intentOutput.response.speech?.plain?.get("speech")?.let { speech ->
            onEvent(AssistEvent.Message.Output(speech))
        }
    }

    /*
     * Handles TTS_END events for backward compatibility with servers that don't support
     * streaming TTS in RUN_START. If [currentPathBeingPlayed] is set, audio is already
     * playing from RUN_START and this handler is skipped.
     */
    private fun handleTtsEnd(
        data: AssistPipelineTtsEnd?,
        isVoice: Boolean,
        turn: RunTurn,
        onEvent: (AssistEvent) -> Unit,
    ) {
        if (!isVoice || currentPathBeingPlayed != null) return

        val audioPath = data?.ttsOutput?.url
        if (audioPath.isNullOrBlank()) {
            onPlaybackDone(turn, onEvent)
        } else {
            launchPlayback(audioPath, markPlayingOnStart = true, turn, onEvent)
        }
    }

    private fun handleRunEnd(turn: RunTurn) {
        turn.runEnded = true
        val playbackPending = turn.playbackJob?.isActive == true
        if (!playbackPending && !turn.continued && !continueConversation.get()) {
            turn.finish()
        }
    }

    /**
     * Plays the TTS answer at [audioPath]. Once the playback completes (without being cancelled),
     * emits [AssistEvent.PlaybackFinished] and either continues the conversation or finishes the turn.
     *
     * @param markPlayingOnStart whether [isPlayingAudio] is set before the player reports it plays
     */
    private fun launchPlayback(
        audioPath: String,
        markPlayingOnStart: Boolean,
        turn: RunTurn,
        onEvent: (AssistEvent) -> Unit,
    ) {
        val usage = ttsPlaybackUsage
        val playbackJob = viewModelScope.launch {
            if (markPlayingOnStart) isPlayingAudio = true
            try {
                playAudio(audioPath, usage).collect { state ->
                    if (state == PlaybackState.PLAYING) isPlayingAudio = true
                }
            } finally {
                isPlayingAudio = false
            }
            onEvent(AssistEvent.PlaybackFinished)
            onPlaybackDone(turn, onEvent)
        }
        currentPlayAudioJob = playbackJob
        turn.playbackJob = playbackJob
    }

    private fun onPlaybackDone(turn: RunTurn, onEvent: (AssistEvent) -> Unit) {
        if (notifyContinueConversationIfNeeded(onEvent)) {
            turn.continued = true
        } else if (turn.runEnded) {
            turn.finish()
        }
    }

    /**
     * Return true if we need to cancel the job
     */
    private fun handleError(data: AssistPipelineError?, turn: RunTurn, onEvent: (AssistEvent) -> Unit): Boolean {
        if (data?.isDuplicatedWakeWord == true) {
            Timber.d("Duplicate wake-up detected, dismissing Assist")
            onEvent(AssistEvent.Dismiss)
            stopRecording()
            return true
        }
        val errorMessage = data?.message ?: return false
        onEvent(AssistEvent.Message.Error(errorMessage))
        stopRecording()
        turn.finish()
        return true
    }

    /**
     * Sets up audio recording and buffering for voice input.
     *
     * Must be called before [runAssistPipelineInternal] for voice pipelines.
     * Audio data is buffered until the server is ready to receive, then all
     * buffered and subsequent audio is forwarded.
     *
     * Collects from [audioStrategy]'s [AssistAudioStrategy.audioData] flow, converts
     * each [ShortArray] chunk to bytes via [toAudioBytes], and sends them to the server.
     */
    @VisibleForTesting(otherwise = PROTECTED)
    fun setupRecorder(onError: (Throwable) -> Unit) {
        Timber.d("Setting up recorder")
        sttReady = CompletableDeferred()

        recorderJob = viewModelScope.launch {
            val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)

            producerJob = launch {
                awaitInputRoute()
                audioStrategy.audioData().catch {
                    Timber.e(it, "Error collecting audio data")
                    onError(it)
                }.collect { samples ->
                    audioChannel.send(samples.toAudioBytes())
                }
            }.apply {
                invokeOnCompletion {
                    audioChannel.close()
                }
            }

            // Consumer: wait for STT to be ready, then forward all buffered and new data
            val handlerId = sttReady?.await() ?: run {
                FailFast.fail { "sttReady not set" }
                producerJob?.cancel()
                audioChannel.close()
                return@launch
            }

            try {
                for (data in audioChannel) {
                    serverManager.webSocketRepository(selectedServerId).sendVoiceData(handlerId, data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error sending audio data to server")
                onError(e)
            }
        }
    }

    /**
     * Plays the audio at [path] on the server. The returned flow completes after the first
     * [PlaybackState.STOP_PLAYING], which is also emitted when the player ends without reporting it
     * (muted stream, player error) or when there is no URL to play from.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun playAudio(path: String, usage: AudioUsage): Flow<PlaybackState> {
        return serverManager.connectionStateProvider(selectedServerId).urlFlow().flatMapLatest { urlState ->
            val baseUrl = if (urlState is UrlState.HasUrl) {
                urlState.url
            } else {
                null
            }
            UrlUtil.handle(baseUrl, path)?.let { url ->
                audioUrlPlayer.playAudio(url.toString(), usage).onCompletion { cause ->
                    if (cause == null) emit(PlaybackState.STOP_PLAYING)
                }
            } ?: flowOf(PlaybackState.STOP_PLAYING)
        }.transformWhile { state ->
            emit(state)
            state != PlaybackState.STOP_PLAYING
        }
    }

    protected fun stopRecording(sendRecorded: Boolean = true) {
        stopAudioCapture()

        binaryHandlerId?.let { handlerId ->
            finalizeRecording(handlerId, sendRecorded)
        } ?: clearRecorderState()

        updateInputModeAfterRecording()
    }

    private fun stopAudioCapture() {
        audioStrategy.abandonFocus()
        producerJob?.cancel()
        producerJob = null
    }

    private fun finalizeRecording(handlerId: Int, sendRecorded: Boolean) {
        viewModelScope.launch {
            if (sendRecorded) {
                recorderJob?.join()
                try {
                    serverManager.webSocketRepository(selectedServerId).sendVoiceData(
                        handlerId,
                        byteArrayOf(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to finalize recording")
                }
            }
            clearRecorderState()
        }
    }

    private fun clearRecorderState() {
        recorderJob?.cancel()
        recorderJob = null
        sttReady = null
        binaryHandlerId = null
    }

    private fun updateInputModeAfterRecording() {
        if (getInput() == AssistInputMode.VOICE_ACTIVE) {
            setInput(if (recorderProactive) AssistInputMode.BLOCKED else AssistInputMode.VOICE_INACTIVE)
        }
        recorderProactive = false
    }

    protected fun stopPlayback() {
        currentPlayAudioJob?.cancel()
    }

    /**
     * Checks if the conversation should continue and notifies the UI if so.
     * Returns whether [AssistEvent.ContinueConversation] was emitted.
     * This is called after audio playback finishes to let the player complete before
     * recording a new entry from the user.
     */
    private fun notifyContinueConversationIfNeeded(onEvent: (AssistEvent) -> Unit): Boolean {
        if (continueConversation.getAndSet(false)) {
            onEvent(AssistEvent.ContinueConversation)
            return true
        }
        return false
    }
}
