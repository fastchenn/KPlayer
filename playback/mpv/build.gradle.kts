import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.davplayer.playback.mpv"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/mpv/jniLibs"))
}

// Consume only native libraries, never upstream application code, resources, or permissions.
// A checksum-verified official release also makes Windows builds independent of the NDK.
val nativeLockFile = file("native-dependencies.properties")
val nativeLock = Properties().apply { nativeLockFile.inputStream().use(::load) }
val mpvAbis = providers.gradleProperty("mpvAbis").orElse("arm64-v8a").get()
    .split(',').map(String::trim).distinct()
require(mpvAbis.isNotEmpty() && mpvAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "mpvAbis supports arm64-v8a,x86_64"
}
android.defaultConfig.ndk.abiFilters.addAll(mpvAbis)
val nativeCache = gradle.gradleUserHomeDir.resolve("caches/kplayer-mpv")
val nativeOutput = layout.buildDirectory.dir("generated/mpv/jniLibs")
val moduleBuildDir = layout.buildDirectory.get().asFile.canonicalFile
val offlineBuild = gradle.startParameter.isOffline
val nativeLibraries = setOf(
    "libmpv.so", "libplayer.so", "libc++_shared.so", "libavcodec.so", "libavdevice.so",
    "libavfilter.so", "libavformat.so", "libavutil.so", "libswresample.so", "libswscale.so",
)

val prepareMpvNative by tasks.registering {
    group = "build setup"
    description = "Verify and extract pinned official libmpv binaries"
    inputs.file(nativeLockFile)
    inputs.property("abis", mpvAbis)
    outputs.dir(nativeOutput)
    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        nativeCache.mkdirs()
        val archives = mpvAbis.associateWith { abi ->
            val expectedHash = nativeLock.getProperty("$abi.sha256")
            val archive = nativeCache.resolve("$expectedHash.apk")
            if (!archive.isFile) {
                check(!offlineBuild) { "libmpv is not cached. Run :playback:mpv:prepareMpvNative once without --offline." }
                val artifact = nativeLock.getProperty("$abi.artifact")
                val url = "https://github.com/mpv-android/mpv-android/releases/download/${nativeLock.getProperty("release")}/$artifact"
                val temp = File.createTempFile("mpv-", ".part", nativeCache)
                try {
                    logger.lifecycle("Downloading official libmpv for $abi")
                    URI(url).toURL().openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 180_000
                    }.getInputStream().use { input -> temp.outputStream().use(input::copyTo) }
                    check(sha256(temp) == expectedHash) { "libmpv checksum mismatch for $abi" }
                    Files.move(temp.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { temp.delete() }
            }
            check(sha256(archive) == expectedHash) { "Cached libmpv checksum mismatch for $abi; remove $archive and retry." }
            archive
        }
        val output = nativeOutput.get().asFile.canonicalFile
        check(output.toPath().startsWith(moduleBuildDir.toPath()) && output != moduleBuildDir)
        check(!output.exists() || output.deleteRecursively()) { "Could not clean generated libmpv directory" }
        archives.forEach { (abi, archive) ->
            ZipFile(archive).use { zip ->
                val destination = output.resolve(abi).apply { mkdirs() }
                nativeLibraries.forEach { name ->
                    val entry = checkNotNull(zip.getEntry("lib/$abi/$name")) { "Missing $abi/$name in pinned release" }
                    zip.getInputStream(entry).use { input -> destination.resolve(name).outputStream().use(input::copyTo) }
                }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareMpvNative) }

dependencies {
    api(project(":playback:api"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
