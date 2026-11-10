import com.android.build.api.dsl.Packaging
import com.android.build.api.variant.BuildConfigField
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 视频礼物双版本构建的更新候选版本码：本流程专用属性，默认 166，必须 > 165。
// 只在 videoUpdate variant 上生效；已存在的 debug/release/performance/baseline 版本保持不变。
// 这个基线值是“当前已交付正式版的 versionCode”，正式版升到 166 以上后这里要跟着改。
val videoUpdateVersionCodeProperty = "orangechatVideoUpdateVersionCode"
val videoUpdateBaselineVersionCode = 165
val videoUpdateVersionCode: Int = run {
    val raw = providers.gradleProperty(videoUpdateVersionCodeProperty).orNull?.trim()
    if (raw.isNullOrEmpty()) {
        videoUpdateBaselineVersionCode + 1
    } else {
        if (!raw.matches(Regex("[0-9]{1,10}"))) {
            throw GradleException(
                "-P$videoUpdateVersionCodeProperty must be a plain integer, got '$raw'."
            )
        }
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed <= videoUpdateBaselineVersionCode || parsed > 2_100_000_000) {
            throw GradleException(
                "-P$videoUpdateVersionCodeProperty must be in " +
                    "${videoUpdateBaselineVersionCode + 1}..2100000000, got '$raw'. " +
                    "The update candidate must install over versionCode " +
                    "$videoUpdateBaselineVersionCode."
            )
        }
        parsed
    }
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37
    val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    val requestedAbis = providers.gradleProperty("orangechatAbis")
        .orNull?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
        ?.ifEmpty { listOf("arm64-v8a", "x86_64") }
        ?: listOf("arm64-v8a", "x86_64")
    val buildUniversalApk = providers.gradleProperty("orangechatUniversalApk")
        .orNull?.toBooleanStrictOrNull() ?: true

    defaultConfig {
        applicationId = "me.rerere.orangechat"
        minSdk = 26
        targetSdk = 37
        versionCode = 165
        versionName = "2.2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Split-only APKs select ABIs below; AGP rejects a second filter here.
            if (isBuildingBundle || buildUniversalApk) abiFilters += requestedAbis
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            isEnable = !isBuildingBundle
            reset()
            isUniversalApk = buildUniversalApk
            include(*requestedAbis.toTypedArray())
        }
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }
    val hasReleaseSigning = localProperties.getProperty("storeFile") != null &&
        localProperties.getProperty("storePassword") != null &&
        localProperties.getProperty("keyAlias") != null &&
        localProperties.getProperty("keyPassword") != null

    // 构建溯源信息
    val gitCommit = try {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) { "unknown" }
    val buildTime = try {
        providers.exec {
            commandLine("git", "log", "-1", "--format=%cd", "--date=iso")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) { "unknown" }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(localProperties.getProperty("storeFile"))
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        }
        // 项目内置共享 debug keystore，保证所有机器/开发者构建的 debug 包签名一致，
        // 避免 ~/.android/debug.keystore 因机器不同导致 adb install -r 覆盖安装失败（Failure [-99]）。
        // keystore 参数与 Android 默认 debug 签名一致：alias=androiddebugkey, password=android。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Release 签名配置在 tasks 执行阶段强制校验，避免 Gradle 配置阶段就阻断 debug 构建
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        }
        debug {
            applicationIdSuffix = ".liquidglass"
            // 统一使用项目内置共享 debug keystore；若配置了 release keystore 则改用 release 签名，
            // 保证不同机器签名一致，避免覆盖安装失败（Failure [-99]）。
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        }
        // Local performance comparison: release optimizations with the existing
        // test app's identity, so the same conversations/settings can be measured.
        // This is not the signed production release variant.
        create("performance") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            applicationIdSuffix = ".liquidglass"
            // Keep this installed channel's original identity even when CI restores the production key.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
        }
        create("baseline") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        }
        // 视频礼物共存版：与已安装的正式版/调试版并存。
        // 使用仓库内共享 debug 密钥签名（密码是公开的 android），
        // 明确不是发布安全签名，绝不使用 local.properties 里的 release 密钥，
        // 因此没有 secrets 的 fork 也能构建。包名、provider authorities、taskAffinity
        // 都由 applicationId 占位符推导，和 me.rerere.orangechat 及其 .liquidglass 变体互不冲突。
        create("videoCoexist") {
            // 与 videoUpdate 使用相同的 R8/资源压缩和库 variant，缩小共存 APK。
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            // 优化不改变已交付共存版的包名/证书，仍可覆盖安装并保留数据。
            applicationIdSuffix = ".videogifts"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
        // 视频礼物更新候选：包名保持正式包 me.rerere.orangechat，复制 release 的
        // 优化/ProGuard 设置，但明确 unsigned：没有原证书签名之前不能安装，也不能
        // 用共享 debug 密钥或任何新生成的密钥伪装成覆盖更新。版本码在
        // androidComponents.onVariants 里用 videoUpdateVersionCode 设置（默认 166）。
        create("videoUpdate") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }

    // Release 构建强制要求配置独立的 release keystore，禁止回退到 debug keystore。
    // 这是为了防止 release APK 使用公开已知的 debug 签名（密码 android）被伪造/篡改。
    tasks.configureEach {
        val taskName = name
        if (taskName.contains("Release", ignoreCase = true) &&
            (taskName.startsWith("assemble") || taskName.startsWith("bundle") || taskName.startsWith("package"))) {
            doFirst {
                if (!hasReleaseSigning) {
                    throw GradleException(
                        "Release build requires a release keystore. " +
                        "Please configure storeFile, storePassword, keyAlias and keyPassword in local.properties."
                    )
                }
            }
        }
    }
}

// 更新候选的版本码/包 metadata 一致性：BuildType DSL 不提供 versionCode，
// 因此只在这个新 variant 的两个 output（ABI split 和 universal）上覆盖，
// 并同步 BuildConfig.VERSION_CODE，保证包 metadata 与 BuildConfig 一致。
// 其他 variant 的版本不受影响。
androidComponents {
    onVariants { variant ->
        if (variant.buildType != "videoUpdate") return@onVariants
        variant.outputs.forEach { output ->
            output.versionCode.set(videoUpdateVersionCode)
        }
        val buildConfigFields = variant.buildConfigFields
            ?: throw GradleException(
                "videoUpdate requires buildConfig to be enabled so VERSION_CODE matches the APK metadata."
            )
        buildConfigFields.put(
            "VERSION_CODE",
            BuildConfigField("String", "\"$videoUpdateVersionCode\"", "Video update candidate version code")
        )
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // BiometricPrompt (fingerprint/face verify_fingerprint tool)
    implementation(libs.androidx.biometric)
    implementation(libs.termux.terminal.view) {
        exclude(group = "com.termux.termux-app", module = "terminal-emulator")
    }
    implementation(project(":terminal"))
    implementation(libs.guava.listenablefuture)

    // Workflow feature: cron parsing + geofence triggers
    implementation("com.cronutils:cron-utils:9.2.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // SSH feature: JSch (mwiede fork) for remote shell + SFTP
    implementation("com.github.mwiede:jsch:0.2.21")

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ktor server (NapCat reverse WS)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.core)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.text.recognition)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // Shizuku (read other app's data)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":common"))
    implementation(project(":workspace"))
    implementation(project(":material3"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
