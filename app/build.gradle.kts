import java.util.Properties

plugins {
    id("com.android.application")
}

// Settings that differ per machine live in local.properties, which is not committed.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// A direct TMDB key. Only used when no catalogue server is configured (see README).
val tmdbApiKey: String = localProps.getProperty("tmdb.apiKey", "").trim()

val tmdbRegion: String = localProps.getProperty("tmdb.region", "IN").trim().uppercase()
    .takeIf { it.length == 2 } ?: "IN"

// -PcatalogueBaseUrl=... overrides the file for a single build.
val catalogueBaseUrl: String = (project.findProperty("catalogueBaseUrl") as String?
    ?: localProps.getProperty("catalogue.baseUrl", "")).trim().trimEnd('/')

android {
    namespace = "com.puredeck.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.puredeck.launcher"
        minSdk = 26 // Android 8.0, the oldest TV set I support
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // With a catalogue server set, the TMDB key stays on the server and out of the APK.
        buildConfigField("String", "TMDB_API_KEY", "\"${if (catalogueBaseUrl.isBlank()) tmdbApiKey else ""}\"")
        buildConfigField("String", "TMDB_REGION", "\"$tmdbRegion\"")
        buildConfigField("String", "CATALOGUE_BASE_URL", "\"$catalogueBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No private keystore for this hobby project, so releases use the debug key.
            // Fine for sideloading; do not publish to the Play Store like this.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
