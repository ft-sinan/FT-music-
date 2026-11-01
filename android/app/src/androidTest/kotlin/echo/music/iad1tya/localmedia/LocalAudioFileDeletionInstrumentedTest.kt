package echo.music.iad1tya.localmedia

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import echo.music.iad1tya.db.InternalDatabase
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.FormatEntity
import echo.music.iad1tya.db.entities.Song
import echo.music.iad1tya.db.entities.SongEntity
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioFileDeletionInstrumentedTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var context: Context
  private lateinit var deletion: LocalAudioFileDeletion
  private val created = mutableListOf<Uri>()
  private val createdFiles = mutableListOf<File>()
  private val bytes by lazy { instrumentation.context.assets.open("test-tone.wav").use { it.readBytes() } }

  @Before fun setup() {
    context = instrumentation.targetContext
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, audioPermission)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      assertTrue("Android 10 legacy storage mode is required to delete the actual file", Environment.isExternalStorageLegacy())
    }
    deletion = LocalAudioFileDeletion(context)
  }

  @After fun cleanup() {
    created.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    createdFiles.forEach { file -> runCatching { file.delete() } }
  }

  private fun createSong(): Song {
    val name = "ft-delete-${UUID.randomUUID()}.wav"
    val values = ContentValues().apply {
      put(MediaStore.Audio.Media.DISPLAY_NAME, name)
      put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.'))
      put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
      put(MediaStore.Audio.Media.IS_MUSIC, 1)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/FT Music Tests/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      } else {
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "FT Music Tests")
        folder.mkdirs()
        val file = File(folder, name)
        createdFiles += file
        @Suppress("DEPRECATION")
        put(MediaStore.Audio.Media.DATA, file.absolutePath)
      }
    }
    val inserted = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(inserted))
    created += uri
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      @Suppress("DEPRECATION")
      context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.let { path -> createdFiles += File(path) }
      }
    }
    context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } else {
      // Legacy MediaStore may not refresh these columns until a scan finishes.
      context.contentResolver.update(uri, ContentValues().apply {
        put(MediaStore.Audio.Media.SIZE, bytes.size)
        put(MediaStore.Audio.Media.DURATION, 1000)
      }, null, null)
    }
    val projection = arrayOf(
      MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.SIZE,
      MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DURATION,
    )
    // Publishing can schedule metadata extraction asynchronously on scoped-storage releases.
    for (attempt in 0 until 40) {
      val durationMs = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DURATION), null, null, null)?.use {
        if (it.moveToFirst()) it.getLong(0) else 0L
      } ?: 0L
      if (durationMs > 0) break
      Thread.sleep(250)
    }
    return context.contentResolver.query(uri, projection, null, null, null)!!.use { cursor ->
      assertTrue(cursor.moveToFirst())
      val displayName = cursor.getString(0).orEmpty()
      val title = cursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
        ?: displayName.substringBeforeLast('.').trim()
      val size = cursor.getLong(2)
      assertTrue("MediaStore did not expose the test file size", size > 0)
      val modified = cursor.getLong(3).takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault()) }
      val durationMs = cursor.getLong(4)
      assertTrue("MediaStore did not extract the test audio duration", durationMs > 0)
      val duration = (durationMs / 1000).toInt()
      Song(
        song = SongEntity(id = uri.toString(), title = title, duration = duration, dateModified = modified, isLocal = true),
        artists = emptyList(),
        format = FormatEntity(uri.toString(), -1, "audio/wav", "", 0, null, size, null, null, null),
      )
    }
  }

  @Test fun databaseCleanupCannotDeleteOnlineOrUnrelatedRows() = runBlocking {
    val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
    try {
      withContext(Dispatchers.IO) {
        database.upsert(SongEntity(id = "online", title = "Online", isLocal = false))
        database.upsert(SongEntity(id = "local", title = "Local", isLocal = true))
        database.upsert(SongEntity(id = "other", title = "Other", isLocal = true))
        assertEquals(0, database.deleteLocalSongById("online"))
        assertEquals(1, database.deleteLocalSongById("local"))
        assertNotNull(database.getSongById("online"))
        assertNotNull(database.getSongById("other"))
        assertNull(database.getSongById("local"))
      }
    } finally {
      database.close()
    }
  }

  @Test fun rescanRefreshesIdentityWithoutLosingSongSettings() = runBlocking {
    val song = createSong()
    val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
    try {
      val scanner = LocalSongScanner(context, database)
      scanner.scanDevice()
      withContext(Dispatchers.IO) {
        val before = database.getSongById(song.id)!!
        // Simulate metadata saved by an older scan without trying to write read-only
        // MediaStore columns on scoped-storage Android versions.
        database.update(before.song.copy(title = "Outdated title", liked = true, lyricsOffset = 42,
          dateModified = before.song.dateModified?.minusDays(1)))
      }
      scanner.scanDevice()
      val after = withContext(Dispatchers.IO) { database.getSongById(song.id)!! }
      assertEquals(song.song.title, after.song.title)
      assertTrue(after.song.liked)
      assertEquals(42, after.song.lyricsOffset)
      assertTrue(deletion.inspect(after) is LocalAudioFileDeletion.Inspection.Ready)
    } finally {
      database.close()
    }
  }

  @Test fun onlySpecificLocalAudioRowsAreAccepted() {
    assertNotNull(deletion.audioUri("content://media/external/audio/media/123"))
    assertNull(deletion.audioUri("content://media/external/audio/media"))
    assertNull(deletion.audioUri("content://media/external/audio/media/123?delete_data=false"))
    assertNull(deletion.audioUri("content://media/external/downloads/123"))
    assertNull(deletion.audioUri("content://other/external/audio/media/123"))
    assertNull(deletion.audioUri("file:///sdcard/Music/other.wav"))
  }

  @Test fun staleMetadataCannotDeleteAReplacement() = runBlocking {
    val song = createSong()
    val target = (deletion.inspect(song) as LocalAudioFileDeletion.Inspection.Ready).target
    val stale = song.copy(format = song.format!!.copy(contentLength = song.format!!.contentLength + 1))
    assertEquals(LocalAudioFileDeletion.Inspection.Changed, deletion.inspect(stale))
    if (Build.VERSION.SDK_INT <= 29) {
      assertEquals(LocalAudioFileDeletion.DeleteResult.Changed, deletion.deleteDirect(stale, target))
      assertEquals(LocalAudioFileDeletion.DeleteResult.Changed, deletion.deleteDirect(song, target.copy(displayName = "replacement.wav")))
    }
    assertEquals(target, LocalAudioFileDeletion.Target.decode(target.encode()))
    val actual = context.contentResolver.openInputStream(Uri.parse(song.id))!!.use { it.readBytes() }
    assertArrayEquals(bytes, actual)
  }

  @Test fun legacyReplacementAfterConfirmationIsNotDeleted() = runBlocking {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return@runBlocking
    val song = createSong()
    val confirmed = (deletion.inspect(song) as LocalAudioFileDeletion.Inspection.Ready).target
    val file = File(confirmed.legacyPath!!)
    val replacement = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
    val savedTime = file.lastModified()
    // Create the replacement before removing the original so it cannot reuse its inode.
    val other = File(file.parentFile, "replacement-${UUID.randomUUID()}.wav")
    other.writeBytes(replacement)
    assertTrue(file.delete())
    assertTrue(other.renameTo(file))
    assertTrue(file.setLastModified(savedTime))
    assertEquals(LocalAudioFileDeletion.DeleteResult.Changed, deletion.deleteDirect(song, confirmed))
    assertArrayEquals(replacement, file.readBytes())
  }

  @Test fun deletionAffectsOnlyTheSelectedSong() = runBlocking {
    val song = createSong()
    val other = createSong()
    val target = (deletion.inspect(song) as LocalAudioFileDeletion.Inspection.Ready).target
    val legacyPath = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      @Suppress("DEPRECATION")
      context.contentResolver.query(Uri.parse(song.id), arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
    } else null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      assertEquals(LocalAudioFileDeletion.DeleteResult.Failed, deletion.deleteDirect(song, target))
      assertNotNull(deletion.systemDeleteRequest(target))
      assertEquals(LocalAudioFileDeletion.Presence.PRESENT, deletion.presence(song.id))
    } else {
      assertEquals(LocalAudioFileDeletion.DeleteResult.Deleted, deletion.deleteDirect(song, target))
      assertEquals(LocalAudioFileDeletion.Presence.MISSING, deletion.presence(song.id))
      if (legacyPath != null) assertTrue("The selected file should be removed", !java.io.File(legacyPath).exists())
    }
    assertEquals(LocalAudioFileDeletion.Presence.PRESENT, deletion.presence(other.id))
    assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(other.id))!!.use { it.readBytes() })
  }
}
