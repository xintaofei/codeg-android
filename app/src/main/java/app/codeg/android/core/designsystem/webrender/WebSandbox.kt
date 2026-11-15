package app.codeg.android.core.designsystem.webrender

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * The sandbox every renderer web view — the off-screen pages and the full-screen viewers —
 * runs in. Port of iOS `WebSandbox`, in Android's terms:
 *
 * - **No network.** Every request the page makes is answered by the app with an empty 403
 *   ([SandboxClient.shouldInterceptRequest]); network loads are also blocked outright
 *   (`blockNetworkLoads`), and every document carries a CSP with no network source. Only
 *   `data:` and `blob:` (never fetched) render. The documents themselves are handed in as
 *   strings (`loadDataWithBaseURL`), never loaded from a URL.
 * - **No navigation.** Any navigation the page starts — a link, a form, a refresh — is
 *   refused, except a jump to a `#fragment` of the same document.
 * - **No local access.** File and content access are off, and so are file-URL origins.
 * - **No storage.** DOM storage, databases and the HTTP cache are off; third-party cookies
 *   are refused.
 * - **Script only where it must run**: on the Mermaid page, whose only content is the
 *   bundled library and diagram sources rendered with `securityLevel: "strict"`. The HTML
 *   preview — off-screen and full screen — never runs script.
 * - A renderer that crashes or is killed takes only its page down
 *   ([SandboxClient.onRenderProcessGone]), never the app.
 */
internal object WebSandbox {
    @SuppressLint("SetJavaScriptEnabled")
    fun makeWebView(context: Context, javaScript: Boolean, client: SandboxClient, zoomable: Boolean = false): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = javaScript
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = false
            // Laid out as the page says, independent of the system font scale.
            textZoom = 100
            setSupportZoom(zoomable)
            builtInZoomControls = zoomable
            displayZoomControls = false
            useWideViewPort = zoomable
            loadWithOverviewMode = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = client
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = zoomable
        view.isHorizontalScrollBarEnabled = zoomable
        view.overScrollMode = View.OVER_SCROLL_NEVER
        return view
    }
}

/**
 * The navigation and request guard of a sandboxed page. [onFinished] runs when a loaded
 * document has finished; [onGone] when the page's renderer died.
 */
internal open class SandboxClient(
    private val onFinished: () -> Unit = {},
    private val onGone: (WebView) -> Unit = {},
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // The document itself: `loadDataWithBaseURL(null, …)` loads the string it is handed as
        // a top-level `data:` URL, and only the app starts one — the page's own navigations
        // are refused (`shouldOverrideUrlLoading`), and Chromium blocks a renderer-initiated
        // top-level navigation to `data:` anyway.
        if (request.isForMainFrame && request.url.scheme == "data") return null
        return refused()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !isSameDocumentJump(view.url, request.url)

    override fun onPageFinished(view: WebView, url: String?) {
        onFinished()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone(view)
        // Handled: the page is thrown away, and the app keeps running.
        return true
    }

    companion object {
        fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        /** A `#fragment` link inside the loaded document — the one navigation allowed. */
        fun isSameDocumentJump(current: String?, target: Uri): Boolean {
            if (current == null || target.fragment == null) return false
            return current.substringBefore('#') == target.toString().substringBefore('#')
        }
    }
}
