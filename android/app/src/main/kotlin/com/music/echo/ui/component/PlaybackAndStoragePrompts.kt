package echo.music.iad1tya.ui.component

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.playback.DownloadUtil
import echo.music.iad1tya.playback.LocalPlaybackPrompt
import echo.music.iad1tya.playback.PlayerConnection

@Composable
fun PlaybackAndStoragePrompts(connection: PlayerConnection?, downloads: DownloadUtil) {
  val context = LocalContext.current
  val prompt by (connection?.localPlaybackPrompt?.collectAsState() ?: remember { mutableStateOf<LocalPlaybackPrompt?>(null) })
  val writePermissionNeeded by downloads.legacyWritePermissionNeeded.collectAsState()
  val problem by downloads.sharedDownloadProblem.collectAsState()
  val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    connection?.onLocalMatchPermissionResult(it)
  }
  val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    downloads.onLegacyWritePermissionResult(it)
  }
  LaunchedEffect(problem) {
    problem?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
  }

  when (val active = prompt) {
    is LocalPlaybackPrompt.Permission -> AlertDialog(
      onDismissRequest = { connection?.onLocalMatchPermissionResult(false) },
      title = { Text("Find songs on your device?") },
      text = { Text("Allow audio access so FT Music can find a local copy of ${active.songTitle} before streaming.") },
      confirmButton = { TextButton(onClick = { audioPermission.launch(active.permission) }) { Text("Allow access") } },
      dismissButton = { TextButton(onClick = { connection?.onLocalMatchPermissionResult(false) }) { Text("Stream instead") } },
    )
    is LocalPlaybackPrompt.Choices -> AlertDialog(
      onDismissRequest = { connection?.cancelLocalMatch() },
      title = { Text("Choose a matching song") },
      text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
          Text("Possible device copies of ${active.songTitle}:", style = MaterialTheme.typography.bodyMedium)
          active.candidates.forEach { track ->
            Column(Modifier.fillMaxWidth().clickable { connection?.chooseLocalMatch(track.id) }.padding(vertical = 10.dp)) {
              Text(track.title, style = MaterialTheme.typography.bodyLarge)
              val time = if (track.durationSeconds > 0) " · ${track.durationSeconds / 60}:${(track.durationSeconds % 60).toString().padStart(2, '0')}" else ""
              Text(track.artist + time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { connection?.chooseLocalMatch(null) }) { Text("Stream instead") } },
      dismissButton = { TextButton(onClick = { connection?.cancelLocalMatch() }) { Text("Cancel") } },
    )
    null -> if (writePermissionNeeded) {
      AlertDialog(
        onDismissRequest = { downloads.onLegacyWritePermissionResult(false) },
        title = { Text("Save downloads in Files") },
        text = { Text("On this Android version, FT Music needs storage access to save songs in Downloads/FT Music. Until access is allowed, downloads remain in the app’s private storage.") },
        confirmButton = { TextButton(onClick = { writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }) { Text("Allow access") } },
        dismissButton = { TextButton(onClick = { downloads.onLegacyWritePermissionResult(false) }) { Text("Not now") } },
      )
    }
  }
}
