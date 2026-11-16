package io.github.ynkcc.moredisplay.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {

    /**
     * system_server's hooks recognise the app by [AppIdentity.PACKAGE]; if it drifts from the Gradle applicationId,
     * every eye, cast and touch request is refused. Unit tests run in the app module directory.
     */
    @Test
    fun packageMatchesTheApplicationId() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
        val applicationId = Regex("""applicationId\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1]
        assertEquals(applicationId, AppIdentity.PACKAGE)
    }
}
