plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.ynkcc.moredisplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.ynkcc.moredisplay"
        minSdk = 29
        targetSdk = 37
        versionCode = 8
        versionName = "1.5-EN"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                // Enable R8: without it the dex reaches 23.4 MB (90% of the APK),
                // because Compose / Material3 / AndroidX / Kotlin stdlib are packaged in full.
                // Since AGP 9, minifyEnabled / shrinkResources are both controlled by this single switch.
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // An Xposed module works by reflecting on hidden framework APIs (DisplayManagerService,
        // VirtualDisplayConfig, etc.); lintVital treats BlockedPrivateApi as fatal in release builds,
        // so given the nature of this module it is exempted as a whole.
        disable += "BlockedPrivateApi"
    }

    buildFeatures {
        aidl = true
        compose = true
    }
}

// AGP 9.x embeds its own KGP on the classpath (2.2.10 here), and the compose plugin uses it to resolve
// org.jetbrains.kotlin:compose-group-mapping in composeMappingProducerClasspath,
// but that artifact is only published since 2.4.x; 2.2.10 does not exist => a full assemble (e.g. CodeQL autobuild) fails.
// Force it to the KGP version the project actually uses.
configurations.matching { it.name == "composeMappingProducerClasspath" }.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:compose-group-mapping:${libs.versions.kotlin.get()}")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    // Preview only; not included in the release package.
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Icons are no longer provided transitively by the BOM / material3 and must be added explicitly
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Pure Compose project: the View-based Material Components and androidx.appcompat are not included;
    // without R8 they contribute several MB of dex, and no View-system code in the project uses them.
    implementation(libs.androidx.core.ktx)
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    compileOnly(libs.androidx.annotation)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// Signing config lives in the subprojects block of the root build.gradle.kts.

// CI uses this to read the version: the Xposed module repository requires release tags of the form `<versionCode>-<versionName>`.
// The version values must be read into local variables at configuration time; referencing `android` in a task action breaks the configuration cache.
val printedVersionName = android.defaultConfig.versionName
val printedVersionCode = android.defaultConfig.versionCode
tasks.register("printVersion") {
    val name = printedVersionName
    val code = printedVersionCode
    doLast {
        println("VERSION_NAME=$name")
        println("VERSION_CODE=$code")
    }
}