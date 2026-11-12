plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.ynkcc.moredisplay.probe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.ynkcc.moredisplay.probe"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                // Same as :app: with R8 enabled, probe-release shrinks from 2.04 MB to about 0.5 MB.
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
}

// Signing config lives in the subprojects block of the root build.gradle.kts: without signing the output would be named
// probe-release-unsigned.apk and CI could not find it by name.
