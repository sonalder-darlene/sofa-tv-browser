import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * Release signing details, from keystore.properties at the repository root (never committed) or, for CI, from
 * SOFA_KEYSTORE_FILE / SOFA_KEYSTORE_PASSWORD / SOFA_KEY_ALIAS / SOFA_KEY_PASSWORD. Without them, release builds
 * fall back to the debug key so they still install for testing.
 */
val releaseKey: Map<String, String?> = run {
    val props = Properties()
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    fun value(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    mapOf(
        "storeFile" to value("storeFile", "SOFA_KEYSTORE_FILE"),
        "storePassword" to value("storePassword", "SOFA_KEYSTORE_PASSWORD"),
        "keyAlias" to value("keyAlias", "SOFA_KEY_ALIAS"),
        "keyPassword" to value("keyPassword", "SOFA_KEY_PASSWORD"),
    )
}
val hasReleaseKey = releaseKey.values.all { !it.isNullOrBlank() }
if (!hasReleaseKey) logger.lifecycle("Sofa: no release key configured; release builds are signed with the debug key.")

/** Where the standard build looks for updates; -Psofa.updateUrl=… points a test build (or a fork) elsewhere. */
val updateUrl: String = providers.gradleProperty("sofa.updateUrl")
    .getOrElse("https://api.github.com/repos/sonalder-darlene/sofa-tv-browser/releases/latest")
/** SHA-256 of the release certificate: only copies signed with it are offered updates, since only they can take one. */
val releaseCert: String = providers.gradleProperty("sofa.releaseCert")
    .getOrElse("74e23f09bb4b011600c38e7d555fb38926acc3835029b7fc02e48466c34c8aa9")

android {
    namespace = "app.sofabrowser"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.sofabrowser"
        minSdk = 24
        targetSdk = 36
        // MAJOR * 10000 + MINOR * 100 + PATCH, so 1.2.3 is 10203. Keep both literal: F-Droid reads them from here.
        versionCode = 10100
        versionName = "1.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // GitHub releases, F-Droid and other app stores all ship this APK. Its update checker is opt-in and
        // stays hidden when an app store installed Sofa.
        create("standard") {
            dimension = "distribution"
            buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
            buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCert\"")
        }
        // Google Play forbids apps that update themselves, so this build has no updater at all.
        create("play") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getValue("storeFile")!!)
                storePassword = releaseKey["storePassword"]
                keyAlias = releaseKey["keyAlias"]
                keyPassword = releaseKey["keyPassword"]
            }
        }
    }

    buildTypes {
        // Test builds install next to the real Sofa (named "Sofa (test)" in src/debug), so testing on a TV keeps its
        // data.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dependenciesInfo {
        // No Google-encrypted dependency metadata blob in the APK.
        includeInApk = false
        includeInBundle = false
    }
}

/** Copies the licence files from the repository root into generated assets. */
abstract class CopyLegalFiles : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        sources.forEach { it.copyTo(File(out, it.name)) }
    }
}

val legalAssets = tasks.register<CopyLegalFiles>("legalAssets") {
    sources.from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"))
}

androidComponents {
    onVariants { variant ->
        // Ships LICENSE and NOTICE.md: the APK carries its licence, and About can show both.
        variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, CopyLegalFiles::outputDir)
    }
}

dependencies {
    // Thin shim over system WebView features (profiles, document-start scripts, UA client hints, headers).
    implementation("androidx.webkit:webkit:1.17.1")

    testImplementation("junit:junit:4.13.2")
}
