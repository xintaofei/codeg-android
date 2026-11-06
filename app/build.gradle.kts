import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import java.util.Properties
import java.security.MessageDigest
import groovy.json.JsonSlurper
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.license.report)
}

// Release signing pulls from a local, gitignored keystore.properties so no
// secrets are committed. On a fresh clone without that file, the release build
// falls back to unsigned (assembleRelease still runs; the APK just isn't signed).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val checkedInThirdPartyLicensesDir = rootProject.layout.projectDirectory.dir("third-party-licenses")
val generatedThirdPartyLicensesDir = layout.buildDirectory.dir("reports/dependency-license")
val supplementalLicensesDir = rootProject.layout.projectDirectory.dir("config/licenses")

val easyTierEnabled = providers.gradleProperty("easyTier").map(String::toBoolean).getOrElse(false)

android {
    namespace = "app.codeg.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.codeg.android"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("boolean", "EASYTIER_ENABLED", easyTierEnabled.toString())
        if (easyTierEnabled) {
            // Other AndroidX dependencies ship native libraries too. Restrict the whole APK
            // to the ABIs that also have EasyTier, so Android cannot select a partial ABI.
            ndk.abiFilters += rootProject.file("build/easytier/jniLibs")
                .listFiles().orEmpty().filter(File::isDirectory).map(File::getName)
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // App-UI localizations: English (default) + Simplified Chinese, mirroring the
    // iOS String Catalog (en + zh-Hans). localeFilters keeps only these in the APK.
    androidResources {
        localeFilters += listOf("en", "zh-rCN")
    }

    signingConfigs {
        // Created only when keystore.properties is present (local dev / release
        // machine). CI or fresh clones without it build unsigned.
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            // Scaffold milestone keeps shrinking off so the first builds are simple
            // to reason about; turn on R8 + the ProGuard rules once features land.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        getByName("main").assets.srcDir(checkedInThirdPartyLicensesDir)
        if (easyTierEnabled) {
            getByName("main").jniLibs.srcDir(rootProject.file("build/easytier/jniLibs"))
            getByName("main").assets.srcDir(rootProject.file("config/easytier"))
            getByName("main").assets.srcDir(rootProject.file("build/easytier/licenses"))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

// Compile to JVM 17 bytecode while running the build on the local JDK 21. No
// jvmToolchain() — that would try to provision a separate JDK 17.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

licenseReport {
    projects = arrayOf(project)
    configurations = arrayOf("releaseRuntimeClasspath")
    outputDir = generatedThirdPartyLicensesDir.get().asFile.absolutePath
    // Parent POMs may advertise licenses for a wider project family that do not
    // apply to the resolved artifact (see config/licenses/LICENSE_REVIEW.md).
    unionParentPomLicenses = false
    excludeBoms = true
    renderers = arrayOf<ReportRenderer>(
        InventoryMarkdownReportRenderer(
            "DEPENDENCIES.md",
            "Codeg for Android",
            null,
            false,
            true,
        ),
        JsonReportRenderer("dependencies.json", false),
    )
}

val cleanGeneratedThirdPartyLicenseReport by tasks.registering(Delete::class) {
    delete(generatedThirdPartyLicensesDir)
}

tasks.named("generateLicenseReport") {
    dependsOn(cleanGeneratedThirdPartyLicenseReport)
    inputs.files(
        rootProject.file("LICENSE"),
        rootProject.file("NOTICE"),
        rootProject.file("THIRD_PARTY_NOTICES.md"),
        supplementalLicensesDir,
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedThirdPartyLicensesDir)
    doLast {
        val reportDir = generatedThirdPartyLicensesDir.get()
        val copiedSources = listOf(
            rootProject.file("LICENSE") to "project/LICENSE",
            rootProject.file("NOTICE") to "project/NOTICE",
            rootProject.file("THIRD_PARTY_NOTICES.md") to "project/THIRD_PARTY_NOTICES.md",
            supplementalLicensesDir.file("EMBEDDED_COMPONENTS.md").asFile to
                "supplemental/EMBEDDED_COMPONENTS.md",
            supplementalLicensesDir.file("LICENSE_REVIEW.md").asFile to
                "supplemental/LICENSE_REVIEW.md",
            supplementalLicensesDir.file("MPL-2.0.txt").asFile to "supplemental/MPL-2.0.txt",
        )
        copiedSources.forEach { (source, relativeTarget) ->
            val target = reportDir.file(relativeTarget).asFile
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }

        fileTree(reportDir).matching {
            include("**/*.md", "**/*.json", "**/*.txt", "**/NOTICE")
        }.files.forEach { reportFile ->
            val normalized = reportFile.readText()
                .lineSequence()
                .joinToString("\n") { it.trimEnd() }
                .trimEnd() + "\n"
            reportFile.writeText(normalized)
        }

        val bundleFiles = fileTree(reportDir).matching {
            exclude("dependencies.json", "NOTICE_BUNDLE.txt")
        }.files.filter(File::isFile).sortedBy {
            it.relativeTo(reportDir.asFile).invariantSeparatorsPath
        }
        val bundle = buildString {
            appendLine("Codeg for Android — Open-source notices")
            appendLine()
            appendLine("This bundle is generated from the release runtime dependency graph.")
            bundleFiles.forEach { noticeFile ->
                val relativePath = noticeFile.relativeTo(reportDir.asFile)
                    .invariantSeparatorsPath
                appendLine()
                appendLine("================================================================================")
                appendLine(relativePath)
                appendLine("================================================================================")
                appendLine(noticeFile.readText().trimEnd())
            }
        }
        reportDir.file("NOTICE_BUNDLE.txt").asFile
            .writeText(bundle.trimEnd() + "\n")
    }
}

val syncThirdPartyLicenseReport by tasks.registering(Sync::class) {
    group = "documentation"
    description = "Regenerates and updates the checked-in third-party license bundle."
    dependsOn("generateLicenseReport")
    from(generatedThirdPartyLicensesDir)
    into(checkedInThirdPartyLicensesDir)
}

val verifyThirdPartyLicenseReport by tasks.registering {
    group = "verification"
    description = "Fails when the checked-in third-party license bundle is stale."
    dependsOn("generateLicenseReport")
    inputs.dir(generatedThirdPartyLicensesDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(checkedInThirdPartyLicensesDir).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        fun snapshot(directory: File): Map<String, ByteArray> = directory.walkTopDown()
            .filter(File::isFile)
            .associate { file -> file.relativeTo(directory).invariantSeparatorsPath to file.readBytes() }

        val generated = snapshot(generatedThirdPartyLicensesDir.get().asFile)
        val checkedIn = snapshot(checkedInThirdPartyLicensesDir.asFile)
        val mismatches = (generated.keys + checkedIn.keys).toSortedSet().filter { path ->
            val generatedBytes = generated[path]
            val checkedInBytes = checkedIn[path]
            generatedBytes == null || checkedInBytes == null ||
                !generatedBytes.contentEquals(checkedInBytes)
        }
        if (mismatches.isNotEmpty()) {
            throw GradleException(
                "Third-party license bundle is stale: ${mismatches.joinToString()}. " +
                    "Run ./gradlew :app:syncThirdPartyLicenseReport and commit the result.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyThirdPartyLicenseReport)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyThirdPartyLicenseReport)
}

dependencies {
    // --- Compose ---
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- AndroidX core / lifecycle / navigation ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // --- DI: Hilt ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // --- Coroutines / serialization ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // --- Networking: Ktor (OkHttp engine) ---
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.logging)

    // --- Persistence ---
    implementation(libs.androidx.datastore.preferences)

    // --- Unit tests (JVM, no device) ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // --- Instrumentation tests ---
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Native artifacts are built from the pinned public source by the explicit script.
// Never silently ship an enabled picker without its transport implementation.
if (easyTierEnabled) {
    tasks.named("preBuild") {
        doFirst {
            val libraries = rootProject.file("build/easytier/jniLibs")
                .walkTopDown().filter { it.name == "libeasytier_android_jni.so" }.toList()
            check(libraries.isNotEmpty()) {
                "EasyTier is enabled but its JNI library is missing. Run scripts/build-easytier.py first."
            }
            val metadata = JsonSlurper().parse(rootProject.file("build/easytier/build-info.json")) as Map<*, *>
            val records = metadata["libraries"] as List<*>
            val hashes = records.associate { item ->
                val record = item as Map<*, *>
                record["abi"] as String to record["sha256"] as String
            }
            check(libraries.map { it.parentFile.name }.toSet() == hashes.keys) {
                "EasyTier ABI outputs do not match this build's manifest. Rebuild the complete requested ABI set."
            }
            libraries.forEach { library ->
                val digest = MessageDigest.getInstance("SHA-256").digest(library.readBytes())
                    .joinToString("") { "%02x".format(it) }
                check(digest == hashes[library.parentFile.name]) { "EasyTier library differs from its build manifest." }
            }
            val notices = JsonSlurper().parse(rootProject.file("build/easytier/licenses/EASYTIER_DEPENDENCIES.json")) as Map<*, *>
            val targets = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android",
                "armeabi-v7a" to "armv7-linux-androideabi", "x86" to "i686-linux-android")
            check((notices["targets"] as List<*>).toSet() == hashes.keys.map { targets.getValue(it) }.toSet() &&
                (notices["packages"] as List<*>).isNotEmpty() && (notices["missing_notice_packages"] as List<*>).isEmpty() &&
                (notices["pointer_notice_files"] as List<*>).isEmpty()) {
                "EasyTier dependency notices are incomplete or belong to another ABI set."
            }
            check(rootProject.file("build/easytier/licenses/THIRD_PARTY_NOTICES.txt").isFile) {
                "EasyTier dependency notices are missing. Run scripts/easytier-license-report.py first."
            }
        }
    }
}
