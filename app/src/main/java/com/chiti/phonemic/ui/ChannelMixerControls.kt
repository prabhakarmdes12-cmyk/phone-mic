package com.chiti.phonemic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.chiti.phonemic.audio.AudioSessionManager

@Composable
fun MixerSummaryCard(session: AudioSessionManager) {
    val channels by session.connectedMicChannels.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connected Microphones (${channels.size})", style = MaterialTheme.typography.titleSmall)
            Text(if (channels.isEmpty()) "Waiting for wireless microphones…" else "${channels.count { !it.isMuted }} open • channel controls in Advanced",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ChannelMixerControls(session: AudioSessionManager) {
    val channels by session.connectedMicChannels.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CHANNEL MIXER · ${channels.size}/8", style = MaterialTheme.typography.labelLarge)
        channels.forEach { channel ->
            key(channel.address) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(channel.deviceName, style = MaterialTheme.typography.titleSmall)
                                Text("${channel.rmsDb.toInt()} dBFS · ${channel.address}", style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(checked = !channel.isMuted,
                                onCheckedChange = { session.setChannelMuted(channel.address, !it) },
                                modifier = Modifier.semantics { contentDescription = "${channel.deviceName} microphone enabled" })
                        }
                        LinearProgressIndicator(progress = ((channel.rmsDb + 60f) / 60f).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                        Text("Gain ${(channel.gain * 100).toInt()}% · lost ${channel.lostPackets} packets", style = MaterialTheme.typography.labelSmall)
                        Slider(value = channel.gain, valueRange = 0f..2f,
                            onValueChange = { session.setChannelGain(channel.address, it) })
                    }
                }
            }
        }
    }
}
