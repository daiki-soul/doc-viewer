package com.example.docviewer

import org.xmlpull.v1.XmlPullParser
import java.math.BigDecimal
import java.math.MathContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.TreeMap
import java.util.zip.ZipFile

object Xlsx {
    private const val MAX_ROWS = 5000
    private const val MAX_COLS = 200

    private class Cell(val text: String, val num: Boolean)

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n - 1
    }

    private fun colLetter(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun isDateCode(code: String): Boolean {
        val c = code.lowercase().replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^\\]]*\\]"), "")
        return c.contains('y') || c.contains('d') || c.contains('h')
    }

    private fun fmtNumber(t: String): String {
        val d = t.toDoubleOrNull() ?: return t
        if (d.isNaN() || d.isInfinite()) return t
        return BigDecimal(d).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun fmtDate(t: String): String {
        val d = t.toDoubleOrNull() ?: return t
        val days = Math.floor(d)
        val secs = Math.round((d - days) * 86400.0)
        val ms = (days - 25569.0) * 86400000.0 + secs * 1000.0
        val f = SimpleDateFormat(if (secs > 0) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd")
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms.toLong()))
    }

    fun toHtml(z: ZipFile): String {
        // Shared strings
        val shared = ArrayList<String>()
        z.getEntry("xl/sharedStrings.xml")?.let { e ->
            val p = xmlParser(z.getInputStream(e))
            val sb = StringBuilder()
            var inSi = false; var inT = false; var inPh = false
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> { sb.setLength(0); inSi = true }
                        "t" -> inT = true
                        "rPh" -> inPh = true
                    }
                    XmlPullParser.TEXT -> if (inSi && inT && !inPh) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "t" -> inT = false
                        "rPh" -> inPh = false
                        "si" -> { shared.add(sb.toString()); inSi = false }
                    }
                }
                p.next()
            }
        }

        // Styles: which cell styles are dates
        val customFmt = HashMap<Int, String>()
        val xfFmt = ArrayList<Int>()
        z.getEntry("xl/styles.xml")?.let { e ->
            val p = xmlParser(z.getInputStream(e))
            var inXfs = false
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "numFmt" -> {
                            val id = p.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                            val code = p.getAttributeValue(null, "formatCode")
                            if (id != null && code != null) customFmt[id] = code
                        }
                        "cellXfs" -> inXfs = true
                        "xf" -> if (inXfs) xfFmt.add(p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
                    }
                    XmlPullParser.END_TAG -> if (p.name == "cellXfs") inXfs = false
                }
                p.next()
            }
        }
        fun isDateStyle(s: Int): Boolean {
            val id = xfFmt.getOrNull(s) ?: return false
            if (id in 14..22 || id in 45..47) return true
            val code = customFmt[id] ?: return false
            return isDateCode(code)
        }

        // Sheet list
        val relMap = HashMap<String, String>()
        z.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
            val p = xmlParser(z.getInputStream(e))
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val t = p.getAttributeValue(null, "Target")
                    if (id != null && t != null) relMap[id] = t
                }
                p.next()
            }
        }
        val names = ArrayList<String>()
        val paths = ArrayList<String>()
        run {
            val p = xmlParser(z.getInputStream(z.getEntry("xl/workbook.xml")))
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    val rid = p.getAttributeValue(null, "r:id")
                    val t = if (rid != null) relMap[rid] else null
                    val idx = names.size + 1
                    val path = when {
                        t == null -> "xl/worksheets/sheet$idx.xml"
                        t.startsWith("/") -> t.substring(1)
                        else -> "xl/$t"
                    }
                    names.add(p.getAttributeValue(null, "name") ?: "Sheet$idx")
                    paths.add(path)
                }
                p.next()
            }
        }

        val out = StringBuilder()
        if (names.size > 1) {
            out.append("<div class='tabs'>")
            for (i in names.indices) out.append("<a href='#s$i'>").append(esc(names[i])).append("</a>")
            out.append("</div>")
        }
        for (i in names.indices) {
            out.append("<h3 id='s$i'>").append(esc(names[i])).append("</h3>")
            val entry = z.getEntry(paths[i])
            if (entry == null) { out.append("<p>(empty)</p>"); continue }
            sheet(z, entry, shared, ::isDateStyle, out)
        }

        return "<!doctype html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1,minimum-scale=0.2,maximum-scale=8,user-scalable=yes'>" +
            "<style>" +
            "body{margin:0;padding:8px;background:#fff;font-family:sans-serif;font-size:14px;color:#111}" +
            "h3{margin:16px 0 6px}.tabs a{display:inline-block;margin:0 8px 8px 0;padding:6px 12px;background:#e8eefc;border-radius:14px;text-decoration:none;color:#1a4fd6}" +
            "table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:3px 8px;white-space:nowrap}" +
            "th{background:#f1f1f1;color:#666;font-weight:normal}td.n{text-align:right}" +
            "</style></head><body>" + out + "</body></html>"
    }

    private fun sheet(
        z: ZipFile,
        entry: java.util.zip.ZipEntry,
        shared: List<String>,
        isDateStyle: (Int) -> Boolean,
        out: StringBuilder
    ) {
        val rows = ArrayList<Pair<Int, TreeMap<Int, Cell>>>()
        var cur = TreeMap<Int, Cell>()
        var rowNum = 0
        var lastRow = 0
        var col = 0; var type = ""; var style = 0
        val text = StringBuilder()
        var inV = false; var inIs = false; var inT = false
        var truncated = false
        val p = xmlParser(z.getInputStream(entry))
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        cur = TreeMap()
                        rowNum = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (lastRow + 1)
                        lastRow = rowNum
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) colIndex(ref) else (cur.lastKey() + 1)
                        type = p.getAttributeValue(null, "t") ?: ""
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        text.setLength(0)
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || (inIs && inT)) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "is" -> inIs = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = text.toString()
                        if (raw.isNotEmpty() && col in 0 until MAX_COLS) {
                            val cell = when (type) {
                                "s" -> Cell(shared.getOrNull(raw.toIntOrNull() ?: -1) ?: "", false)
                                "b" -> Cell(if (raw == "1") "TRUE" else "FALSE", false)
                                "str", "inlineStr", "e" -> Cell(raw, false)
                                else -> if (isDateStyle(style)) Cell(fmtDate(raw), false) else Cell(fmtNumber(raw), true)
                            }
                            cur[col] = cell
                        }
                    }
                    "row" -> {
                        if (rows.size < MAX_ROWS) rows.add(Pair(rowNum, cur)) else truncated = true
                    }
                }
            }
            p.next()
        }

        var maxCol = 0
        for (r in rows) if (r.second.isNotEmpty()) maxCol = maxOf(maxCol, r.second.lastKey() + 1)
        if (maxCol == 0) { out.append("<p>(empty)</p>"); return }

        out.append("<table><tr><th></th>")
        for (c in 0 until maxCol) out.append("<th>").append(colLetter(c)).append("</th>")
        out.append("</tr>")
        for ((num, cells) in rows) {
            out.append("<tr><th>").append(num).append("</th>")
            for (c in 0 until maxCol) {
                val cell = cells[c]
                if (cell == null) out.append("<td></td>")
                else out.append(if (cell.num) "<td class='n'>" else "<td>").append(esc(cell.text)).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
        if (truncated) out.append("<p>Showing the first $MAX_ROWS rows.</p>")
    }
}
