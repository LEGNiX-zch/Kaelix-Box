import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// Build-time proot downloader task.
// Requirement: proot must be fetched from the repo main branch and packaged
// into the APK during the Gradle build. Runtime download is forbidden.
// proot is staged as a fake shared library (libproot.so) under jniLibs so
// AGP packages it into lib/arm64-v8a/ of the APK. At runtime the system
// extracts it under nativeLibraryDir, which is exec-permitted — this is
// what lets proot run on Android 13+ where filesDir is noexec and SELinux
// blocks executing arbitrary binaries out of app private storage. Same
// trick Termux / tiny_computer use.
// If the network download fails AND there is no usable committed copy in
// jniLibs, the build must terminate (no silent skip).

val PROOT_URL = "https://raw.githubusercontent.com/LEGNiX-zch/Kaelix-Box/main/proot"

fun copyStream(input: InputStream, output: OutputStream) {
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        output.write(buf, 0, n)
    }
    output.flush()
}

tasks.register("downloadProot") {
    description = "Downloads proot as libproot.so into jniLibs so AGP packages it into lib/arm64-v8a/."
    group = "kaelix"
    val out: File = project.file("src/main/jniLibs/arm64-v8a/libproot.so")
    val tmp: File = File(out.parentFile, "libproot.so.download")
    doLast {
        out.parentFile.mkdirs()
        try {
            logger.lifecycle("[Kaelix-Box] Downloading proot from $PROOT_URL")
            URL(PROOT_URL).openStream().use { input ->
                tmp.outputStream().use { output -> copyStream(input, output) }
            }
            if (tmp.length() < 1024) {
                throw GradleException("Downloaded proot is too small (${tmp.length()} bytes), aborting.")
            }
            tmp.copyTo(out, overwrite = true)
            out.setExecutable(true)
            tmp.delete()
            logger.lifecycle("[Kaelix-Box] proot installed to ${out.absolutePath} (${out.length()} bytes)")
        } catch (e: Exception) {
            tmp.delete()
            if (out.exists() && out.length() >= 1024) {
                logger.warn("[Kaelix-Box] Network download of proot failed (${e.message}); using committed copy ${out.absolutePath}.")
            } else {
                throw GradleException("Downloading proot failed and no usable copy is present: ${e.message}", e)
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn("downloadProot") }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build-time: extract the arm64-v8a native lib for zstd-jni out of its JAR
// and stage it under build/generated/jniLibs so the AGP jniLibs pipeline
// packages it into lib/arm64-v8a/ of the APK. Without this the linux/aarch64
// libzstd-jni-*.so shipped inside the JAR is dropped by AGP and decompression
// would crash on Android at runtime with UnsatisfiedLinkError.
val zstdJniLibDir: File = layout.buildDirectory.file("generated/jniLibs/arm64-v8a").get().asFile

tasks.register("extractZstdNative") {
    description = "Extracts the arm64-v8a libzstd-jni .so out of the zstd-jni dependency JAR."
    group = "kaelix"
    val cfg = configurations.named("debugRuntimeClasspath").get()
    val outDir = zstdJniLibDir
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        // Find the zstd-jni JAR on the runtime classpath.
        val jar = cfg.files.firstOrNull { it.name.matches(Regex("zstd-jni-.*\\.jar")) }
            ?: throw GradleException("zstd-jni jar not found on classpath")
        logger.lifecycle("[Kaelix-Box] Extracting arm64-v8a libzstd-jni from ${jar.name}")
        val zip = ZipFile(jar)
        val entry: ZipEntry? = zip.getEntry("linux/aarch64/libzstd-jni-${jar.nameWithoutExtension.removePrefix("zstd-jni-")}.so")
            ?: zip.getEntry("linux/aarch64/libzstd-jni.so")
            ?: throw GradleException("linux/aarch64 libzstd-jni .so not found inside ${jar.name}")
        zip.getInputStream(entry).use { input ->
            val out = File(outDir, "libzstd-jni-${jar.nameWithoutExtension.removePrefix("zstd-jni-")}.so")
            out.outputStream().use { output -> copyStream(input, output) }
            logger.lifecycle("[Kaelix-Box] staged ${out.name} (${out.length()} bytes) at ${out.absolutePath}")
        }
        zip.close()
    }
}

tasks.named("preBuild").configure { dependsOn("extractZstdNative") }

android {
    namespace = "com.kaelixbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kaelixbox"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/*.kotlin_module"
        )
        jniLibs.useLegacyPackaging = true
        // The zstd-jni JAR also ships darwin/win libs as JAR resources; they
        // are useless on Android and bloat the APK — drop them.
        resources.excludes += setOf(
            "darwin/**",
            "win/**",
            "freebsd/**",
            "aix/**",
            "linux/**"
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.luben:zstd-jni:1.5.7-15")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
