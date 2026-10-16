package com.binh.chordoffline.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regex ICU trên Android từ chối "]" và "}" không escape khi dùng như ký tự thường,
 * trong khi java.util.regex trên JVM lại chấp nhận — nên test JVM thông thường không bắt được.
 * Test này quét mọi Regex("""...""") trong mã nguồn và báo lỗi nếu có ký tự như vậy.
 */
class RegexAndroidCompatTest {

    private fun problems(pattern: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var inClass = false
        var braceOpen = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                inClass -> if (c == ']' && pattern[i - 1] != '[' && !pattern.startsWith("[^]", i - 2)) inClass = false
                c == '[' -> inClass = true
                c == '{' -> braceOpen = true
                c == '}' -> if (braceOpen) braceOpen = false else out += "\"}\" không escape ở vị trí $i"
                c == ']' -> out += "\"]\" không escape ở vị trí $i"
            }
            i++
        }
        return out
    }

    @Test fun allSourceRegexesAreIcuSafe() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java")).filter { it.isDirectory }
        assertTrue("Không tìm thấy thư mục mã nguồn", roots.isNotEmpty())
        val literal = Regex("Regex\\(\"\"\"(.*?)\"\"\"\\)")
        val errors = mutableListOf<String>()
        var count = 0
        roots.first().walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            literal.findAll(f.readText()).forEach { m ->
                count++
                problems(m.groupValues[1]).forEach { errors += "${f.name}: ${m.groupValues[1]} → $it" }
            }
        }
        assertTrue("Không tìm thấy regex nào để kiểm tra", count > 0)
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun detectorCatchesTheOriginalBug() {
        assertTrue(problems("""^\{([a-z_]+):\s?(.*)}$""").isNotEmpty())
        assertTrue(problems("""\[([^\]\n]{1,16})]""").isNotEmpty())
        assertTrue(problems("""\[([^\]\n]{1,16})\]""").isEmpty())
        assertTrue(problems("""^\{([a-z_]+):\s?(.*)\}$""").isEmpty())
    }
}
