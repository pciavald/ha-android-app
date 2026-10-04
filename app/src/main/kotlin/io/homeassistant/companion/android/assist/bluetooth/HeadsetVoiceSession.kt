package io.homeassistant.companion.android.assist.bluetooth

import android.bluetooth.BluetoothDevice
import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@VisibleForTesting
internal val HEADSET_PROXY_TIMEOUT = 1.seconds

@VisibleForTesting
internal val HEADSET_AUDIO_ROUTE_TIMEOUT = 3.seconds

/**
 * Voice recognition session with a Bluetooth hands-free headset (or watch) that asked the phone
 * for its assistant (AT+BVRA=1).
 *
 * The Bluetooth stack only waits a few seconds for the assistant to accept the request: [start]
 * must be called as soon as the voice command arrives. Once started, the stack opens the SCO audio
 * link itself, so the microphone and voice communication audio go through the headset.
 */
interface HeadsetVoiceSession {

    /** Whether a session was started and not stopped yet. */
    val isActive: Boolean

    /** Emits when the headset closed the audio link on its own (e.g. the user hung up on the watch). */
    val endedByHeadset: Flow<Unit>

    /**
     * Accepts the voice recognition request of the connected headset.
     *
     * @return true if the session started, false when it could not (no permission, no headset,
     * request refused or expired), in which case Assist keeps using the phone audio
     */
    suspend fun start(): Boolean

    /**
     * Waits until the headset audio link is connected so that recording captures the headset
     * microphone. Returns false if it is still not connected after [timeout].
     */
    suspend fun awaitAudioRoute(timeout: Duration = HEADSET_AUDIO_ROUTE_TIMEOUT): Boolean

    /** Ends the session and releases the headset, if one is active. Safe to call several times. */
    fun stop()
}

internal class HeadsetVoiceSessionImpl @Inject constructor(private val platform: HeadsetPlatform) :
    HeadsetVoiceSession {

    private var profile: HeadsetProfile? = null
    private var device: BluetoothDevice? = null
    private var unregisterReceiver: (() -> Unit)? = null
    private var removeRouteListener: (() -> Unit)? = null

    /** Whether the SCO link was seen connected, so that a disconnection means the headset hung up. */
    private var audioConnected = false
    private val routeReady = MutableStateFlow(false)
    private val endedByHeadsetFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val isActive: Boolean
        get() = device != null

    override val endedByHeadset: Flow<Unit> = endedByHeadsetFlow.asSharedFlow()

    override suspend fun start(): Boolean {
        if (isActive) return true
        if (!platform.hasConnectPermission()) {
            Timber.w("Missing Bluetooth connect permission, headset voice recognition not started")
            return false
        }
        val headset = openProfile() ?: run {
            Timber.w("Bluetooth headset profile unavailable")
            return false
        }
        val target = runCatchingSecurity { selectDevice(headset) }
        if (target == null) {
            Timber.w("No connected Bluetooth headset for voice recognition")
            headset.close()
            return false
        }

        profile = headset
        device = target
        // Registered before starting so that the SCO connection triggered by the start is not missed
        unregisterReceiver = platform.registerAudioStateReceiver(::onAudioStateChanged)
        if (runCatchingSecurity { headset.startVoiceRecognition(target) } != true) {
            Timber.w("Bluetooth headset refused to start voice recognition")
            release()
            return false
        }
        Timber.d("Headset voice recognition started")
        return true
    }

    override suspend fun awaitAudioRoute(timeout: Duration): Boolean {
        if (!isActive) return false
        if (removeRouteListener == null) {
            removeRouteListener = platform.addScoRouteListener { isSco ->
                if (isSco) routeReady.value = true
            }
        }
        val ready = withTimeoutOrNull(timeout) { routeReady.first { it } } != null
        if (!ready) Timber.w("Headset audio not connected after $timeout, recording anyway")
        return ready
    }

    override fun stop() {
        val headset = profile
        val target = device
        if (headset != null && target != null) {
            runCatchingSecurity { headset.stopVoiceRecognition(target) }
            Timber.d("Headset voice recognition stopped")
        }
        release()
    }

    private fun release() {
        removeRouteListener?.invoke()
        removeRouteListener = null
        unregisterReceiver?.invoke()
        unregisterReceiver = null
        profile?.close()
        profile = null
        device = null
        audioConnected = false
        routeReady.value = false
    }

    private fun onAudioStateChanged(eventDevice: BluetoothDevice?, connected: Boolean) {
        val current = device ?: return
        if (eventDevice != null && eventDevice != current) return
        if (connected) {
            audioConnected = true
            routeReady.value = true
        } else if (audioConnected) {
            audioConnected = false
            routeReady.value = false
            endedByHeadsetFlow.tryEmit(Unit)
        }
    }

    private suspend fun openProfile(): HeadsetProfile? = withTimeoutOrNull(HEADSET_PROXY_TIMEOUT) {
        suspendCancellableCoroutine { continuation ->
            val requested = platform.openHeadsetProfile { opened ->
                if (continuation.isActive) {
                    continuation.resume(opened) { _, value, _ -> value.close() }
                } else {
                    opened.close()
                }
            }
            if (!requested) continuation.resume(null)
        }
    }

    /** Prefers a headset that reports voice recognition support, else the first connected one. */
    private fun selectDevice(headset: HeadsetProfile): BluetoothDevice? {
        val devices = headset.connectedDevices()
        return devices.firstOrNull { headset.isVoiceRecognitionSupported(it) } ?: devices.firstOrNull()
    }

    private inline fun <T> runCatchingSecurity(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        Timber.e(e, "Bluetooth headset call refused")
        null
    }
}
