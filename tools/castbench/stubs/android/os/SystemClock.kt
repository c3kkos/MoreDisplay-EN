package android.os

/** Desktop stand-in for android.os.SystemClock (cast bench). */
object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
}
