package echo.music.iad1tya.viewmodels

import android.content.Context
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import echo.music.iad1tya.App
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.AccountChannelHandleKey
import echo.music.iad1tya.constants.AccountEmailKey
import echo.music.iad1tya.constants.AccountNameKey
import echo.music.iad1tya.constants.DataSyncIdKey
import echo.music.iad1tya.constants.InnerTubeCookieKey
import echo.music.iad1tya.constants.VisitorDataKey
import echo.music.iad1tya.utils.SyncUtils
import echo.music.iad1tya.utils.dataStore
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AccountSettingsViewModel
@Inject
constructor(
  private val syncUtils: SyncUtils,
) : ViewModel() {

  fun logoutAndClearSyncedContent(context: Context, onCookieChange: (String) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      syncUtils.clearAllSyncedContent()

      App.forgetAccount(context)

      onCookieChange("")
    }
  }

  fun logoutKeepData(context: Context, onCookieChange: (String) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      App.forgetAccount(context)
      withContext(Dispatchers.Main) { onCookieChange("") }
    }
  }

  /**
   * Makes [accountName] the active YouTube account. Writing the preferences is the whole operation.
   *
   * `App.observeSettingsChanges` already collects the cookie, visitor data and data sync id and
   * assigns them to the live YouTube session, and `HomeViewModel` collects the cookie to clear the
   * previous account's carousels and playlists, re-read the account name and avatar and reload them,
   * discarding any result that came back under the old cookie. Logging out has always worked this
   * way, through the same collectors.
   *
   * This used to relaunch the activity and then kill the process on top of all that, which stopped
   * playback and destroyed every in-memory queue in order to change a preference the app was
   * already watching. The user is told the switch happened, because nothing restarts to show it.
   */
  fun switchAccount(
    context: Context,
    cookie: String,
    visitorData: String,
    dataSyncId: String,
    accountName: String,
    accountEmail: String,
    accountChannelHandle: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      context.dataStore.edit { settings ->
        settings[InnerTubeCookieKey] = cookie
        settings[VisitorDataKey] = visitorData
        settings[DataSyncIdKey] = dataSyncId
        settings[AccountNameKey] = accountName
        settings[AccountEmailKey] = accountEmail
        settings[AccountChannelHandleKey] = accountChannelHandle
      }
      withContext(Dispatchers.Main) {
        Toast.makeText(
          context,
          context.getString(R.string.account_switched, accountName),
          Toast.LENGTH_SHORT,
        ).show()
      }
    }
  }
}
