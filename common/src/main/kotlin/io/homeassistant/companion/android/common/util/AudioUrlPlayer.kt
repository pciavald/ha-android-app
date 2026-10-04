package io.homeassistant.companion.android.common.util

import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.media.AudioManager.STREAM_VOICE_CALL
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

/**
 * Represents the playback state of the audio player.
 */
enum class PlaybackState {
    /** Player is ready to play but hasn't started yet. */
    READY,

    /** Player is actively playing audio. */
    PLAYING,

    /** Player stopped playing - either finished, stream ended, or encountered an error. */
    STOP_PLAYING,
}

/**
 * Audio usage of a playback, deciding how the system routes it and which volume applies.
 */
enum class AudioUsage {
    /** Regular media playback, on the music stream. */
    MEDIA,

    /** Assistant answers, routed like media (never to a Bluetooth SCO headset). */
    ASSISTANT,

    /** Speech for an ongoing voice session, routed to the communication device (e.g. Bluetooth SCO). */
    VOICE_COMMUNICATION,
}

/**
 * Simple interface for playing streaming audio from URLs.
 */
class AudioUrlPlayer @VisibleForTesting constructor(
    private val audioManager: AudioManager?,
    private val playerCreator: suspend (ExoPlayer.() -> Unit) -> ExoPlayer,
) {

    constructor(
        context: Context,
        audioManager: AudioManager?,
        dataSourceFactoryProvider: SuspendProvider<DataSource.Factory>,
    ) : this(
        audioManager,
        {
            val player = initializePlayer(context, dataSourceFactoryProvider())
            player.apply(it)
        },
    )

    /**
     * Streams and plays audio from the provided [url].
     *
     * The player starts when the Flow is collected. The flow ensures that all interactions with the
     * player happen on the main thread.
     *
     * The Flow emits [PlaybackState] changes and completes if an error occurs or the upstream ends.
     * If the [audioManager] is null or the current volume of the stream matching [usage] is 0
     * ([STREAM_VOICE_CALL] for [AudioUsage.VOICE_COMMUNICATION], [STREAM_MUSIC] otherwise),
     * the Flow completes immediately without playing.
     *
     * The player is properly released once the flow is canceled.
     *
     * @param url the URL to stream audio from
     * @param usage the audio usage of the playback, deciding its routing and focus attributes
     * @return a Flow that emits [PlaybackState] changes
     */
    @OptIn(UnstableApi::class)
    fun playAudio(url: String, usage: AudioUsage = AudioUsage.ASSISTANT): Flow<PlaybackState> = callbackFlow {
        if (!canPlay(usage)) {
            close()
            return@callbackFlow
        }
        var request: AudioFocusRequestCompat? = null

        val player = playerCreator {
            setAudioAttributes(
                buildAudioAttributes(usage),
                false, // handleAudioFocus doesn't support USAGE_ASSISTANT
            )

            var hasStartedPlayback = false

            addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                if (!hasStartedPlayback) {
                                    hasStartedPlayback = true
                                    trySend(PlaybackState.READY)
                                    request = requestFocus(usage)
                                    play()
                                    trySend(PlaybackState.PLAYING)
                                }
                            }

                            Player.STATE_BUFFERING -> {
                                if (hasStartedPlayback) {
                                    Timber.d("Stream buffering - no data currently available")
                                }
                            }

                            Player.STATE_ENDED -> {
                                Timber.d("Stream playback ended")
                                trySend(PlaybackState.STOP_PLAYING)
                            }

                            Player.STATE_IDLE -> {
                                // Player is idle, nothing to do
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val isStreamEnded = hasEofException(error)
                        if (isStreamEnded) {
                            Timber.d("Stream ended - no more data from server")
                            trySend(PlaybackState.STOP_PLAYING)
                        } else {
                            Timber.e(error, "ExoPlayer encountered error")
                        }
                        releasePlayer(this@playerCreator, request)
                        close()
                    }
                },
            )

            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }

        awaitClose {
            Timber.d("Flow closed, releasing player")
            releasePlayer(player, request)
        }
    }.flowOn(Dispatchers.Main)

    private fun canPlay(usage: AudioUsage): Boolean {
        val stream = if (usage == AudioUsage.VOICE_COMMUNICATION) STREAM_VOICE_CALL else STREAM_MUSIC
        return try {
            audioManager != null && audioManager.getStreamVolume(stream) != 0
        } catch (e: RuntimeException) {
            Timber.e(e, "Couldn't get stream volume")
            true
        }
    }

    private fun buildAudioAttributes(usage: AudioUsage): AudioAttributes = AudioAttributes.Builder()
        .setContentType(
            when (usage) {
                AudioUsage.MEDIA -> C.AUDIO_CONTENT_TYPE_MUSIC
                AudioUsage.ASSISTANT, AudioUsage.VOICE_COMMUNICATION -> C.AUDIO_CONTENT_TYPE_SPEECH
            },
        )
        .setUsage(
            when (usage) {
                AudioUsage.MEDIA -> C.USAGE_MEDIA
                AudioUsage.ASSISTANT -> C.USAGE_ASSISTANT
                AudioUsage.VOICE_COMMUNICATION -> C.USAGE_VOICE_COMMUNICATION
            },
        )
        .build()

    private fun releasePlayer(player: Player, request: AudioFocusRequestCompat?) {
        player.release()
        abandonFocus(request)
    }

    private fun requestFocus(usage: AudioUsage): AudioFocusRequestCompat? {
        if (audioManager == null) return null
        val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(buildAudioAttributesCompat(usage))
            .setOnAudioFocusChangeListener { /* Focus changes are ignored */ }
            .build()

        return try {
            AudioManagerCompat.requestAudioFocus(audioManager, request)
            request
        } catch (e: Exception) {
            // Focus request failed, continue without audio focus
            Timber.w(e, "Failed to request focus")
            null
        }
    }

    private fun buildAudioAttributesCompat(usage: AudioUsage): AudioAttributesCompat =
        AudioAttributesCompat.Builder()
            .setUsage(
                when (usage) {
                    AudioUsage.MEDIA -> AudioAttributesCompat.USAGE_MEDIA
                    AudioUsage.ASSISTANT -> AudioAttributesCompat.USAGE_ASSISTANT
                    AudioUsage.VOICE_COMMUNICATION -> AudioAttributesCompat.USAGE_VOICE_COMMUNICATION
                },
            )
            .setContentType(
                when (usage) {
                    AudioUsage.MEDIA -> AudioAttributesCompat.CONTENT_TYPE_MUSIC
                    AudioUsage.ASSISTANT,
                    AudioUsage.VOICE_COMMUNICATION,
                    -> AudioAttributesCompat.CONTENT_TYPE_SPEECH
                },
            )
            .build()

    private fun abandonFocus(requestCompat: AudioFocusRequestCompat?) {
        if (audioManager == null || requestCompat == null) return
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, requestCompat)
    }

    /**
     * Checks if the exception chain contains an EOFException, indicating the stream ended.
     */
    private fun hasEofException(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { it is java.io.EOFException }
}
