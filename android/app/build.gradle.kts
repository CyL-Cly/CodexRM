import javax.inject.Inject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class BuildDirectLibraries @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val minApi: Property<Int>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val ndk: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val repo: DirectoryProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun compile() {
        val os = System.getProperty("os.name").lowercase()
        val windows = os.startsWith("windows")
        val host = when {
            windows -> "windows-x86_64"
            os.startsWith("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val bin = ndk.get().dir("toolchains/llvm/prebuilt/$host/bin")
        val cc = bin.file("aarch64-linux-android${minApi.get()}-clang${if (windows) ".cmd" else ""}").asFile
        val output = destination.get().file("arm64-v8a/libcodexrm_p2p.so").asFile
        exec.exec {
            workingDir(repo.get().asFile)
            environment("GOOS", "android")
            environment("GOARCH", "arm64")
            environment("CGO_ENABLED", "1")
            environment("CC", cc.absolutePath)
            environment("CGO_LDFLAGS", "-Wl,-z,max-page-size=16384")
            commandLine("go", "build", "-buildmode=c-shared", "-trimpath", "-ldflags", "-s -w", "-o", output.absolutePath, "./cmd/codexrm-android-p2p")
        }
        output.resolveSibling("libcodexrm_p2p.h").delete()
    }
}

val p2pQa = project.hasProperty("codexrm.p2pQa")
val secureRelayQa = project.hasProperty("codexrm.secureRelayQa")
val fileTransferQa = project.hasProperty("codexrm.fileTransferQa")

android {
    namespace = "com.codexrm.mobile"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    val releaseQa = project.hasProperty("codexrm.releaseQa")
    testBuildType = if (releaseQa) "release" else "debug"

    val imageQa = project.hasProperty("codexrm.imageQa")
    val mathQa = project.hasProperty("codexrm.mathQa")
    require(listOf(imageQa, mathQa, p2pQa, fileTransferQa, secureRelayQa).count { it } <= 1) { "Choose only one CodexRM QA variant" }

    defaultConfig {
        applicationId = "com.codexrm.mobile"
        minSdk = 28
        targetSdk = 35
        ndk { abiFilters.add("arm64-v8a") }
        versionCode = 60
        versionName = "1.6.9"
        testInstrumentationRunner = if (p2pQa || fileTransferQa || secureRelayQa) {
            "com.codexrm.mobile.DirectInstrumentation"
        } else if (mathQa) {
            "com.codexrm.mobile.MathRenderingInstrumentation"
        } else {
            "com.codexrm.mobile.ImageRenderingInstrumentation"
        }
    }

    signingConfigs.getByName("debug") {
        storeFile = rootProject.projectDir.parentFile.resolve(".signature/debug.keystore")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (imageQa) applicationIdSuffix = ".imageqa"
            if (mathQa) applicationIdSuffix = ".mathqa"
            if (p2pQa) applicationIdSuffix = ".p2pqa"
            if (secureRelayQa) applicationIdSuffix = ".securerelayqa"
            if (fileTransferQa) applicationIdSuffix = ".filetransferqa"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (project.hasProperty("codexrm.testSign")) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            if (secureRelayQa) applicationIdSuffix = ".securerelayqa"
            if (imageQa) applicationIdSuffix = ".imageqa"
            if (mathQa) applicationIdSuffix = ".mathqa"
            if (p2pQa) applicationIdSuffix = ".p2pqa"
            if (fileTransferQa) applicationIdSuffix = ".filetransferqa"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/**",
                "kotlin/**",
            )
        }
    }

    androidResources {
        localeFilters += setOf("zh", "en")
    }
}

androidComponents {
    val repoDir = rootProject.layout.projectDirectory.dir("..")
    val buildDirectLibraries = tasks.register<BuildDirectLibraries>("buildDirectLibraries") {
        minApi.set(28)
        ndk.set(sdkComponents.ndkDirectory)
        repo.set(repoDir)
        sources.from(repoDir.files("go.mod", "go.sum"),
            repoDir.dir("cmd/codexrm-android-p2p"), repoDir.dir("internal/p2p"), repoDir.dir("internal/protocol"),
            repoDir.dir("internal/securechannel"))
        destination.set(layout.buildDirectory.dir("generated/direct-libs/arm64-v8a"))
    }
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildDirectLibraries, BuildDirectLibraries::destination)
    }
}

dependencies {
    implementation("androidx.activity:activity:1.12.4")
    val miuixVersion = "0.9.1"
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuixVersion")
    implementation("androidx.core:core:1.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.4")
    val markwonVersion = "4.6.2"
    implementation("io.noties.markwon:core:$markwonVersion")
    implementation("io.noties.markwon:inline-parser:$markwonVersion")
    implementation("io.noties.markwon:ext-latex:$markwonVersion")
    implementation("io.noties.markwon:ext-tables:$markwonVersion")
    implementation("io.noties.markwon:ext-strikethrough:$markwonVersion")
    implementation("io.noties.markwon:ext-tasklist:$markwonVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
