import java.util.Properties

plugins {
    id("com.android.application")
}

// A single semantic version controls both the human version and a monotonic Android versionCode.
val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val ftVersionName = versionProperties.getProperty("versionName") ?: error("Missing versionName")
require(Regex("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matches(ftVersionName)) { "Use a numeric major.minor.patch version" }
val ftVersionParts = ftVersionName.split(".").map { it.toInt() }
require(ftVersionParts[0] <= 2099 && ftVersionParts[1] <= 999 && ftVersionParts[2] <= 999) { "Version is outside Android versionCode limits" }
val ftVersionCode = ftVersionParts[0] * 1_000_000 + ftVersionParts[1] * 1_000 + ftVersionParts[2]
require(ftVersionCode > 0) { "versionCode must be positive" }

val bundledAssets = layout.buildDirectory.dir("generated/bundledAssets")
val copyBundledAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../public")) { include("audio/**", "images/**") }
    into(bundledAssets)
}

android {
    namespace = "com.ftmusic.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.ftmusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ftVersionCode
        versionName = ftVersionName
    }
    sourceSets.getByName("main").assets.srcDir(bundledAssets)
    sourceSets.getByName("test").java.srcDir("../tests")
    androidResources { noCompress += "mp3" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
tasks.named("preBuild") { dependsOn(copyBundledAssets) }


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// These standalone entry points are invoked by NativeCoreJUnitTest or a dedicated harness.
// They are not JUnit test classes and must not be discovered as such by Gradle.
tasks.withType<Test>().configureEach {
    exclude("**/NativeCoreTest.class", "**/SyncIntegrationTest.class")
}
