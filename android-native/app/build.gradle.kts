import java.util.Properties
import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("androidx.baselineprofile")
}

// Release signing uses the same key.properties format as the Tauri app
// (storeFile, storePassword, keyAlias, keyPassword); it is git-ignored.
val keyProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// armeabi-v7a: TVs and TV boxes, which mostly run 32-bit Android even on 64-bit chips.
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val splitAbis = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// ---- Harbor's shared core (../../harbor-core), built for Android through harbor-core-jni ----
// The same Rust crate the desktop app links, so ranking matches it exactly. Needs rustup with
// the aarch64-linux-android and x86_64-linux-android targets, and the NDK below.
val ndkVersionUsed = "27.2.12479018"
val sdkFromProperties: String? = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("sdk.dir")
val sdkDir = sequenceOf(sdkFromProperties, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    .filterNotNull().firstOrNull { File(it).isDirectory } ?: ""
val rustTargets = mapOf("arm64-v8a" to "aarch64-linux-android", "armeabi-v7a" to "armv7-linux-androideabi", "x86_64" to "x86_64-linux-android")
// The NDK names its 32-bit ARM compilers armv7a-…, where Rust says armv7-….
val clangPrefix = mapOf("armv7-linux-androideabi" to "armv7a-linux-androideabi")
val coreJniDir = rootProject.file("harbor-core-jni")
val coreLibsDir = layout.buildDirectory.dir("harborCoreJniLibs")

val buildHarborCore by tasks.registering {
    description = "Builds harbor-core for Android (libharbor_core_jni.so per ABI)."
    inputs.dir(coreJniDir.resolve("src"))
    inputs.file(coreJniDir.resolve("Cargo.toml"))
    inputs.dir(rootProject.file("../harbor-core/src"))
    outputs.dir(coreLibsDir)
    doLast {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val host = when {
            windows -> "windows-x86_64"
            System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val clangDir = File(sdkDir, "ndk/$ndkVersionUsed/toolchains/llvm/prebuilt/$host/bin")
        val cargoHome = File(System.getProperty("user.home"), ".cargo/bin")
        val cargo = File(cargoHome, if (windows) "cargo.exe" else "cargo").takeIf { it.exists() }?.path ?: "cargo"
        rustTargets.forEach { (abi, triple) ->
            val linker = File(clangDir, "${clangPrefix[triple] ?: triple}26-clang" + if (windows) ".cmd" else "")
            providers.exec {
                workingDir = coreJniDir
                commandLine(cargo, "build", "--release", "--target", triple)
                environment("CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER", linker.path)
                environment("PATH", cargoHome.path + File.pathSeparator + System.getenv("PATH"))
                // 16 KB pages (Android 15+ devices): the library must be aligned for them to load.
                environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
            }.result.get()
            val so = coreJniDir.resolve("target/$triple/release/libharbor_core_jni.so")
            so.copyTo(coreLibsDir.get().asFile.resolve("$abi/libharbor_core_jni.so"), overwrite = true)
        }
    }
}
tasks.named("preBuild") { dependsOn(buildHarborCore) }

// Harbor's own typefaces (Sentient, Switzer, General Sans, Cabinet Grotesk) come from Fontshare, as
// the desktop app loads them. Their licence (ITF FFL) allows embedding them in an app but not
// publishing the files, so they are fetched at build time into the APK, never committed.
val fontshareDir = layout.buildDirectory.dir("fontshareAssets")
val fontshareFonts = mapOf(
    "sentient" to "Sentient-Variable.ttf",
    "switzer" to "Switzer-Variable.ttf",
    "general-sans" to "GeneralSans-Variable.ttf",
    "cabinet-grotesk" to "CabinetGrotesk-Variable.ttf",
)
val fetchFontshareFonts by tasks.registering {
    description = "Downloads Harbor's Fontshare typefaces into the app's assets."
    outputs.dir(fontshareDir)
    doLast {
        val out = fontshareDir.get().asFile.resolve("fonts").apply { mkdirs() }
        fontshareFonts.forEach { (slug, variable) ->
            val target = out.resolve("$slug.ttf")
            if (target.length() > 0) return@forEach
            val zip = URI("https://api.fontshare.com/v2/fonts/download/$slug").toURL().openStream()
            ZipInputStream(zip).use { entries ->
                while (true) {
                    val entry = entries.nextEntry ?: break
                    if (entry.name.endsWith("/TTF/$variable")) {
                        target.outputStream().use { entries.copyTo(it) }
                        break
                    }
                }
            }
            check(target.length() > 0) { "Fontshare's $slug download has no $variable" }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchFontshareFonts) }

android {
    namespace = "app.harbor.android"
    compileSdk = 36
    ndkVersion = ndkVersionUsed
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        // Harbor for Android: its own id, so it installs beside any other Harbor or Lart build.
        applicationId = "app.harbor.android"
        minSdk = 26
        targetSdk = 36
        // Whisper has a private C++ runtime. mpv's newer shared runtime must keep winning
        // its existing dependency merge; substituting the NDK's can prevent mpv loading.
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
        versionCode = 28
        versionName = "1.0.16"
        // Phones (arm64), 32-bit TVs (armeabi-v7a) and the x86_64 emulator; mpv adds about 25 MB per ABI.
        if (!splitAbis) ndk { abiFilters += abis }
    }

    // Release builds come as one APK per ABI, so a phone downloads only arm64's
    // libraries (about half the size), plus one universal APK with every ABI for anyone unsure which
    // to pick; debug builds keep both in one APK.
    splits {
        abi {
            isEnable = splitAbis
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keyProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // harbor-core's library (buildHarborCore above) ships beside mpv's.
    sourceSets["main"].jniLibs.srcDir(coreLibsDir)
    sourceSets["main"].assets.srcDir(fontshareDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // libmpv and the FFmpeg audio decoder both ship the C++ runtime.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    // Installs the baseline profile (baselineprofile/) so the first launch is already compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // QR codes for setting up a TV from a phone (Big Picture's phone setup).
    implementation("com.google.zxing:core:3.5.3")
    baselineProfile(project(":baselineprofile"))
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Harbor's streaming-service logos are SVGs (assets/services).
    implementation("io.coil-kt:coil-svg:2.7.0")
    // Trailer streams from YouTube, as Harbor gets them with yt-dlp (GPL-3, like the rest).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // Cloudstream's extension library (GPL-3): the API surface Android extensions (.cs3, Harbor's
    // "android-extension" plugins) are built against, so they load and run here as in Cloudstream.
    // Android pieces Cloudstream extensions use from the Cloudstream app (settings screens, preferences).
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.github.recloudstream.cloudstream:library:4.8.0") {
        // The same NewPipeExtractor as above under another group spelling; one copy, the newer.
        exclude(group = "com.github.teamnewpipe")
    }

    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    // AC3, E-AC3, DTS, TrueHD and 7.1 audio the device cannot decode (GPL-3.0).
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    // mpv, the main player engine (libmpv-android, MIT; mpv and FFmpeg inside are LGPL/GPL).
    implementation("dev.jdtech.mpv:libmpv:1.0.0")

    // Chromecast: the Cast framework with Google's default media receiver.
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}
