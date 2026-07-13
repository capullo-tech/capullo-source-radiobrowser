plugins {
    alias(libs.plugins.android.library)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin support (see capullo-audio).
    alias(libs.plugins.ksp)
    id("maven-publish")
}

android {
    namespace = "tech.capullo.source.radiobrowser"
    compileSdk = 36

    defaultConfig {
        // 23 to match capullo-audio / lib-snapcast-android and stay ≤ every consuming app.
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // New DSL for Kotlin 2.3 / AGP 9.x (mirrors capullo-audio, the known-good reference).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // Layer 1 SPI - `api` because the public surface implements/returns its types
    // (MediaSourceProvider, NowPlaying via NowPlayingSource, PlaybackQueue).
    api(pins.capullo.audio.contracts)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Radio Browser API + JSON models.
    implementation(pins.retrofit)
    implementation(pins.retrofit.converter.gson)
    implementation(pins.gson)
    // Playlist/HLS resolution (PlaylistResolver) + Shazam stream capture/API (AudioCapturer, ShazamApiClient).
    implementation(pins.okhttp)
    // Favorites/groups persistence.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // YouTube link enrichment for identified tracks (YoutubeSearcher).
    implementation(pins.newpipe.extractor)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "tech.capullo.source.radiobrowser"
            artifactId = "capullo-source-radiobrowser"
            version = "0.1.0-SNAPSHOT"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/capullo-tech/capullo-source-radiobrowser")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
