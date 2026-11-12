package echo.music.iad1tya.echomusic.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil3.compose.AsyncImage
import echo.music.iad1tya.BuildConfig
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.downloadmanager.DownloadNotificationManager
import echo.music.iad1tya.echomusic.updater.downloadmanager.UpdateDownloadWorker
import echo.music.iad1tya.ui.component.AnimatedActionButton
import echo.music.iad1tya.ui.component.ChangelogItem
import echo.music.iad1tya.ui.component.DefaultDialog
import echo.music.iad1tya.ui.component.ErrorSnackbar
import echo.music.iad1tya.ui.component.ExpressiveIconButton
import echo.music.iad1tya.ui.component.detachedItemShape
import echo.music.iad1tya.ui.component.endItemShape
import echo.music.iad1tya.ui.component.leadingItemShape
import echo.music.iad1tya.ui.component.middleItemShape
import echo.music.iad1tya.ui.component.parseMarkdown
import echo.music.iad1tya.ui.utils.parseMarkdownToSections
import echo.music.iad1tya.ui.utils.parseSimpleMarkdown
import java.io.File
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChangelogSection(val title: String, val items: List<String>)

sealed class EchoUpdateStatus {
  object Idle : EchoUpdateStatus()

  object Checking : EchoUpdateStatus()

  data class Available(
    val version: String,
    val changelog: List<ChangelogSection>,
    val size: String,
    val releaseDate: String,
    val description: String?,
    val imageUrl: String?,
    val apk: UpdateApk,
    val releaseUrl: String
  ) : EchoUpdateStatus()

  /** A newer release exists, but it publishes nothing this device can install. */
  data class NoPackageForDevice(val version: String, val releaseUrl: String) : EchoUpdateStatus()

  data class NoUpdate(val version: String) : EchoUpdateStatus()

