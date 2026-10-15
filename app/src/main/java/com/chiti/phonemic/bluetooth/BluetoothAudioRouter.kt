package com.chiti.phonemic.bluetooth

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.chiti.phonemic.model.AudioDeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectedAudioDevice(
    val name: String,
    val type: AudioDeviceType,
    val deviceInfo: AudioDeviceInfo? = null,
    val isWireless: Boolean = true
)

/**
 * Monitors and routes audio output to Bluetooth A2DP, Wired, or USB devices.
 */
class BluetoothAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _connectedOutput = MutableStateFlow<ConnectedAudioDevice?>(null)
    val connectedOutput: StateFlow<ConnectedAudioDevice?> = _connectedOutput.asStateFlow()

    var onOutputRemoved: (() -> Unit)? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val currentId = _connectedOutput.value?.deviceInfo?.id
            if (removedDevices?.any { it.id == currentId } == true) onOutputRemoved?.invoke()
            refreshAudioDevices()
        }
    }

    fun startListening() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshAudioDevices()
    }

    fun stopListening() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun refreshAudioDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Prioritize Bluetooth A2DP, then Wired / USB, fallback to internal speaker
        val bluetoothDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val wiredDevice = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        val active = when {
            bluetoothDevice != null -> ConnectedAudioDevice(
                name = bluetoothDevice.productName.toString().ifEmpty { "Bluetooth Speaker" },
                type = AudioDeviceType.BLUETOOTH_A2DP,
                deviceInfo = bluetoothDevice,
                isWireless = true
            )
            wiredDevice != null -> ConnectedAudioDevice(
                name = wiredDevice.productName.toString().ifEmpty { "Wired Speaker / AUX" },
                type = if (wiredDevice.type == AudioDeviceInfo.TYPE_USB_DEVICE) AudioDeviceType.USB_AUDIO else AudioDeviceType.WIRED_HEADSET,
                deviceInfo = wiredDevice,
                isWireless = false
            )
            else -> null
        }

        _connectedOutput.value = active
    }
}
