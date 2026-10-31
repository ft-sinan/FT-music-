package echo.music.iad1tya.ui.component

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import echo.music.iad1tya.LocalDatabase
import echo.music.iad1tya.LocalDownloadUtil
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.R
import echo.music.iad1tya.db.entities.Song
import echo.music.iad1tya.localmedia.LocalAudioFileDeletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The app confirmation is separate from any Android permission/delete confirmation. */
@Composable
fun LocalAudioDeleteDialog(song: Song, onDismiss: () -> Unit, onFinished: () -> Unit) {
  val context = LocalContext.current
  val database = LocalDatabase.current
  val downloads = LocalDownloadUtil.current
  val playerConnection = LocalPlayerConnection.current
  val deletion = remember(context) { LocalAudioFileDeletion(context.applicationContext) }
  var stage by rememberSaveable(song.id) { mutableStateOf("idle") }
  var error by rememberSaveable(song.id) { mutableStateOf<Int?>(null) }
  var confirmedTarget by rememberSaveable(song.id) { mutableStateOf<String?>(null) }
  val initial by produceState<LocalAudioFileDeletion.Inspection?>(null, song.id) {
    value = deletion.inspect(song)
  }
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) stage = "starting"
    else {
      error = R.string.ft_delete_device_permission_denied
      stage = "idle"
    }
  }
  val systemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    if (result.resultCode != Activity.RESULT_OK) {
      error = R.string.ft_delete_device_cancelled
      stage = "idle"
    } else {
      stage = if (stage == "wait_grant") "retry" else "verify"
    }
  }

  LaunchedEffect(stage, song.id) {
    fun fail(message: Int) {
      error = message
      stage = "idle"
    }

    fun launchConsent(sender: android.content.IntentSender, nextStage: String) {
      try {
        stage = nextStage
        systemLauncher.launch(IntentSenderRequest.Builder(sender).build())
      } catch (_: Exception) {
        fail(R.string.ft_delete_device_failed)
      }
    }

    fun handleDirect(result: LocalAudioFileDeletion.DeleteResult, allowConsent: Boolean) {
      when (result) {
        LocalAudioFileDeletion.DeleteResult.Deleted -> stage = "finalize_deleted"
        LocalAudioFileDeletion.DeleteResult.Missing -> stage = "finalize_missing"
        LocalAudioFileDeletion.DeleteResult.Changed -> fail(R.string.ft_delete_device_changed)
        LocalAudioFileDeletion.DeleteResult.Failed -> fail(R.string.ft_delete_device_failed)
        is LocalAudioFileDeletion.DeleteResult.NeedsConsent -> {
          if (allowConsent && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            launchConsent(result.sender, "wait_grant")
          } else fail(R.string.ft_delete_device_failed)
        }
      }
    }

    when (stage) {
      "starting" -> when (val inspected = deletion.inspect(song)) {
        LocalAudioFileDeletion.Inspection.Missing -> stage = "finalize_missing"
        LocalAudioFileDeletion.Inspection.Changed -> fail(R.string.ft_delete_device_changed)
        LocalAudioFileDeletion.Inspection.Unavailable -> fail(R.string.ft_delete_device_unavailable)
        is LocalAudioFileDeletion.Inspection.Ready -> {
          val confirmed = confirmedTarget?.let { LocalAudioFileDeletion.Target.decode(it) }
          if (confirmed == null || confirmed != inspected.target) {
            fail(R.string.ft_delete_device_changed)
          } else if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            try {
              stage = "wait_permission"
              permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } catch (_: Exception) { fail(R.string.ft_delete_device_failed) }
          } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = deletion.systemDeleteRequest(inspected.target)
            if (sender == null) fail(R.string.ft_delete_device_failed)
            else launchConsent(sender, "wait_delete")
          } else {
            handleDirect(deletion.deleteDirect(song, confirmed), allowConsent = true)
          }
        }
      }
      "retry" -> {
        val confirmed = confirmedTarget?.let { LocalAudioFileDeletion.Target.decode(it) }
        if (confirmed == null) fail(R.string.ft_delete_device_changed)
        else handleDirect(deletion.deleteDirect(song, confirmed), allowConsent = false)
      }
      "verify" -> {
        var presence = deletion.presence(song.id)
        repeat(4) {
          if (presence != LocalAudioFileDeletion.Presence.MISSING) {
            delay(250)
            presence = deletion.presence(song.id)
          }
        }
        if (presence == LocalAudioFileDeletion.Presence.MISSING) stage = "finalize_deleted"
        else fail(R.string.ft_delete_device_unverified)
      }
      "finalize_deleted", "finalize_missing" -> {
        val removedFile = stage == "finalize_deleted"
        val updated = try {
          withContext(Dispatchers.IO) {
            database.withTransaction {
              // Never delete an online song or another local row by accident.
              if (deleteLocalSongById(song.id) > 0) deleteFormat(song.id)
            }
          }
          true
        } catch (_: Exception) { false }
        downloads.refreshSharedDownloads()
        if (updated) {
          try {
            playerConnection?.player?.let { player ->
              for (index in (player.mediaItemCount - 1) downTo 0) {
                if (player.getMediaItemAt(index).mediaId == song.id) player.removeMediaItem(index)
              }
            }
          } catch (_: Exception) { /* A stopped service will refresh its queue on the next scan. */ }
        }
        val message = when {
          !updated -> R.string.ft_delete_device_library_error
          removedFile -> R.string.ft_delete_device_done
          else -> R.string.ft_delete_device_entry_removed
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onFinished()
      }
    }
  }

  val idle = stage == "idle"
  val canConfirm = initial is LocalAudioFileDeletion.Inspection.Ready || initial == LocalAudioFileDeletion.Inspection.Missing
  AlertDialog(
    onDismissRequest = { if (idle) onDismiss() },
    title = {
      Text(stringResource(if (initial == LocalAudioFileDeletion.Inspection.Missing) R.string.ft_delete_device_missing_title else R.string.ft_delete_device_title))
    },
    text = {
      when {
        error != null -> Text(stringResource(error!!))
        initial == null -> CircularProgressIndicator()
        initial is LocalAudioFileDeletion.Inspection.Ready -> {
          val target = (initial as LocalAudioFileDeletion.Inspection.Ready).target
          Text(stringResource(R.string.ft_delete_device_confirmation, song.song.title, target.legacyPath ?: target.displayName))
        }
        initial == LocalAudioFileDeletion.Inspection.Missing -> Text(stringResource(R.string.ft_delete_device_missing_body))
        initial == LocalAudioFileDeletion.Inspection.Changed -> Text(stringResource(R.string.ft_delete_device_changed))
        else -> Text(stringResource(R.string.ft_delete_device_unavailable))
      }
    },
    confirmButton = {
      if (canConfirm) {
        TextButton(enabled = idle, onClick = {
          confirmedTarget = (initial as? LocalAudioFileDeletion.Inspection.Ready)?.target?.encode()
          error = null
          stage = "starting"
        }) {
          Text(stringResource(if (initial == LocalAudioFileDeletion.Inspection.Missing) R.string.ft_delete_device_remove_entry else R.string.delete))
        }
      }
    },
    dismissButton = { TextButton(enabled = idle, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