  data class Error(val message: String) : EchoUpdateStatus()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(navController: NavHostController) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var status by remember {
    mutableStateOf<EchoUpdateStatus>(EchoUpdateStatus.NoUpdate(BuildConfig.VERSION_NAME))
  }
  var isDownloading by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableStateOf(0f) }
  var isDownloadComplete by remember { mutableStateOf(false) }
  var downloadedFile by remember { mutableStateOf<File?>(null) }
  // Whether the finished download proved itself. `verified` means the checksum matched the
  // release's published SHA256SUMS.txt; `signatureUnreadable` means the checksum matched but this
  // device would not report the signing certificate, so the user is told rather than left guessing.
  var downloadVerified by remember { mutableStateOf(false) }
  var signatureUnreadable by remember { mutableStateOf(false) }
  var downloadRejected by remember { mutableStateOf<String?>(null) }
  // WorkManager keeps the result of the last run, so opening this screen again would otherwise
  // re-apply a download from a previous session - possibly one for an older release. Only a result
  // carrying the token of a download this screen started is acted on.
  var downloadToken by remember { mutableStateOf(0) }
  val snackbarHostState = remember { SnackbarHostState() }

  val currentVersion = BuildConfig.VERSION_NAME
  val autoUpdateCheckEnabled = getAutoUpdateCheckSetting(context)

  LaunchedEffect(Unit) { DownloadNotificationManager.initialize(context) }

  LaunchedEffect(Unit) {
    WorkManager.getInstance(context)
      .getWorkInfosForUniqueWorkLiveData("update_download")
      .observeForever { workInfos ->
        val workInfo = workInfos?.firstOrNull() ?: return@observeForever
        // A finished result from an earlier session is ignored; a running one is still worth
        // showing, because it can only be a download the user started.
        if (
          workInfo.state.isFinished &&
            workInfo.outputData.getLong("run_token", -1L) != downloadToken.toLong()
        ) {
          return@observeForever
        }

        when (workInfo.state) {
          WorkInfo.State.RUNNING -> {
            isDownloading = true
            downloadProgress = workInfo.progress.getFloat("progress", 0f)
          }
          WorkInfo.State.SUCCEEDED -> {
            isDownloading = false
            val filePath = workInfo.outputData.getString("file_path")
            val file = filePath?.let { File(it) }
            if (file == null || !file.exists()) {
              isDownloadComplete = false
              downloadedFile = null
              return@observeForever
            }
            // The worker already proved the checksum. What is left is the signature: a package
            // signed by anybody else must not reach the installer, because Android's own refusal
            // asks the user to uninstall first and that would destroy their library.
            when (checkApkSignature(context, file)) {
              SignatureMatch.FT_MUSIC -> {
                downloadedFile = file
                downloadVerified = workInfo.outputData.getBoolean("verified", false)
                signatureUnreadable = false
                downloadRejected = null
                isDownloadComplete = true
              }
              SignatureMatch.UNREADABLE -> {
                downloadedFile = file
                downloadVerified = workInfo.outputData.getBoolean("verified", false)
                signatureUnreadable = true
                downloadRejected = null
                isDownloadComplete = true
              }
              SignatureMatch.DIFFERENT -> {
                runCatching { file.delete() }
                isDownloadComplete = false
                downloadedFile = null
                downloadProgress = 0f
                downloadRejected = context.getString(R.string.update_signature_mismatch)
              }
            }
          }
          WorkInfo.State.FAILED -> {
            isDownloading = false
            isDownloadComplete = false
            downloadedFile = null
            downloadProgress = 0f
            val reason = workInfo.outputData.getString("failure_reason")
            val message =
              if (reason == UpdateDownloadWorker.REASON_CHECKSUM_MISMATCH) {
                context.getString(R.string.update_checksum_mismatch)
              } else {
                context.getString(R.string.download_failed)
              }
            downloadRejected = message
          }
          WorkInfo.State.CANCELLED -> {
            isDownloading = false
            isDownloadComplete = false
            downloadedFile = null
            downloadProgress = 0f
          }
          else -> {}
        }
      }
  }

  LaunchedEffect(isDownloadComplete, downloadedFile) {
    if (isDownloadComplete && downloadedFile != null) {
      if (!downloadedFile!!.exists()) {
        isDownloadComplete = false
        downloadedFile = null
        downloadProgress = 0f
        downloadVerified = false
        signatureUnreadable = false
      }
    }
  }

  /**
   * Offers an already-downloaded package for install, but only after proving it again here. A file
   * that no longer matches, or that is signed by anybody else, is discarded rather than adopted.
   */
  fun adoptVerifiedDownload(apk: UpdateApk) {
    val file = findVerifiedDownload(context, apk) ?: return
    downloadedFile = file
    downloadVerified = true
    signatureUnreadable = checkApkSignature(context, file) == SignatureMatch.UNREADABLE
    downloadRejected = null
    downloadProgress = 1f
    isDownloading = false
    isDownloadComplete = true
  }

  fun triggerUpdateCheck() {
    status = EchoUpdateStatus.Checking
    // A previous download belongs to a previous check: forget it, and its verdict with it.
    isDownloading = false
    isDownloadComplete = false
    downloadedFile = null
    downloadProgress = 0f
    downloadVerified = false
    signatureUnreadable = false
    downloadRejected = null
    scope.launch {
      delay(1000L)
      when (val outcome = checkForUpdate(context)) {
        is UpdateCheckOutcome.Available -> {
          saveLastCheckedTime(
            context,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a"))
          )
          saveUpdateAvailableState(context, true)
          adoptVerifiedDownload(outcome.apk)
          status =
            EchoUpdateStatus.Available(
              version = outcome.version,
              changelog = outcome.changelog,
              size = outcome.size,
              releaseDate = outcome.releaseDate,
              description = outcome.description,
              imageUrl = outcome.imageUrl,
              apk = outcome.apk,
              releaseUrl = outcome.releaseUrl
            )
        }
        is UpdateCheckOutcome.NoPackageForDevice -> {
          saveLastCheckedTime(
            context,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a"))
          )
          saveUpdateAvailableState(context, false)
          status =
            EchoUpdateStatus.NoPackageForDevice(outcome.version, outcome.releaseUrl)
        }
        is UpdateCheckOutcome.UpToDate -> {
          saveLastCheckedTime(
            context,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a"))
          )
          saveUpdateAvailableState(context, false)
          status = EchoUpdateStatus.NoUpdate(outcome.version)
        }
        is UpdateCheckOutcome.Failed -> {
          saveUpdateAvailableState(context, false)
          status = EchoUpdateStatus.Error(context.getString(R.string.cant_check_updates))
        }
      }
    }
  }

  // Reaching this screen is already a deliberate tap - from Settings, or from a notification that
  // says an update is ready - so it checks rather than showing a button to press again. The
  // setting is kept as the way to opt out.
  LaunchedEffect(Unit) {
    if (autoUpdateCheckEnabled) {
      triggerUpdateCheck()
    } else {
      status = EchoUpdateStatus.Checking
      when (val outcome = checkForUpdate(context)) {
        is UpdateCheckOutcome.Available -> {
          saveLastCheckedTime(
            context,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a"))
          )
          saveUpdateAvailableState(context, true)
          adoptVerifiedDownload(outcome.apk)
          status =
            EchoUpdateStatus.Available(
              version = outcome.version,
              changelog = outcome.changelog,
              size = outcome.size,
              releaseDate = outcome.releaseDate,
              description = outcome.description,
              imageUrl = outcome.imageUrl,
              apk = outcome.apk,
              releaseUrl = outcome.releaseUrl
            )
        }
        is UpdateCheckOutcome.NoPackageForDevice -> {
          saveUpdateAvailableState(context, false)
          status = EchoUpdateStatus.NoPackageForDevice(outcome.version, outcome.releaseUrl)
        }
        is UpdateCheckOutcome.UpToDate -> {
          saveUpdateAvailableState(context, false)
          status = EchoUpdateStatus.NoUpdate(outcome.version)
        }
        is UpdateCheckOutcome.Failed -> {
          saveUpdateAvailableState(context, false)
          status = EchoUpdateStatus.Error(context.getString(R.string.cant_check_updates))
        }
      }
    }
  }

  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    topBar = {
      LargeTopAppBar(
        title = {
          val titleText =
            if (status is EchoUpdateStatus.Available) {
              buildAnnotatedString {
                append(stringResource(R.string.new_update) + " ")
                withStyle(
                  SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                ) {
                  append((status as EchoUpdateStatus.Available).version)
                }
              }
            } else {
              AnnotatedString(stringResource(R.string.settings_check_updates_title))
            }
          Text(text = titleText, maxLines = 1)
        },
        navigationIcon = {
          Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
            ExpressiveIconButton(
              onClick = { navController.navigateUp() },
              painter = painterResource(R.drawable.arrow_back),
              contentDescription = stringResource(R.string.cancel),
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface
            )
          }
        },
        scrollBehavior = scrollBehavior,
        colors =
          TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
          )
      )
    },
    bottomBar = {
      Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .padding(WindowInsets.navigationBars.asPaddingValues())
              .padding(horizontal = 24.dp, vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          downloadRejected?.let { message ->
            Text(
              text = message,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
          }
          when (val currentStatus = status) {
            is EchoUpdateStatus.Idle,
            is EchoUpdateStatus.Checking,
            is EchoUpdateStatus.NoUpdate,
            is EchoUpdateStatus.Error -> {
              AnimatedActionButton(
                text = stringResource(R.string.check_for_update),
                onClick = { triggerUpdateCheck() },
                enabled = currentStatus !is EchoUpdateStatus.Checking && !isDownloading,
                modifier = Modifier.fillMaxWidth()
              )
            }
            is EchoUpdateStatus.NoPackageForDevice -> {
              AnimatedActionButton(
                text = stringResource(R.string.open_release_in_browser),
                onClick = {
                  runCatching {
                    context.startActivity(
                      Intent(Intent.ACTION_VIEW, Uri.parse(currentStatus.releaseUrl))
                    )
                  }
                },
                modifier = Modifier.fillMaxWidth()
              )
            }
            is EchoUpdateStatus.Available -> {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                AnimatedActionButton(
                  text = stringResource(R.string.later),
                  onClick = { navController.navigateUp() },
                  modifier = Modifier.weight(1f),
                  isOutlined = true,
                  enabled = !isDownloading
                )
                AnimatedActionButton(
                  text =
                    if (isDownloading) "${(downloadProgress * 100).toInt()}%"
                    else if (isDownloadComplete) stringResource(R.string.install)
                    else stringResource(R.string.update_available),
                  onClick = {
                    if (isDownloadComplete) {
                      val file = downloadedFile
                      if (file == null || !file.exists()) {
                        isDownloadComplete = false
                        downloadedFile = null
                        downloadProgress = 0f
                        downloadVerified = false
                        signatureUnreadable = false
                        return@AnimatedActionButton
                      }
                      // Last gate before Android's own confirmation. The checksum was proven when
                      // the download finished; a certificate that could be read and is not FT
                      // Music's stops here, because installing it would first require uninstalling
                      // the app and losing the library.
                      if (!downloadVerified && !signatureUnreadable) {
                        downloadRejected = context.getString(R.string.update_not_verified)
                        isDownloadComplete = false
                        downloadedFile = null
                        downloadProgress = 0f
                        return@AnimatedActionButton
                      }
                      when (checkApkSignature(context, file)) {
                        SignatureMatch.DIFFERENT -> {
                          runCatching { file.delete() }
                          downloadRejected =
                            context.getString(R.string.update_signature_mismatch)
                          isDownloadComplete = false
                          downloadedFile = null
                          downloadProgress = 0f
                          return@AnimatedActionButton
                        }
                        SignatureMatch.UNREADABLE -> signatureUnreadable = true
                        SignatureMatch.FT_MUSIC -> signatureUnreadable = false
                      }
                      file.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                          if (!context.packageManager.canRequestPackageInstalls()) {
                            val intent =
                              Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .apply { data = Uri.parse("package:${context.packageName}") }
                            context.startActivity(intent)
                            return@let
                          }
                        }
                        val uri =
                          FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.FileProvider",
                            file
                          )
                        val installIntent =
                          Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                          }
                        // Android shows its own confirmation, so nothing installs silently.
                        ContextCompat.startActivity(context, installIntent, null)
                      }
                    } else {
                      // No guessed URLs: the address, the file name and the checksum all come
                      // from the release itself. A release that publishes no checksum cannot be
                      // verified, so its update is not offered for download at all.
                      if (currentStatus.apk.sha256.isNullOrBlank()) {
                        downloadRejected = context.getString(R.string.update_not_verified)
                        return@AnimatedActionButton
                      }
                      downloadRejected = null
                      val constraints =
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

                      val downloadRequest =
                        OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                          .setInputData(
                            workDataOf(
                              "apk_url" to currentStatus.apk.downloadUrl,
                              "apk_name" to currentStatus.apk.name,
                              "expected_sha256" to currentStatus.apk.sha256,
                              "version" to currentStatus.version,
                              "file_size" to currentStatus.size,
                              "run_token" to (downloadToken + 1).toLong()
                            )
                          )
                          .setConstraints(constraints)
                          .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            10,
                            java.util.concurrent.TimeUnit.SECONDS
                          )
                          .addTag("update_download")
                          .build()
                      WorkManager.getInstance(context)
                        .enqueueUniqueWork(
                          "update_download",
                          ExistingWorkPolicy.REPLACE,
                          downloadRequest
                        )
                      isDownloading = true
                      downloadProgress = 0f
                      downloadToken += 1
                    }
                  },
                  modifier = Modifier.weight(1f),
                  enabled = !isDownloading || isDownloadComplete
                )
              }
            }
          }
        }
      }
    },
    snackbarHost = { ErrorSnackbar(hostState = snackbarHostState) },
    containerColor = MaterialTheme.colorScheme.background,
    modifier =
      Modifier.widthIn(max = 700.dp)
        .fillMaxSize()
        .nestedScroll(scrollBehavior.nestedScrollConnection)
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        item {
          val contentModifier =
            if (status is EchoUpdateStatus.Available) {
              Modifier.fillMaxWidth()
            } else {
              Modifier.fillParentMaxSize()
            }

          Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
            when (val currentStatus = status) {
              is EchoUpdateStatus.Checking -> {
                androidx.compose.material3.ContainedLoadingIndicator(
                  modifier = Modifier.size(64.dp)
                )
              }
              is EchoUpdateStatus.NoUpdate -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(
                    painter = painterResource(R.drawable.deployed_app_update),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.primary
                  )
                  Spacer(modifier = Modifier.height(24.dp))
                  Text(
                    text = stringResource(R.string.on_latest_version),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = stringResource(R.string.current_version_v, currentStatus.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                  )
                }
              }
              is EchoUpdateStatus.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(
                    painter = painterResource(R.drawable.error),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.error
                  )
                  Spacer(modifier = Modifier.height(24.dp))
                  Text(
                    text = currentStatus.message,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
              is EchoUpdateStatus.NoPackageForDevice -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(
                    painter = painterResource(R.drawable.error),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.error
                  )
                  Spacer(modifier = Modifier.height(24.dp))
                  Text(
                    text = stringResource(R.string.update_no_package_title, currentStatus.version),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = stringResource(R.string.update_no_package_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                  )
                }
              }
              is EchoUpdateStatus.Available -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                  Spacer(modifier = Modifier.height(12.dp))
                  if (currentStatus.releaseDate.isNotBlank()) {
                    Text(
                      text = stringResource(R.string.release_date_v, currentStatus.releaseDate),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  // The package that will be installed, and only a size the release states.
                  Text(
                    text = stringResource(R.string.update_package_name, currentStatus.apk.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  if (currentStatus.size.isNotEmpty()) {
                    Text(
                      text = stringResource(R.string.update_size_v, currentStatus.size),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Spacer(modifier = Modifier.height(24.dp))
                  if (!currentStatus.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                      model = currentStatus.imageUrl,
                      contentDescription = null,
                      modifier =
                        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)),
                      contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                  }
                  if (!currentStatus.description.isNullOrBlank()) {
                    val annotatedText = currentStatus.description.parseMarkdown()

                    ClickableText(
                      text = annotatedText,
                      onClick = { offset ->
                        annotatedText
                          .getStringAnnotations("URL", offset, offset)
                          .firstOrNull()
                          ?.let {
                            ContextCompat.startActivity(
                              context,
                              Intent(Intent.ACTION_VIEW, Uri.parse(it.item)),
                              null
                            )
                          }
                      },
                      style =
                        MaterialTheme.typography.bodyMedium.copy(
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          lineHeight = 20.sp
                        ),
                      modifier = Modifier.padding(bottom = 24.dp)
                    )
                  }

                  currentStatus.changelog.forEach { section ->
                    Text(
                      text = section.title,
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onBackground,
                      modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    section.items.forEachIndexed { index, item ->
                      val shape =
                        when {
                          section.items.size == 1 -> detachedItemShape()
                          index == 0 -> leadingItemShape()
                          index == section.items.size - 1 -> endItemShape()
                          else -> middleItemShape()
                        }
                      ChangelogItem(text = item, shape = shape)
                      if (index != section.items.size - 1) {
                        Spacer(modifier = Modifier.height(2.dp))
                      }
                    }
                  }

                  if (currentStatus.changelog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                  }

                  if (signatureUnreadable && isDownloadComplete) {
                    Text(
                      text = stringResource(R.string.update_signature_unreadable),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.error,
                      modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                  }

                  if (isDownloading) {
                    if (downloadProgress > 0f) {
                      androidx.compose.material3.LinearProgressIndicator(
                        progress = downloadProgress,
                        modifier =
                          Modifier.fillMaxWidth()
                            .height(8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                      )
                    } else {
                      androidx.compose.material3.LinearProgressIndicator(
                        modifier =
                          Modifier.fillMaxWidth()
                            .height(8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                      )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                  }
                }
              }
              else -> {}
            }
          }
        }
      }
    }
  }
}

const val PREFS_NAME = "settings"
const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
const val KEY_LAST_CHECKED_TIME = "last_checked_time"
const val KEY_BETA_UPDATES = "beta_updates"
const val KEY_UPDATE_AVAILABLE = "update_available"
const val KEY_LAST_AUTOMATIC_CHECK_MS = "last_automatic_check_ms"
const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"
const val KEY_LAST_SEEN_UPDATE_TAG = "last_seen_update_tag"

/** How often the app may look for a newer release on its own, when the user allowed it. */
const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

fun getUpdateAvailableState(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_UPDATE_AVAILABLE, false)
}

