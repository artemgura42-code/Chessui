plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.chessvision"
    compileSdk = 34
    defaultConfig { applicationId = "app.chessvision"; minSdk = 29; targetSdk = 34; versionCode = 4; versionName = "1.4"
        ndk { abiFilters += "arm64-v8a" } }
    // libstockfish.so must be extracted to disk so it can be executed
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}


// Stockfish is fetched from the official Stockfish 19 release during CI build.
// The Android ARM64 executable is packaged as lib/<abi>/libstockfish.so so
// Android extracts it into nativeLibraryDir with executable permissions.
val downloadStockfish by tasks.registering {
    val outputBinary = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libstockfish.so")
    val licenseFile = layout.projectDirectory.file("src/main/assets/stockfish/COPYING.txt")
    outputs.file(outputBinary)
    outputs.file(licenseFile)
    outputs.upToDateWhen { outputBinary.asFile.isFile && outputBinary.asFile.length() > 50_000_000L && licenseFile.asFile.isFile && licenseFile.asFile.length() > 10_000L }
    doLast {
        val target = outputBinary.asFile
        if (target.exists() && target.length() > 50_000_000L) {
            logger.lifecycle("Stockfish binary already present: ${target.length()} bytes")
        } else {
            target.parentFile.mkdirs()
            val work = layout.buildDirectory.dir("stockfish-download").get().asFile
            work.mkdirs()
            val archive = work.resolve("stockfish-android-arm64-universal.tar.gz")
            val url = java.net.URL("https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-android-arm64-universal.tar.gz")
            logger.lifecycle("Downloading official Stockfish 19 Android ARM64...")
            url.openStream().use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
            val extracted = work.resolve("extracted")
            extracted.deleteRecursively()
            extracted.mkdirs()
            project.exec { commandLine("tar", "-xzf", archive.absolutePath, "-C", extracted.absolutePath) }
            val binary = project.fileTree(extracted).files.firstOrNull {
                it.isFile && (it.name == "stockfish-android-arm64-universal" || it.name == "stockfish")
            } ?: throw GradleException("Не найден исполняемый файл Stockfish в официальном архиве sf_19")
            if (binary.length() < 50_000_000L) throw GradleException("Файл Stockfish подозрительно мал: ${binary.length()} bytes")
            binary.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            val licenseTarget = licenseFile.asFile
            licenseTarget.parentFile.mkdirs()
            val licenseUrl = java.net.URL("https://raw.githubusercontent.com/official-stockfish/Stockfish/sf_19/Copying.txt")
            licenseUrl.openStream().use { input -> licenseTarget.outputStream().use { output -> input.copyTo(output) } }
            logger.lifecycle("Stockfish packaged: ${target.length()} bytes; GPL license included")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadStockfish) }
