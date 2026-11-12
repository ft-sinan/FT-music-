package echo.music.iad1tya.localmedia

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import echo.music.iad1tya.db.InternalDatabase
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.SongEntity
import java.io.ByteArrayOutputStream
import java.io.File
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

/**
 * Tag editing runs the real bundled ffmpeg against a real published audio file.
 *
 * The point of these tests is the promise made to the user: the tags land in the file, the audio
 * itself is copied rather than re-encoded, a container that cannot hold a cover says so instead of
 * pretending, and a refusal leaves the file exactly as it was.
 */
@RunWith(AndroidJUnit4::class)
class AudioTagWriterInstrumentedTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var context: Context
  private lateinit var database: MusicDatabase
  private lateinit var writer: AudioTagWriter
  private val created = mutableListOf<Uri>()
  private val createdFiles = mutableListOf<File>()

  /** A one second, 22050 Hz mono PCM tone published with the app. */
  private val wav by lazy { instrumentation.context.assets.open("test-tone.wav").use { it.readBytes() } }

  private class Fixture(val songId: String, val uri: Uri, val name: String)

  @Before
  fun setup() {
    context = instrumentation.targetContext
    val readPermission =
      if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
      else Manifest.permission.READ_EXTERNAL_STORAGE
    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, readPermission)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      instrumentation.uiAutomation.grantRuntimePermission(
        context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE
      )
    }
    database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
    writer = AudioTagWriter(context, database)
  }

  @After
  fun cleanup() {
    created.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    createdFiles.forEach { file -> runCatching { file.delete() } }
    database.close()
  }

  /**
   * Publishes [bytes] as a device audio file and returns its FT Music id.
   *
   * [expectDuration] is false for a container MediaProvider cannot parse: waiting for a duration
   * that will never be extracted only blocks the instrumentation thread.
   */
  private suspend fun publish(
    name: String,
    mimeType: String,
    bytes: ByteArray,
    expectDuration: Boolean = true,
  ): Fixture {
    val values = ContentValues().apply {
      put(MediaStore.Audio.Media.DISPLAY_NAME, name)
      put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.'))
      put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
      put(MediaStore.Audio.Media.IS_MUSIC, 1)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/FT Music Tests/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      } else {
        @Suppress("DEPRECATION")
        val folder = File(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
          "FT Music Tests",
        )
        folder.mkdirs()
        val file = File(folder, name)
        createdFiles += file
        @Suppress("DEPRECATION")
        put(MediaStore.Audio.Media.DATA, file.absolutePath)
      }
    }
    val inserted = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
    assertNotNull("MediaStore refused to publish the test fixture", inserted)
    val uri = ContentUris.withAppendedId(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(inserted!!)
    )
    created += uri
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      legacyPath(uri)?.let { createdFiles += File(it) }
    }
    context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      context.contentResolver.update(
        uri,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null,
      )
    } else {
      context.contentResolver.update(uri, ContentValues().apply {
        put(MediaStore.Audio.Media.SIZE, bytes.size)
        put(MediaStore.Audio.Media.DURATION, 1000)
      }, null, null)
    }
    // Extraction is asynchronous on scoped-storage releases; wait for a usable duration.
    if (expectDuration) {
      for (attempt in 0 until 40) {
        if (mediaStoreDuration(uri) > 0) break
        kotlinx.coroutines.delay(250)
      }
    }
    val songId = uri.toString()
    withContext(Dispatchers.IO) {
      database.upsert(SongEntity(id = songId, title = name.substringBeforeLast('.'), isLocal = true))
    }
    return Fixture(songId, uri, name)
  }

  private fun legacyPath(uri: Uri): String? = runCatching {
    @Suppress("DEPRECATION")
    context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
  }.getOrNull()

  private fun readAll(uri: Uri): ByteArray {
    val path = legacyPath(uri)
    if (path != null) {
      val file = File(path)
      if (file.isFile) return file.readBytes()
    }
    return ByteArrayOutputStream().use { sink ->
      context.contentResolver.openInputStream(uri)!!.use { it.copyTo(sink) }
      sink.toByteArray()
    }
  }

  private fun mediaStoreDuration(uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DURATION), null, null, null)
      ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
  }.getOrDefault(0L)

  private fun mediaStoreTitle(uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.TITLE), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
  }.getOrNull()

  private fun retrieverDuration(uri: Uri): Long = withRetriever(uri) { retriever ->
    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
  } ?: 0L

  private fun embeddedPicture(uri: Uri): ByteArray? = withRetriever(uri) { it.embeddedPicture }

  /** Closed by hand: MediaMetadataRetriever is only AutoCloseable from API 29. */
  private fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T): T? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uri)
      block(retriever)
    } finally {
      runCatching { retriever.release() }
    }
  }.getOrNull()

  private fun jpegCover(): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(0xFF3366CC.toInt())
    return ByteArrayOutputStream().use { stream ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
      stream.toByteArray()
    }.also { bitmap.recycle() }
  }

  // ---- WAV structure helpers: assert against the bytes ffmpeg really wrote ----

  private fun chunks(bytes: ByteArray): List<Pair<String, Int>> {
    val found = mutableListOf<Pair<String, Int>>()
    var position = 12
    while (position + 8 <= bytes.size) {
      val id = String(bytes, position, 4, Charsets.US_ASCII)
      val size = littleEndianInt(bytes, position + 4)
      found += id to position
      position += 8 + size + (size and 1)
    }
    return found
  }

  private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 3].toInt() and 0xFF) shl 24)

  private fun dataChunk(bytes: ByteArray): ByteArray {
    val position = chunks(bytes).firstOrNull { it.first == "data" }?.second ?: return ByteArray(0)
    val size = littleEndianInt(bytes, position + 4)
    val start = position + 8
    return bytes.copyOfRange(start, minOf(start + size, bytes.size))
  }

  /** The RIFF INFO tags ffmpeg writes for a WAV: INAM title, IART artist, IPRD album, ICRD date. */
  private fun infoTags(bytes: ByteArray): Map<String, String> {
    val tags = mutableMapOf<String, String>()
    val position = chunks(bytes).firstOrNull { it.first == "LIST" }?.second ?: return tags
    val size = littleEndianInt(bytes, position + 4)
    if (String(bytes, position + 8, 4, Charsets.US_ASCII) != "INFO") return tags
    val end = minOf(position + 8 + size, bytes.size)
    var inner = position + 12
    while (inner + 8 <= end) {
      val key = String(bytes, inner, 4, Charsets.US_ASCII)
      val length = littleEndianInt(bytes, inner + 4)
      val valueEnd = minOf(inner + 8 + length, end)
      val value = String(bytes, inner + 8, valueEnd - inner - 8, Charsets.US_ASCII)
        .trim('\u0000', ' ')
      if (value.isNotEmpty()) tags[key] = value
      inner += 8 + length + (length and 1)
    }
    return tags
  }

  @Test
  fun tagsLandInTheFileWhileTheAudioBytesStayIdentical() = runBlocking {
    val fixture = publish("ft-tag-${UUID.randomUUID()}.wav", "audio/wav", wav)
    val before = readAll(fixture.uri)
    val beforeDuration = retrieverDuration(fixture.uri)
    assertTrue("the fixture must expose a duration", beforeDuration > 0)

    val target = writer.inspect(fixture.songId)
    assertNotNull("the published fixture must be inspectable", target)
    assertEquals(TagContainer.WAV, target!!.container)

    val result = writer.write(
      fixture.songId,
      AudioTagEdit(
        title = "Midnight City",
        artists = "Artist One, Artist Two",
        album = "Hurry Up We Are Dreaming",
        year = 2011,
      ),
    )

    assertTrue("tag edit failed: ${result.result}", result.result is AudioTagEditResult.Written)
    val written = result.result as AudioTagEditResult.Written
    assertEquals(TagContainer.WAV, written.container)
    assertTrue("WAV cannot store a cover, so none may be claimed", !written.coverWritten)

    val after = readAll(fixture.uri)
    assertArrayEquals(
      "the audio stream must be copied, not re-encoded",
      dataChunk(before),
      dataChunk(after),
    )
    val tags = infoTags(after)
    assertEquals("Midnight City", tags["INAM"])
    assertEquals("Artist One, Artist Two", tags["IART"])
    assertEquals("Hurry Up We Are Dreaming", tags["IPRD"])
    assertEquals("2011", tags["ICRD"])
    assertEquals(beforeDuration, retrieverDuration(fixture.uri))

    // MediaStore and FT Music's own library must agree with the file.
    assertEquals("Midnight City", mediaStoreTitle(fixture.uri))
    val song = database.getSongById(fixture.songId)
    assertNotNull(song)
    assertEquals("Midnight City", song!!.song.title)
    assertEquals(2011, song.song.year)
    assertEquals(listOf("Artist One", "Artist Two"), song.artists.map { it.name })
  }

  @Test
  fun aContainerThatCannotHoldACoverSaysSoInsteadOfFakingOne() = runBlocking {
    val fixture = publish("ft-tag-nocover-${UUID.randomUUID()}.wav", "audio/wav", wav)

    val result = writer.write(
      fixture.songId,
      AudioTagEdit(
        title = "Cover Requested",
        artists = "Artist One",
        album = null,
        year = null,
        coverBytes = jpegCover(),
        coverMimeType = "image/jpeg",
      ),
    )

    val written = result.result as? AudioTagEditResult.Written
    assertNotNull("tag edit failed: ${result.result}", written)
    assertTrue(!written!!.coverWritten)
    val after = readAll(fixture.uri)
    assertEquals("Cover Requested", infoTags(after)["INAM"])
    assertNull("no picture may be claimed for a container that cannot hold one", embeddedPicture(fixture.uri))
  }

  @Test
  fun anUnsupportedContainerIsRefusedAndTheFileIsLeftExactlyAsItWas() = runBlocking {
    // WAV bytes under a name and mime FT Music cannot retag. MediaProvider cannot parse it either,
    // so there is no duration to wait for.
    val fixture = publish(
      name = "ft-tag-unsupported-${UUID.randomUUID()}.ape",
      mimeType = "audio/x-ape",
      bytes = wav,
      expectDuration = false,
    )
    val before = readAll(fixture.uri)

    val target = writer.inspect(fixture.songId)
    assertNotNull("the published fixture must be inspectable", target)
    assertNull("an APE file must not be reported as retaggable", target!!.container)

    val result = writer.write(
      fixture.songId,
      AudioTagEdit(title = "Should Not Apply", artists = "Nobody", album = null, year = null),
    )

    val failed = result.result as? AudioTagEditResult.Failed
    assertNotNull("an unsupported container must be refused, not written", failed)
    assertEquals(AudioTagEditFailure.UNSUPPORTED_FORMAT, failed!!.reason)
    assertArrayEquals("a refused edit must not touch the file", before, readAll(fixture.uri))
    assertEquals(fixture.name.substringBeforeLast('.'), mediaStoreTitle(fixture.uri))
  }

  @Test
  fun aSongWhoseFileIsGoneIsReportedRatherThanWritten() = runBlocking {
    val missingId = "content://media/external/audio/media/${Int.MAX_VALUE - 7}"

    assertNull(writer.inspect(missingId))
    val result = writer.write(
      missingId, AudioTagEdit(title = "Nowhere", artists = "Nobody", album = null, year = null)
    )

    val failed = result.result as? AudioTagEditResult.Failed
    assertNotNull(failed)
    assertEquals(AudioTagEditFailure.NO_FILE, failed!!.reason)
  }

  @Test
  fun aBlankTitleIsRefusedSoTagsCannotBeWiped() = runBlocking {
    val fixture = publish("ft-tag-blank-${UUID.randomUUID()}.wav", "audio/wav", wav)
    val before = readAll(fixture.uri)

    val result = writer.write(
      fixture.songId, AudioTagEdit(title = "   ", artists = "Artist One", album = null, year = null)
    )

    val failed = result.result as? AudioTagEditResult.Failed
    assertNotNull("a blank title must be refused", failed)
    assertEquals(AudioTagEditFailure.WRITE_FAILED, failed!!.reason)
    assertArrayEquals(before, readAll(fixture.uri))
  }

}
