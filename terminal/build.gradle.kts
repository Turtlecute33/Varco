// The terminal engine of Termux (terminal-emulator + terminal-view, v0.118.3), changed for remote sessions.
// See NOTICE.md for the source, the licences and the list of changes.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        // Upstream code: warnings are not fixed here, to keep the diff to Termux small.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