fun saveUpdateAvailableState(context: Context, available: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_UPDATE_AVAILABLE, available).apply()
}

/**
 * Whether the update screen checks as soon as it opens. Off by default. Downloading and installing
 * are always a separate, deliberate tap whichever way this is set.
 */
fun getAutoUpdateCheckSetting(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_AUTO_UPDATE_CHECK, false)
}

fun saveAutoUpdateCheckSetting(context: Context, enabled: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, enabled).apply()
}

const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"

fun getUpdateNotificationsSetting(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_UPDATE_NOTIFICATIONS, true)
}

fun saveUpdateNotificationsSetting(context: Context, enabled: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_UPDATE_NOTIFICATIONS, enabled).apply()
}

fun saveLastCheckedTime(context: Context, timestamp: String) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putString(KEY_LAST_CHECKED_TIME, timestamp).apply()
}

fun getLastCheckedTime(context: Context): String {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getString(KEY_LAST_CHECKED_TIME, "") ?: ""
}

fun getBetaUpdatesSetting(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_BETA_UPDATES, false)
}

fun saveBetaUpdatesSetting(context: Context, enabled: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_BETA_UPDATES, enabled).apply()
}

private fun formatGitHubDate(githubDate: String): String =
  try {
    val githubFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val displayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")
    val dateTime = LocalDateTime.parse(githubDate, githubFormatter)
    dateTime.format(displayFormatter)
  } catch (e: Exception) {
    githubDate
  }

fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
  val latestVersionClean = latestVersion.removePrefix("b").removePrefix("v")
  val currentVersionClean = currentVersion.removePrefix("b").removePrefix("v")

  val latestParts = latestVersionClean.split(".").map { it.toIntOrNull() ?: 0 }
  val currentParts = currentVersionClean.split(".").map { it.toIntOrNull() ?: 0 }

  for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
    val latest = latestParts.getOrElse(i) { 0 }
    val current = currentParts.getOrElse(i) { 0 }
    when {
      latest > current -> return true
      latest < current -> return false
    }
  }

  if (latestVersionClean == currentVersionClean) {
    val latestIsBeta = latestVersion.startsWith("b")
    val currentIsBeta = currentVersion.startsWith("b")

    if (currentIsBeta && !latestIsBeta) return true
  }

  return false
}

/** What a check for updates concluded. */
sealed interface UpdateCheckOutcome {
  /** A newer release exists and this device has a package it can install. */
  data class Available(
    val version: String,
    val changelog: List<ChangelogSection>,
    val size: String,
    val releaseDate: String,
    val description: String?,
    val imageUrl: String?,
    val apk: UpdateApk,
    val releaseUrl: String
  ) : UpdateCheckOutcome

  /** A newer release exists but publishes no package for this device's ABI. */
  data class NoPackageForDevice(val version: String, val releaseUrl: String) : UpdateCheckOutcome

