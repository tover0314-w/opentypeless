import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Copy

@DisableCachingByDefault(
    because = "The manifest intentionally contains absolute paths from the current Gradle invocation",
)
abstract class WriteCompiledClassPathManifest : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directories: ListProperty<Directory>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun writeManifest() {
        val paths = (jars.get().map { it.asFile } + directories.get().map { it.asFile })
            .map { it.absoluteFile.normalize() }
            .distinctBy { it.path }
            .sortedBy { it.path }

        val output = manifestFile.get().asFile
        output.parentFile.mkdirs()
        output.outputStream().buffered().use { stream ->
            paths.forEach { path ->
                stream.write(path.path.toByteArray(StandardCharsets.UTF_8))
                stream.write(0)
            }
        }
    }
}

plugins {
    id("com.android.application")
}

val sherpaAsrRuntime = layout.projectDirectory.file("libs/sherpa-onnx-asr-1.13.4.aar").asFile
val expectedSherpaAsrRuntimeSha256 =
    "35af2790bfcb39a1bfe6d0d495193b7fadc367c5c6f07e5e95996ba210cb9196"
require(sherpaAsrRuntime.isFile) {
    "Missing pinned ASR-only runtime: ${sherpaAsrRuntime.path}"
}
val actualSherpaAsrRuntimeSha256 = MessageDigest.getInstance("SHA-256")
    .digest(sherpaAsrRuntime.readBytes())
    .joinToString("") { "%02x".format(it) }
require(actualSherpaAsrRuntimeSha256 == expectedSherpaAsrRuntimeSha256) {
    "ASR-only runtime checksum mismatch: expected $expectedSherpaAsrRuntimeSha256, " +
        "got $actualSherpaAsrRuntimeSha256"
}

val rimeRuntime = layout.projectDirectory
    .file("libs/opentypeless-rime-runtime-1.17.0.aar")
    .asFile
val expectedRimeRuntimeSha256 =
    "5fce6f0e5356d1f80cc080d8ca7f55e8177caa8cbc28538ebde7e69bd1665d2d"
require(rimeRuntime.isFile) {
    "Missing pinned Rime runtime: ${rimeRuntime.path}"
}
val actualRimeRuntimeSha256 = MessageDigest.getInstance("SHA-256")
    .digest(rimeRuntime.readBytes())
    .joinToString("") { "%02x".format(it) }
require(actualRimeRuntimeSha256 == expectedRimeRuntimeSha256) {
    "Rime runtime checksum mismatch: expected $expectedRimeRuntimeSha256, " +
        "got $actualRimeRuntimeSha256"
}

// Normal CI/release builds remain universal. Direct device handoffs can request one pinned ABI
// so the native ASR runtime stays below messaging-platform attachment limits.
val deliveryAbi = providers.gradleProperty("opentypeless.deliveryAbi").orNull
val supportedDeliveryAbis = setOf("arm64-v8a", "x86_64")
require(deliveryAbi == null || deliveryAbi in supportedDeliveryAbis) {
    "Unsupported OpenTypeless delivery ABI: $deliveryAbi"
}

val releaseStorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseValues = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseValues.all { !it.isNullOrBlank() }
require(releaseValues.none { !it.isNullOrBlank() } || releaseSigningConfigured) {
    "Android release signing requires keystore path, store password, key alias, and key password"
}

android {
    namespace = "com.opentypeless.android"
    compileSdk = 35

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        applicationId = "com.opentypeless.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        if (deliveryAbi != null) {
            ndk {
                abiFilters += deliveryAbi
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

val exportCompiledArchitectureInputs = tasks.register("exportCompiledArchitectureInputs") {
    group = "verification"
    description = "Exports debug and release class inputs for the compiled architecture gate."
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.name !in setOf("debug", "release")) {
            return@onVariants
        }

        val variantTaskName = variant.name.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
        val outputDirectory = layout.buildDirectory.dir("editor-architecture/${variant.name}")

        val projectManifest = tasks.register<WriteCompiledClassPathManifest>(
            "export${variantTaskName}ProjectCompiledArchitecturePaths",
        ) {
            group = "verification"
            description = "Exports ${variant.name} project classes for architecture verification."
            manifestFile.set(outputDirectory.map { it.file("project.paths") })
        }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(projectManifest)
            .toGet(
                ScopedArtifact.CLASSES,
                WriteCompiledClassPathManifest::jars,
                WriteCompiledClassPathManifest::directories,
            )

        val allManifest = tasks.register<WriteCompiledClassPathManifest>(
            "export${variantTaskName}AllCompiledArchitecturePaths",
        ) {
            group = "verification"
            description = "Exports ${variant.name} complete class path for architecture verification."
            manifestFile.set(outputDirectory.map { it.file("all.paths") })
        }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.ALL)
            .use(allManifest)
            .toGet(
                ScopedArtifact.CLASSES,
                WriteCompiledClassPathManifest::jars,
                WriteCompiledClassPathManifest::directories,
            )

        exportCompiledArchitectureInputs.configure {
            dependsOn(projectManifest, allManifest)
        }

        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val verifyKeyboardShellManifest = tasks.register<Exec>(
            "verify${variantTaskName}KeyboardShellManifest",
        ) {
            group = "verification"
            description = "Verifies the ${variant.name} KBD-001 merged-manifest boundary."
            inputs.file(mergedManifest)
            inputs.file(rootProject.file("scripts/verify_keyboard_shell_manifest.py"))
            inputs.file(project.file("src/main/res/xml/data_extraction_rules.xml"))
            doFirst {
                commandLine(
                    "python3",
                    rootProject.file("scripts/verify_keyboard_shell_manifest.py").absolutePath,
                    "--manifest",
                    mergedManifest.get().asFile.absolutePath,
                    "--rules",
                    project.file("src/main/res/xml/data_extraction_rules.xml").absolutePath,
                    "--variant",
                    variant.name,
                )
            }
        }
        tasks.matching {
            it.name == "check" || it.name == "assemble${variantTaskName}"
        }.configureEach {
            dependsOn(verifyKeyboardShellManifest)
        }
    }
}

dependencies {
    implementation(files(sherpaAsrRuntime))
    implementation(files(rimeRuntime))
    //noinspection GradleDependency -- matches the pinned sherpa runtime's Kotlin ABI.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.20")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
