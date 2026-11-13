plugins {
    // AGP 9 內建 Kotlin 支援，不需要再套 org.jetbrains.kotlin.android
    id("com.android.application")
}

android {
    namespace = "com.joechiboo.autokaraoke"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.joechiboo.autokaraoke"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // mp3 已經是壓縮格式；不壓縮才能讓 ExoPlayer 直接 seek asset
        noCompress += listOf("mp3")
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("com.google.guava:guava:33.4.8-android")

    testImplementation("junit:junit:4.13.2")
}
