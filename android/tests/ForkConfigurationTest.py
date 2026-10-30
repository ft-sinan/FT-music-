#!/usr/bin/env python3
"""Fast, offline checks for FT Music's Echo fork configuration."""
from pathlib import Path
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
        self.assertIn("GPLv3 · Based on Echo Music", about)
        self.assertIn("https://github.com/EchoMusicApp/Echo-Music", about)
        self.assertIn("https://github.com/ft-sinan/FT-music-", about)

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

    def test_updates_are_ft_owned_and_manual(self):
        update = (MAIN / "kotlin/com/music/echo/ui/screens/settings/UpdateSettings.kt").read_text()
        nav = (MAIN / "kotlin/com/music/echo/ui/screens/NavigationBuilder.kt").read_text()
        activity = (MAIN / "kotlin/com/music/echo/MainActivity.kt").read_text()
        self.assertIn('https://github.com/ft-sinan/FT-music-/releases', update)
        self.assertNotIn('echomusic.fun', update)
        self.assertIn('composable("update") { UpdateSettings(navController, scrollBehavior) }', nav)
        self.assertNotIn('if (getAutoUpdateCheckSetting(context))', activity)

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
