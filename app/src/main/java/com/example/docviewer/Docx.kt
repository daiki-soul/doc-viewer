package com.example.docviewer

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile

object Docx {
    private fun on(p: XmlPullParser): Boolean {
        val v = p.getAttributeValue(null, "w:val")
        return v == null || (v != "0" && v != "false")
    }

    fun toHtml(z: ZipFile): String {
        val rels = HashMap<String, String>()
        z.getEntry("word/_rels/document.xml.rels")?.let { e ->
            val rp = xmlParser(z.getInputStream(e))
            while (rp.eventType != XmlPullParser.END_DOCUMENT) {
                if (rp.eventType == XmlPullParser.START_TAG && rp.name == "Relationship") {
                    val id = rp.getAttributeValue(null, "Id")
                    val t = rp.getAttributeValue(null, "Target")
                    if (id != null && t != null) rels[id] = t
                }
                rp.next()
            }
        }

        val body = StringBuilder()
        val para = StringBuilder()
        var style = ""
        var numbered = false
        var align = ""
        var bold = false
        var italic = false
        var under = false
        var inT = false
        var inRPr = false
        var inPPr = false
        var tableDepth = 0

        val p = xmlParser(z.getInputStream(z.getEntry("word/document.xml")))
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { para.setLength(0); style = ""; numbered = false; align = "" }
                    "w:pPr" -> inPPr = true
                    "w:pStyle" -> if (inPPr) style = p.getAttributeValue(null, "w:val") ?: ""
                    "w:numPr" -> if (inPPr) numbered = true
                    "w:jc" -> if (inPPr) align = p.getAttributeValue(null, "w:val") ?: ""
                    "w:r" -> { bold = false; italic = false; under = false }
                    "w:rPr" -> inRPr = true
                    "w:b" -> if (inRPr) bold = on(p)
                    "w:i" -> if (inRPr) italic = on(p)
                    "w:u" -> if (inRPr) under = (p.getAttributeValue(null, "w:val") ?: "single") != "none"
                    "w:t" -> inT = true
                    "w:tab" -> if (!inPPr) para.append("&emsp;")
                    "w:br", "w:cr" -> para.append("<br>")
                    "w:tbl" -> { tableDepth++; body.append("<table>") }
                    "w:tr" -> body.append("<tr>")
                    "w:tc" -> body.append("<td>")
                    "a:blip" -> {
                        val id = p.getAttributeValue(null, "r:embed")
                        val t = if (id != null) rels[id] else null
                        if (t != null) {
                            val path = if (t.startsWith("/")) t.substring(1) else "word/$t"
                            val en = z.getEntry(path)
                            if (en != null && en.size < 8_000_000) {
                                val mime = when (path.substringAfterLast('.').lowercase()) {
                                    "png" -> "image/png"
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "gif" -> "image/gif"
                                    "bmp" -> "image/bmp"
                                    "webp" -> "image/webp"
                                    else -> null
                                }
                                if (mime != null) {
                                    val bytes = z.getInputStream(en).readBytes()
                                    para.append("<img src=\"data:").append(mime).append(";base64,")
                                        .append(Base64.encodeToString(bytes, Base64.NO_WRAP)).append("\">")
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inT) {
                    var s = esc(p.text)
                    if (bold) s = "<b>$s</b>"
                    if (italic) s = "<i>$s</i>"
                    if (under) s = "<u>$s</u>"
                    para.append(s)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inT = false
                    "w:pPr" -> inPPr = false
                    "w:rPr" -> inRPr = false
                    "w:p" -> {
                        val content = para.toString()
                        if (content.isEmpty()) {
                            if (tableDepth == 0) body.append("<p>&nbsp;</p>")
                        } else {
                            val sl = style.lowercase().replace(" ", "")
                            val tag = when {
                                sl.startsWith("heading") && sl.last().isDigit() ->
                                    "h" + sl.last().digitToInt().coerceIn(1, 6)
                                sl == "title" -> "h1"
                                sl == "subtitle" -> "h2"
                                else -> "p"
                            }
                            var css = when (align) {
                                "center" -> "text-align:center;"
                                "right", "end" -> "text-align:right;"
                                "both" -> "text-align:justify;"
                                else -> ""
                            }
                            var prefix = ""
                            if (numbered) { css += "margin-left:1.5em;"; prefix = "&bull; " }
                            body.append("<").append(tag).append(" style='").append(css).append("'>")
                                .append(prefix).append(content).append("</").append(tag).append(">")
                        }
                        para.setLength(0)
                    }
                    "w:tc" -> body.append("</td>")
                    "w:tr" -> body.append("</tr>")
                    "w:tbl" -> { tableDepth--; body.append("</table>") }
                }
            }
            p.next()
        }

        return "<!doctype html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1,minimum-scale=0.5,maximum-scale=8,user-scalable=yes'>" +
            "<style>" +
            "body{margin:0;background:#e6e6e6;font-family:sans-serif}" +
            ".page{background:#fff;max-width:820px;margin:8px auto;padding:20px 16px;box-sizing:border-box;line-height:1.5;font-size:16px;overflow-wrap:anywhere}" +
            "p{margin:0 0 .6em}img{max-width:100%;height:auto}" +
            "table{border-collapse:collapse;max-width:100%;margin:8px 0}" +
            "td{border:1px solid #bbb;padding:4px 6px;vertical-align:top}" +
            "</style></head><body><div class='page'>" + body + "</div></body></html>"
    }
}
