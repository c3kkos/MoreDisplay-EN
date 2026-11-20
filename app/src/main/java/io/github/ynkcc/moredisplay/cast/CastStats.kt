package io.github.ynkcc.moredisplay.cast

/** The tablet side of a running cast, per second over the last few seconds (shown on the cast card). */
data class TabletStats(
    /** Frames the display drew, frames sent to the encoder, frames encoded, per second. */
    val displayFps: Float,
    val drawnFps: Float,
    val encodedFps: Float,
    val segmentsMade: Long,
    val segmentsSent: Int,
    val requests: Int,
    /** Speed of the last segment sent to the TV, Mbit/s. */
    val sendMbps: Float,
    val hasSound: Boolean,
    /** Silence added / sound dropped to keep the sound track gapless, ms in total. */
    val silenceMs: Long,
    val droppedMs: Long,
    /** Net sound added (+) or removed (-) to keep in step with the picture clock, ms in total. */
    val clockMs: Long = 0
)

/** What the TV's player reports (shown on the cast card). */
data class ReceiverStats(
    val player: String,
    /** How far behind the newest point it can play the TV is, in seconds, if it reports it. */
    val behindS: Float?,
    /** Pauses for more data since it started playing, and seeks back asked by StallGuard. */
    val stalls: Int,
    val seeksBack: Int
)
