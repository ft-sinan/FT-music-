package echo.music.iad1tya.ui.component

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.ByteArrayOutputStream
import echo.music.iad1tya.R
import echo.music.iad1tya.localmedia.AudioTagEditFailure
import echo.music.iad1tya.localmedia.LocalSongInfoEditor
import echo.music.iad1tya.localmedia.LocalSongInfoSnapshot
import echo.music.iad1tya.localmedia.TagSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edits the tags stored inside a local audio file: title, artists, album, year and cover.
 *
 * Suggestions come from a YouTube Music search the user starts and accepts one result at a time,
 * then corrects by hand. Nothing is written until Apply, and every refusal - unsupported format,
 * Android's permission prompt, a failed write, a result that does not read back - is reported
 * instead of quietly closing.
 */
@Composable
fun LocalSongInfoDialog(onDismiss: () -> Unit) {
  val viewModel = LocalSongInfo.current
  val song = viewModel.selectedSong ?: return
  val context = LocalContext.current
  val snapshot = viewModel.snapshot
  val target = viewModel.target
  val container = target?.container
  var query by rememberSaveable(song.id) {
    mutableStateOf("${song.song.title} ${song.artists.firstOrNull()?.name.orEmpty()}".trim())
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) viewModel.retryAfterPermission()
    else viewModel.permissionRefused()
  }
  val scope = rememberCoroutineScope()
  val coverPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      val cover = withContext(Dispatchers.IO) { resizedCover(context, uri) }
      if (cover == null) {
        Toast.makeText(context, R.string.ft_edit_info_cover_unreadable, Toast.LENGTH_LONG).show()
      } else {
        viewModel.setCoverFromFile(cover.first, cover.second)
      }
    }
  }

  LaunchedEffect(viewModel.outcome) {
    when (val outcome = viewModel.outcome) {
      null -> Unit
      LocalSongInfoViewModel.Outcome.Saved -> {
        Toast.makeText(
          context,
          if (snapshot.coverBytes != null) R.string.ft_edit_info_saved
          else R.string.ft_edit_info_saved_without_cover,
          Toast.LENGTH_LONG,
        ).show()
        onDismiss()
      }
      is LocalSongInfoViewModel.Outcome.PermissionNeeded -> {
        val sender = outcome.sender
        if (sender == null) viewModel.permissionRefused()
        else {
          runCatching {
            permissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
          }.onFailure { viewModel.permissionRefused() }
        }
      }
      is LocalSongInfoViewModel.Outcome.Problem -> Unit
    }
  }

  val problem = viewModel.outcome as? LocalSongInfoViewModel.Outcome.Problem
  AlertDialog(
    onDismissRequest = { if (!viewModel.working) onDismiss() },
    icon = { Icon(painterResource(R.drawable.edit), contentDescription = null) },
    title = { Text(stringResource(R.string.ft_edit_info_title)) },
    text = {
      Column(
        Modifier
          .heightIn(max = 520.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Text(
          text = target?.displayName ?: song.song.title,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.ft_edit_info_audio_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (container == null && target != null) {
          Spacer(Modifier.height(12.dp))
          Text(
            text = stringResource(R.string.ft_edit_info_failed_format),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        } else {
          Spacer(Modifier.height(12.dp))
          CoverRow(
            snapshot = snapshot,
            containerSupportsCover = container?.supportsCover ?: true,
            working = viewModel.working,
            onPick = {
              coverPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            },
            onRemove = viewModel::clearCover,
          )
          Spacer(Modifier.height(12.dp))
          OutlinedTextField(
            value = snapshot.title,
            onValueChange = viewModel::setTitle,
            label = { Text(stringResource(R.string.ft_edit_info_title_field)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = snapshot.artists,
            onValueChange = viewModel::setArtists,
            label = { Text(stringResource(R.string.ft_edit_info_artists)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = snapshot.album,
            onValueChange = viewModel::setAlbum,
            label = { Text(stringResource(R.string.ft_edit_info_album)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = snapshot.year?.toString().orEmpty(),
            onValueChange = viewModel::setYear,
            label = { Text(stringResource(R.string.ft_edit_info_year)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(12.dp))
          SearchSection(
            query = query,
            onQueryChange = { query = it },
            snapshot = snapshot,
            working = viewModel.working,
            onSearch = { viewModel.search(query) },
            onAccept = viewModel::acceptResult,
            onClear = viewModel::clearSearch,
          )
          if (snapshot.notices.contains(LocalSongInfoEditor.COVER_UNSUPPORTED)) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.ft_edit_info_cover_unsupported),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (snapshot.coverProblem != null) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.ft_edit_info_cover_failed),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          if (problem != null) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = failureMessage(problem.failure),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
            problem.detail?.takeIf { it.isNotBlank() }?.let { detail ->
              Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = !viewModel.working && container != null && snapshot.canApply,
        onClick = viewModel::apply,
      ) {
        if (viewModel.working) {
          CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.size(8.dp))
          Text(stringResource(R.string.ft_edit_info_saving))
        } else {
          Text(stringResource(R.string.ft_edit_info_apply))
        }
      }
    },
    dismissButton = {
      TextButton(enabled = !viewModel.working, onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
private fun CoverRow(
  snapshot: LocalSongInfoSnapshot,
  containerSupportsCover: Boolean,
  working: Boolean,
  onPick: () -> Unit,
  onRemove: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
      Modifier
        .size(72.dp)
        .clip(RoundedCornerShape(8.dp))
    ) {
      val cover = snapshot.coverBytes
      if (cover != null) {
        AsyncImage(model = cover, contentDescription = null, modifier = Modifier.size(72.dp))
      } else {
        Icon(
          painterResource(R.drawable.image),
          contentDescription = null,
          modifier = Modifier.size(72.dp).padding(20.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Column {
      Text(
        text = stringResource(R.string.ft_edit_info_cover),
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = stringResource(
          when {
            !containerSupportsCover -> R.string.ft_edit_info_cover_unsupported
            snapshot.hasCover -> R.string.ft_edit_info_cover_set
            else -> R.string.ft_edit_info_cover_none
          }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = !working && containerSupportsCover, onClick = onPick) {
          Text(stringResource(R.string.ft_edit_info_cover_choose))
        }
        if (snapshot.hasCover) {
          TextButton(enabled = !working, onClick = onRemove) {
            Text(stringResource(R.string.ft_edit_info_cover_remove))
          }
        }
      }
    }
  }
}

@Composable
private fun SearchSection(
  query: String,
  onQueryChange: (String) -> Unit,
  snapshot: LocalSongInfoSnapshot,
  working: Boolean,
  onSearch: () -> Unit,
  onAccept: (TagSearchResult) -> Unit,
  onClear: () -> Unit,
) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    label = { Text(stringResource(R.string.ft_edit_info_search_hint)) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
    trailingIcon = {
      TextButton(enabled = !working && query.isNotBlank(), onClick = onSearch) {
        Text(stringResource(R.string.ft_edit_info_search))
      }
    },
  )
  when (val stage = snapshot.searchStage) {
    LocalSongInfoEditor.SearchStage.Idle -> Unit
    LocalSongInfoEditor.SearchStage.Searching -> {
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text(
          stringResource(R.string.ft_edit_info_searching),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    is LocalSongInfoEditor.SearchStage.Failed -> {
      Spacer(Modifier.height(8.dp))
      Text(
        text = stringResource(
          when (stage.reason) {
            LocalSongInfoEditor.SearchFailure.NO_RESULTS -> R.string.ft_edit_info_no_results
            else -> R.string.ft_edit_info_lookup_failed
          }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is LocalSongInfoEditor.SearchStage.Ready -> {
      Spacer(Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.ft_edit_info_results, stage.results.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      stage.results.take(8).forEach { result ->
        Row(
          Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          AsyncImage(
            model = result.coverUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
          )
          Column(Modifier.weight(1f)) {
            Text(
              text = result.title,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = listOfNotNull(result.artists.takeIf { it.isNotBlank() }, result.album)
                .joinToString(" · "),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          TextButton(enabled = !working, onClick = { onAccept(result) }) {
            Text(stringResource(R.string.ft_edit_info_use))
          }
        }
      }
      TextButton(enabled = !working, onClick = onClear) {
        Text(stringResource(R.string.ft_edit_info_hide_results))
      }
    }
  }
}

@Composable
private fun failureMessage(failure: AudioTagEditFailure): String = stringResource(
  when (failure) {
    AudioTagEditFailure.UNSUPPORTED_FORMAT -> R.string.ft_edit_info_failed_format
    AudioTagEditFailure.PERMISSION_DENIED -> R.string.ft_edit_info_failed_permission
    AudioTagEditFailure.NO_FILE -> R.string.ft_edit_info_failed_nofile
    AudioTagEditFailure.WRITE_FAILED -> R.string.ft_edit_info_failed_write
    AudioTagEditFailure.VERIFY_FAILED -> R.string.ft_edit_info_failed_verify
  }
)

/**
 * Shrinks a picked photo to a sane embedded-cover size. ffmpeg copies the picture verbatim, so an
 * 8 MB camera photo would otherwise be glued onto the end of the song.
 */
private fun resizedCover(context: Context, uri: Uri): Pair<ByteArray, String>? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
  var sample = 1
  while (bounds.outWidth / sample > MAX_COVER_PX || bounds.outHeight / sample > MAX_COVER_PX) sample *= 2
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  val decoded = context.contentResolver.openInputStream(uri)?.use {
    BitmapFactory.decodeStream(it, null, options)
  } ?: return@runCatching null
  val scaled = if (decoded.width > MAX_COVER_PX || decoded.height > MAX_COVER_PX) {
    val ratio = minOf(MAX_COVER_PX.toFloat() / decoded.width, MAX_COVER_PX.toFloat() / decoded.height)
    Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt(), (decoded.height * ratio).toInt(), true)
  } else {
    decoded
  }
  val bytes = ByteArrayOutputStream().use { stream ->
    scaled.compress(Bitmap.CompressFormat.JPEG, 92, stream)
    stream.toByteArray()
  }
  if (scaled !== decoded) scaled.recycle()
  decoded.recycle()
  if (bytes.isEmpty()) null else bytes to "image/jpeg"
}.getOrNull()

private const val MAX_COVER_PX = 1200
