package com.binh.chordoffline.core

/** Bộ đọc robots.txt tối giản (nhóm User-agent, Allow/Disallow, * và $; luật dài nhất thắng). */
class RobotsRules(val rules: List<Rule>, val source: String) {

    data class Rule(val allow: Boolean, val path: String)

    fun isAllowed(path: String): Boolean {
        val p = path.ifEmpty { "/" }
        val best = rules.filter { matches(it.path, p) }
            .maxWithOrNull(compareBy<Rule>({ it.path.length }, { it.allow })) ?: return true
        return best.allow
    }

    private fun matches(pattern: String, path: String): Boolean {
        if (pattern.isEmpty()) return false
        val rx = buildString {
            append('^')
            pattern.forEachIndexed { i, c ->
                when {
                    c == '*' -> append(".*")
                    c == '$' && i == pattern.lastIndex -> append('$')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        return Regex(rx).containsMatchIn(path)
    }

    companion object {
        fun parse(text: String, agent: String = "*", source: String = "robots.txt"): RobotsRules {
            data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Rule> = mutableListOf())
            val groups = mutableListOf<Group>()
            var cur: Group? = null
            var lastWasAgent = false
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val k = line.substringBefore(':').trim().lowercase()
                val v = line.substringAfter(':', "").trim()
                when (k) {
                    "user-agent" -> {
                        if (cur == null || !lastWasAgent) { cur = Group(); groups += cur }
                        cur.agents += v.lowercase(); lastWasAgent = true
                    }
                    "allow", "disallow" -> {
                        lastWasAgent = false
                        if (v.isNotEmpty()) cur?.rules?.add(Rule(k == "allow", v))
                    }
                    else -> lastWasAgent = false
                }
            }
            val a = agent.lowercase()
            val specific = groups.filter { g -> a != "*" && g.agents.any { it != "*" && a.contains(it) } }
            val chosen = specific.ifEmpty { groups.filter { "*" in it.agents } }
            return RobotsRules(chosen.flatMap { it.rules }, source)
        }

        /** Bản sao robots.txt của hopamviet.vn (kiểm tra ngày 2026-10-16), dùng khi không tải được robots.txt. */
        val FALLBACK_HOPAMVIET = RobotsRules(
            listOf(
                Rule(true, "/"),
                Rule(false, "/chord/printsong/"),
                Rule(false, "/chord/search"),
                Rule(false, "/auth/"),
                Rule(false, "/user/"),
                Rule(false, "/sheet/download/"),
            ),
            source = "bản dự phòng (2026-10-16)",
        )
    }
}

/** Chỉ cho phép URL trang bài hát https://hopamviet.vn/chord/song/... */
object UrlPolicy {
    private val HOSTS = setOf("hopamviet.vn", "www.hopamviet.vn")

    /** Trả về null nếu hợp lệ, hoặc lý do từ chối. */
    fun validateSongUrl(url: String): String? {
        val u = try { java.net.URI(url.trim()) } catch (e: Exception) { return "URL không hợp lệ." }
        if (u.scheme?.lowercase() != "https") return "Chỉ chấp nhận https://"
        if (u.host?.lowercase() !in HOSTS) return "Chỉ hỗ trợ trang bài hát của hopamviet.vn."
        if (u.rawPath?.startsWith("/chord/song/") != true) return "Đường dẫn phải là /chord/song/... (trang bài hát)."
        return null
    }

    fun path(url: String): String = try { java.net.URI(url.trim()).rawPath ?: "/" } catch (e: Exception) { "/" }
}
