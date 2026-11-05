package com.example.autopay

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class Txn(val ts: Long, val text: String, val amount: Double, val key: String)

data class Finding(
    val name: String, val avg: Double, val freq: String?, val count: Int,
    val last: Long, val next: Long?, val flags: List<String>,
    val status: String, val score: Int, val monthly: Double
)

private class F(val lo: Int, val hi: Int, val name: String, val d: Int)

object Detector {
    private val I = RegexOption.IGNORE_CASE
    private const val DAY = 86_400_000L

    private val flagDefs = listOf(
        Regex("auto[\\s-]*pay", I) to "Autopay",
        Regex("\\bnach\\b|\\becs\\b|e-?mandate|\\bmandate\\b|\\bumrn\\b", I) to "Mandate",
        Regex("standing instruction|\\bsi\\b", I) to "Standing instruction",
        Regex("auto[\\s-]*debit|recurring|subscription|renewal", I) to "Recurring"
    )
    private val stopRe = Regex("revoked|cancelled|canceled|paused|deleted|deactivated|stopped|unsubscribed", I)
    private val amtRe = Regex("(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]+)?)", I)
    private val debitRe = Regex("debited|debit of|spent|paid|sent|withdrawn|purchase|will be debited", I)
    private val merchRe = Regex("(?:\\bto\\b|\\bat\\b|towards|\\bfor\\b|merchant[:\\s]+|info[:\\-\\s]+)\\s+([A-Za-z][A-Za-z0-9 .&*]{2,30})", I)
    private val noise = setOf(
        "UPI", "NEFT", "IMPS", "RTGS", "POS", "ECOM", "ACH", "NACH", "ECS", "THE", "AND", "YOUR",
        "ACCOUNT", "VPA", "REF", "TXN", "AVL", "BAL", "BALANCE", "INFO", "WITH", "FROM", "FOR",
        "AUTOPAY", "AUTO", "PAY", "DEBIT", "DEBITED", "MANDATE", "PAYMENT", "BANK", "CARD", "SMS", "NOT", "YOU"
    )
    private val table = listOf(
        F(5, 9, "hafta", 7), F(12, 17, "15 din", 14), F(26, 35, "mahina", 30),
        F(80, 100, "3 mahine", 91), F(170, 195, "6 mahine", 182), F(350, 380, "saal", 365)
    )

    fun keyOf(s: String): String =
        s.uppercase().replace(Regex("[^A-Z ]"), " ").split(Regex("\\s+"))
            .filter { it.length > 2 && it !in noise }.take(2).joinToString(" ")

    private fun num(s: String) = s.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull() ?: 0.0

    fun fromSms(address: String, body: String, ts: Long): Txn? {
        val low = body.lowercase()
        if ("otp" in low) return null
        val flagged = flagDefs.any { it.first.containsMatchIn(body) }
        val isDebit = debitRe.containsMatchIn(body)
        if (!flagged && !isDebit) return null
        if (!isDebit && "credited" in low) return null
        val amt = amtRe.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        var key = merchRe.findAll(body).map { keyOf(it.groupValues[1]) }.firstOrNull { it.isNotBlank() } ?: ""
        if (key.isBlank()) key = keyOf(address)
        if (key.isBlank()) return null
        return Txn(ts, body, amt, key)
    }

