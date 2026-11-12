package echo.music.iad1tya.ui.component

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.LocalDownloadUtil
import echo.music.iad1tya.R
import echo.music.iad1tya.utils.ShareableAudio
import echo.music.iad1tya.utils.audioShareIntent
import echo.music.iad1tya.utils.findShareableAudio
import echo.music.iad1tya.utils.isLocalMediaId
import echo.music.iad1tya.utils.youtubeSongLink

private sealed interface FileLookup {
  data object Checking : FileLookup
  data object Unavailable : FileLookup
  data class Ready(val audio: ShareableAudio) : FileLookup
}

@Composable
fun SongShareDialog(mediaId: String, mimeHint: String? = null, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val downloadUtil = LocalDownloadUtil.current
  val store = downloadUtil.sharedDownloadStore
  val link = youtubeSongLink(mediaId)
  val file by produceState<FileLookup>(initialValue = FileLookup.Checking, mediaId, mimeHint, downloadUtil) {
    var audio = findShareableAudio(context, store, mediaId, mimeHint)
    if (audio == null && !mediaId.isLocalMediaId()) {
      val copy = downloadUtil.copyPrivateDownloadForSharing(mediaId)
      if (copy != null) {
        try {
          audio = findShareableAudio(context, store, Uri.fromFile(copy.file).toString(), copy.mimeType)
        } finally {
          if (audio == null) copy.file.delete()
        }
      } else {
        // Publication may have finished while the private-copy check was waiting for the lock.
        audio = findShareableAudio(context, store, mediaId, mimeHint)
      }
    }
    value = audio?.let { FileLookup.Ready(it) } ?: FileLookup.Unavailable
  }
  fun launchChooser(intent: Intent) {
    try {
      context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
      onDismiss()
    } catch (_: Exception) {
      Toast.makeText(context, context.getString(R.string.share_unavailable), Toast.LENGTH_SHORT).show()
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.share_song_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          when (file) {
            FileLookup.Checking -> stringResource(R.string.share_file_checking)
            FileLookup.Unavailable -> stringResource(R.string.share_file_not_available)
            is FileLookup.Ready -> stringResource(R.string.share_file_available)
          }
        )
        Button(
          modifier = Modifier.fillMaxWidth(),
          enabled = file is FileLookup.Ready,
          onClick = { (file as? FileLookup.Ready)?.let { launchChooser(audioShareIntent(context, it.audio)) } },
        ) { Text(stringResource(R.string.share_file)) }
        OutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          enabled = link != null,
          onClick = {
            link?.let { launchChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it)) }
          },
        ) { Text(stringResource(R.string.share_link)) }
        OutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          enabled = link != null,
          onClick = { link?.let { launchChooser(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
        ) { Text(stringResource(R.string.share_open_link)) }
        if (link == null) Text(stringResource(R.string.share_no_youtube_link))
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
  )
}
