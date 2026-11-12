package dev.dialup.app

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android's regex engine (ICU) rejects a closing brace that no opening brace of the pattern matches ("Unmatched closing '}'"),
 * where the JVM's engine takes it as a literal. A pattern like that passes every JVM test and then stops the bridge from
 * starting on the phone (c6d4dc4e). This reads the main sources of every module and fails on such a literal.
 */
class RegexLiteralsAndroidTest {
    /** A `Regex("...")` or `Regex("""...""")` literal found in a source file: the pattern as the regex engine sees it, and where. */
    private data class Literal(val file: String, val line: Int, val pattern: String)

    private fun repoRoot(): File {
        var dir: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "settings.gradle.kts not found above the working directory" }
    }

    /** The regex literals in [source]: only a string literal directly after `Regex(` is read, escapes of a plain string decoded. */
    private fun literalsIn(source: String, file: String): List<Literal> {
        val out = ArrayList<Literal>()
        var from = 0
        while (true) {
            val at = source.indexOf("Regex(", from)
            if (at < 0) break
            from = at + 6
            var i = from
            while (i < source.length && source[i].isWhitespace()) i++
            val line = source.substring(0, at).count { it == '\n' } + 1
            if (source.startsWith("\"\"\"", i)) {
                val end = source.indexOf("\"\"\"", i + 3)
                if (end > 0) out.add(Literal(file, line, withoutTemplates(source.substring(i + 3, end), raw = true)))
            } else if (i < source.length && source[i] == '"') {
                var j = i + 1
                while (j < source.length && source[j] != '"' && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
                if (j < source.length && source[j] == '"') out.add(Literal(file, line, withoutTemplates(source.substring(i + 1, j), raw = false)))
            }
        }
        return out
    }

    /** Decodes a plain Kotlin string's escapes and replaces every `${...}` or `$name` template by a letter, so only literal text is judged. */
    private fun withoutTemplates(body: String, raw: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                !raw && c == '\\' && i + 1 < body.length -> {
                    sb.append(
                        when (val n = body[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> n // \\ \" \$ \' and the like stand for the character itself
                        },
                    )
                    i += 2
                }
                c == '$' && i + 1 < body.length && body[i + 1] == '{' -> {
                    var depth = 0
                    var j = i + 1
                    while (j < body.length) {
                        if (body[j] == '{') depth++
                        if (body[j] == '}' && --depth == 0) break
                        j++
                    }
                    sb.append('x')
                    i = j + 1
                }
                c == '$' && i + 1 < body.length && (body[i + 1].isLetter() || body[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < body.length && (body[j].isLetterOrDigit() || body[j] == '_')) j++
                    sb.append('x')
                    i = j
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** The index of the first closing brace in [pattern] that is not escaped, not inside a character class and has no unescaped opening brace before it. */
    private fun unmatchedClosingBrace(pattern: String): Int? {
        var open = 0
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++
                '[' -> inClass = true
                ']' -> inClass = false
                '{' -> if (!inClass) open++
                '}' -> if (!inClass) {
                    if (open == 0) return i
                    open--
                }
            }
            i++
        }
        return null
    }

    @Test
    fun `the scanner flags the placeholder pattern that stopped the bridge on the phone and accepts the fixed one`() {
        val broken = literalsIn("""private val PLACEHOLDER = Regex(${"\"\"\""}\{(name|manufacturer|model)}${"\"\"\""})""", "Broken.kt").single()
        assertEquals("""\{(name|manufacturer|model)}""", broken.pattern)
        assertEquals(broken.pattern.lastIndex, unmatchedClosingBrace(broken.pattern))

        val fixed = literalsIn("""private val PLACEHOLDER = Regex(${"\"\"\""}\{(name|manufacturer|model)\}${"\"\"\""})""", "Fixed.kt").single()
        assertEquals(null, unmatchedClosingBrace(fixed.pattern))

        for (ok in listOf("""a{2,3}""", """\d{4}-\d{2}""", """[}]""", """\{x\}""", """(?<n>a{1}){2}""")) {
            assertEquals(null, unmatchedClosingBrace(ok), "'$ok' is fine on Android")
        }
        for (bad in listOf("}", "a}", """\{a}""", "a{1}}")) {
            assertTrue(unmatchedClosingBrace(bad) != null, "'$bad' is rejected by Android")
        }
    }

    @Test
    fun `a plain string literal is read with its escapes and a template is not mistaken for a brace`() {
        val lits = literalsIn("val a = Regex(\"^\\\\{id\\\\}\\\\d{2}\$\")\nval b = Regex(\"x\${n}y{1}\")\nval c = Regex(someVariable)", "T.kt")
        assertEquals(listOf("""^\{id\}\d{2}$""", "xxy{1}"), lits.map { it.pattern })
        assertEquals(listOf(1, 2), lits.map { it.line })
    }

    @Test
    fun `no regex literal in any module's main sources has a closing brace that Android's engine would reject`() {
        val root = repoRoot()
        val files = root.listFiles { f -> f.isDirectory && File(f, "src/main").isDirectory }.orEmpty()
            .flatMap { module -> File(module, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 100, "the scan found only ${files.size} source files: is the root right?")
        val literals = files.flatMap { literalsIn(it.readText(), it.relativeTo(root).path) }
        assertTrue(literals.size >= 10, "the scan found only ${literals.size} regex literals")
        val bad = literals.filter { unmatchedClosingBrace(it.pattern) != null }
            .map { "${it.file}:${it.line}: ${it.pattern}" }
        assertEquals(emptyList(), bad, "Android's regex engine rejects an unescaped '}' that closes nothing; write \\} instead")
    }
}
