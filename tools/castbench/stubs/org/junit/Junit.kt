package org.junit

/** Minimal JUnit 4 stand-in so the app's pure unit tests run in the cast bench (see run-tests.sh). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

object Assert {
    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) throw AssertionError("expected true") }
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) { if (!condition) throw AssertionError(message) }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual && !(expected is Number && actual is Number && expected.toLong() == actual.toLong()))
            throw AssertionError("expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message: expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) throw AssertionError("arrays differ")
    }
    @JvmStatic fun assertNotNull(value: Any?) { if (value == null) throw AssertionError("expected not null") }
    @JvmStatic fun assertNull(value: Any?) { if (value != null) throw AssertionError("expected null but was <$value>") }
}
