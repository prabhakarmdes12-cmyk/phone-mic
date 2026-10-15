package com.chiti.phonemic.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.chiti.phonemic.audio.*
import com.chiti.phonemic.model.NetworkRole
import kotlinx.coroutines.delay

@Composable
fun SessionTools(session: AudioSessionManager) {
    val role by session.role.collectAsState()
    val audio by session.audioState.collectAsState()
    val recording by session.recorder.state.collectAsState()
    val receivers by session.discovery.receivers.collectAsState()
    val discoveryStatus by session.discovery.status.collectAsState()
    val connection by session.connectionStatus.collectAsState()
    val context = LocalContext.current
    val active = audio is AudioState.Active
    val busy = active || audio == AudioState.Connecting
    var expanded by remember { mutableStateOf(false) }
    var blink by remember { mutableStateOf(true) }
    var shareError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recording.recording) {
        while (recording.recording) { blink = !blink; delay(500) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SESSION MODE", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(NetworkRole.entries) { item ->
                FilterChip(selected = role == item, enabled = !busy,
                    onClick = { session.setRole(item) }, label = {
                        Text(when (item) {
                            NetworkRole.STANDALONE -> "Single Phone"
                            NetworkRole.TRANSMITTER -> "Presenter"
                            NetworkRole.RECEIVER -> "Speaker Station"
                        })
                    })
            }
        }
        if (role != NetworkRole.STANDALONE) {
            Text(connection, style = MaterialTheme.typography.bodySmall)
            Text("Unencrypted local audio. Use trusted Wi-Fi. First presenter locks the station until stopped.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (role == NetworkRole.TRANSMITTER && !busy) {
            Text(discoveryStatus, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { session.discovery.stop(); session.discovery.discoverServices() }) { Text("Refresh receivers") }
            receivers.forEach { receiver ->
                OutlinedButton(onClick = { session.selectReceiver(receiver) }) {
                    Text("${receiver.name} (${receiver.address.hostAddress})")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { session.toggleRecording() }, enabled = active || recording.recording) {
                Text(if (recording.recording) "${if (blink) "●" else "○"} REC ${recording.seconds / 60}:${(recording.seconds % 60).toString().padStart(2, '0')} • STOP" else "● REC")
            }
            OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide sounds" else "Soundboard") }
        }
        if (expanded) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(1 to "🔔 Chime", 2 to "👏 Applause", 3 to "🚨 Siren")) { (id, title) ->
                    OutlinedButton(enabled = active, onClick = { session.triggerSound(id) }) { Text(title) }
                }
            }
            Text("Sounds are mixed before the master limiter. Keep speaker volume low.", style = MaterialTheme.typography.bodySmall)
        }
        if (!recording.recording) recording.file?.let { file ->
            Text("${file.name} • ${recording.seconds}s", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = android.content.ClipData.newRawUri("PhoneMic recording", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share PhoneMic recording"))
                    }.onFailure { shareError = "No compatible sharing app available" }
                }) { Text("SHARE") }
                TextButton(enabled = !busy, onClick = { session.playPreview() }) { Text("PLAY PREVIEW") }
            }
        }
        recording.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (recording.droppedSamples > 0) Text("Recording queue overflow: ${recording.droppedSamples} samples dropped", color = MaterialTheme.colorScheme.error)
        shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
    }
}
