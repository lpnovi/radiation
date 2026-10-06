plugins {
    id("com.android.application")
}

android {
    namespace = "com.lpnovi.radiation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lpnovi.radiation"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.palette:palette:1.0.0")
    // Shuffle state/control: only exposed through the AndroidX media-compat session protocol.
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
}

// Name deprecated API uses instead of only counting them.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
