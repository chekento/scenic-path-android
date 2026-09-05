import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun configValue(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val debugApiBaseUrl = configValue("SCENIC_DEBUG_API_BASE_URL")
    ?: configValue("SCENIC_API_BASE_URL")
    ?: "http://10.0.2.2:8787"
val releaseApiBaseUrl = configValue("SCENIC_API_BASE_URL")
val debugMapStyleUrl = configValue("MAP_STYLE_URL")
    ?: "https://tiles.openfreemap.org/styles/liberty"
val releaseMapStyleUrl = configValue("SCENIC_MAP_STYLE_URL")
    ?: configValue("MAP_STYLE_URL")
val privacyPolicyUrl = configValue("SCENIC_PRIVACY_POLICY_URL")

val uploadStoreFile = configValue("SCENIC_UPLOAD_STORE_FILE")
val uploadStorePassword = configValue("SCENIC_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = configValue("SCENIC_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = configValue("SCENIC_UPLOAD_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }

val productionServicesConfigured =
    releaseApiBaseUrl?.startsWith("https://") == true &&
        releaseMapStyleUrl?.startsWith("https://") == true &&
        privacyPolicyUrl?.startsWith("https://") == true

android {
    namespace = "cloud.kosch.scenicpath"
    compileSdk = 36

    defaultConfig {
        applicationId = "cloud.kosch.scenicpath"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "0.6.2-rc1"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            buildConfigField("String", "SCENIC_API_BASE_URL", quoted(debugApiBaseUrl))
            buildConfigField("String", "MAP_STYLE_URL", quoted(debugMapStyleUrl))
            buildConfigField("String", "PRIVACY_POLICY_URL", quoted(privacyPolicyUrl.orEmpty()))
            buildConfigField("boolean", "PRODUCTION_SERVICES_CONFIGURED", "false")
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField(
                "String",
                "SCENIC_API_BASE_URL",
                quoted(releaseApiBaseUrl ?: "https://invalid.invalid"),
            )
            buildConfigField(
                "String",
                "MAP_STYLE_URL",
                quoted(releaseMapStyleUrl ?: debugMapStyleUrl),
            )
            buildConfigField("String", "PRIVACY_POLICY_URL", quoted(privacyPolicyUrl.orEmpty()))
            buildConfigField(
                "boolean",
                "PRODUCTION_SERVICES_CONFIGURED",
                productionServicesConfigured.toString(),
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

tasks.register("verifyPlayReleaseConfig") {
    group = "verification"
    description = "Fails unless the production Play release configuration is complete."
    doLast {
        val api = releaseApiBaseUrl.orEmpty()
        check(api.startsWith("https://")) {
            "SCENIC_API_BASE_URL must be an HTTPS production backend URL."
        }
        check(
            !api.contains("10.0.2.2") &&
                !api.contains("127.0.0.1") &&
                !api.contains("localhost") &&
                !api.contains("invalid.invalid")
        ) {
            "SCENIC_API_BASE_URL must not point to a local/demo endpoint."
        }
        val mapStyle = releaseMapStyleUrl.orEmpty()
        check(mapStyle.startsWith("https://")) {
            "SCENIC_MAP_STYLE_URL (or MAP_STYLE_URL) must be an HTTPS production map style."
        }
        val privacy = privacyPolicyUrl.orEmpty()
        check(privacy.startsWith("https://")) {
            "SCENIC_PRIVACY_POLICY_URL must be a public HTTPS URL."
        }
        check(releaseSigningConfigured) {
            "Upload signing is incomplete. Configure SCENIC_UPLOAD_STORE_FILE, SCENIC_UPLOAD_STORE_PASSWORD, SCENIC_UPLOAD_KEY_ALIAS and SCENIC_UPLOAD_KEY_PASSWORD."
        }
        check(file(uploadStoreFile!!).isFile) {
            "SCENIC_UPLOAD_STORE_FILE does not exist: $uploadStoreFile"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.maplibre.gl:android-sdk:13.4.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
