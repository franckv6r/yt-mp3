plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciKeystore = System.getenv("KEYSTORE_FILE")
val buildVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 9
val buildVersionName = (findProperty("versionName") as String?) ?: "0.4"

android {
    namespace = "com.fp.ytmp3"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fp.ytmp3"
        minSdk = 26
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = buildVersionName
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Sans secret CI, on garde une APK installable localement en debug.
            signingConfig = if (ciKeystore != null) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("de.sciss:jump3r:1.0.5")
    implementation("com.mpatric:mp3agic:0.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
