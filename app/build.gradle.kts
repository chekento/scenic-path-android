import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "cloud.kosch.scenicpath"
    compileSdk = 36

    defaultConfig {
        applicationId = "cloud.kosch.scenicpath"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.4.8"
        manifestPlaceholders["usesCleartextTraffic"] = "false"

        buildConfigField(
            "String", "SCENIC_API_BASE_URL",
            "\"${localProps.getProperty("SCENIC_API_BASE_URL", "http://10.0.2.2:8787")}\""
        )
        buildConfigField(
            "String", "MAP_STYLE_URL",
            "\"${localProps.getProperty("MAP_STYLE_URL", "https://tiles.openfreemap.org/styles/liberty")}\""
        )
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        getByName("release") {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
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
