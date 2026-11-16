package io.github.ynkcc.moredisplay.cast

/**
 * Keeps the TV's player far enough behind the newest segment.
 *
 * A live HLS player that plays at the newest edge of the stream can only download a segment once it's finished,
 * so it pauses at every segment (every 2 s with 2-second segments). A pause hardly helps by itself: the next
 * segment arrives a moment later and the player is at the edge again. The playlist asks players to stay
 * [HlsSegmenter]'s hold-back behind, but how Google's player on the TV picks its position isn't documented, so
 * this guard watches what the player does: after [stallsToAct] pauses within [windowMs] it asks for a seek
 * [backMs] behind the current position (at most once per [cooldownMs], at most [maxSeeks] times). Each seek
 * leaves a reserve of [backMs] that the player keeps from then on, as both it and the stream advance in real time.
 *
 * Pure Kotlin (unit-tested); not thread-safe (used on the main thread).
 */
class StallGuard(
    private val backMs: Long,
    private val stallsToAct: Int = 2,
    private val windowMs: Long = 20_000,
    private val cooldownMs: Long = 20_000,
    private val maxSeeks: Int = 6
) {
    enum class Player { PLAYING, BUFFERING, OTHER }

    private var started = false
    private var last = Player.OTHER
    private val stallTimes = ArrayDeque<Long>()
    private var lastSeekMs = Long.MIN_VALUE / 2

    /** Pauses for more data since the player first played. */
    var stalls = 0
        private set

    /** Seeks back asked for. */
    var seeks = 0
        private set

    /** Seeks asked for past the start of the reported seekable range (that range left too little room). */
    var unclampedSeeks = 0
        private set

    /**
     * The player's state changed (or was re-reported) at [nowMs], at [positionMs]. Returns the position to seek to,
     * or null. [rangeStartMs], if known, is the oldest position the player can seek to.
     */
    fun onState(state: Player, nowMs: Long, positionMs: Long, rangeStartMs: Long? = null): Long? {
        val previous = last
        last = state
        if (state == Player.PLAYING) {
            started = true
            return null
        }
        if (state != Player.BUFFERING || previous == Player.BUFFERING || !started) return null
        stalls++
        stallTimes.addLast(nowMs)
        while (stallTimes.isNotEmpty() && nowMs - stallTimes.first() > windowMs) stallTimes.removeFirst()
        if (stallTimes.size < stallsToAct || seeks >= maxSeeks || nowMs - lastSeekMs < cooldownMs) return null
        var target = positionMs - backMs
        // Stay inside the seekable range the player reports, unless that leaves less than half the step: on the
        // SHIELD the reported range started ~1.2 s behind the position, so the clamped seek gained only 0.7 s and
        // the pauses went on. Then ask for the full step anyway; a player that can't go there refuses or clamps
        // the seek itself (CastController logs a refusal).
        if (rangeStartMs != null) {
            val clamped = maxOf(target, rangeStartMs + 500)
            if (positionMs - clamped >= backMs / 2) target = clamped else unclampedSeeks++
        }
        if (target >= positionMs) return null // nowhere to go back to
        seeks++
        lastSeekMs = nowMs
        stallTimes.clear()
        return target.coerceAtLeast(0)
    }
}
