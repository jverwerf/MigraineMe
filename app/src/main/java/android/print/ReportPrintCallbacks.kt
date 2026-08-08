package android.print

/**
 * `PrintDocumentAdapter.LayoutResultCallback` and `WriteResultCallback` have
 * package-private constructors, so they can only be subclassed from inside
 * `android.print`. These two shims exist purely so ReportHtmlPrinter can drive
 * a WebView straight to a PDF file without putting the system print dialog in
 * front of the user.
 */
abstract class OpenLayoutResultCallback : PrintDocumentAdapter.LayoutResultCallback()

abstract class OpenWriteResultCallback : PrintDocumentAdapter.WriteResultCallback()