  /** Nothing newer than what is installed. */
  data class UpToDate(val version: String) : UpdateCheckOutcome

  /** The check could not be completed, so nothing is claimed about being up to date. */
  data class Failed(val problem: UpdateDiscoveryProblem) : UpdateCheckOutcome
}

/**
 * Looks for a newer FT Music release and picks the package that belongs on this device.
 *
 * The release notes come from the release body, which is what the published notes file becomes;
 * there is no separate machine-readable changelog asset to fetch. The APK is chosen from
 * `Build.SUPPORTED_ABIS` with the universal package as a fallback, and its SHA-256 is read from the
 * release's own `SHA256SUMS.txt` so the download can be verified before it is offered.
 */
suspend fun checkForUpdate(
  context: Context,
  includePrereleases: Boolean = getBetaUpdatesSetting(context),
  supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
): UpdateCheckOutcome =
  withContext(Dispatchers.IO) {
    val currentVersion = BuildConfig.VERSION_NAME
    val discovery =
      runCatching {
        discoverUpdate(
          currentVersion = currentVersion,
          supportedAbis = supportedAbis,
          includePrereleases = includePrereleases,
          fetchText = { url -> httpGetText(url) }
        )
      }.getOrElse { error ->
        Log.e("UpdateCheck", "Error checking for updates: ${error.message}", error)
        return@withContext UpdateCheckOutcome.Failed(classifyDiscoveryError(error))
      }

    discovery.problem?.let { return@withContext UpdateCheckOutcome.Failed(it) }
    val release = discovery.release ?: return@withContext UpdateCheckOutcome.Failed(
      UpdateDiscoveryProblem.NoRelease
    )

    if (!release.isNewerThan(currentVersion)) {
      return@withContext UpdateCheckOutcome.UpToDate(currentVersion)
    }
    if (release.noPackageForThisDevice) {
      return@withContext UpdateCheckOutcome.NoPackageForDevice(release.tag, release.releaseUrl)
    }
    val apk = release.apk
      ?: return@withContext UpdateCheckOutcome.NoPackageForDevice(release.tag, release.releaseUrl)

    val body = release.body ?: context.getString(R.string.no_changelog_available)
    var imageUrl: String? = null
    var changelogBody = body
    val imageRegex = Regex("!\\[(.*?)\\]\\((.*?)\\)")
    imageRegex.find(body)?.let { match ->
      imageUrl = match.groupValues[2]
      changelogBody = body.replace(match.value, "").trim()
    }
    val (parsedDescription, sections) = parseMarkdownToSections(changelogBody)

    UpdateCheckOutcome.Available(
      version = release.tag,
      changelog = sections,
      size = if (apk.sizeBytes > 0) String.format("%.1f", apk.sizeBytes / (1024.0 * 1024.0)) else "",
      releaseDate = release.publishedAt?.let { formatGitHubDate(it) } ?: "",
      description = parsedDescription ?: changelogBody.takeIf { sections.isEmpty() },
      imageUrl = imageUrl,
      apk = apk,
      releaseUrl = release.releaseUrl
    )
  }

