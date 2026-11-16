package io.github.ynkcc.moredisplay.core

/**
 * The installed app's package name (Gradle `applicationId`), as system_server's hooks need it to recognise the
 * module and its own app.
 *
 * Since 2.0.0 the English edition installs as its own app, `io.github.c3kkos.moredisplay`, so it can sit next to
 * ynkcc's original (`io.github.ynkcc.moredisplay`, a different signing key). The Kotlin package stays
 * `io.github.ynkcc.moredisplay` in honour of the original and to keep the fork close to upstream.
 *
 * Must match `applicationId` in app/build.gradle.kts (checked by AppIdentityTest).
 */
object AppIdentity {
    const val PACKAGE = "io.github.c3kkos.moredisplay"
}
