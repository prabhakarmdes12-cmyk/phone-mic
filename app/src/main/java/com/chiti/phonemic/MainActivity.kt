package com.chiti.phonemic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiti.phonemic.audio.AudioSessionManager
import com.chiti.phonemic.audio.AudioState
import com.chiti.phonemic.audio.MuteReason
import com.chiti.phonemic.ui.SessionTools
import com.chiti.phonemic.ui.RecordingsSheet
import com.chiti.phonemic.model.NetworkRole
import com.chiti.phonemic.bluetooth.BluetoothAudioRouter
import com.chiti.phonemic.model.VoicePreset
import com.chiti.phonemic.model.ScenarioProfile
import kotlinx.coroutines.launch

enum class MicOperationMode(val label: String, val iconDescription: String) {
    TOGGLE("Toggle", "Tap once to talk"),
    PUSH_TO_TALK("Push to Talk", "Hold down to speak");

    companion object {
        val ALL: List<MicOperationMode> = values().toList()
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var audioEngine: AudioSessionManager
    private lateinit var bluetoothRouter: BluetoothAudioRouter
    private var pttTrigger: ((Boolean) -> Unit)? = null
    private var isPttActiveCheck: () -> Boolean = { false }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            isPttActiveCheck()) {
            if (event?.repeatCount == 0) {
                pttTrigger?.invoke(true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            isPttActiveCheck()) {
            pttTrigger?.invoke(false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

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
                        },
                        onRegisterPttHandler = { check, trigger ->
                            isPttActiveCheck = check
                            pttTrigger = trigger
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
    onToggleScreenWake: (Boolean) -> Unit,
    onRegisterPttHandler: (isPttMode: () -> Boolean, trigger: ((Boolean) -> Unit)?) -> Unit = { _, _ -> }
) {
    val audioState by audioEngine.audioState.collectAsState()
    val connectedDevice by bluetoothRouter.connectedOutput.collectAsState()
    val rmsDb by audioEngine.currentRmsDb.collectAsState()
    val peakDb by audioEngine.currentPeakDb.collectAsState()
    val isFeedbackActive by audioEngine.isFeedbackActive.collectAsState()
    val networkRole by audioEngine.role.collectAsState()
    val recordings by audioEngine.recordings.collectAsState()
    val recordingState by audioEngine.recorder.state.collectAsState()
    val currentScenario by audioEngine.scenarioProfile.collectAsState()

    var inputGainDb by remember { mutableFloatStateOf(0.0f) }
    var selectedPresetId by remember { mutableIntStateOf(0) }
    var micMode by remember { mutableStateOf(MicOperationMode.TOGGLE) }
    var isPttHolding by remember { mutableStateOf(false) }

    // Dialog & Overlay States
    var showDistanceDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRecordingsSheet by remember { mutableStateOf(false) }
    var isTouchLocked by remember { mutableStateOf(false) }
    var feedbackSuppressionOn by remember { mutableStateOf(true) }
    var keepScreenAwake by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val currentMicMode by rememberUpdatedState(micMode)
    val currentConnectedDevice by rememberUpdatedState(connectedDevice)
    val currentInputGainDb by rememberUpdatedState(inputGainDb)
    val currentAudioState by rememberUpdatedState(audioState)

    // Wire hardware volume key PTT
    DisposableEffect(Unit) {
        onRegisterPttHandler(
            { currentMicMode == MicOperationMode.PUSH_TO_TALK },
            { pressed ->
                if (pressed) {
                    isPttHolding = true
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    val devName = currentConnectedDevice?.name ?: "Phone speaker"
                    scope.launch {
                        if (currentAudioState is AudioState.Muted) {
                            audioEngine.setMuted(false)
                        } else if (currentAudioState !is AudioState.Active) {
                            audioEngine.start(devName, currentInputGainDb)
                        }
                    }
                } else {
                    isPttHolding = false
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    audioEngine.setMuted(true, MuteReason.PUSH_TO_TALK_RELEASED)
                }
            }
        )
        onDispose {
            onRegisterPttHandler({ false }, null)
        }
    }

    // Keep screen awake default effect
    LaunchedEffect(keepScreenAwake) {
        onToggleScreenWake(keepScreenAwake)
    }

    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
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
                        IconButton(onClick = { showRecordingsSheet = true }) {
                            BadgedBox(
                                badge = {
                                    if (recordings.isNotEmpty()) {
                                        Badge(containerColor = Color(0xFF9955FF)) {
                                            Text("${recordings.size}", color = Color.White, fontSize = 10.sp)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Recordings",
                                    tint = if (recordings.isNotEmpty()) Color(0xFFC499FF) else Color(0xFF9DA3AF)
                                )
                            }
                        }
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

            Spacer(modifier = Modifier.height(14.dp))

            // Scenario Optimization Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14161C))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CORE SCENARIOS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9DA3AF),
                            letterSpacing = 1.sp
                        )
                        if (currentScenario != null) {
                            Text(
                                text = "Active Profile",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFC499FF)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ScenarioProfile.ALL, key = { it.id }) { profile ->
                            val isSelected = currentScenario == profile
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) Color(0xFF9955FF) else Color(0xFF1E212B))
                                    .clickable {
                                        runCatching {
                                            audioEngine.applyScenarioProfile(profile)
                                            inputGainDb = profile.defaultGainDb
                                            selectedPresetId = profile.recommendedPreset.id
                                            feedbackSuppressionOn = profile.feedbackSuppression
                                            if (profile.preferPtt) {
                                                micMode = MicOperationMode.PUSH_TO_TALK
                                            }
                                            runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                        }.onFailure { e ->
                                            android.util.Log.e("PhoneMic", "Error selecting scenario ${profile.id}", e)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = profile.iconEmoji, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = profile.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color(0xFF090A0B) else Color.White
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    // Real-Time Distance Guidance
                    val tip = currentScenario?.distanceTip ?: "Optimal mic distance: 5–10 cm from mouth for maximum clarity without feedback."
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1C1F2A))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFC499FF),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tip,
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            SessionTools(
                session = audioEngine,
                onOpenRecordings = { showRecordingsSheet = true }
            )

            // Mode Selector Bar (Toggle vs Push-To-Talk)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF16181C))
                    .padding(4.dp)
            ) {
                MicOperationMode.ALL.forEach { mode ->
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
                                runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
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
                // Prominent Live Recording Banner
                AnimatedVisibility(visible = recordingState.recording) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(bottom = 16.dp)
                            .clickable { audioEngine.toggleRecording() },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E0D12)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEF4444))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "RECORDING LIVE AUDIO",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFCA5A5)
                                    )
                                    Text(
                                        text = "${recordingState.seconds / 60}:${(recordingState.seconds % 60).toString().padStart(2, '0')} • Tap to Stop",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFDC2626))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "STOP",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

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
                        .pointerInput(micMode, hasPermissions, networkRole) {
                            val needsMic = !hasPermissions && networkRole != NetworkRole.RECEIVER
                            if (needsMic) {
                                detectTapGestures {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                            } else if (micMode == MicOperationMode.PUSH_TO_TALK) {
                                detectTapGestures(
                                    onPress = {
                                        runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
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
                                        runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                        audioEngine.setMuted(true, MuteReason.PUSH_TO_TALK_RELEASED)
                                    }
                                )
                            } else {
                                // Toggle Mode
                                detectTapGestures {
                                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    scope.launch {
                                        if (isActive) {
                                            audioEngine.stop()
                                        } else if (audioState is AudioState.Muted) {
                                            audioEngine.setMuted(false)
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
                            imageVector = when {
                                networkRole == NetworkRole.RECEIVER -> if (isActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff
                                isActive || isPttHolding -> Icons.Default.Mic
                                else -> Icons.Default.MicOff
                            },
                            contentDescription = if (networkRole == NetworkRole.RECEIVER) "Speaker Station Toggle" else "Microphone Toggle",
                            tint = if (isActive || isPttHolding) Color(0xFF090A0B) else Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                networkRole == NetworkRole.RECEIVER && isActive -> "STATION PLAYING • STOP"
                                networkRole == NetworkRole.RECEIVER && audioState == AudioState.Connecting -> "CONNECTING"
                                networkRole == NetworkRole.RECEIVER -> "START SPEAKER STATION"
                                micMode == MicOperationMode.PUSH_TO_TALK && isPttHolding -> "LIVE (PTT)"
                                micMode == MicOperationMode.PUSH_TO_TALK -> "HOLD BUTTON / VOL KEYS"
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
                    text = if (isActive || isPttHolding) "${rmsDb.toInt()} dBFS" else "MIC ON STANDBY",
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
                    items(VoicePreset.ALL, key = { it.id }) { preset ->
                        val isSelected = preset.id == selectedPresetId
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) Color(0xFF9955FF) else Color(0xFF1E1E24))
                                .clickable {
                                    selectedPresetId = preset.id
                                    audioEngine.setVoicePreset(preset.id)
                                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
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

                Spacer(modifier = Modifier.height(8.dp))
                val activePreset = VoicePreset.fromId(selectedPresetId)
                Text(
                    text = activePreset.description,
                    fontSize = 11.sp,
                    color = Color(0xFFC499FF)
                )
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
                    .pointerInput(Unit) {
                        detectTapGestures { /* consume touches */ }
                    }
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
                            runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
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

        // Recordings Library Sheet
        if (showRecordingsSheet) {
            RecordingsSheet(
                session = audioEngine,
                onDismiss = { showRecordingsSheet = false }
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
