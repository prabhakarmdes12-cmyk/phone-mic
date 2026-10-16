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

        // Prioritize Bluetooth A2DP & BLE Audio, then Wired / USB / AUX, fallback to internal speaker
        val isBle = { d: AudioDeviceInfo ->
            Build.VERSION.SDK_INT >= 31 && (d.type == 26 /* TYPE_BLE_HEADSET */ || d.type == 27 /* TYPE_BLE_SPEAKER */ || d.type == 30 /* TYPE_BLE_BROADCAST */)
        }
        val bluetoothDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || isBle(it) }
        val wiredDevice = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_AUX_LINE ||
            it.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
            it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
        }

        val active = when {
            bluetoothDevice != null -> {
                val isBleDevice = isBle(bluetoothDevice)
                ConnectedAudioDevice(
                    name = bluetoothDevice.productName.toString().ifEmpty { if (isBleDevice) "BLE Audio Speaker" else "Bluetooth Speaker" },
                    type = if (isBleDevice) AudioDeviceType.BLUETOOTH_LE else AudioDeviceType.BLUETOOTH_A2DP,
                    deviceInfo = bluetoothDevice,
                    isWireless = true
                )
            }
            wiredDevice != null -> ConnectedAudioDevice(
                name = wiredDevice.productName.toString().ifEmpty { "Wired Speaker / AUX" },
                type = when (wiredDevice.type) {
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceType.USB_AUDIO
                    else -> AudioDeviceType.WIRED_HEADSET
                },
                deviceInfo = wiredDevice,
                isWireless = false
            )
            else -> null
        }

        _connectedOutput.value = active
    }
}
