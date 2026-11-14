package echo.music.iad1tya.localmedia

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retagging must never touch the audio stream, must not claim a cover it cannot store, and must
 * report a search or download failure instead of guessing. These cover the arguments ffmpeg is
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
    val args = buildTagArguments("/in/a.mp3", "/out/a.mp3", edit, TagContainer.MP3, coverPath = null)

    assertRun("-c", "copy", inArgs = args)
    assertNever("libmp3lame", inArgs = args, because = "audio must never be re-encoded")
    assertNever("-b:a", inArgs = args, because = "no bitrate may be requested")
    assertRun("-id3v2_version", "3", inArgs = args)
    assertMeta("title", "Midnight City", args)
    assertMeta("artist", "M83, Anthony Gonzalez", args)
    // The apostrophe survives untouched - see apostrophesReachFfmpegUnchanged.
    assertMeta("album", "Hurry Up, We're Dreaming", args)
    // MP3 readers look at both year fields, so both are written.
    assertMeta("date", "2011", args)
    assertMeta("year", "2011", args)
  }

  @Test
  fun aContainerWithoutANewCoverKeepsEveryExistingStream() {
    val args = buildTagArguments("/in/a.m4a", "/out/a.m4a", edit, TagContainer.MP4, coverPath = null)

    assertRun("-map", "0", "-c", "copy", inArgs = args, because = "an embedded cover must survive")
    assertNever("-id3v2_version", inArgs = args, because = "MP4 has no ID3 tags")
    assertMeta("date", "2011", args)
    assertNever("year=2011", inArgs = args, because = "MP4 has no year tag")
  }

  @Test
  fun aNewCoverIsAttachedAsAPictureWithoutReEncodingAudio() {
    val jpeg = buildTagArguments(
      "/in/a.mp3", "/out/a.mp3", edit.copy(coverMimeType = "image/jpeg"), TagContainer.MP3, "/tmp/cover.jpg"
    )
    val png = buildTagArguments(
      "/in/a.flac", "/out/a.flac", edit.copy(coverMimeType = "image/png"), TagContainer.FLAC, "/tmp/cover.png"
    )

    assertRun("-map", "0:a:0", "-map", "1:v:0", inArgs = jpeg)
    assertRun("-c:a", "copy", inArgs = jpeg)
    assertRun("-c:v", "mjpeg", inArgs = jpeg)
    assertRun("-disposition:v", "attached_pic", inArgs = jpeg)
    assertRun("-metadata:s:v", "comment=Cover (front)", inArgs = jpeg)
    assertRun("-c:v", "png", inArgs = png)
    assertNever("-id3v2_version", inArgs = png, because = "FLAC has no ID3 tags")
    assertEquals("the cover is its own input", "/tmp/cover.png", png[4])
  }

  @Test
  fun aContainerThatCannotHoldACoverStillGetsItsTextTags() {
    listOf(TagContainer.OGG, TagContainer.WAV, TagContainer.MATROSKA).forEach { container ->
      val args = buildTagArguments("/in/a.ogg", "/out/a.ogg", edit, container, "/tmp/cover.jpg")

      assertNever("1:v:0", inArgs = args, because = "$container cannot store a picture")
      assertNever("libmp3lame", inArgs = args, because = "$container must not re-encode")
      assertMeta("title", "Midnight City", args)
    }
  }

  /**
   * `FFmpegKit.execute(String)` parses its string itself and reads `'` as a quote delimiter without
   * implementing the POSIX `'\''` idiom, so a hand-escaped value reached ffmpeg with a literal
   * backslash: "Don't Stop" was tagged "Don\'t Stop". Passing argv means nothing parses the value.
   * This is the regression guard for that, and it also covers spaces in paths, which used to depend
   * on quoting to survive.
   */
  @Test
  fun apostrophesReachFfmpegUnchanged() {
    val args = buildTagArguments(
      "/in/My Song.mp3",
      "/out/My Song.mp3",
      AudioTagEdit(
        title = "Don't Stop Me Now",
        artists = "O'Brien, Sinead",
        album = "'round Midnight, Live at \"Wembley\"",
        year = null,
      ),
      TagContainer.MP3,
      coverPath = "/tmp/O'Brien's cover.jpg",
    )

    assertMeta("title", "Don't Stop Me Now", args)
    assertMeta("artist", "O'Brien, Sinead", args)
    assertMeta("album", "'round Midnight, Live at \"Wembley\"", args)
    assertEquals("/in/My Song.mp3", args[args.indexOf("-i") + 1])
    assertEquals("/tmp/O'Brien's cover.jpg", args[args.lastIndexOf("-i") + 1])
    assertEquals("the output path is one argument", "/out/My Song.mp3", args.last())
    assertTrue("no value may carry escaping backslashes: $args", args.none { '\\' in it })
  }

  @Test
  fun missingOptionalTagsAreLeftOutEntirely() {
    val args = buildTagArguments(
      "/in/a.mp3", "/out/a.mp3",
      AudioTagEdit(title = "Untitled", artists = "Unknown", album = null, year = null),
      TagContainer.MP3, coverPath = null,
    )

    // JUnit's first argument is the failure message, so it has to be a String and not the list.
    assertTrue("no album tag may be written: $args", args.none { it.startsWith("album=") })
    assertTrue("no date tag may be written: $args", args.none { it.startsWith("date=") })
    assertTrue("no year tag may be written: $args", args.none { it.startsWith("year=") })
  }

  /** Asserts [expected] appears in [inArgs] as one contiguous run of arguments. */
  private fun assertRun(vararg expected: String, inArgs: List<String>, because: String? = null) {
    val run = expected.toList()
    val found = inArgs.windowed(run.size).any { it == run }
    assertTrue((because ?: run.joinToString(" ")) + "\n" + inArgs, found)
  }

  private fun assertNever(expected: String, inArgs: List<String>, because: String) {
    assertFalse("$because\n$inArgs", inArgs.contains(expected))
  }

  /**
   * Asserts one `-metadata key=value` argument carries [value] exactly, with no escaping added.
   * Matched as a contiguous run so a picture's own stream tag, which is also written as `title=`,
   * cannot be mistaken for the song's title.
   */
  private fun assertMeta(key: String, value: String, args: List<String>) {
    assertRun("-metadata", "$key=$value", inArgs = args, because = "$key must be written exactly")
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
