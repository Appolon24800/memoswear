package dev.appolon.memoswear.ui.signin

import android.content.Context
import android.webkit.WebView

/** Whether this watch can show web pages at all. Watches differ: some ship without a WebView. */
internal object WebViewAvailability {
    /**
     * True when the system has a WebView implementation. Creating one throws on a watch that
     * has none, so this is asked first.
     */
    fun hasProvider(context: Context): Boolean = runCatching {
        WebView.getCurrentWebViewPackage() != null
    }.getOrDefault(false)
}
