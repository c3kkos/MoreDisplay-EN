package android.util

/** Desktop stand-in for android.util.Log, so the app's stream classes run in the cast bench. */
object Log {
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int { println("I $tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { println("W $tag: $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null): Int { println("E $tag: $msg ${t ?: ""}"); return 0 }
}
