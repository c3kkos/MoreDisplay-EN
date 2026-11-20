package castbench

/** Runs the @Test methods of the given test classes (cast bench; no Gradle or Android SDK needed). */
fun main(args: Array<String>) {
    var failed = 0
    for (name in args) {
        val cls = Class.forName(name)
        for (m in cls.methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }) {
            val result = runCatching { m.invoke(cls.getDeclaredConstructor().newInstance()) }
            val error = result.exceptionOrNull()?.let { it.cause ?: it }
            if (error == null) println("PASS ${cls.simpleName}.${m.name}")
            else { failed++; println("FAIL ${cls.simpleName}.${m.name}: $error"); error.stackTrace.take(6).forEach { println("    at $it") } }
        }
    }
    println(if (failed == 0) "all passed" else "$failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
