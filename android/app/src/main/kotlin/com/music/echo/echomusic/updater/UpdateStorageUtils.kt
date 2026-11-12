package echo.music.iad1tya.echomusic.updater

import android.content.Context
import android.os.Environment
import java.io.File

/** Pulls `v0.7.0` out of `FT-Music-v0.7.0-arm64-v8a.apk`. */
private val APK_VERSION_IN_NAME = Regex("-((v?\\d+(?:\\.\\d+)*))-")

fun getDownloadedApksDir(context: Context): File {
  return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "echo_updates")
}

fun getDownloadedApkCount(context: Context): Int {
  val dir = getDownloadedApksDir(context)
  if (!dir.exists() || !dir.isDirectory) return 0
  return dir
    .listFiles { file -> file.isFile && file.name.endsWith(".apk", ignoreCase = true) }
    ?.size ?: 0
}

fun clearDownloadedApks(context: Context): Boolean {
  val dir = getDownloadedApksDir(context)
  if (!dir.exists() || !dir.isDirectory) return true
  var allDeleted = true
  dir
    .listFiles { file -> file.isFile && file.name.endsWith(".apk", ignoreCase = true) }
    ?.forEach { file ->
      if (!file.delete()) {
        allDeleted = false
      }
    }
  return allDeleted
}

fun autoClearOldApks(context: Context) {
  val dir = getDownloadedApksDir(context)
  if (!dir.exists() || !dir.isDirectory) return
  val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
  dir
    .listFiles { file ->
      file.isFile &&
        file.name.endsWith(".apk", ignoreCase = true) &&
        file.lastModified() < oneDayAgo
    }
    ?.forEach { it.delete() }
}

/**
 * Drops update packages that are no longer any use: anything older than a day, and anything for a
 * release other than the one now installed. A ~200 MB universal APK should not sit in app storage
 * after the user updated, and the package for the installed version is kept so the update screen
 * can still offer it if the install did not actually go through.
 */
fun clearStaleUpdateApks(context: Context, installedVersion: String) {
  autoClearOldApks(context)
  val dir = getDownloadedApksDir(context)
  if (!dir.exists() || !dir.isDirectory) return
  val installed = installedVersion.removePrefix("v").removePrefix("b").trim()
  if (installed.isEmpty()) return
  dir
    .listFiles { file -> file.isFile && file.name.endsWith(".apk", ignoreCase = true) }
    ?.forEach { file ->
      val tag = APK_VERSION_IN_NAME.find(file.name)?.groupValues?.getOrNull(1)
      val version = tag?.removePrefix("v")?.removePrefix("b")?.trim()
      if (version != null && version != installed) file.delete()
    }
}

