import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Push needs the Firebase app's IDs. They come from google-services.json (Firebase console,
// Project settings, Your apps, Android app com.nickcorona.cue), read here instead of through
// the google-services plugin so the app still builds without it: it just has no push then.
// The file isn't secret (it ships inside every APK), so it can be committed next to this one.
val firebase: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    val j = JsonSlurper().parse(f) as Map<String, Any?>
    val info = j["project_info"] as Map<String, Any?>
    val client = (j["client"] as List<Map<String, Any?>>).first {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "com.nickcorona.cue"
    }
    mapOf(
        "appId" to ((client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String),
        "apiKey" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String),
        "projectId" to (info["project_id"] as String),
        "senderId" to (info["project_number"] as String),
    )
} ?: emptyMap()

android {
    namespace = "com.nickcorona.cue"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nickcorona.cue"
        minSdk = 29
        targetSdk = 35
        // CI run number, so every published build is a strictly newer version (Obtainium and Android both need that)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
        buildConfigField("String", "BASE_URL", "\"https://nick-cue.vercel.app\"")
        for (k in listOf("appId", "apiKey", "projectId", "senderId")) {
            buildConfigField("String", "FCM_${k.uppercase()}", "\"${firebase[k] ?: ""}\"")
        }
    }
    buildFeatures { buildConfig = true }

    // Every build has to be signed with the same key, or an update can't install over the old app.
    // The key stays out of git: put it at app/cue.jks and set CUE_KEY_PASSWORD, or in CI store it
    // as the CUE_KEYSTORE_B64 and CUE_KEY_PASSWORD secrets.
    val keystore = file("cue.jks")
    val keyPassword = System.getenv("CUE_KEY_PASSWORD")
    val personal = if (keystore.exists() && keyPassword != null) signingConfigs.create("personal") {
        storeFile = keystore
        storePassword = keyPassword
        keyAlias = System.getenv("CUE_KEY_ALIAS") ?: "cue"
        this.keyPassword = keyPassword
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = personal ?: signingConfigs.getByName("debug")
        }
        debug {
            if (personal != null) signingConfig = personal
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Optional mirror for Robolectric's runtime jar download: -ProbolectricRepo=<maven url>
        unitTests.all { t -> findProperty("robolectricRepo")?.let { t.systemProperty("robolectric.dependency.repo.url", it) } }
    }
}

dependencies {
    implementation("com.google.firebase:firebase-messaging:24.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