/**
 * A package already on disk that was downloaded for this exact release. Recognising it means a
 * notification tap lands on a working Install button instead of a second download, and it is only
 * ever recognised after the published checksum and the signing certificate have been checked again.
 */
fun findVerifiedDownload(context: Context, apk: UpdateApk): File? {
  val name = safeApkFileName(apk.name) ?: return null
  val file = File(getDownloadedApksDir(context), name)
  if (!file.exists() || file.length() <= 0L) return null
  if (!matchesPublishedChecksum(file, apk.sha256)) return null
  if (checkApkSignature(context, file) == SignatureMatch.DIFFERENT) {
    runCatching { file.delete() }
    return null
  }
  return file
}

private fun classifyDiscoveryError(error: Throwable): UpdateDiscoveryProblem = when (error) {
  is java.io.IOException -> UpdateDiscoveryProblem.Unreachable(error.message)
  else -> UpdateDiscoveryProblem.UnexpectedResponse
}

/** The tag of the newest release the app has already told the user about. */
fun getLastSeenUpdateTag(context: Context): String {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getString(KEY_LAST_SEEN_UPDATE_TAG, "") ?: ""
}

fun saveLastSeenUpdateTag(context: Context, tag: String) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putString(KEY_LAST_SEEN_UPDATE_TAG, tag).apply()
}

