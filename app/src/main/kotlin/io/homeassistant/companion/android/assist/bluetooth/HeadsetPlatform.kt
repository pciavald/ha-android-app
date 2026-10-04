package io.homeassistant.companion.android.assist.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.util.SdkVersion
import javax.inject.Inject
import timber.log.Timber

/** Connected headset profile, closed with [close] once it is not needed anymore. */
internal interface HeadsetProfile {
    fun connectedDevices(): List<BluetoothDevice>
    fun isVoiceRecognitionSupported(device: BluetoothDevice): Boolean
    fun startVoiceRecognition(device: BluetoothDevice): Boolean
    fun stopVoiceRecognition(device: BluetoothDevice): Boolean
    fun close()
}

/** Platform calls used by [HeadsetVoiceSessionImpl], isolated so they can be faked in tests. */
internal interface HeadsetPlatform {

    /** Whether the app may talk to connected Bluetooth devices (always true before Android 12). */
    fun hasConnectPermission(): Boolean

    /**
     * Requests the headset profile, delivered to [onOpened] on the main thread.
     *
     * @return false if Bluetooth is unavailable and [onOpened] will never be called
     */
    fun openHeadsetProfile(onOpened: (HeadsetProfile) -> Unit): Boolean

    /**
     * Listens to the SCO audio link state of headsets: [onChanged] receives the device and whether
     * the link is now connected (true) or disconnected (false).
     *
     * @return a function unregistering the listener
     */
    fun registerAudioStateReceiver(onChanged: (BluetoothDevice?, Boolean) -> Unit): () -> Unit

    /**
     * Listens to the communication device: [onChanged] receives whether it is a Bluetooth SCO
     * device, once right away and then on every change.
     *
     * @return a function removing the listener, or null when not supported (before Android 12)
     */
    fun addScoRouteListener(onChanged: (Boolean) -> Unit): (() -> Unit)?
}

internal class AndroidHeadsetPlatform @Inject constructor(@ApplicationContext private val context: Context) :
    HeadsetPlatform {

    override fun hasConnectPermission(): Boolean = !SdkVersion.isAtLeast(Build.VERSION_CODES.S) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    override fun openHeadsetProfile(onOpened: (HeadsetProfile) -> Unit): Boolean {
        val adapter = context.getSystemService<BluetoothManager>()?.adapter ?: return false
        return adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HEADSET) return
                    onOpened(
                        AndroidHeadsetProfile(proxy as BluetoothHeadset) {
                            adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        },
                    )
                }

                override fun onServiceDisconnected(profile: Int) {
                    Timber.d("Bluetooth headset profile disconnected")
                }
            },
            BluetoothProfile.HEADSET,
        )
    }

    override fun registerAudioStateReceiver(onChanged: (BluetoothDevice?, Boolean) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED)) {
                    BluetoothHeadset.STATE_AUDIO_CONNECTED -> onChanged(device, true)
                    BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> onChanged(device, false)
                }
            }
        }
        // Protected system broadcast: no other app can send it
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        return { context.unregisterReceiver(receiver) }
    }

    override fun addScoRouteListener(onChanged: (Boolean) -> Unit): (() -> Unit)? {
        if (!SdkVersion.isAtLeast(Build.VERSION_CODES.S)) return null
        val audioManager = context.getSystemService<AudioManager>() ?: return null
        fun isSco(device: AudioDeviceInfo?) = device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val listener = AudioManager.OnCommunicationDeviceChangedListener { onChanged(isSco(it)) }
        audioManager.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), listener)
        onChanged(isSco(audioManager.communicationDevice))
        return { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
    }
}

/** The connect permission is checked by [HeadsetPlatform.hasConnectPermission] before any call. */
@SuppressLint("MissingPermission")
private class AndroidHeadsetProfile(private val headset: BluetoothHeadset, private val onClose: () -> Unit) :
    HeadsetProfile {

    override fun connectedDevices(): List<BluetoothDevice> = headset.connectedDevices

    override fun isVoiceRecognitionSupported(device: BluetoothDevice): Boolean =
        SdkVersion.isAtLeast(Build.VERSION_CODES.S) && headset.isVoiceRecognitionSupported(device)

    override fun startVoiceRecognition(device: BluetoothDevice): Boolean = headset.startVoiceRecognition(device)

    override fun stopVoiceRecognition(device: BluetoothDevice): Boolean = headset.stopVoiceRecognition(device)

    override fun close() = onClose()
}
