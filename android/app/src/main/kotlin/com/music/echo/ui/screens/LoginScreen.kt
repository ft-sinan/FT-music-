package echo.music.iad1tya.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.innertube.YouTube
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.AccountChannelHandleKey
import echo.music.iad1tya.constants.AccountEmailKey
import echo.music.iad1tya.constants.AccountNameKey
import echo.music.iad1tya.constants.DataSyncIdKey
import echo.music.iad1tya.constants.InnerTubeCookieKey
import echo.music.iad1tya.constants.SavedAccountsKey
import echo.music.iad1tya.constants.VisitorDataKey
import echo.music.iad1tya.models.AccountData
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.utils.rememberPreference
import echo.music.iad1tya.utils.reportException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Google sign-in in an embedded WebView.
 *
 * Two changes matter here beyond the cosmetics. Google rejects sign-in pages that advertise
 * themselves as a WebView - the two-factor step used to stall with no error - so the user agent
 * drops the `; wv` marker and the `Version/x.y` token that give the embedded browser away. And a
 * successful sign-in navigates back instead of killing the process: the old
 * `Runtime.getRuntime().exit(0)` tore down the whole app, playback included, every time somebody
 * logged in.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(
  navController: NavController,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var visitorData by rememberPreference(VisitorDataKey, "")
  var dataSyncId by rememberPreference(DataSyncIdKey, "")
  var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
  var accountName by rememberPreference(AccountNameKey, "")
  var accountEmail by rememberPreference(AccountEmailKey, "")
  var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")
  var savedAccountsJson by rememberPreference(SavedAccountsKey, "[]")
  var hasCompletedLogin by remember { mutableStateOf(false) }

  var webView: WebView? = null

  /** Leaves the screen the way the app expects to be left, whatever the back stack looks like. */
  fun leaveLogin() {
    webView?.apply {
      stopLoading()
      clearHistory()
      clearCache(true)
      clearFormData()
    }
    if (!navController.navigateUp()) navController.backToMain()
  }

  fun saveAccount(account: AccountData, matchByCookie: Boolean) {
    val accounts =
      try {
        Json.decodeFromString<List<AccountData>>(savedAccountsJson)
      } catch (e: Exception) {
        emptyList()
      }.toMutableList()
    accounts.removeAll { if (matchByCookie) it.cookie == account.cookie else it.name == account.name }
    accounts.add(account)
    savedAccountsJson = Json.encodeToString(accounts)
  }

  /**
   * Validates a captured cookie against YouTube Music and keeps the account either way.
   *
   * A failed validation is usually a transient network error rather than a bad login, so the cookie
   * is still saved - throwing it away would force the whole sign-in again for nothing. The user is
   * told the details could not be confirmed instead of being shown a success message FT Music
   * cannot back up.
   */
  fun processSuccessfulAuth(capturedCookie: String) {
    if (hasCompletedLogin) return
    hasCompletedLogin = true
    innerTubeCookie = capturedCookie

    coroutineScope.launch {
      delay(500)

      YouTube.cookie = innerTubeCookie
      YouTube.dataSyncId = dataSyncId
      YouTube.visitorData = visitorData

      Timber.d("Login: validating the captured cookie")

      YouTube.accountInfo()
        .onSuccess {
          accountName = it.name
          accountEmail = it.email.orEmpty()
          accountChannelHandle = it.channelHandle.orEmpty()

          saveAccount(
            AccountData(
              name = it.name,
              email = it.email.orEmpty(),
              channelHandle = it.channelHandle.orEmpty(),
              cookie = innerTubeCookie,
              visitorData = visitorData,
              dataSyncId = dataSyncId,
              avatarUrl = it.thumbnailUrl.orEmpty(),
            ),
            matchByCookie = false,
          )

          Timber.d("Login: signed in as ${it.name}")
          Toast.makeText(
            context,
            context.getString(R.string.logged_in_as, it.name),
            Toast.LENGTH_SHORT,
          ).show()
          leaveLogin()
        }
        .onFailure { error ->
          Timber.e(error, "Login: could not validate the account")
          reportException(error)
          saveAccount(
            AccountData(
              name = accountName.ifBlank { "YouTube Music" },
              email = accountEmail,
              channelHandle = accountChannelHandle,
              cookie = innerTubeCookie,
              visitorData = visitorData,
              dataSyncId = dataSyncId,
              avatarUrl = "",
            ),
            matchByCookie = true,
          )
          Toast.makeText(
            context,
            context.getString(R.string.login_unverified),
            Toast.LENGTH_LONG,
          ).show()
          leaveLogin()
        }
    }
  }

  AndroidView(
    modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current).fillMaxSize(),
    factory = { webViewContext ->
      WebView(webViewContext).apply {
        webViewClient =
          object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
              view: WebView,
              request: WebResourceRequest,
            ): Boolean {
              val url = request.url ?: return false
              val host = url.host?.lowercase().orEmpty()
              val scheme = url.scheme?.lowercase().orEmpty()

              // A non-web scheme is an app handoff: let the system decide who takes it.
              if (scheme != "http" && scheme != "https") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                  .onFailure { Timber.w(it, "Nothing can open $url") }
                return true
              }
              if (isAllowedLoginHost(host)) return false

              runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                .onFailure { Timber.w(it, "Nothing can open $url") }
              return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
              // Reading window.yt.config_ on a page that does not define it throws, so it is only
              // attempted on YouTube, and guarded in the page as well as by the host.
              if (url?.contains("youtube.com") == true) {
                evaluateJavascript(
                  "(function() { return (window.yt && window.yt.config_) ? window.yt.config_.VISITOR_DATA : ''; })();"
                ) { value ->
                  val unquoted = value?.removeSurrounding("\""")
                  if (!unquoted.isNullOrEmpty() && unquoted != "null") visitorData = unquoted
                }
                evaluateJavascript(
                  "(function() { return (window.yt && window.yt.config_) ? window.yt.config_.DATASYNC_ID : ''; })();"
                ) { value ->
                  val unquoted = value?.removeSurrounding("\""")
                  if (!unquoted.isNullOrEmpty() && unquoted != "null") {
                    dataSyncId = unquoted.substringBefore("||")
                  }
                }
                loadUrl(
                  "javascript:Android.onRetrieveVisitorData(window.yt && window.yt.config_ ? window.yt.config_.VISITOR_DATA : '')"
                )
                loadUrl(
                  "javascript:Android.onRetrieveDataSyncId(window.yt && window.yt.config_ ? window.yt.config_.DATASYNC_ID : '')"
                )
              }

              // A two-factor step can finish on www.youtube.com rather than music.youtube.com, so
              // the cookie is accepted on any YouTube host - but only once it really carries an auth
              // token. Judged on the parsed host, never on a URL prefix: startsWith would also match
              // music.youtube.com.attacker.example.
              val musicCookie = CookieManager.getInstance().getCookie("https://music.youtube.com")
              val pageCookie =
                if (!url.isNullOrEmpty()) CookieManager.getInstance().getCookie(url) else null
              val effectiveCookie = musicCookie ?: pageCookie
              val host = url?.let(Uri::parse)?.host?.lowercase().orEmpty()
              val isYouTubeMusic = host == "music.youtube.com"
              val isYouTube = host.endsWith("youtube.com") && isAllowedLoginHost(host)
              val hasAuthToken =
                effectiveCookie?.let { cookie -> AUTH_COOKIE_NAMES.any { cookie.contains(it) } } ==
                  true

              if (effectiveCookie != null &&
                !hasCompletedLogin &&
                (isYouTubeMusic || (isYouTube && hasAuthToken))
              ) {
                processSuccessfulAuth(effectiveCookie)
              }
            }
          }

        // Google's sign-in pages open the recovery and help links in a new window; without a
        // WebChromeClient that tap does nothing at all and the flow dead-ends.
        webChromeClient =
          object : WebChromeClient() {
            override fun onCreateWindow(
              view: WebView?,
              isDialog: Boolean,
              isUserGesture: Boolean,
              resultMsg: Message?,
            ): Boolean {
              val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
              transport.webView = view
              resultMsg.sendToTarget()
              return true
            }
          }

        settings.apply {
          javaScriptEnabled = true
          // Google's sign-in depends on all four of these; without them the page fails part way.
          domStorageEnabled = true
          databaseEnabled = true
          javaScriptCanOpenWindowsAutomatically = true
          setSupportMultipleWindows(true)
          // A WebView holding Google credentials has no business reading local files.
          allowFileAccess = false
          allowContentAccess = false
          setSupportZoom(true)
          builtInZoomControls = true
          displayZoomControls = false
          mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

          // Take the embedded-WebView markers out of the user agent so Google's two-factor step
          // stops rejecting the page.
          val defaultUserAgent = userAgentString ?: WebSettings.getDefaultUserAgent(context)
          userAgentString =
            defaultUserAgent.replace("; wv", "").replace(VERSION_TOKEN_REGEX, "")
        }

        addJavascriptInterface(
          object {
            @JavascriptInterface
            fun onRetrieveVisitorData(newVisitorData: String?) {
              if (newVisitorData != null) visitorData = newVisitorData
            }

            @JavascriptInterface
            fun onRetrieveDataSyncId(newDataSyncId: String?) {
              if (newDataSyncId != null) dataSyncId = newDataSyncId.substringBefore("||")
            }
          },
          "Android",
        )
        webView = this

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // Google's accounts and youtube domains are different origins, so the sign-in cookie is
        // third-party from the page's point of view and is dropped without this.
        cookieManager.setAcceptThirdPartyCookies(this, true)
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        loadUrl(LOGIN_START_URL)
      }
    },
  )

  TopAppBar(
    title = { Text(stringResource(R.string.login)) },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
      }
    },
  )

  BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
}

private const val LOGIN_START_URL =
  "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

/** Any of these in the cookie jar means Google really did authenticate this session. */
private val AUTH_COOKIE_NAMES = listOf("SAPISID", "__Secure-3PSID", "LOGIN_INFO")

/** The WebView's own version token, which together with `; wv` identifies an embedded browser. */
private val VERSION_TOKEN_REGEX = Regex("Version/\\d+\\.\\d+\\s*")
