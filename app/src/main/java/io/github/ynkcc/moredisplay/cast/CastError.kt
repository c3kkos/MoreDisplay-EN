package io.github.ynkcc.moredisplay.cast

/**
 * Cast error codes, shown in the app ("CAST-04: …") and in the log (tag `MoreDisplay_Cast`), and listed in the
 * README's troubleshooting table. The text after the code says what was found.
 */
enum class CastError(val code: String, val meaning: String) {
    NO_WIFI("CAST-01", "No Wi-Fi address"),
    ENCODER_START("CAST-02", "The video encoder couldn't start"),
    GPU("CAST-03", "The picture copier (OpenGL) failed"),
    DISPLAY_OUTPUT("CAST-04", "The display's picture couldn't be taken"),
    NO_PICTURE("CAST-05", "No video was produced"),
    DEVICE("CAST-06", "Couldn't connect to the Cast device"),
    PLAYBACK("CAST-07", "The Chromecast couldn't play the stream"),
    NOT_REACHED("CAST-08", "The Chromecast never reached the tablet"),
    DISPLAY_MISSING("CAST-09", "The display to cast doesn't exist"),
    UNAVAILABLE("CAST-10", "Cast isn't available on this device");

    fun format(detail: String): String = "$code $meaning: $detail"
}

class CastException(val error: CastError, detail: String, cause: Throwable? = null) :
    Exception(error.format(detail), cause)
