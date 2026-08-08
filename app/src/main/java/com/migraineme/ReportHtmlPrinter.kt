package com.migraineme

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.OpenLayoutResultCallback
import android.print.OpenWriteResultCallback
import android.print.PrintDocumentAdapter
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Turns the report HTML from the `build-report-html` edge function into a PDF
 * using the system WebView.
 *
 * The document's layout lives in that function, not here — this file only
 * renders and shares it. That is what lets Android, iOS, VertigoMe and
 * MeSeries print the same report without four separate drawing engines.
 */
object ReportHtmlPrinter {
    private const val TAG = "ReportHtmlPrinter"

    /** A4 at 1/1000 inch, matching the @page size the HTML declares. */
    private val A4 = PrintAttributes.MediaSize.ISO_A4

    suspend fun renderToPdf(context: Context, html: String): File? = withContext(Dispatchers.Main) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = false
        webView.settings.loadWithOverviewMode = false

        // The WebView must have finished laying out before the print adapter
        // will produce anything but a blank page.
        val loaded = suspendCancellableCoroutine { cont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(true)
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        if (!loaded) return@withContext null

        val adapter = webView.createPrintDocumentAdapter("MigraineMe report")
        val attributes = PrintAttributes.Builder()
            .setMediaSize(A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val out = File(dir, "MigraineMe_Report_${System.currentTimeMillis()}.pdf")

        val ok = suspendCancellableCoroutine { cont ->
            adapter.onLayout(null, attributes, CancellationSignal(),
                object : OpenLayoutResultCallback() {
                    override fun onLayoutFinished(info: android.print.PrintDocumentInfo?, changed: Boolean) {
                        val fd = ParcelFileDescriptor.open(
                            out,
                            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE,
                        )
                        adapter.onWrite(arrayOf(PageRange.ALL_PAGES), fd, CancellationSignal(),
                            object : OpenWriteResultCallback() {
                                override fun onWriteFinished(pages: Array<out PageRange>?) {
                                    runCatching { fd.close() }
                                    if (cont.isActive) cont.resume(true)
                                }

                                override fun onWriteFailed(error: CharSequence?) {
                                    Log.e(TAG, "write failed: $error")
                                    runCatching { fd.close() }
                                    if (cont.isActive) cont.resume(false)
                                }
                            })
                    }

                    override fun onLayoutFailed(error: CharSequence?) {
                        Log.e(TAG, "layout failed: $error")
                        if (cont.isActive) cont.resume(false)
                    }
                }, Bundle())
        }

        if (ok && out.length() > 0) out else null
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
