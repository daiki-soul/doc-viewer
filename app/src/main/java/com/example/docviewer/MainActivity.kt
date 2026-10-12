package com.example.docviewer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

class MainActivity : Activity() {
    private val base = "https://doc.local/"
    private val lock = Any()
    private var renderer: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private lateinit var web: WebView
    private lateinit var empty: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.setBackgroundColor(Color.parseColor("#2b2b2b"))
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                return pageResponse(u)
            }
        }
        web.visibility = View.GONE

        empty = LinearLayout(this)
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        val title = TextView(this)
        title.text = "Doc Viewer\nPDF · DOCX · XLSX"
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 48)
        val btn = Button(this)
        btn.text = "Open a document"
        btn.setOnClickListener { pick() }
        empty.addView(title)
        empty.addView(btn)

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(web, FrameLayout.LayoutParams(mp, mp))
        root.addView(empty, FrameLayout.LayoutParams(mp, mp))
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val uri = if (i?.action == Intent.ACTION_VIEW) i.data else null
        if (uri != null) load(uri)
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { load(it) }
    }

    private fun load(uri: Uri) {
        empty.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadDataWithBaseURL(base, msg("Opening…"), "text/html", "utf-8", null)
        Thread {
            try {
                synchronized(lock) {
                    renderer?.close(); renderer = null
                    pdfFd?.close(); pdfFd = null
                }
                cacheDir.listFiles()?.forEach { if (it.name.startsWith("doc_")) it.delete() }
                val f = File(cacheDir, "doc_" + System.nanoTime())
                val input = contentResolver.openInputStream(uri) ?: throw Exception("Can't read file")
                input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val head = ByteArray(4)
                f.inputStream().use { it.read(head) }
                val html = when {
                    head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() -> openPdf(f)
                    head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> openZip(f)
                    else -> throw Exception("Only PDF, DOCX and XLSX are supported")
                }
                runOnUiThread { web.loadDataWithBaseURL(base, html, "text/html", "utf-8", null) }
            } catch (e: Throwable) {
                runOnUiThread {
                    web.loadDataWithBaseURL(base, msg("Can't open this file: " + e.message), "text/html", "utf-8", null)
                }
            }
        }.start()
    }

    private fun openZip(f: File): String {
        val z = ZipFile(f)
        try {
            return when {
                z.getEntry("word/document.xml") != null -> Docx.toHtml(z)
                z.getEntry("xl/workbook.xml") != null -> Xlsx.toHtml(z)
                else -> throw Exception("Only PDF, DOCX and XLSX are supported")
            }
        } finally {
            z.close()
        }
    }

    // PDF pages are rendered lazily: the WebView asks for each page image as you scroll,
    // and WebView's own pinch-zoom keeps scrolling and zooming smooth.
    private fun openPdf(f: File): String {
        val r: PdfRenderer
        synchronized(lock) {
            val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfFd = fd
            r = PdfRenderer(fd)
            renderer = r
        }
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset='utf-8'>")
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1,minimum-scale=0.5,maximum-scale=8,user-scalable=yes'>")
        sb.append("<style>body{margin:0;background:#2b2b2b}img{display:block;width:100%;height:auto;background:#fff;margin:0 0 6px 0}</style>")
        sb.append("</head><body>")
        for (n in 0 until r.pageCount) {
            var w = 1
            var h = 1
            synchronized(lock) {
                val page = r.openPage(n)
                try { w = page.width; h = page.height } finally { page.close() }
            }
            sb.append("<img src='").append(base).append("p/").append(n).append(".jpg' width='").append(w)
                .append("' height='").append(h).append("' style='aspect-ratio:").append(w).append("/").append(h)
                .append("' loading='lazy'>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun pageResponse(u: Uri): WebResourceResponse? {
        val p = u.path ?: return null
        if (!p.startsWith("/p/")) return null
        val n = p.removePrefix("/p/").removeSuffix(".jpg").toIntOrNull() ?: return null
        val bytes: ByteArray? = synchronized(lock) {
            val r = renderer
            if (r == null || n < 0 || n >= r.pageCount) {
                null
            } else {
                val page = r.openPage(n)
                try {
                    val w = 2000
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    bmp.recycle()
                    out.toByteArray()
                } finally {
                    page.close()
                }
            }
        }
        if (bytes == null) return null
        return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
    }

    override fun onDestroy() {
        synchronized(lock) {
            renderer?.close(); renderer = null
            pdfFd?.close(); pdfFd = null
        }
        super.onDestroy()
    }
}
