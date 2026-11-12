package echo.music.iad1tya.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import echo.music.iad1tya.BuildConfig
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.FT_MUSIC_RELEASES_URL
import echo.music.iad1tya.echomusic.updater.getAutoUpdateCheckSetting
import echo.music.iad1tya.echomusic.updater.getBetaUpdatesSetting
import echo.music.iad1tya.echomusic.updater.getUpdateNotificationsSetting
import echo.music.iad1tya.echomusic.updater.saveAutoUpdateCheckSetting
import echo.music.iad1tya.echomusic.updater.saveBetaUpdatesSetting
import echo.music.iad1tya.echomusic.updater.saveUpdateNotificationsSetting
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.component.Material3SettingsGroup
import echo.music.iad1tya.ui.component.Material3SettingsItem
import echo.music.iad1tya.ui.utils.backToMain

/**
 * Update controls. Checking in the app, downloading and installing are all deliberate acts: the
 * automatic check is opt-in, and Android shows its own confirmation before anything is installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettings(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
  highlightKey: String? = null
) {
  val context = LocalContext.current
  val scrollState = rememberScrollState()
  var autoCheck by remember { mutableStateOf(getAutoUpdateCheckSetting(context)) }
  var betaUpdates by remember { mutableStateOf(getBetaUpdatesSetting(context)) }
  var notifyAboutUpdates by remember { mutableStateOf(getUpdateNotificationsSetting(context)) }

  Column(
    Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp)
  ) {
    Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
      scrollState = scrollState,
      title = stringResource(R.string.app_updates_title),
      items = listOf(
        Material3SettingsItem(
          isHighlighted = highlightKey == stringResource(R.string.system_update),
          icon = painterResource(R.drawable.update),
          title = { Text(stringResource(R.string.check_for_updates_in_app)) },
          description = {
            Text(stringResource(R.string.check_for_updates_in_app_subtitle))
          },
          onClick = { navController.navigate("update") }
        ),
        Material3SettingsItem(
          icon = painterResource(R.drawable.update),
          title = { Text(stringResource(R.string.auto_update_check)) },
          description = { Text(stringResource(R.string.auto_update_check_subtitle)) },
          trailingContent = {
            Switch(
              checked = autoCheck,
              onCheckedChange = {
                autoCheck = it
                saveAutoUpdateCheckSetting(context, it)
              }
            )
          },
          onClick = {
            autoCheck = !autoCheck
            saveAutoUpdateCheckSetting(context, autoCheck)
          }
        ),
        Material3SettingsItem(
          icon = painterResource(R.drawable.update),
          title = { Text(stringResource(R.string.update_notifications)) },
          description = { Text(stringResource(R.string.update_notifications_subtitle)) },
          trailingContent = {
            Switch(
              checked = notifyAboutUpdates,
              onCheckedChange = {
                notifyAboutUpdates = it
                saveUpdateNotificationsSetting(context, it)
              }
            )
          },
          onClick = {
            notifyAboutUpdates = !notifyAboutUpdates
            saveUpdateNotificationsSetting(context, notifyAboutUpdates)
          }
        ),
        Material3SettingsItem(
          icon = painterResource(R.drawable.update),
          title = { Text(stringResource(R.string.beta_updates)) },
          description = { Text(stringResource(R.string.beta_updates_subtitle_stable)) },
          trailingContent = {
            Switch(
              checked = betaUpdates,
              onCheckedChange = {
                betaUpdates = it
                saveBetaUpdatesSetting(context, it)
              }
            )
          },
          onClick = {
            betaUpdates = !betaUpdates
            saveBetaUpdatesSetting(context, betaUpdates)
          }
        ),
        Material3SettingsItem(
          icon = painterResource(R.drawable.update),
          title = { Text("Open FT Music releases") },
          description = { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME)) },
          onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FT_MUSIC_RELEASES_URL)))
          }
        )
      )
    )
    Text(
      text = stringResource(R.string.updates_how_it_works),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
    )
    Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)))
  }
  TopAppBar(
    title = { Text(stringResource(R.string.update_settings_title)) },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
      }
    }
  )
}
