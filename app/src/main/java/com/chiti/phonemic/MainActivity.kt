package com.chiti.phonemic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiti.phonemic.audio.AudioEngine
import com.chiti.phonemic.audio.AudioSessionManager
import com.chiti.phonemic.audio.AudioState
import com.chiti.phonemic.audio.MuteReason
import com.chiti.phonemic.bluetooth.BluetoothAudioRouter
import com.chiti.phonemic.model.VoicePreset
import kotlinx.coroutines.launch

enum class MicOperationMode(val label: String, val iconDescription: String) {
    TOGGLE("Toggle", "Tap once to talk"),
    PUSH_TO_TALK("Push to Talk", "Hold down to speak")
}

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
                        onToggleScreenWake = { enableWake ->
                            if (enableWake) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PhoneMicScreen(
    audioEngine: AudioSessionManager,
    bluetoothRouter: BluetoothAudioRouter,
    onToggleScreenWake: (Boolean) -> Unit
) {
    val audioState by audioEngine.audioState.collectAsState()
    val connectedDevice by bluetoothRouter.connectedOutput.collectAsState()
    val rmsDb by audioEngine.currentRmsDb.collectAsState()
    val peakDb by audioEngine.currentPeakDb.collectAsState()
    val isFeedbackActive by audioEngine.isFeedbackActive.collectAsState()

    var inputGainDb by remember { mutableFloatStateOf(0.0f) }
    var selectedPresetId by remember { mutableIntStateOf(0) }
    var micMode by remember { mutableStateOf(MicOperationMode.TOGGLE) }
    var isPttHolding by remember { mutableStateOf(false) }

    // Dialog & Overlay States
    var showDistanceDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isTouchLocked by remember { mutableStateOf(false) }
    var feedbackSuppressionOn by remember { mutableStateOf(true) }
    var keepScreenAwake by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Keep screen awake default effect
    LaunchedEffect(keepScreenAwake) {
        onToggleScreenWake(keepScreenAwake)
    }

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
        targetValue = when {
            isPttHolding || isActive -> Color(0xFF9955FF)
            audioState is AudioState.Muted -> Color(0xFF382855)
            else -> Color(0xFF26262C)
        },
        animationSpec = tween(durationMillis = 200),
        label = "ButtonColor"
    )

    // Pulse animation based on live audio RMS level
    val normalizedRms = ((rmsDb + 60.0f) / 60.0f).coerceIn(0.0f, 1.0f)
    val pulseScale by animateFloatAsState(
        targetValue = if (isActive || isPttHolding) 1.0f + (normalizedRms * 0.15f) else 1.0f,
        label = "PulseScale"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "CHITI / PhoneMic",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (isActive) "● LIVE BROADCASTING" else "READY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isActive) Color(0xFF9955FF) else Color(0xFF9DA3AF)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showDistanceDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Distance Guide",
                                tint = Color(0xFF9DA3AF)
                            )
                        }
                        IconButton(onClick = { isTouchLocked = true }) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lock Controls",
                                tint = Color(0xFF9DA3AF)
                            )
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color(0xFF9DA3AF)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

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
                                .size(42.dp)
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
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = if (connectedDevice != null) "External Audio • Connected" else "Keep volume low • Avoid feedback",
                                fontSize = 12.sp,
                                color = Color(0xFF9DA3AF)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(Color(0xFF2A1B4E), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = micMode.label.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB580FF)
                            )
                        }
                    }
                }

                // Dynamic Feedback Warning Banner
                AnimatedVisibility(visible = isFeedbackActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF4A1A10))
                            .border(1.dp, Color(0xFFE83050), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Feedback Alert",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Acoustic feedback detected! Notch filter engaged. Move 2m away from speaker.",
                                fontSize = 12.sp,
                                color = Color(0xFFFFD4D8),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // Mode Selector Bar (Toggle vs Push-To-Talk)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF16181C))
                    .padding(4.dp)
            ) {
                MicOperationMode.entries.forEach { mode ->
                    val isSelected = micMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Color(0xFF9955FF) else Color.Transparent)
                            .clickable {
                                if (isActive) {
                                    scope.launch { audioEngine.stop() }
                                }
                                micMode = mode
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = mode.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF090A0B) else Color(0xFF9DA3AF)
                        )
                    }
                }
            }

            // Center: Giant Microphone Action Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(buttonColor)
                        .border(
                            width = 4.dp,
                            color = if (isActive || isPttHolding) Color(0xFFC499FF) else Color(0xFF383842),
                            shape = CircleShape
                        )
                        .pointerInput(micMode, hasPermissions) {
                            if (!hasPermissions) {
                                detectTapGestures {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                            } else if (micMode == MicOperationMode.PUSH_TO_TALK) {
                                detectTapGestures(
                                    onPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isPttHolding = true
                                        val devName = connectedDevice?.name ?: "Phone speaker"
                                        scope.launch {
                                            if (audioState is AudioState.Muted) {
                                                audioEngine.setMuted(false)
                                            } else if (audioState !is AudioState.Active) {
                                                audioEngine.start(devName, inputGainDb)
                                            }
                                        }
                                        tryAwaitRelease()
                                        // On release
                                        isPttHolding = false
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        audioEngine.setMuted(true, MuteReason.PUSH_TO_TALK_RELEASED)
                                    }
                                )
                            } else {
                                // Toggle Mode
                                detectTapGestures {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch {
                                        if (isActive) {
                                            audioEngine.stop()
                                        } else {
                                            val devName = connectedDevice?.name ?: "Phone speaker"
                                            audioEngine.start(devName, inputGainDb)
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isActive || isPttHolding) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Microphone Toggle",
                            tint = if (isActive || isPttHolding) Color(0xFF090A0B) else Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                micMode == MicOperationMode.PUSH_TO_TALK && isPttHolding -> "LIVE (PTT)"
                                micMode == MicOperationMode.PUSH_TO_TALK -> "HOLD TO TALK"
                                isActive -> "MIC ON • TAP TO STOP"
                                audioState == AudioState.Connecting -> "CONNECTING"
                                audioState is AudioState.Muted -> "TAP TO RESUME"
                                else -> "TAP TO TALK"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive || isPttHolding) Color(0xFF090A0B) else Color.White
                        )
                    }
                }

                (audioState as? AudioState.Error)?.let {
                    Text(
                        text = it.message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // VU Meter Representation
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
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

                Spacer(modifier = Modifier.height(14.dp))

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
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

        // Accidental Touch Lock Mode Fullscreen Scrim
        AnimatedVisibility(
            visible = isTouchLocked,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF0090A0B))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = Color(0xFF9955FF),
                        modifier = Modifier.size(72.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "MICROPHONE LOCKED",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isActive) "Broadcasting to ${connectedDevice?.name ?: "Speaker"}" else "Microphone is on standby",
                        fontSize = 13.sp,
                        color = Color(0xFF9DA3AF),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(40.dp))

                    Button(
                        onClick = {
                            isTouchLocked = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26262C)),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.LockOpen, contentDescription = "Unlock", tint = Color.White)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("TAP TO UNLOCK", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Distance & Feedback Guidance Modal Dialog
        if (showDistanceDialog) {
            AlertDialog(
                onDismissRequest = { showDistanceDialog = false },
                containerColor = Color(0xFF16181C),
                title = {
                    Text("Physical Distance Guidance", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "To eliminate feedback howling and get clear amplification:",
                            color = Color(0xFFD1D5DB),
                            fontSize = 13.sp
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF21242A), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📱 YOU (Phone)  ◄── 2–3m ──►  🔊 SPEAKER",
                                color = Color(0xFF00D5FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Text("• Keep 2 to 3 meters distance between phone and speaker.", color = Color(0xFFA3A9B0), fontSize = 12.sp)
                        Text("• Do not point the speaker directly at the phone mic.", color = Color(0xFFA3A9B0), fontSize = 12.sp)
                        Text("• If squealing occurs, lower the speaker volume or reduce input gain.", color = Color(0xFFA3A9B0), fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDistanceDialog = false }) {
                        Text("GOT IT", color = Color(0xFF9955FF), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Diagnostics & Audio Settings Modal Dialog
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                containerColor = Color(0xFF16181C),
                title = {
                    Text("Audio Diagnostics & Settings", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text("Active Audio Stack", fontSize = 11.sp, color = Color(0xFF9DA3AF), fontWeight = FontWeight.SemiBold)
                            Text(audioEngine.activeEngineName, fontSize = 13.sp, color = Color(0xFF00D5FF), fontWeight = FontWeight.Bold)
                        }

                        Column {
                            Text("Buffer Underruns (xRuns)", fontSize = 11.sp, color = Color(0xFF9DA3AF), fontWeight = FontWeight.SemiBold)
                            Text("${audioEngine.getUnderrunCount()} underruns", fontSize = 13.sp, color = Color.White)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Feedback Suppression", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                Text("Auto-notch howling resonances", fontSize = 11.sp, color = Color(0xFF9DA3AF))
                            }
                            Switch(
                                checked = feedbackSuppressionOn,
                                onCheckedChange = {
                                    feedbackSuppressionOn = it
                                    audioEngine.setFeedbackSuppression(it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF9955FF))
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Keep Screen Awake", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                Text("Prevent phone display sleep", fontSize = 11.sp, color = Color(0xFF9DA3AF))
                            }
                            Switch(
                                checked = keepScreenAwake,
                                onCheckedChange = {
                                    keepScreenAwake = it
                                    onToggleScreenWake(it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF9955FF))
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("CLOSE", color = Color(0xFF9955FF), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
fun PhoneMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF9955FF),
            secondary = Color(0xFF00D5FF),
            background = Color(0xFF090A0B),
            surface = Color(0xFF121416),
            onPrimary = Color(0xFF090A0B),
            onSurface = Color(0xFFFAFAFA),
            error = Color(0xFFE83050)
        ),
        content = content
    )
}
