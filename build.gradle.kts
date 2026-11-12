import com.android.build.api.dsl.ApplicationExtension
import java.util.Base64

// ---- Build classpath vulnerability fixes (Dependabot alerts #1-#9) ----
// These packages are pulled in transitively by AGP and live only on the buildscript classpath, never in the APK.
// Force them to non-vulnerable versions (kotlin-gradle-plugin is AGP's embedded toolchain; not force-upgraded, for compatibility).
// Note: buildscript{} must come before plugins{}.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force(
                // bcprov < 1.85 has a critical CVE
                "org.bouncycastle:bcprov-jdk18on:1.85",
                "org.bouncycastle:bcpkix-jdk18on:1.85",
                "org.bouncycastle:bcutil-jdk18on:1.85",
                "org.bitbucket.b_c:jose4j:0.9.6",
                "org.jdom:jdom2:2.0.6.1",
                "org.apache.commons:commons-lang3:3.18.0",
            )
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// ---- Shared release signing configuration (keys are never committed) ----
// Source priority: environment variables > gradle.properties > none.
//   RELEASE_KEYSTORE_FILE   : path to an existing .jks / .keystore (typical for local builds)
//   RELEASE_KEYSTORE_BASE64 : base64 of the keystore file (for CI, together with GitHub Secrets)
//   RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
// If any of the four values is missing => no signing config; the release build falls back to unsigned (it does not fail).
//
// Both application modules (:app, the module itself, and :probe, the acceptance probe) must be signed,
// otherwise the unsigned output is named *-release-unsigned.apk and CI cannot find it by name.
subprojects {
    plugins.withId("com.android.application") {
        fun envOrProp(name: String): String? =
            project.providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
                ?: project.providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

        val keystoreFile: File? = run {
            val direct = envOrProp("RELEASE_KEYSTORE_FILE")
            if (direct != null) return@run File(direct)
            val b64 = envOrProp("RELEASE_KEYSTORE_BASE64") ?: return@run null
            val out = File(project.layout.buildDirectory.get().asFile, "keystore/release.jks")
            out.parentFile.mkdirs()
            out.writeBytes(Base64.getDecoder().decode(b64.trim()))
            out
        }

        extensions.configure<ApplicationExtension> {
            if (keystoreFile != null) {
                signingConfigs {
                    create("release") {
                        storeFile = keystoreFile
                        storePassword = envOrProp("RELEASE_STORE_PASSWORD")
                        keyAlias = envOrProp("RELEASE_KEY_ALIAS")
                        keyPassword = envOrProp("RELEASE_KEY_PASSWORD")
                    }
                }
                buildTypes.getByName("release") {
                    signingConfig = signingConfigs.getByName("release")
                }
                // debug reuses the release signature: debug and release packages can be installed over each other,
                // and signature checks stay consistent for Xposed / Shizuku; debuggable is unaffected.
                buildTypes.getByName("debug") {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
        }
    }
}
