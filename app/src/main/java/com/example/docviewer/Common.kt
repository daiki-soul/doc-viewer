package com.example.docviewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

fun esc(s: String?): String {
    if (s == null) return ""
    val sb = StringBuilder(s.length + 16)
    for (c in s) when (c) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        else -> sb.append(c)
    }
    return sb.toString()
}

fun msg(t: String): String =
    "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
        "<body style='font-family:sans-serif;padding:24px;color:#333'>${esc(t)}</body></html>"

fun xmlParser(s: InputStream): XmlPullParser {
    val p = Xml.newPullParser()
    p.setInput(s, null)
    return p
}