    private fun split(line: String, d: Char): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        for (c in line) {
            if (c == '"') q = !q else if (c == d && !q) { out.add(sb.toString()); sb.setLength(0) } else sb.append(c)
        }
        out.add(sb.toString()); return out
    }

    fun fromCsv(text: String): List<Txn> {
        val lines = text.lines().filter { it.isNotBlank() }
        val descKeys = listOf("desc", "narr", "particular", "detail", "remark")
        val hi = lines.indexOfFirst { val l = it.lowercase(); "date" in l && descKeys.any { k -> k in l } }
        if (hi < 0) return emptyList()
        val d = if (lines[hi].count { it == ';' } > lines[hi].count { it == ',' }) ';' else ','
        val h = split(lines[hi], d).map { it.trim().lowercase() }
        var di = h.indexOfFirst { "date" in it && "value" !in it }
        if (di < 0) di = h.indexOfFirst { "date" in it }
        val ni = h.indexOfFirst { x -> descKeys.any { it in x } }
        val dr = h.indexOfFirst { "debit" in it || "withdraw" in it }
        val am = h.indexOfFirst { ("amount" in it || "amt" in it) && "bal" !in it }
        if (di < 0 || ni < 0 || (dr < 0 && am < 0)) return emptyList()
        val body = lines.drop(hi + 1)
        val hasNeg = am >= 0 && body.any { num(split(it, d).getOrElse(am) { "" }) < 0 }
        val fmts = listOf("dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-dd", "dd MMM yyyy", "dd-MMM-yyyy", "dd/MM/yy", "dd-MM-yy")
            .map { SimpleDateFormat(it, Locale.ENGLISH).apply { isLenient = false } }
        val out = mutableListOf<Txn>()
        for (line in body) {
            val c = split(line, d)
            if (c.size <= max(di, ni)) continue
            val ts = fmts.firstNotNullOfOrNull { f ->
                try { f.parse(c[di].trim())?.time?.takeIf { it > 0 } } catch (e: Exception) { null }
            } ?: continue
            val a = if (dr >= 0) abs(num(c.getOrElse(dr) { "" })) else {
                val v = num(c.getOrElse(am) { "" })
                if (hasNeg) (if (v < 0) -v else 0.0) else v
            }
            if (a <= 0) continue
            val key = keyOf(c[ni]); if (key.isBlank()) continue
            out.add(Txn(ts, c[ni], a, key))
        }
        return out
    }

    fun analyse(all: List<Txn>): List<Finding> {
        if (all.isEmpty()) return emptyList()
        val latest = all.maxOf { it.ts }
        val out = mutableListOf<Finding>()
        for ((k, g0) in all.groupBy { it.key }) {
            val g = g0.sortedBy { it.ts }
            val flags = linkedSetOf<String>()
            g.forEach { t -> flagDefs.forEach { (re, n) -> if (re.containsMatchIn(t.text)) flags.add(n) } }
            val days = g.distinctBy { it.ts / DAY }
            var f: F? = null
            var score = 0
            if (days.size >= 2) {
                val gaps = days.zipWithNext { a, b -> ((b.ts - a.ts) / DAY).toInt() }
                val med = gaps.sorted()[gaps.size / 2]
                val hit = table.firstOrNull { med in it.lo..it.hi }
                val ok = gaps.count { abs(it - med) <= max(3.0, med * 0.15) }.toDouble() / gaps.size
                if (hit != null && ok >= 0.6) { f = hit; score += 30 + (if (days.size >= 3) 15 else 0) }
            }
            val amts = days.map { it.amount }
            if (f != null && amts.max() / amts.min() <= 1.1) score += 10
            if (flags.isNotEmpty()) score += if (flags.size > 1) 55 else 45
            if (score < 35) continue
            val last = g.last()
            val avg = amts.average()
            val status = when {
                flags.isNotEmpty() && stopRe.containsMatchIn(last.text) -> "Band / cancel ho chuka"
                f != null && latest - last.ts > f.d * 1.6 * DAY -> "Shayad band ho gaya"
                else -> "Active"
            }
            out.add(Finding(
                k.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                avg, f?.name, days.size, last.ts,
                f?.let { last.ts + it.d * DAY }, flags.toList(), status, minOf(score, 100),
                if (f != null && status == "Active") avg * 30.4 / f.d else 0.0
            ))
        }
        return out.sortedWith(compareByDescending<Finding> { it.score }.thenByDescending { it.avg })
    }
}
