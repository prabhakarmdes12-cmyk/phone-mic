package com.chiti.phonemic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chiti.phonemic.audio.AudioState
import com.chiti.phonemic.audio.AudioSessionManager
import com.chiti.phonemic.audio.AudioEngine
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.chiti.phonemic.bluetooth.BluetoothAudioRouter
import com.chiti.phonemic.model.VoicePreset
import com.chiti.phonemic.service.MicForegroundService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var audioEngine: AudioSessionManager
    private lateinit var bluetoothRouter: BluetoothAudioRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioEngine = AudioSessionManager.get(this)
        bluetoothRouter = audioEngine.router

        setContent {
            PhoneMicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090A0B)
                ) {
                    PhoneMicScreen(
                        audioEngine = audioEngine,
                        bluetoothRouter = bluetoothRouter,
                        onStartForeground = { deviceName ->
                            // Session manager requests foreground service before opening streams.
                        },
                        onStopForeground = {
                            // Session manager owns service lifetime.
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Foreground service deliberately survives Activity destruction.
        // Session manager owns service lifetime.
        super.onDestroy()
    }
}

@Composable
fun PhoneMicScreen(
    audioEngine: AudioEngine,
    bluetoothRouter: BluetoothAudioRouter,
    onStartForeground: (String) -> Unit,
    onStopForeground: () -> Unit
) {
    val audioState by audioEngine.audioState.collectAsState()
    val connectedDevice by bluetoothRouter.connectedOutput.collectAsState()
    val rmsDb by audioEngine.currentRmsDb.collectAsState()
    val peakDb by audioEngine.currentPeakDb.collectAsState()

    var inputGainDb by remember { mutableFloatStateOf(0.0f) }
    var selectedPresetId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Permission check
    var hasPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    val isActive = audioState is AudioState.Active
    val buttonColor by animateColorAsState(
        targetValue = if (isActive) Color(0xFF9955FF) else Color(0xFF26262C),
        animationSpec = tween(durationMillis = 250),
        label = "ButtonColor"
    )

    // Pulse animation based on live audio RMS level
    val normalizedRms = ((rmsDb + 60.0f) / 60.0f).coerceIn(0.0f, 1.0f)
    val pulseScale by animateFloatAsState(
        targetValue = if (isActive) 1.0f + (normalizedRms * 0.15f) else 1.0f,
        label = "PulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Bar & Output Device Card
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CHITI / PhoneMic",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (isActive) "● LIVE" else "READY",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) Color(0xFF9955FF) else Color(0xFF9DA3AF)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Output Speaker Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121416))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFF2A2A32), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Speaker",
                            tint = Color(0xFF00D5FF)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connectedDevice?.name ?: "Phone speaker",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = if (connectedDevice != null) "Bluetooth • Connected" else "Keep volume low • Avoid feedback",
                            fontSize = 12.sp,
                            color = Color(0xFF9DA3AF)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF00382B), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "DELAY VARIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9955FF)
                        )
                    }
                }
            }
        }

        // Center: Giant Microphone Action Button & Live VU Meter
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(buttonColor)
                    .border(
                        width = 4.dp,
                        color = if (isActive) Color(0xFF69F0AE) else Color(0xFF383842),
                        shape = CircleShape
                    )
                    .clickable(enabled = audioState != AudioState.Connecting) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!hasPermissions) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        } else scope.launch {
                            if (isActive) {
                                audioEngine.stop()
                                onStopForeground()
                            } else {
                                val devName = connectedDevice?.name ?: "Phone speaker"
                                val started = audioEngine.start(devName, inputGainDb)
                                if (started) onStartForeground(devName)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Microphone Toggle",
                        tint = if (isActive) Color(0xFF090A0B) else Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when { isActive -> "MIC ON • TAP TO STOP"; audioState == AudioState.Connecting -> "CONNECTING"; audioState is AudioState.Muted -> "TAP TO RESUME"; else -> "TAP TO TALK" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color(0xFF090A0B) else Color.White
                    )
                }
            }

            (audioState as? AudioState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error)
            }
            if (audioState is AudioState.Muted) Text("Paused. Resume only when safe.", color = Color(0xFFA3A9B0))
            Spacer(modifier = Modifier.height(28.dp))

            // VU Meter Representation
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF26262C))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(normalizedRms)
                        .height(8.dp)
                        .background(
                            if (normalizedRms > 0.85f) Color(0xFFFF5252)
                            else if (normalizedRms > 0.6f) Color(0xFFFFD600)
                            else Color(0xFF9955FF)
                        )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${rmsDb.toInt()} dBFS",
                fontSize = 11.sp,
                color = Color(0xFF9DA3AF)
            )
        }

        // Bottom: Gain Control & Voice Presets
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "INPUT GAIN",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF9DA3AF)
                )
                Text(
                    text = "${if (inputGainDb > 0) "+" else ""}${inputGainDb.toInt()} dB",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Slider(
                value = inputGainDb,
                onValueChange = {
                    inputGainDb = it
                    audioEngine.setInputGain(it)
                },
                valueRange = -12.0f..12.0f,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF9955FF),
                    activeTrackColor = Color(0xFF9955FF),
                    inactiveTrackColor = Color(0xFF26262C)
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "VOICE PRESET",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9DA3AF)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(VoicePreset.entries) { preset ->
                    val isSelected = preset.id == selectedPresetId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Color(0xFF9955FF) else Color(0xFF1E1E24))
                            .clickable {
                                selectedPresetId = preset.id
                                audioEngine.setVoicePreset(preset.id)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = preset.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSelected) Color(0xFF090A0B) else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PhoneMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF9955FF), secondary = Color(0xFF00D5FF),
        background = Color(0xFF090A0B), surface = Color(0xFF121416),
        onPrimary = Color(0xFF090A0B), onSurface = Color(0xFFFAFAFA),
        error = Color(0xFFE83050)
    ), content = content)
}
