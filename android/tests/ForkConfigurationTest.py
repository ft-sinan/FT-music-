#!/usr/bin/env python3
"""Fast, offline checks for FT Music's Echo fork configuration."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
MAIN = APP / "src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class ForkConfigurationTest(unittest.TestCase):
    def test_ft_identity_and_license(self):
        name = ET.parse(MAIN / "res/values/app_name.xml").find("string[@name='app_name']")
        self.assertEqual(name.text, "FT Music")
        build = (APP / "build.gradle.kts").read_text()
        self.assertIn('applicationId = "com.ftmusic.app"', build)
        self.assertIn('versionCode = ftVersionCode', build)
        self.assertIn('versionName = ftVersionName', build)
        self.assertTrue((MAIN / "res/drawable/ft_launcher.xml").is_file())
        self.assertTrue((MAIN / "res/drawable/ic_launcher_nobg.xml").is_file())
        self.assertIn('GNU GENERAL PUBLIC LICENSE', (ROOT / "LICENSE").read_text())
        self.assertIn('c0de840532aadc36a84475b63291fbc6452a1668', (ROOT / "UPSTREAM.md").read_text())
        about = (MAIN / "kotlin/com/music/echo/ui/screens/settings/AboutScreen.kt").read_text()
        self.assertIn("GPLv3 · No warranty", about)
        self.assertIn("https://github.com/ft-sinan/FT-music-", about)
        self.assertNotIn('Text("Original Echo Music project")', about)
        self.assertIn("Echo Music", (ROOT / "UPSTREAM.md").read_text())
        self.assertIn("https://github.com/EchoMusicApp/Echo-Music", (ROOT / "UPSTREAM.md").read_text())

    def test_visible_ft_branding_and_official_share_links(self):
        # Check translated string values, not internal resource names or retained source notices.
        old_brand = re.compile(r"(?i)(?<![a-z])(echomusic|echo(?:[-\s]+music)?)(?![a-z])")
        for resource in (MAIN / "res").glob("values*/*.xml"):
            for element in ET.parse(resource).getroot():
                if element.tag not in {"string", "string-array", "plurals"}:
                    continue
                self.assertNotRegex("".join(element.itertext()), old_brand, str(resource))
        creator = (MAIN / "kotlin/com/music/echo/ui/screens/settings/CreatorSpace.kt").read_text()
        about = (MAIN / "kotlin/com/music/echo/ui/screens/settings/AboutScreen.kt").read_text()
        self.assertNotIn('"Built on Echo Music', creator)
        self.assertNotIn('"Echo Music creator"', about)
        ytitems = (ROOT / "innertube/src/main/kotlin/com/music/innertube/models/YTItem.kt").read_text()
        self.assertIn("https://music.youtube.com/watch?v=$id", ytitems)
        self.assertNotIn("share.echomusic.fun", ytitems)

    def test_apple_inspired_player_is_the_consistent_default(self):
        defaults = (ROOT / "core/src/main/kotlin/echo/music/iad1tya/constants/PreferenceKeys.kt").read_text()
        self.assertIn("const val USE_NEW_PLAYER_DESIGN = false", defaults)
        self.assertIn("val BACKGROUND_STYLE = PlayerBackgroundStyle.APPLE_MUSIC", defaults)
        kotlin = MAIN / "kotlin/com/music/echo/ui"
        for path in ("screens/settings/AppearanceSettings.kt", "player/Player.kt", "player/Queue.kt"):
            source = (kotlin / path).read_text()
            self.assertIn("rememberPreference(UseNewPlayerDesignKey, defaultValue = DefaultPlayerAppearance.USE_NEW_PLAYER_DESIGN)", source, path)
        for path in ("screens/settings/AppearanceSettings.kt", "player/Player.kt", "player/Thumbnail.kt", "component/Lyrics.kt"):
            source = (kotlin / path).read_text()
            self.assertIn("defaultValue = DefaultPlayerAppearance.BACKGROUND_STYLE", source, path)
        settings = (kotlin / "screens/settings/AppearanceSettings.kt").read_text()
        self.assertIn("checked = !useNewPlayerDesign", settings)
        self.assertIn("onPlayerBackgroundChange(PlayerBackgroundStyle.APPLE_MUSIC)", settings)
        self.assertRegex(settings, r"MiniPlayerBackgroundStyleKey,\s*defaultValue = PlayerBackgroundStyle.DEFAULT")

    def test_player_source_label_is_simple_and_keeps_quality(self):
        player_dir = MAIN / "kotlin/com/music/echo/ui/player"
        player = (player_dir / "Player.kt").read_text()
        badge = (player_dir / "PlayerSourceBadge.kt").read_text()
        self.assertIn("playerSourceBadge(playbackOrigins[mediaMetadata.id], isCasting)", player)
        self.assertIn("PlayerSourceBadge.LOCAL -> stringResource(R.string.filter_local)", player)
        self.assertIn("playerDetailsText(sourceLabel, qualityLabel)", player)
        self.assertIn("origin == PlaybackOrigin.DEVICE || origin == PlaybackOrigin.FT_DOWNLOAD", badge)
        self.assertNotIn('"Streaming / cache"', player)
        self.assertEqual(ET.parse(MAIN / "res/values/strings.xml").find("string[@name='filter_local']").text, "Local")

    def test_font_is_redistributable(self):
        self.assertFalse((MAIN / "res/font/google_sans_flex.ttf").exists())
        self.assertTrue((MAIN / "res/font/sans_flex.ttf").is_file())
        self.assertIn("SIL OPEN FONT LICENSE", (ROOT / "third-party/fonts/Google-Sans-Flex-OFL.txt").read_text())
        self.assertNotIn("R.font.google_sans_flex", (MAIN / "kotlin/com/music/echo/ui/theme/Type.kt").read_text())

    def test_no_android_auto_discovery_but_phone_media_remains(self):
        manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()
        names = [el.get(ANDROID + "name", "") for el in manifest.iter()]
        self.assertNotIn("com.google.android.gms.car.application", names)
        self.assertNotIn("android.hardware.type.automotive", names)
        self.assertNotIn("android.media.browse.MediaBrowserService", names)
        self.assertNotIn("androidx.media3.session.MediaLibraryService", names)
        self.assertIn("androidx.media3.session.MediaSessionService", names)
        self.assertFalse((MAIN / "res/xml/automotive_app_desc.xml").exists())
        settings = (MAIN / "kotlin/com/music/echo/ui/screens/settings/PlayerSettings.kt").read_text()
        self.assertNotIn("autoplay_on_automotive_connect", settings)
        callback = (MAIN / "kotlin/com/music/echo/playback/MediaLibrarySessionCallback.kt").read_text()
        self.assertIn('controller.packageName == "com.google.android.projection.gearhead"', callback)
        self.assertIn('return MediaSession.ConnectionResult.reject()', callback)

    def test_youtube_login_and_sync_are_retained(self):
        nav = (MAIN / "kotlin/com/music/echo/ui/screens/NavigationBuilder.kt").read_text()
        login = (MAIN / "kotlin/com/music/echo/ui/screens/LoginScreen.kt").read_text()
        settings = (MAIN / "kotlin/com/music/echo/ui/screens/settings/AccountSettingsScreen.kt").read_text()
        self.assertIn('composable("login") { LoginScreen(navController) }', nav)
        self.assertIn('https://accounts.google.com/ServiceLogin?', login)
        self.assertIn('YouTube.useLoginForBrowse', settings)
        self.assertIn('finishedUrl.host == "music.youtube.com"', login)
        self.assertNotIn('url?.startsWith("https://music.youtube.com")', login)

    def test_updates_are_ft_owned_and_verified_before_install(self):
        """In-app updating may only offer packages it can prove are FT Music's own."""
        update = (MAIN / "kotlin/com/music/echo/ui/screens/settings/UpdateSettings.kt").read_text()
        nav = (MAIN / "kotlin/com/music/echo/ui/screens/NavigationBuilder.kt").read_text()
        activity = (MAIN / "kotlin/com/music/echo/MainActivity.kt").read_text()
        updater_dir = MAIN / "kotlin/com/music/echo/echomusic/updater"
        screen = (updater_dir / "echomusicupdater.kt").read_text()
        source = (updater_dir / "UpdateReleaseSource.kt").read_text()
        verify = (updater_dir / "UpdateVerification.kt").read_text()
        worker = (updater_dir / "downloadmanager/UpdateDownloadWorker.kt").read_text()
        notifications = (updater_dir / "downloadmanager/downloadnotificationmanager.kt").read_text()

        # Still FT Music's own releases, and never the upstream update service.
        self.assertIn("https://github.com/$FT_MUSIC_REPO/releases", source)
        self.assertIn("FT_MUSIC_RELEASES_URL", update)
        # MainActivity keeps an unrelated deep-link host check from upstream, so the updater
        # sources are the ones held to this.
        for text in (update, screen, source, verify, worker, notifications):
            self.assertNotIn("echomusic.fun", text)

        # The screen that downloads and installs is reachable, not orphaned.
        self.assertIn('composable("update") { UpdateScreen(navController) }', nav)
        self.assertIn('navController.navigate("update")', update)

        # Looking for an update on its own is opt-in, and never downloads anything.
        self.assertIn("getBoolean(KEY_AUTO_UPDATE_CHECK, false)", screen)
        self.assertIn("isAutomaticUpdateCheckDue(context)", activity)
        # Checking in the background is gated on the notification setting, which is also the
        # setting the user can see and turn off.
        notify = (updater_dir / "UpdateNotification.kt").read_text()
        self.assertIn("getBoolean(KEY_UPDATE_NOTIFICATIONS, true)", screen)
        self.assertIn("if (!getUpdateNotificationsSetting(context)) return false", notify)
        self.assertIn("if (!echo.music.iad1tya.echomusic.updater.isAutomaticUpdateCheckDue(context))", activity)

        # The package comes from the release's own asset list, chosen for this device, and its
        # checksum comes from the published SHA256SUMS.txt. No URL is ever assembled by hand.
        self.assertIn("Build.SUPPORTED_ABIS.toList()", screen)
        self.assertIn("fun chooseApkAsset(", verify)
        self.assertIn("-universal.apk", verify)
        self.assertIn('const val CHECKSUM_ASSET_NAME = "SHA256SUMS.txt"', verify)
        self.assertNotIn("FT-Music-${currentStatus.version}.apk", screen)

        # Nothing reaches the installer without both proofs.
        self.assertIn("PINNED_SIGNING_CERT_SHA256", verify)
        for state in ("FT_MUSIC", "DIFFERENT", "UNREADABLE"):
            self.assertIn(state, verify)
        # Android 9 and 10 collect an archive's certificates only when the deprecated
        # GET_SIGNATURES flag is set, so asking with the modern flag alone would report every
        # genuine update as unverifiable on exactly the older phones most likely to need it.
        # 0x08000000 is GET_SIGNING_CERTIFICATES, 0x00000040 is GET_SIGNATURES.
        self.assertIn("0x08000000", verify)
        self.assertIn("0x00000040", verify)
        self.assertIn("ARCHIVE_SIGNING_FLAGS = 0x08000040", verify)
        self.assertIn("pinnedDigestAmong(digests) != null", verify)
        # A case-insensitive match has to hand back the canonical constant, not whatever casing the
        # device reported, so a caller comparing it literally is not quietly broken.
        self.assertIn("digests.any { PINNED_SIGNING_CERT_SHA256.equals(it, ignoreCase = true) }", verify)
        self.assertIn("if (!downloadVerified && !signatureUnreadable)", screen)
        self.assertIn("SignatureMatch.DIFFERENT ->", screen)
        self.assertIn("if (expectedSha256 != null)", worker)
        self.assertIn("finalFile.delete()", worker)
        self.assertIn("REASON_CHECKSUM_MISMATCH", worker)

        # Android still asks, so nothing installs silently.
        self.assertIn("canRequestPackageInstalls()", screen)
        self.assertIn("ContextCompat.startActivity(context, installIntent, null)", screen)

        # A notification is never a shortcut past those checks: it opens the screen that does them.
        self.assertNotIn("application/vnd.android.package-archive", notifications)
        self.assertEqual(notifications.count("EXTRA_OPEN_UPDATE_SCREEN"), 2)
        self.assertIn("handleUpdateScreenIntent(activeIntent, navController)", activity)
        self.assertIn('navController.navigate("update") { launchSingleTop = true }', activity)

        # A package already on disk is offered again only after being proved a second time, and a
        # package for a release the user has moved past is deleted rather than left in storage.
        self.assertIn("fun findVerifiedDownload", screen)
        self.assertIn("matchesPublishedChecksum(file, apk.sha256)", screen)
        self.assertIn("checkApkSignature(context, file) == SignatureMatch.DIFFERENT", screen)
        self.assertIn("clearStaleUpdateApks(context, BuildConfig.VERSION_NAME)", activity)

        # The pinned certificate is the one the release pipeline signs with.
        pinned = (ROOT / "signing-cert-sha256.txt").read_text().strip()
        self.assertIn(pinned, verify)

    def test_local_file_deletion_requires_confirmation_and_targets_only_local_rows(self):
        menu = (MAIN / "kotlin/com/music/echo/ui/menu/SongMenu.kt").read_text()
        dialog = (MAIN / "kotlin/com/music/echo/ui/component/LocalAudioDeleteDialog.kt").read_text()
        deletion = (MAIN / "kotlin/com/music/echo/localmedia/LocalAudioFileDeletion.kt").read_text()
        dao = (ROOT / "core/src/main/kotlin/echo/music/iad1tya/db/DatabaseDao.kt").read_text()
        activity = (MAIN / "kotlin/com/music/echo/MainActivity.kt").read_text()
        controller = (MAIN / "kotlin/com/music/echo/ui/component/LocalAudioDeletionViewModel.kt").read_text()
        self.assertIn("if (song.song.isLocal)", menu)
        self.assertIn("localAudioDeletion.request(song)", menu)
        self.assertIn("LocalAudioDeleteDialog(", activity)
        self.assertIn("SavedStateHandle", controller)
        self.assertIn("MediaStore.createDeleteRequest", deletion)
        self.assertIn("Environment.isExternalStorageLegacy()", deletion)
        self.assertIn("WRITE_EXTERNAL_STORAGE", dialog)
        self.assertIn("deleteLegacy(inspected.target)", deletion)
        self.assertIn("deleteLocalSongById(song.id)", dialog)
        self.assertIn("WHERE id = :songId AND isLocal = 1", dao)
        manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()
        self.assertEqual(manifest.find("application").get(ANDROID + "requestLegacyExternalStorage"), "true")
        write = next(el for el in manifest.findall("uses-permission")
                     if el.get(ANDROID + "name") == "android.permission.WRITE_EXTERNAL_STORAGE")
        self.assertEqual(write.get(ANDROID + "maxSdkVersion"), "29")

    def test_search_suggestions_pass_song_metadata_to_local_matcher(self):
        suggestions = (MAIN / "kotlin/com/music/echo/ui/screens/search/suggestions/SuggestionsViewModel.kt").read_text()
        self.assertEqual(suggestions.count('YouTubeQueue(WatchEndpoint(videoId = bestMatch.id), bestMatch.toMediaMetadata())'), 2)

    def test_device_specific_download_mappings_are_never_backed_up(self):
        store = (MAIN / "kotlin/com/music/echo/playback/SharedDownloadStore.kt").read_text()
        registry = (MAIN / "kotlin/com/music/echo/playback/DownloadSourceRegistry.kt").read_text()
        names = {"ft_shared_downloads", "ft_shared_pending_removals", "ft_download_sources"}
        for name in names:
            self.assertIn(f'getSharedPreferences("{name}"', store + registry)
        expected = {f"{name}.xml" for name in names}
        legacy = ET.parse(MAIN / "res/xml/backup_rules.xml").getroot()
        self.assertTrue(expected <= {el.get("path") for el in legacy.findall("exclude") if el.get("domain") == "sharedpref"})
        modern = ET.parse(MAIN / "res/xml/data_extraction_rules.xml").getroot()
        for section in ("cloud-backup", "device-transfer"):
            exclusions = {el.get("path") for el in modern.findall(f"{section}/exclude") if el.get("domain") == "sharedpref"}
            self.assertTrue(expected <= exclusions, section)

    def test_temporary_cache_expires_after_one_hour_but_downloads_do_not(self):
        playback = MAIN / "kotlin/com/music/echo/playback"
        expiry = (playback / "StreamingCacheExpiry.kt").read_text()
        guard = (playback / "StreamingCacheGuard.kt").read_text()
        module = " ".join((MAIN / "kotlin/com/music/echo/di/AppModule.kt").read_text().split())
        service = " ".join((playback / "MusicService.kt").read_text().split())
        settings = (MAIN / "kotlin/com/music/echo/ui/screens/settings/StorageSettings.kt").read_text()
        self.assertIn("STREAMING_CACHE_LIFETIME_MS = 60 * 60 * 1000L", expiry)
        self.assertIn("@PlayerCache private val cache: SimpleCache", expiry)
        # Expiry must never be handed the download cache.
        self.assertNotIn("DownloadCache", expiry + guard)
        self.assertIn("StreamingCacheEvictor(sizeEvictor, ages)", module)
        self.assertIn('resolve("download"), NoOpCacheEvictor(), databaseProvider', module)
        # Only the temporary player-cache data source is guarded by expiry.
        self.assertEqual(service.count("GuardedStreamingDataSource("), 1)
        self.assertIn("val temporaryCacheFactory = CacheDataSource.Factory() .setCache(playerCache)", service)
        self.assertIn(
            "GuardedStreamingDataSource( temporaryCacheFactory.createDataSource(), "
            "streamingCacheGuard, streamingCacheExpiry, )",
            service,
        )
        self.assertIn("R.string.song_cache_expiry_note", settings)
        note = ET.parse(MAIN / "res/values/echo_strings.xml").find("string[@name='song_cache_expiry_note']")
        self.assertIn("one hour", note.text)
        self.assertIn("Downloaded songs are kept", note.text)

    def test_removed_beat_matching_keeps_crossfade_and_radio_queue(self):
        service = (MAIN / "kotlin/com/music/echo/playback/MusicService.kt").read_text()
        player = (MAIN / "kotlin/com/music/echo/ui/player/Player.kt").read_text()
        settings = (MAIN / "kotlin/com/music/echo/ui/screens/settings/PlayerSettings.kt").read_text()
        self.assertNotIn("AutomixCrossfadeKey", service + settings)
        self.assertNotIn("computeAutomixPlan", service)
        self.assertIn("CrossfadeEnabledKey", service + settings)
        self.assertIn("EqualPowerCurve.calculateGains", service)
        self.assertIn("automixItems", service)
        self.assertIn("addToQueueAutomix", player)
        for menu in ("PlayerMenu.kt", "OldPlayerMenu.kt"):
            self.assertNotIn("Song DNA", (MAIN / "kotlin/com/music/echo/ui/menu" / menu).read_text())

    def test_every_download_tap_offers_formats_before_queueing(self):
        playback = MAIN / "kotlin/com/music/echo/playback"
        util = (playback / "DownloadUtil.kt").read_text()
        chooser = (playback / "DownloadChooser.kt").read_text()
        discovery = (playback / "DownloadOptionDiscovery.kt").read_text()
        activity = (MAIN / "kotlin/com/music/echo/MainActivity.kt").read_text()
        # One queueing path: no screen or service builds a download request any more.
        offenders = [
            str(path.relative_to(MAIN))
            for path in (MAIN / "kotlin").rglob("*.kt")
            if "sendAddDownload" in path.read_text() and path.name != "DownloadUtil.kt"
        ]
        self.assertEqual([], offenders)
        # Every download entry point asks through the chooser instead.
        entry_points = [
            path for path in (MAIN / "kotlin").rglob("*.kt")
            if re.search(r"(downloadChooser|chooser)\.request\(", path.read_text())
        ]
        self.assertGreaterEqual(len(entry_points), 19)
        for path in entry_points:
            source = path.read_text()
            self.assertTrue(
                "import echo.music.iad1tya.LocalDownloadChooser" in source
                or "chooser: DownloadChooserController" in source,
                str(path.relative_to(MAIN)),
            )
        # Menus are bottom-sheet windows, so they close and the chooser is the only dialog shown.
        for path in entry_points:
            relative = str(path.relative_to(MAIN))
            if "ui/menu/" not in relative and "ui/screens/playlist/" not in relative:
                continue
            lines = path.read_text().split("\n")
            for index, line in enumerate(lines):
                if not re.search(r"(downloadChooser|chooser)\.request\(", line):
                    continue
                following = "\n".join(lines[index + 1:index + 4])
                self.assertTrue(
                    "onDismiss()" in following
                    or "menuState.dismiss()" in following
                    or "clearAction()" in following,
                    f"{relative}:{index + 1}",
                )
        # The dialog is hosted once, next to the other playback prompts.
        self.assertIn("LocalDownloadChooser provides downloadChooser", activity)
        self.assertIn("DownloadChooserDialog(downloadChooser)", activity)
        self.assertIn("@Inject lateinit var downloadChooser: DownloadChooserController", activity)
        # Opening the chooser is read-only: no queueing and no source lock during discovery.
        self.assertNotIn("sendAddDownload", discovery + chooser)
        self.assertNotIn("qualityFor", discovery + chooser)
        # The chosen format is locked before the request reaches the download service.
        self.assertLess(util.index("sourceRegistry.qualityFor(id, quality)"), util.index("sendAddDownload(request)"))
        # A whole batch asks once, using the first song's real options.
        self.assertEqual(1, chooser.count("queue.discover(items.first().id)"))
        # Automatic downloads keep following the saved setting.
        self.assertIn("downloadUtil.enqueueDownload(song.id, song.title, quality)", (playback / "MusicService.kt").read_text())
        # A device file has no YouTube formats, so it is copied as the old button did.
        self.assertIn("pending.partition { queue.isLocalMedia(it.id) }", chooser)
        self.assertIn("queue.enqueue(local, queue.savedQuality())", chooser)
        self.assertIn("override fun isLocalMedia(id: String): Boolean = id.isLocalMediaId()", util)
        self.assertIn("override fun savedQuality(): DownloadQuality = downloadQuality.canonical()", util)

    def test_download_chooser_reports_only_real_formats_and_sizes(self):
        playback = MAIN / "kotlin/com/music/echo/playback"
        dialog = (MAIN / "kotlin/com/music/echo/ui/component/DownloadChooserDialog.kt").read_text()
        chooser = (playback / "DownloadChooser.kt").read_text()
        strings = ET.parse(MAIN / "res/values/strings.xml").getroot()
        values = {element.get("name"): "".join(element.itertext()) for element in strings}
        for name in (
            "ft_download_chooser_title",
            "ft_download_chooser_title_multiple",
            "ft_download_chooser_loading",
            "ft_download_chooser_suggested",
            "ft_download_chooser_size_unknown",
            "ft_download_chooser_multiple_note",
            "ft_download_chooser_unavailable",
            "ft_download_chooser_locked_note",
            "ft_download_chooser_no_options",
            "ft_download_chooser_lookup_failed",
            "ft_download_queue_failed",
        ):
            self.assertIn(name, values)
            self.assertIn("R.string." + name, dialog + chooser + (playback / "DownloadUtil.kt").read_text(), name)
        # Nothing is promised to be lossless, and no format is invented.
        self.assertNotRegex(dialog + chooser, re.compile(r"(?i)flac|lossless"))
        # An unknown size stays unknown instead of being estimated.
        self.assertIn("?: unknownSize", dialog)
        self.assertEqual(values["ft_download_chooser_size_unknown"], "Size unknown")
        # No file-size ceiling: large formats are offered like any other.
        self.assertNotRegex(dialog + chooser, re.compile(r"15\s*(_|\*)?\s*(000|1024)|MAX_DOWNLOAD_SIZE"))
        # A missing format and a failed lookup are both reported, never silently queued.
        self.assertIn("DownloadChooserFailure.NO_AUDIO_FORMAT", dialog + chooser)
        self.assertIn("DownloadChooserFailure.LOOKUP_FAILED", dialog + chooser)
        self.assertIn("Nothing was queued", values["ft_download_chooser_no_options"])
        self.assertIn("Nothing was queued", values["ft_download_chooser_lookup_failed"])
        # YouTube High is the suggested choice.
        self.assertIn("quality == DownloadQuality.HIGH", (playback / "DownloadOptionDiscovery.kt").read_text())
        self.assertIn("option.isRecommended", dialog)

    def test_cached_songs_are_never_reported_as_downloaded(self):
        playback = MAIN / "kotlin/com/music/echo/playback"
        util = (playback / "DownloadUtil.kt").read_text()
        service = " ".join((playback / "MusicService.kt").read_text().split())
        cached = (MAIN / "kotlin/com/music/echo/viewmodels/CachePlaylistViewModel.kt").read_text()
        dao = (ROOT / "core/src/main/kotlin/echo/music/iad1tya/db/DatabaseDao.kt").read_text()
        # Streaming writes only to the temporary cache; the download cache is read-only there.
        self.assertIn(".setCache(downloadCache)", service)
        self.assertIn(".setCacheWriteDataSinkFactory(null)", service)
        self.assertIn(".setCache(playerCache)", service)
        # The Cached list is what is left after real downloads are removed from it.
        self.assertIn("cachedIds.subtract(downloadedIds)", cached)
        # The stored flag is reconciled against the live download list on start.
        self.assertIn('@Query("SELECT id FROM song WHERE isDownloaded = 1")', dao)
        self.assertIn("fun downloadedSongIds(): List<String>", dao)
        self.assertIn("reconcileStaleDownloadedFlags()", util)
        self.assertIn(
            "filterNot { id -> downloads.value[id]?.state == Download.STATE_COMPLETED }", util
        )
        # Only a completed download ever sets the flag the Downloaded lists read.
        self.assertIn("current?.state == Download.STATE_COMPLETED", util)

    def test_downloads_keep_their_real_container_and_mp3_stays_on_demand(self):
        playback = MAIN / "kotlin/com/music/echo/playback"
        store = (playback / "SharedDownloadStore.kt").read_text()
        names = (playback / "SharedDownloadNames.kt").read_text()
        exporter = (playback / "AudioExportService.kt").read_text()
        menus = (MAIN / "kotlin/com/music/echo/ui/menu/SongMenu.kt").read_text()
        # Publishing copies the downloaded bytes; it never re-encodes them.
        self.assertNotIn("FFmpeg", store)
        self.assertNotIn("libmp3lame", store)
        self.assertIn("SharedDownloadNames.extension(mime)", store)
        self.assertIn('"audio/webm", "video/webm" -> "webm"', names)
        self.assertIn('"audio/mp4", "audio/x-m4a", "video/mp4" -> "m4a"', names)
        # MP3 remains an explicit, separate export action.
        self.assertIn("libmp3lame", exporter)
        self.assertIn("enableExportAsMp3", menus)


    def test_local_song_info_edits_the_file_without_reencoding_audio(self):
        localmedia = MAIN / "kotlin/com/music/echo/localmedia"
        tags = (localmedia / "AudioTagEdit.kt").read_text()
        writer = (localmedia / "AudioTagWriter.kt").read_text()
        editor = (localmedia / "LocalSongInfoEditor.kt").read_text()
        # Retagging copies streams; it never decodes or re-encodes the audio.
        self.assertIn('" -map 0:a:0 -map 1:v:0 -c:a copy -c:v $codec -disposition:v attached_pic"', tags)
        self.assertIn("-map 0 -c copy$mp3Id3$metadata", tags)
        self.assertNotIn("libmp3lame", tags)
        self.assertNotIn("-b:a", tags)
        # Only containers ffmpeg can really mux a picture into claim to support a cover.
        self.assertIn("MP3(true)", tags)
        self.assertIn("MP4(true)", tags)
        self.assertIn("FLAC(true)", tags)
        self.assertIn("OGG(false)", tags)
        self.assertIn("WAV(false)", tags)
        self.assertIn("MATROSKA(false)", tags)
        # The rewritten file is checked before it replaces anything, and the descriptor is truncated
        # so no stale tail survives a shorter write.
        self.assertIn("private fun verify(", writer)
        self.assertIn("sink.channel.truncate(0)", writer)
        self.assertIn("data class Source(val file: File, val isTheUsersFile: Boolean)", writer)
        self.assertIn("if (source.isTheUsersFile && original.canWrite())", writer)
        # MediaStore and FT Music's own library are refreshed from the file that was written.
        self.assertIn("MediaScannerConnection.scanFile", writer)
        self.assertIn("database.withTransaction", writer)
        self.assertIn("deleteSongArtistMaps(songId)", writer)
        # A container that cannot hold a cover drops it rather than pretending to write one.
        self.assertIn("if (container.supportsCover) coverBytes else null", editor)

    def test_edit_info_reports_every_refusal_and_never_writes_before_apply(self):
        localmedia = MAIN / "kotlin/com/music/echo/localmedia"
        ui = MAIN / "kotlin/com/music/echo/ui/component"
        menu = (MAIN / "kotlin/com/music/echo/ui/menu/SongMenu.kt").read_text()
        activity = (MAIN / "kotlin/com/music/echo/MainActivity.kt").read_text()
        dialog = (ui / "LocalSongInfoDialog.kt").read_text()
        viewmodel = (ui / "LocalSongInfoViewModel.kt").read_text()
        search = (localmedia / "TagSearchSource.kt").read_text()
        strings = (MAIN / "res/values/echo_strings.xml").read_text()
        # A local song's Edit entry writes into its file; online songs keep the database-only edit.
        self.assertIn("localSongInfo.request(song)", menu)
        self.assertIn("R.string.ft_edit_info_title else R.string.edit", menu)
        self.assertIn("LocalSongInfo provides localSongInfo", activity)
        self.assertIn("LocalSongInfoDialog(onDismiss = localSongInfo::clear)", activity)
        # Suggestions come from a real YouTube Music song search the user accepts one at a time.
        self.assertIn("YouTube.SearchFilter.FILTER_SONG", search)
        self.assertIn("fun acceptResult(result: TagSearchResult)", viewmodel)
        self.assertIn("fun apply()", viewmodel)
        # Android's own permission prompt is launched, and refusing it is reported.
        self.assertIn("StartIntentSenderForResult", dialog)
        self.assertIn("viewModel.retryAfterPermission()", dialog)
        self.assertIn("viewModel.permissionRefused()", dialog)
        # Every refusal has a message of its own.
        for failure in (
            "UNSUPPORTED_FORMAT", "PERMISSION_DENIED", "NO_FILE", "WRITE_FAILED", "VERIFY_FAILED",
        ):
            self.assertIn(f"AudioTagEditFailure.{failure} ->", dialog)
        for name in (
            "ft_edit_info_failed_format", "ft_edit_info_failed_permission", "ft_edit_info_failed_nofile",
            "ft_edit_info_failed_write", "ft_edit_info_failed_verify", "ft_edit_info_cover_unsupported",
            "ft_edit_info_audio_note", "ft_edit_info_saved", "ft_edit_info_search_hint",
        ):
            self.assertIn(f'<string name="{name}">', strings)

    def test_ft_owned_shortcut_targets(self):
        shortcuts = ET.parse(MAIN / "res/xml/shortcuts.xml").getroot()
        for item in shortcuts.findall("shortcut/intent"):
            self.assertEqual(item.get(ANDROID + "targetPackage"), "@string/ft_shortcut_target_package")
        release = ET.parse(MAIN / "res/values/ft_package.xml").find("string")
        debug = ET.parse(APP / "src/debug/res/values/ft_package.xml").find("string")
        self.assertEqual(release.text, "com.ftmusic.app")
        self.assertEqual(debug.text, "com.ftmusic.app.debug")


if __name__ == "__main__":
    unittest.main()
