package echo.music.iad1tya.localmedia

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retagging must never touch the audio stream, must not claim a cover it cannot store, and must
 * report a search or download failure instead of guessing. These cover the command that ffmpeg is
 * handed and the editing decisions the sheet shows.
 */
class AudioTagEditTest {
  private val edit = AudioTagEdit(
    title = "Midnight City",
    artists = "M83, Anthony Gonzalez",
    album = "Hurry Up, We're Dreaming",
    year = 2011,
  )

  @Test
  fun mp3TagsCopyTheAudioStreamInsteadOfReEncodingIt() {
    val command = buildTagCommand("/in/a.mp3", "/out/a.mp3", edit, TagContainer.MP3, coverPath = null)

    assertTrue(command, command.contains("-c copy"))
    assertFalse("audio must never be re-encoded", command.contains("libmp3lame"))
    assertFalse(command, command.contains("-b:a"))
    assertTrue(command, command.contains("-id3v2_version 3"))
    assertTrue(command, command.contains("-metadata title='Midnight City'"))
    assertTrue(command, command.contains("-metadata artist='M83, Anthony Gonzalez'"))
    // An apostrophe in the album is escaped for the shell rather than silently dropped.
    assertTrue(command, command.contains("""-metadata album='Hurry Up, We'\''re Dreaming'"""))
    // MP3 readers look at both fields, so both are written.
    assertTrue(command, command.contains("-metadata date='2011'"))
    assertTrue(command, command.contains("-metadata year='2011'"))
  }

  @Test
  fun aContainerWithoutANewCoverKeepsEveryExistingStream() {
    val command = buildTagCommand("/in/a.m4a", "/out/a.m4a", edit, TagContainer.MP4, coverPath = null)

    assertTrue("an embedded cover must survive", command.contains("-map 0 -c copy"))
    assertFalse(command, command.contains("-id3v2_version"))
    assertTrue(command, command.contains("-metadata date='2011'"))
    assertFalse("MP4 has no year tag", command.contains("-metadata year="))
  }

  @Test
  fun aNewCoverIsAttachedAsAPictureWithoutReEncodingAudio() {
    val jpeg = buildTagCommand("/in/a.mp3", "/out/a.mp3", edit.copy(coverMimeType = "image/jpeg"), TagContainer.MP3, "/tmp/cover.jpg")
    val png = buildTagCommand("/in/a.flac", "/out/a.flac", edit.copy(coverMimeType = "image/png"), TagContainer.FLAC, "/tmp/cover.png")

    assertTrue(jpeg, jpeg.contains("-map 0:a:0 -map 1:v:0"))
    assertTrue(jpeg, jpeg.contains("-c:a copy"))
    assertTrue(jpeg, jpeg.contains("-c:v mjpeg"))
    assertTrue(jpeg, jpeg.contains("-disposition:v attached_pic"))
    assertTrue(jpeg, jpeg.contains("-metadata:s:v comment='Cover (front)'"))
    assertTrue(png, png.contains("-c:v png"))
    assertFalse(png, png.contains("-id3v2_version"))
  }

  @Test
  fun aContainerThatCannotHoldACoverStillGetsItsTextTags() {
    listOf(TagContainer.OGG, TagContainer.WAV, TagContainer.MATROSKA).forEach { container ->
      val command = buildTagCommand("/in/a.ogg", "/out/a.ogg", edit, container, "/tmp/cover.jpg")

      assertFalse("$container cannot store a picture", command.contains("-map 1:v:0"))
      assertFalse("$container must not re-encode", command.contains("libmp3lame"))
      assertTrue("$container keeps its text tags", command.contains("-metadata title='Midnight City'"))
    }
  }

  @Test
  fun apostrophesInTagsAreEscapedForTheShell() {
    val command = buildTagCommand(
      "/in/a.mp3", "/out/a.mp3", edit.copy(title = "Don't Stop"), TagContainer.MP3, coverPath = null
    )

    assertTrue(command, command.contains("""-metadata title='Don'\''t Stop'"""))
    assertFalse(command, command.contains("title='Don't Stop'"))
  }

  @Test
  fun missingOptionalTagsAreLeftOutEntirely() {
    val command = buildTagCommand(
      "/in/a.mp3", "/out/a.mp3",
      AudioTagEdit(title = "Untitled", artists = "Unknown", album = null, year = null),
      TagContainer.MP3, coverPath = null,
    )

    assertFalse(command, command.contains("-metadata album="))
    assertFalse(command, command.contains("-metadata date="))
    assertFalse(command, command.contains("-metadata year="))
  }

  @Test
  fun containersAreRecognisedFromTheMimeTypeId3AndTheFileName() {
    assertEquals(TagContainer.MP3, TagContainer.from("audio/mpeg", "song.mp3"))
    assertEquals(TagContainer.MP3, TagContainer.from(null, "song.MP3"))
    assertEquals(TagContainer.MP4, TagContainer.from("audio/mp4", "song.m4a"))
    assertEquals(TagContainer.MP4, TagContainer.from("audio/x-m4a", "song.m4a"))
    assertEquals(TagContainer.FLAC, TagContainer.from("audio/flac", "song.flac"))
    assertEquals(TagContainer.OGG, TagContainer.from("audio/ogg", "song.ogg"))
    assertEquals(TagContainer.OGG, TagContainer.from("audio/opus", "song.opus"))
    assertEquals(TagContainer.WAV, TagContainer.from("audio/x-wav", "song.wav"))
    assertEquals(TagContainer.MATROSKA, TagContainer.from("audio/webm", "song.webm"))
    // A parameterised mime type still resolves.
    assertEquals(TagContainer.MP3, TagContainer.from("audio/mpeg; codecs=mp3", "song.mp3"))
    assertNull(TagContainer.from("audio/x-ape", "song.ape"))
    assertNull(TagContainer.from(null, null))
  }

