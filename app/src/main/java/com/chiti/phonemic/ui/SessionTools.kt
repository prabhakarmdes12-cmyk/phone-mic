package com.chiti.phonemic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chiti.phonemic.audio.*
import com.chiti.phonemic.model.NetworkRole
import kotlinx.coroutines.delay

@Composable
fun SessionTools(
    session: AudioSessionManager,
    onOpenRecordings: () -> Unit
) {
    val role by session.role.collectAsState()
    val audio by session.audioState.collectAsState()
    val recording by session.recorder.state.collectAsState()
    val recordingsList by session.recordings.collectAsState()
    val receivers by session.discovery.receivers.collectAsState()
    val discoveryStatus by session.discovery.status.collectAsState()
    val connection by session.connectionStatus.collectAsState()
    val selectedReceiver by session.selectedReceiver.collectAsState()
    val bgmTrack by session.bgmTrack.collectAsState()
    val bgmVolume by session.bgmVolume.collectAsState()
    val autoDucking by session.autoDucking.collectAsState()

    val active = audio is AudioState.Active
    val busy = active || audio == AudioState.Connecting
    var expandedSoundboard by remember { mutableStateOf(false) }
    var blink by remember { mutableStateOf(true) }

    LaunchedEffect(recording.recording) {
        while (recording.recording) { blink = !blink; delay(500) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161C))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mode Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NETWORK & TOOLS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9DA3AF),
                    letterSpacing = 1.sp
                )
                // Direct link to recordings
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenRecordings() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Recordings",
                        tint = Color(0xFF9955FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Recordings (${recordingsList.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFC499FF)
                    )
                }
            }

            // Session Role Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(NetworkRole.ALL, key = { it.name }) { item ->
                    val isSelected = role == item
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) Color(0xFF9955FF) else Color(0xFF1F222C))
                            .clickable(enabled = !busy) { session.setRole(item) }
                            .sizeIn(minWidth = 48.dp, minHeight = 44.dp)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = item.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color(0xFF090A0B) else Color(0xFFD1D5DB)
                        )
                    }
                }
            }

            if (role == NetworkRole.RECEIVER_MIXER || role == NetworkRole.WALKIE_TALKIE) ChannelMixerControls(session)
            if (role == NetworkRole.WALKIE_TALKIE) Text("LAN-1 · simultaneous talkers are mixed · trusted Wi-Fi only", fontSize = 11.sp)
            if (role != NetworkRole.STANDALONE) {
                Text(connection, fontSize = 11.sp, color = Color(0xFF9DA3AF))
            }
            if (role == NetworkRole.TRANSMITTER && !busy) {
                Text(discoveryStatus, fontSize = 11.sp, color = Color(0xFF9DA3AF))
                TextButton(onClick = { session.discovery.stop(); session.discovery.discoverServices() }) {
                    Text("Scan for Speaker Stations", fontSize = 12.sp)
                }
                receivers.forEach { receiver ->
                    val isCurrent = receiver.address == selectedReceiver?.address
                    OutlinedButton(
                        onClick = { session.selectReceiver(receiver) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isCurrent) Color(0xFF3B2D54) else Color.Transparent
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(if (isCurrent) Color(0xFF9955FF) else Color(0xFF383B4A))
                        )
                    ) {
                        Text(
                            text = if (isCurrent) "✓ ${receiver.name} (${receiver.address.hostAddress})" else "${receiver.name} (${receiver.address.hostAddress})",
                            fontSize = 12.sp,
                            color = if (isCurrent) Color(0xFFC499FF) else Color.White
                        )
                    }
                }
            }

            // Quick Actions: Recording & Soundboard Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Record Pill Button
                Button(
                    onClick = { session.toggleRecording() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording.recording) Color(0xFFDC2626) else Color(0xFF242734)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (recording.recording && blink) Color.White else Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (recording.recording) "REC ${recording.seconds / 60}:${(recording.seconds % 60).toString().padStart(2, '0')}" else "● Record",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                // Soundboard Drawer Toggle Button
                OutlinedButton(
                    onClick = { expandedSoundboard = !expandedSoundboard },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (expandedSoundboard || bgmTrack > 0) Color(0xFF261D38) else Color.Transparent
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(if (bgmTrack > 0) Color(0xFF9955FF) else Color(0xFF383B4A))
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (bgmTrack > 0) Color(0xFFC499FF) else Color(0xFF9DA3AF),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (bgmTrack > 0) "BGM Playing" else (if (expandedSoundboard) "Close Sounds" else "Soundboard & BGM"),
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }

            // Soundboard & Continuous BGM Section
            if (expandedSoundboard) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1A1C24))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Continuous Background Music (BGM)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CONTINUOUS BACKGROUND MUSIC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC499FF)
                        )
                        if (bgmTrack > 0) {
                            Text(
                                text = "BGM VOL: ${(bgmVolume * 100).toInt()}%",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF9DA3AF)
                            )
                        }
                    }

                    // BGM Track Selector Chips
                    val bgmTracks = listOf(
                        0 to "Off",
                        1 to "🎸 Lo-Fi Chill",
                        2 to "🥁 Pop Groove",
                        3 to "✨ Vocal Pad",
                        4 to "🎵 R&B Beat"
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bgmTracks) { (trackId, title) ->
                            val isSelected = bgmTrack == trackId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF9955FF) else Color(0xFF262936))
                                    .clickable { session.setBgmTrack(trackId) }
                                    .sizeIn(minWidth = 48.dp, minHeight = 44.dp)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color(0xFF090A0B) else Color.White
                                )
                            }
                        }
                    }

                    // BGM Volume Slider
                    if (bgmTrack > 0) {
                        Slider(
                            value = bgmVolume,
                            onValueChange = { session.setBgmVolume(it) },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF9955FF),
                                activeTrackColor = Color(0xFF9955FF),
                                inactiveTrackColor = Color(0xFF2F3240)
                            ),
                            modifier = Modifier.height(28.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Auto-Ducking (Speech Priority)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Ducks background music by -14dB when speaking",
                                    fontSize = 10.sp,
                                    color = Color(0xFF9DA3AF)
                                )
                            }
                            Switch(
                                checked = autoDucking,
                                onCheckedChange = { session.setAutoDucking(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF9955FF),
                                    uncheckedThumbColor = Color(0xFF9DA3AF),
                                    uncheckedTrackColor = Color(0xFF262936)
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFF282B36), modifier = Modifier.padding(vertical = 4.dp))

                    // 2. One-Shot Sound Cues
                    Text(
                        text = "ONE-SHOT SOUND CUES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF9DA3AF)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(1 to "🔔 Chime", 2 to "👏 Applause", 3 to "🚨 Siren")) { (id, title) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF262936))
                                    .clickable(enabled = active) { session.triggerSound(id) }
                                    .sizeIn(minWidth = 48.dp, minHeight = 44.dp)
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    color = if (active) Color.White else Color(0xFF6B7280)
                                )
                            }
                        }
                    }
                    Text(
                        text = if (active) "BGM and sounds are blended with speech and limited to -0.5 dBFS." else "Start microphone to play sound cues and BGM into stream.",
                        fontSize = 10.sp,
                        color = if (active) Color(0xFF6B7280) else Color(0xFFC499FF)
                    )
                }
            }
        }
    }
}
