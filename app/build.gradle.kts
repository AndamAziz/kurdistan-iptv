plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kurdistan.iptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kurdistan.iptv"
        minSdk = 24
        targetSdk = 36
        /* every build from GitHub gets a higher number, so a new APK always
           installs over the old one; a build made by hand stays at 1 */
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
    }

    /* Two builds from the same code, differing only in how they update:
         direct - the APK for GitHub / Uptodown. It downloads its own update
                  and hands it to Android's installer (app/src/direct/ adds
                  the permission for that).
         play   - the bundle for Google Play. Play forbids an app installing
                  itself, so this one asks Google Play for its update instead.
       Same applicationId, same key, same version number. */
    flavorDimensions += "store"
    productFlavors {
        create("direct") { dimension = "store" }
        create("play") { dimension = "store" }
    }

    /* The same key signs every release build, so an APK installs over the one
       before it and keeps the playlists, favourites and history. The key never
       lives in this repository: the build gets it from the GitHub secrets. */
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "kurdistan"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    /* a warning must never stop the build */
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    val media3 = "1.7.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    /* streams are fetched with OkHttp: it lets the player change how it asks
       (agent, certificates, DNS) when a server refuses the first way */
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    /* the system's own media controls (lock screen, headset buttons) while
       the sound keeps playing in the background */
    implementation("androidx.media3:media3-session:$media3")

    // FFmpeg audio decoders (AC3, EAC3, DTS, TrueHD ...); version must start with the Media3 version
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")

    // Google Play's own in-app update screen (used only by the Play build;
    // the GitHub build carries it too but never calls it)
    implementation("com.google.android.play:app-update:2.1.0")
}
