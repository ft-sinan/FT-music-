package echo.music.iad1tya.ui.component

import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.DataSaverEnabledKey
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.ui.player.ManualCanvasSelections
import echo.music.iad1tya.ui.player.searchManualCanvas
import echo.music.iad1tya.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun rememberLoadAnimationItem(metadata: MediaMetadata, onDismiss: () -> Unit): Material3MenuItemData {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val dataSaver by rememberPreference(DataSaverEnabledKey, false)
  var loading by remember(metadata.id) { mutableStateOf(false) }

  return Material3MenuItemData(
    title = { Text(stringResource(if (loading) R.string.animation_searching else R.string.animation_load)) },
    description = { Text(stringResource(R.string.animation_load_desc)) },
    icon = { Icon(painterResource(R.drawable.slow_motion_video), contentDescription = null) },
    onClick = if (loading) null else {
      {
        if (dataSaver) {
          Toast.makeText(context, R.string.animation_data_saver, Toast.LENGTH_LONG).show()
        } else {
          scope.launch {
            loading = true
            try {
              val artwork = searchManualCanvas(metadata)
              if (artwork == null) {
                Toast.makeText(context, R.string.animation_not_found, Toast.LENGTH_LONG).show()
              } else {
                val saved = ManualCanvasSelections.save(context, metadata.id, artwork)
                ManualCanvasSelections.select(metadata.id, artwork)
                Toast.makeText(context, if (saved) R.string.animation_found else R.string.animation_found_session, Toast.LENGTH_LONG).show()
                onDismiss()
              }
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Exception) {
              Toast.makeText(context, R.string.animation_not_found, Toast.LENGTH_LONG).show()
            } finally {
              loading = false
            }
          }
        }
      }
    },
  )
}
