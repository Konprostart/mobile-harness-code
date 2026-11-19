import java.util.Properties
import java.util.UUID
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val testSecrets = Properties().apply {
    val secretsFile = rootProject.file("test-secrets.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use(::load)
}
val playBuild = providers.gradleProperty("playBuild").orNull?.toBoolean() == true ||
    providers.gradleProperty("playFeasibility").orNull?.toBoolean() == true
val privacyPolicyUrl = providers.gradleProperty("privacyPolicyUrl").orNull
    ?: "https://github.com/Konprostart/mobile-harness-code/blob/main/PRIVACY.md"
val uploadStorePath = providers.environmentVariable("MH_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("MH_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("MH_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("MH_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStorePath,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }
val runtimeReleaseBaseUrl =
    "https://github.com/Konprostart/mobile-harness-code/releases/download/runtime-2026.09.4"
val appUpdateManifestUrl =
    "https://github.com/Konprostart/mobile-harness-code/releases/latest/download/mobile-harness-update.json"
val runtimeBundleDir = rootProject.layout.projectDirectory.dir("dist/runtime-bundles")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtime-assets")

val prepareBundledAgentAssets = tasks.register<Sync>("prepareBundledAgentAssets") {
    from(runtimeBundleDir.file("pocketdev-agy-arm64-2026.09.1.tar.zst"))
    into(generatedRuntimeAssets.map { it.dir("shared/runtime") })
}

val prepareOfflineRuntimeAssets = tasks.register<Sync>("prepareOfflineRuntimeAssets") {
    from(
        runtimeBundleDir.file("pocketdev-core-arm64-2026.09.5.tar.zst"),
        runtimeBundleDir.file("pocketdev-claude-arm64-2026.11.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-python-arm64-2026.09.2.tar.zst"),
        runtimeBundleDir.file("pocketdev-android-arm64-2026.09.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-dsh-arm64-2026.09.1.tar.zst"),
    )
    into(generatedRuntimeAssets.map { it.dir("offline/runtime") })
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.mobileharness.code"
    compileSdk = 36
    // NDK 28 emits flexible-page-size binaries for the static PRoot loader.
    // NDK 26 accepted the max-page-size flag but still produced 4 KiB LOAD
    // segments, which crash under 16 KiB Android runtimes. Builders may still
    // override this explicitly with -PmhNdkVersion=<version>.
    ndkVersion = providers.gradleProperty("mhNdkVersion").orNull ?: "28.2.13676358"

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(checkNotNull(uploadStorePath))
                storePassword = checkNotNull(uploadStorePassword)
                keyAlias = checkNotNull(uploadKeyAlias)
                keyPassword = checkNotNull(uploadKeyPassword)
                // AGP drops the legacy JAR signature for minSdk >= 24, but some
                // OEM installers (vivo) reject APKs without it as "missing the
                // developer certificate". Keep all three: V1 for legacy OEMs,
                // V2 for the 7.0+ scheme, and V3 for Android 16 key-rotation /
                // current-installer compatibility. The key is unchanged.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.mobileharness.code"
        minSdk = 28
        // The direct APK retains the proven target-28 PRoot execution path. The
        // Play build targets current Android while its runtime path is validated.
        targetSdk = if (playBuild) 36 else 28
        // Keep literal defaults so F-Droid's static manifest parser can detect
        // the tagged release. Gradle properties may still override Play builds.
        versionCode = 15
        versionName = "1.3.1"
        providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        providers.gradleProperty("appVersionName").orNull?.let { versionName = it }

        ndk.abiFilters += "arm64-v8a"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("boolean", "IS_PLAY_BUILD", playBuild.toString())
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(privacyPolicyUrl))

        buildConfigField(
            "String",
            "TEST_OPENROUTER_API_KEY",
            "\"\"",
        )
    }

    flavorDimensions += "runtimeDelivery"
    productFlavors {
        create("online") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "false")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"online\"")
        }
        create("offline") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "true")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"offline\"")
        }
    }

    sourceSets.getByName("offline").assets.srcDir(generatedRuntimeAssets.map { it.dir("offline") })
    sourceSets.getByName("main").assets.srcDir(generatedRuntimeAssets.map { it.dir("shared") })

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "TEST_OPENROUTER_API_KEY",
                buildConfigString(testSecrets.getProperty("openrouter.apiKey", "")),
            )
        }
        release {
            isMinifyEnabled = false
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("upload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.jniLibs.useLegacyPackaging = true
    androidResources.noCompress += "zst"
}

tasks.matching { it.name.startsWith("mergeOffline") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("Offline") && it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.register("playReadinessCheck") {
    group = "verification"
    description = "Checks configuration required before uploading a Mobile Harness Code Play bundle."
    doLast {
        check(playBuild) { "Run with -PplayBuild=true." }
        check(privacyPolicyUrl.startsWith("https://")) {
            "privacyPolicyUrl must be a public HTTPS URL."
        }
        check(hasUploadSigning) {
            "Set MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS, and MH_UPLOAD_KEY_PASSWORD."
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// Reproducible build + SBOM + license transparency (#108)
// ---------------------------------------------------------------------------

// Deterministic archive contents: strip file timestamps and use a sorted entry
// order so two clean builds of the same source produce byte-identical artifacts.
// This makes third-party AV "detections" auditable and enables reproducible builds.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// SPDX license for each direct dependency (keyed as `group:name`). Transitive
// components are still listed in the SBOM (with their purl) for downstream audit.
val directDependencyLicenses = mapOf(
    "androidx.compose:compose-bom" to "Apache-2.0",
    "androidx.core:core-ktx" to "Apache-2.0",
    "androidx.activity:activity-compose" to "Apache-2.0",
    "androidx.compose.ui:ui" to "Apache-2.0",
    "androidx.compose.ui:ui-tooling-preview" to "Apache-2.0",
    "androidx.compose.material3:material3" to "Apache-2.0",
    "androidx.compose.material:material-icons-extended" to "Apache-2.0",
    "androidx.lifecycle:lifecycle-runtime-ktx" to "Apache-2.0",
    "androidx.lifecycle:lifecycle-runtime-compose" to "Apache-2.0",
    "androidx.lifecycle:lifecycle-viewmodel-compose" to "Apache-2.0",
    "androidx.lifecycle:lifecycle-viewmodel-ktx" to "Apache-2.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android" to "Apache-2.0",
    "org.jetbrains.kotlin:kotlin-stdlib" to "Apache-2.0",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk8" to "Apache-2.0",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7" to "Apache-2.0",
    "org.jetbrains:annotations" to "Apache-2.0",
    "org.apache.commons:commons-compress" to "Apache-2.0",
    "com.github.luben:zstd-jni" to "BSD-2-Clause",
)

val allowedSpdxLicenses = setOf(
    "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC",
    "LGPL-2.1", "LGPL-3.0", "MPL-2.0", "EPL-2.0", "CC0-1.0",
)

val sbomOutputDir = layout.buildDirectory.dir("reports/sbom")

val generateSbom = tasks.register("generateSbom") {
    group = "verification"
    description = "Writes a dependency-free CycloneDX SBOM of the online release runtime classpath."
    val outputFile = sbomOutputDir.map { it.file("cyclonedx.json") }
    outputs.file(outputFile)
    doLast {
        fun esc(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        fun purl(group: String, name: String, version: String) = "pkg:maven/$group/$name@$version"
        val resolved = configurations.getByName("onlineReleaseRuntimeClasspath").resolvedConfiguration
        val seen = sortedSetOf<String>()
        val componentJsons = mutableListOf<String>()
        fun addComponent(group: String, name: String, version: String, license: String?) {
            if (!seen.add("$group:$name:$version")) return
            val licenseJson = if (license.isNullOrBlank()) ""
            else ""","licenses":[{"license":{"id":"${esc(license)}"}}]"""
            componentJsons +=
                """{"type":"library","name":"${esc(group)}:${esc(name)}","version":"${esc(version)}","purl":"${esc(purl(group, name, version))}"$licenseJson}"""
        }
        // Direct dependencies carry their curated SPDX license; transitive ones are listed by purl.
        resolved.firstLevelModuleDependencies.forEach {
            addComponent(it.moduleGroup, it.moduleName, it.moduleVersion, directDependencyLicenses["${it.moduleGroup}:${it.moduleName}"])
        }
        resolved.lenientConfiguration.allModuleDependencies.forEach {
            addComponent(it.moduleGroup, it.moduleName, it.moduleVersion, null)
        }
        val json = buildString {
            appendLine("{")
            appendLine("  \"bomFormat\": \"CycloneDX\",")
            appendLine("  \"specVersion\": \"1.4\",")
            appendLine("  \"serialNumber\": \"urn:uuid:${UUID.randomUUID()}\",")
            appendLine("  \"version\": 1,")
            appendLine("  \"metadata\": { \"component\": { \"type\": \"application\", \"name\": \"Mobile Harness Code\", \"version\": \"1.1.0\" } },")
            appendLine("  \"components\": [")
            componentJsons.forEachIndexed { index, component ->
                append("    $component")
                if (index != componentJsons.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ]")
            append("}")
        }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(json)
        println("SBOM written to $file")
    }
}

val licenseCheck = tasks.register("licenseCheck") {
    group = "verification"
    description = "Fails the build if a direct dependency has a denied or unverified license."
    dependsOn(generateSbom)
    doLast {
        val resolved = configurations.getByName("onlineReleaseRuntimeClasspath").resolvedConfiguration
        val problems = mutableListOf<String>()
        resolved.firstLevelModuleDependencies.forEach { dep ->
            val coord = "${dep.moduleGroup}:${dep.moduleName}"
            val license = directDependencyLicenses[coord]
            when {
                license == null -> problems += "Unverified license for direct dependency $coord:${dep.moduleVersion}"
                license.startsWith("GPL") || license.startsWith("AGPL") ->
                    problems += "Copyleft license $license for $coord is not compatible with this distribution"
                license !in allowedSpdxLicenses -> problems += "License $license for $coord is not on the allow-list"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("License check failed:\n" + problems.joinToString("\n") { " - $it" })
        }
        println("License check passed for ${resolved.firstLevelModuleDependencies.size} direct dependencies.")
    }
}