/**
 * Whether looking for an update in the background is due. This is the switch behind "Update
 * notifications": with it off the app never reaches the network for updates on its own. With it on
 * the app looks at most once a day, and only ever reports what it finds - it never downloads or
 * installs anything by itself.
 */
fun isAutomaticUpdateCheckDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
  if (!getUpdateNotificationsSetting(context)) return false
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  val last = sharedPrefs.getLong(KEY_LAST_AUTOMATIC_CHECK_MS, 0L)
  // A clock that moved backwards should not block checks forever.
  if (last <= 0L || nowMs < last) return true
  return nowMs - last >= AUTOMATIC_UPDATE_CHECK_INTERVAL_MS
}

fun saveAutomaticUpdateCheckTime(context: Context, nowMs: Long = System.currentTimeMillis()) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putLong(KEY_LAST_AUTOMATIC_CHECK_MS, nowMs).apply()
}

/** A release the user already declined, so it is not offered again until something newer appears. */
fun getDismissedUpdateVersion(context: Context): String {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getString(KEY_DISMISSED_UPDATE_VERSION, "") ?: ""
}

fun saveDismissedUpdateVersion(context: Context, version: String) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putString(KEY_DISMISSED_UPDATE_VERSION, version).apply()
}

const val KEY_LAST_SEEN_CHANGELOG_VERSION = "last_seen_changelog_version"

fun getLastSeenChangelogVersion(context: Context): String {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getString(KEY_LAST_SEEN_CHANGELOG_VERSION, "") ?: ""
}

fun saveLastSeenChangelogVersion(context: Context, version: String) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putString(KEY_LAST_SEEN_CHANGELOG_VERSION, version).apply()
}

data class WhatsNewInfo(
  val changelog: List<ChangelogSection>,
  val description: String?,
)

/**
 * URLConnection defaults both connect and read timeouts to 0 (infinite) — opening a stream without
 * setting them can hang this Dispatchers.IO call indefinitely on a stalled request.
 */
private fun openTimedStream(url: String): java.io.InputStream =
  (URL(url).openConnection() as java.net.URLConnection)
    .apply {
      connectTimeout = 15_000
      readTimeout = 15_000
    }
    .getInputStream()

/**
 * Looks up the release whose tag matches [currentVersion] (not necessarily the latest — the user
 * may be a version or two behind "latest" right after installing an update) and returns its
 * changelog, mirroring the parsing [checkForUpdate] does for the newest release.
 */
