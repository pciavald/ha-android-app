package io.homeassistant.companion.android.assist.bluetooth

import android.bluetooth.BluetoothDevice
import app.cash.turbine.test
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeadsetVoiceSessionImplTest {

    private val watch: BluetoothDevice = mockk()
    private val earbuds: BluetoothDevice = mockk()
    private val profile = FakeHeadsetProfile(devices = listOf(earbuds, watch), voiceRecognitionDevices = setOf(watch))
    private val platform = FakeHeadsetPlatform(profile)
    private val session = HeadsetVoiceSessionImpl(platform)

    @Test
    fun `Given no connect permission when start then returns false without any Bluetooth call`() = runTest {
        platform.hasPermission = false

        assertFalse(session.start())

        assertFalse(session.isActive)
        assertEquals(emptyList<String>(), platform.calls)
        assertEquals(emptyList<String>(), profile.calls)
    }

    @Test
    fun `Given connected headsets when start then starts voice recognition on the supporting device after registering the receiver`() = runTest {
        assertTrue(session.start())

        assertTrue(session.isActive)
        assertEquals(listOf("openHeadsetProfile", "registerAudioStateReceiver"), platform.calls)
        assertEquals(listOf("startVoiceRecognition" to watch), profile.recognitionCalls)
    }

    @Test
    fun `Given started session when awaiting the audio route then the communication device is only observed after starting`() = runTest {
        session.start()
        val startedAt = platform.log.indexOf("startVoiceRecognition")

        startAwaitingRoute()
        platform.emitAudioState(watch, connected = true)
        runCurrent()

        val routeAt = platform.log.indexOf("addScoRouteListener")
        assertTrue(startedAt >= 0)
        assertTrue(routeAt > startedAt, "Communication device used before startVoiceRecognition: ${platform.log}")
    }

    @Test
    fun `Given no headset supports voice recognition when start then uses the first connected device`() = runTest {
        val plainProfile = FakeHeadsetProfile(devices = listOf(earbuds, watch), voiceRecognitionDevices = emptySet())
        val plainSession = HeadsetVoiceSessionImpl(FakeHeadsetPlatform(plainProfile))

        assertTrue(plainSession.start())

        assertEquals(listOf("startVoiceRecognition" to earbuds), plainProfile.recognitionCalls)
    }

    @Test
    fun `Given no connected headset when start then returns false and closes the profile`() = runTest {
        val emptyProfile = FakeHeadsetProfile(devices = emptyList(), voiceRecognitionDevices = emptySet())
        val emptyPlatform = FakeHeadsetPlatform(emptyProfile)
        val emptySession = HeadsetVoiceSessionImpl(emptyPlatform)

        assertFalse(emptySession.start())

        assertEquals(1, emptyProfile.closeCount)
        assertEquals(listOf("openHeadsetProfile"), emptyPlatform.calls)
    }

    @Test
    fun `Given headset refuses voice recognition when start then returns false and releases everything`() = runTest {
        profile.startResult = false

        assertFalse(session.start())

        assertFalse(session.isActive)
        assertEquals(1, profile.closeCount)
        assertEquals(1, platform.unregisterCount)
    }

    @Test
    fun `Given headset profile never connects when start then returns false after the proxy timeout`() = runTest {
        platform.deliverProfile = false

        assertFalse(session.start())

        assertEquals(HEADSET_PROXY_TIMEOUT.inWholeMilliseconds, currentTime)
        assertFalse(session.isActive)
    }

    @Test
    fun `Given started session when the SCO audio connects then awaitAudioRoute resumes`() = runTest {
        session.start()
        val route = startAwaitingRoute()

        platform.emitAudioState(watch, connected = true)
        runCurrent()

        assertTrue(route.await())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `Given started session when the communication device becomes SCO then awaitAudioRoute resumes`() = runTest {
        session.start()
        val route = startAwaitingRoute()

        platform.emitScoRoute(true)
        runCurrent()

        assertTrue(route.await())
    }

    @Test
    fun `Given started session when the audio never connects then awaitAudioRoute returns false after the timeout`() = runTest {
        session.start()

        assertFalse(session.awaitAudioRoute())

        assertEquals(HEADSET_AUDIO_ROUTE_TIMEOUT.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `Given connected SCO audio when the headset disconnects it then endedByHeadset emits`() = runTest {
        session.start()

        session.endedByHeadset.test {
            platform.emitAudioState(watch, connected = false)
            expectNoEvents()

            platform.emitAudioState(watch, connected = true)
            platform.emitAudioState(earbuds, connected = false)
            expectNoEvents()

            platform.emitAudioState(watch, connected = false)
            awaitItem()
        }
    }

    @Test
    fun `Given started session when stop is called twice then voice recognition is stopped and released once`() = runTest {
        session.start()

        session.stop()
        session.stop()

        assertEquals(
            listOf("startVoiceRecognition" to watch, "stopVoiceRecognition" to watch),
            profile.recognitionCalls,
        )
        assertEquals(1, profile.closeCount)
        assertEquals(1, platform.unregisterCount)
        assertFalse(session.isActive)
    }

    @Test
    fun `Given no session when stop then nothing is called`() {
        session.stop()

        assertEquals(emptyList<Pair<String, BluetoothDevice>>(), profile.recognitionCalls)
        assertEquals(0, platform.unregisterCount)
    }

    private fun TestScope.startAwaitingRoute() = async { session.awaitAudioRoute() }.also { runCurrent() }

    private class FakeHeadsetProfile(
        private val devices: List<BluetoothDevice>,
        private val voiceRecognitionDevices: Set<BluetoothDevice>,
    ) : HeadsetProfile {
        var log: MutableList<String>? = null
        var startResult = true
        var closeCount = 0
        val calls = mutableListOf<String>()
        val recognitionCalls = mutableListOf<Pair<String, BluetoothDevice>>()

        override fun connectedDevices(): List<BluetoothDevice> = devices

        override fun isVoiceRecognitionSupported(device: BluetoothDevice): Boolean = device in voiceRecognitionDevices

        override fun startVoiceRecognition(device: BluetoothDevice): Boolean {
            record("startVoiceRecognition", device)
            return startResult
        }

        override fun stopVoiceRecognition(device: BluetoothDevice): Boolean {
            record("stopVoiceRecognition", device)
            return true
        }

        override fun close() {
            closeCount++
        }

        private fun record(name: String, device: BluetoothDevice) {
            calls += name
            recognitionCalls += name to device
            log?.add(name)
        }
    }

    private class FakeHeadsetPlatform(private val profile: FakeHeadsetProfile) : HeadsetPlatform {
        var hasPermission = true
        var deliverProfile = true
        var unregisterCount = 0
        val calls = mutableListOf<String>()

        /** Platform and profile calls in order. */
        val log = mutableListOf<String>()
        private var audioListener: ((BluetoothDevice?, Boolean) -> Unit)? = null
        private var routeListener: ((Boolean) -> Unit)? = null

        init {
            profile.log = log
        }

        override fun hasConnectPermission(): Boolean = hasPermission

        override fun openHeadsetProfile(onOpened: (HeadsetProfile) -> Unit): Boolean {
            record("openHeadsetProfile")
            if (deliverProfile) onOpened(profile)
            return true
        }

        override fun registerAudioStateReceiver(onChanged: (BluetoothDevice?, Boolean) -> Unit): () -> Unit {
            record("registerAudioStateReceiver")
            audioListener = onChanged
            return {
                unregisterCount++
                audioListener = null
            }
        }

        override fun addScoRouteListener(onChanged: (Boolean) -> Unit): (() -> Unit)? {
            record("addScoRouteListener")
            routeListener = onChanged
            return { routeListener = null }
        }

        fun emitAudioState(device: BluetoothDevice?, connected: Boolean) {
            audioListener?.invoke(device, connected)
        }

        fun emitScoRoute(isSco: Boolean) {
            routeListener?.invoke(isSco)
        }

        private fun record(name: String) {
            calls += name
            log += name
        }
    }
}