  @Test
  fun onlyContainersThatMuxPicturesClaimToSupportACover() {
    assertTrue(TagContainer.MP3.supportsCover)
    assertTrue(TagContainer.MP4.supportsCover)
    assertTrue(TagContainer.FLAC.supportsCover)
    assertFalse(TagContainer.OGG.supportsCover)
    assertFalse(TagContainer.WAV.supportsCover)
    assertFalse(TagContainer.MATROSKA.supportsCover)
  }

  private fun editor(
    results: List<TagSearchResult> = emptyList(),
    cover: ByteArray? = null,
    searchThrows: Boolean = false,
  ) = LocalSongInfoEditor(
    searchYouTube = {
      if (searchThrows) throw java.io.IOException("offline")
      results
    },
    fetchCover = { cover },
  )

  /** What an existing MP3 on the device already reports. */
  private fun LocalSongInfoEditor.loadFile() = load(TagContainer.MP3, "Old Title", "Old Artist", "Old Album", 1999)

  @Test
  fun theEditorPrefillsFromWhatTheFileAlreadySays() {
    val editor = editor()
    editor.loadFile()

    assertEquals("Old Title", editor.title)
    assertEquals("Old Artist", editor.artists)
    assertEquals("Old Album", editor.album)
    assertEquals(1999, editor.year)
  }

  @Test
  fun acceptingASearchResultFillsTheFieldsAndItsCover() = runTest {
    val bytes = byteArrayOf(1, 2, 3)
    val editor = editor(
      results = listOf(
        TagSearchResult("vid", "New Title", "New Artist", "New Album", 2020, 200_000L, "https://img/cover.jpg")
      ),
      cover = bytes,
    )
    editor.loadFile()
    editor.search("anything")
    val stage = editor.searchStage as LocalSongInfoEditor.SearchStage.Ready
    editor.accept(stage.results.first())

    assertEquals("New Title", editor.title)
    assertEquals("New Artist", editor.artists)
    assertEquals("New Album", editor.album)
    assertEquals(2020, editor.year)
    assertSameBytes(bytes, editor.coverBytes)
    assertEquals("image/jpeg", editor.coverMimeType)
    assertEquals(LocalSongInfoEditor.CoverSource.SEARCH, editor.coverSource)
    assertNull(editor.coverProblem)
  }

  @Test
  fun aCoverThatWillNotDownloadIsReportedAndTheTextTagsStillApply() = runTest {
    val editor = editor(
      results = listOf(TagSearchResult("vid", "T", "A", null, null, 0L, "https://img/c.jpg")),
      cover = null,
    )
    editor.search("t")
    editor.accept((editor.searchStage as LocalSongInfoEditor.SearchStage.Ready).results.first())

    assertEquals("the cover could not be downloaded", editor.coverProblem)
    assertNull(editor.coverBytes)
    assertTrue(editor.canApply())
  }

  @Test
  fun searchFailuresAreDistinguishedFromAnEmptyResultList() = runTest {
    val failed = editor(searchThrows = true)
    failed.search("query")
    assertEquals(
      LocalSongInfoEditor.SearchStage.Failed(LocalSongInfoEditor.SearchFailure.LOOKUP_FAILED),
      failed.searchStage,
    )

    val empty = editor(results = emptyList())
    empty.search("query")
    assertEquals(
      LocalSongInfoEditor.SearchStage.Failed(LocalSongInfoEditor.SearchFailure.NO_RESULTS),
      empty.searchStage,
    )
  }

  @Test
  fun aBlankQueryNeverSearches() = runTest {
    val editor = editor()
    editor.search("   ")

    assertEquals(LocalSongInfoEditor.SearchStage.Idle, editor.searchStage)
  }

  @Test
  fun aCoverIsDroppedForContainersThatCannotStoreOne() {
    val editor = editor()
    editor.load(TagContainer.OGG, "Old Title", "Old Artist", "Old Album", 1999)
    editor.setTitle("T")
    editor.setArtists("A")
    editor.setCoverFromFile(byteArrayOf(9, 9), "image/png")

    assertNull(editor.edit().coverBytes)
    assertTrue(editor.notices().contains(LocalSongInfoEditor.COVER_UNSUPPORTED))
    assertTrue(editor.notices().contains(LocalSongInfoEditor.AUDIO_UNTOUCHED))
  }

  @Test
  fun applyNeedsBothATitleAndAnArtist() {
    val editor = editor()
    editor.loadFile()
    editor.setTitle("")

    assertFalse(editor.canApply())
    editor.setTitle("Something")
    assertTrue(editor.canApply())
    assertEquals("Something", editor.edit().title)
  }

  @Test
  fun aYearOutsideAnyPlausibleRangeIsIgnored() {
    val editor = editor()
    editor.setYear("not a year")
    assertNull(editor.year)
    editor.setYear("999")
    assertNull(editor.year)
    editor.setYear("3000")
    assertNull(editor.year)
    editor.setYear("-5")
    assertNull(editor.year)
    // A trailing space and an unusual but real year are both kept.
    editor.setYear("2024 ")
    assertEquals(2024, editor.year)
    editor.setYear("1899")
    assertEquals(1899, editor.year)
  }

  /** A byte array needs a content comparison, and JUnit's own overload rejects a nullable one. */
  private fun assertSameBytes(expected: ByteArray, actual: ByteArray?) {
    assertEquals(expected.toList(), actual?.toList())
  }
}