suspend fun fetchChangelogForVersion(currentVersion: String): WhatsNewInfo? =
  withContext(Dispatchers.IO) {
    try {
      val cleanCurrent = currentVersion.removePrefix("b").removePrefix("v").trim()
      val releasesJson =
        openTimedStream("https://api.github.com/repos/ft-sinan/FT-music-/releases")
          .bufferedReader()
          .use { it.readText() }
      val releases = JSONArray(releasesJson)

      var matchedRelease: JSONObject? = null
      for (i in 0 until releases.length()) {
        val release = releases.getJSONObject(i)
        val tagClean = release.getString("tag_name").removePrefix("b").removePrefix("v").trim()
        if (tagClean == cleanCurrent) {
          matchedRelease = release
          break
        }
      }
      val release = matchedRelease ?: return@withContext null
      val tag = release.getString("tag_name")

      val changelogList = mutableListOf<ChangelogSection>()
      var description: String? = null
      try {
        val changelogJson =
          openTimedStream(
              "https://github.com/ft-sinan/FT-music-/releases/download/$tag/changelog.json"
            )
            .bufferedReader()
            .use { it.readText() }
        val changelogData = JSONObject(changelogJson)
        description = changelogData.optString("description").takeIf { it.isNotEmpty() }
        val changelogArray = changelogData.getJSONArray("changelog")
        for (j in 0 until changelogArray.length()) {
          val sectionObj = changelogArray.getJSONObject(j)
          val title = sectionObj.getString("title")
          val itemsArray = sectionObj.getJSONArray("items")
          val itemsList = mutableListOf<String>()
          for (k in 0 until itemsArray.length()) {
            itemsList.add(itemsArray.getString(k))
          }
          changelogList.add(ChangelogSection(title, itemsList))
        }
      } catch (e: Exception) {
        var body = release.optString("body", "")
        val imageRegex = Regex("!\\[(.*?)\\]\\((.*?)\\)")
        imageRegex.find(body)?.let { match -> body = body.replace(match.value, "").trim() }
        val (parsedDesc, parsedSections) = parseMarkdownToSections(body)
        description = parsedDesc ?: body.takeIf { parsedSections.isEmpty() }
        changelogList.addAll(parsedSections)
      }

      if (changelogList.isEmpty() && description.isNullOrBlank()) return@withContext null
      WhatsNewInfo(changelogList, description)
    } catch (e: Exception) {
      Log.e("WhatsNew", "Failed to fetch changelog for version $currentVersion: ${e.message}", e)
      null
    }
  }

@Composable
fun WhatsNewDialog(
  version: String,
  info: WhatsNewInfo,
  onDismiss: () -> Unit,
) {
  DefaultDialog(
    onDismiss = onDismiss,
    title = { Text(stringResource(R.string.whats_new_title, version)) },
    buttons = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } }
  ) {
    val (effectiveDescription, effectiveSections) =
      remember(info) {
        if (info.changelog.isNotEmpty()) {
          Pair(info.description?.takeIf { it.isNotBlank() }, info.changelog)
        } else if (!info.description.isNullOrBlank()) {
          parseMarkdownToSections(info.description)
        } else {
          Pair(null, emptyList())
        }
      }

    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
      effectiveDescription?.let { description ->
        item {
          Text(
            text = parseSimpleMarkdown(description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
          )
        }
      }
      effectiveSections.forEach { section ->
        if (section.title.isNotBlank()) {
          item {
            Text(
              text = section.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
          }
        }
        itemsIndexed(section.items) { index, changelogItem ->
          val shape =
            when {
              section.items.size == 1 -> detachedItemShape()
              index == 0 -> leadingItemShape()
              index == section.items.size - 1 -> endItemShape()
              else -> middleItemShape()
            }
          ChangelogItem(text = changelogItem, shape = shape)
          if (index != section.items.size - 1) {
            Spacer(modifier = Modifier.height(2.dp))
          }
        }
      }
    }
  }
}

fun String.extractUrls(): List<Pair<IntRange, String>> {
  val urlPattern =
    Pattern.compile("(?:^|[\\s])((https?://|www\\.|pic\\.)[\\w-]+(\\.[\\w-]+)+([/?].*)?)")
  val matcher = urlPattern.matcher(this)
  val urlList = mutableListOf<Pair<IntRange, String>>()

  while (matcher.find()) {
    val url = matcher.group(1)?.trim() ?: continue
    val range = IntRange(matcher.start(1), matcher.end(1) - 1)

    val fullUrl = if (url.startsWith("http")) url else "https://$url"
    urlList.add(range to fullUrl)
  }

  return urlList
}
