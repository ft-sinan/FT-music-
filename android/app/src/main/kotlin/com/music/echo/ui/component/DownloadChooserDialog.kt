package echo.music.iad1tya.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.DownloadQuality
import echo.music.iad1tya.playback.DownloadChooserController
import echo.music.iad1tya.playback.DownloadChooserFailure
import echo.music.iad1tya.playback.DownloadChooserStage
import echo.music.iad1tya.ui.utils.formatFileSize

/** Labels match the download-quality setting so the two screens cannot disagree. */
internal fun downloadQualityLabel(quality: DownloadQuality): Int =
  if (quality.canonical() == DownloadQuality.DATA_SAVER) R.string.ft_download_quality_saver
  else R.string.ft_download_quality_high

/** Reports only what the source actually said; unknown values stay visibly unknown. */
internal fun downloadOptionDetails(
  codecs: String?,
  bitrate: Int?,
  sampleRate: Int?,
  sizeBytes: Long?,
  unknownSize: String,
): String {
  val parts = mutableListOf<String>()
  codecs?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it.replaceFirstChar(Char::uppercase) }
  bitrate?.takeIf { it > 0 }?.let { parts += "${it / 1000} kbps" }
  sampleRate?.takeIf { it > 0 }?.let {
    // A technical spec keeps a fixed decimal separator whatever the device language is.
    val khz = java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols(java.util.Locale.ROOT))
      .format(it / 1000.0)
    parts += "$khz kHz"
  }
  parts += sizeBytes?.takeIf { size -> size > 0 }?.let { formatFileSize(it) } ?: unknownSize
  return parts.joinToString(" · ")
}

/**
 * Offers the formats the resolver actually returned for this song, with YouTube High suggested.
 * No file-size limit is applied, and a batch download asks once for every song in it.
 */
@Composable
fun DownloadChooserDialog(controller: DownloadChooserController) {
  val state by controller.state.collectAsState()
  val current = state ?: return
  val unknownSize = stringResource(R.string.ft_download_chooser_size_unknown)

  AlertDialog(
    onDismissRequest = controller::dismiss,
    title = {
      val stage = current.stage
      val count = if (stage is DownloadChooserStage.Ready) stage.itemCount else current.items.size
      Text(
        if (count > 1) stringResource(R.string.ft_download_chooser_title_multiple, count)
        else stringResource(R.string.ft_download_chooser_title)
      )
    },
    text = {
      Column(
        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        when (val stage = current.stage) {
          DownloadChooserStage.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ft_download_chooser_loading))
          }

          is DownloadChooserStage.Ready -> {
            if (stage.itemCount > 1) {
              Text(
                stringResource(
                  R.string.ft_download_chooser_multiple_note, stage.sampleTitle, stage.itemCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            stage.options.forEach { option ->
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { controller.choose(option.quality) }
                  .padding(vertical = 10.dp),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    stringResource(downloadQualityLabel(option.quality)),
                    style = MaterialTheme.typography.bodyLarge,
                  )
                  if (option.isRecommended) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                      stringResource(R.string.ft_download_chooser_suggested),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
                Text(
                  downloadOptionDetails(
                    option.codecs, option.bitrate, option.sampleRate, option.sizeBytes, unknownSize,
                  ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            // A quality the resolver could not return is reported instead of silently dropped.
            val offered = stage.options.map { it.quality.canonical() }.toSet()
            stage.unavailable
              .map { it.canonical() }
              .distinct()
              .filterNot { it in offered }
              .forEach { quality ->
                Text(
                  stringResource(
                    R.string.ft_download_chooser_unavailable,
                    stringResource(downloadQualityLabel(quality)),
                  ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            if (stage.lockedCount > 0) {
              Text(
                stringResource(R.string.ft_download_chooser_locked_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          is DownloadChooserStage.Failed -> Text(
            stringResource(
              if (stage.reason == DownloadChooserFailure.NO_AUDIO_FORMAT) {
                R.string.ft_download_chooser_no_options
              } else {
                R.string.ft_download_chooser_lookup_failed
              }
            )
          )
        }
      }
    },
    confirmButton = {
      if (current.stage is DownloadChooserStage.Failed) {
        TextButton(onClick = controller::retry) { Text(stringResource(R.string.retry)) }
      }
    },
    dismissButton = {
      TextButton(onClick = controller::dismiss) { Text(stringResource(R.string.cancel)) }
    },
  )
}
