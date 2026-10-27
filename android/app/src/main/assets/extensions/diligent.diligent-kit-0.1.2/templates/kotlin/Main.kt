// {{label}}: a Kotlin program, compiled with kotlinc and run on Java.

data class Language(val name: String, val year: Int)

fun main() {
    println("Hello from {{label}} on Kotlin ${KotlinVersion.CURRENT}")
    val languages = listOf(Language("Java", 1995), Language("Kotlin", 2011))
    languages.sortedBy { it.year }.forEach { println("${it.name} came out in ${it.year}") }
}
