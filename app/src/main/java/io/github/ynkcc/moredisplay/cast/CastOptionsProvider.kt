package io.github.ynkcc.moredisplay.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Cast SDK configuration: Google's Default Media Receiver, so no receiver app has to be registered. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            // Never resume a saved session after our process died (app update, force-stop, low memory): its
            // stream server died with the process, and the half-resumed session left the device "selected",
            // so the next cast's selectRoute did nothing and timed out with CAST-06.
            .setResumeSavedSession(false)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
