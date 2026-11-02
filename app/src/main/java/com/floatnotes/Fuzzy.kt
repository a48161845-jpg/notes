package com.floatnotes

/** Нечёткий поиск: понимает опечатки, части слов и общий смысл по пересечению триграмм. */
object Fuzzy {

    private const val THRESHOLD = 0.35

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').trim()

    private fun tokens(s: String): List<String> =
        s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun tokenSim(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length >= 2 && b.startsWith(a)) return 0.95
        val m = maxOf(a.length, b.length)
        val s = 1.0 - lev(a, b).toDouble() / m
        return if (s >= 0.5) s else 0.0
    }

    private fun trigrams(s: String): Set<String> {
        val p = "  $s "
        val out = HashSet<String>()
        for (i in 0..p.length - 3) out.add(p.substring(i, i + 3))
        return out
    }

    fun score(query: String, text: String): Double {
        val q = norm(query)
        val t = norm(text)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (t.contains(q)) return 1.0

        val qt = tokens(q)
        val tt = tokens(t)
        val tokenScore = if (qt.isEmpty() || tt.isEmpty()) 0.0 else
            qt.map { a -> tt.maxOf { b -> tokenSim(a, b) } }.average()

        val qg = trigrams(q)
        val tg = trigrams(t)
        val inter = qg.count { it in tg }.toDouble()
        val union = (qg.size + tg.size - inter)
        val tri = if (union == 0.0) 0.0 else inter / union

        return 0.65 * tokenScore + 0.35 * tri
    }

    fun <T> rank(query: String, items: List<T>, textOf: (T) -> String, limit: Int = 50): List<T> =
        items.map { it to score(query, textOf(it)) }
            .filter { it.second >= THRESHOLD }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
}
