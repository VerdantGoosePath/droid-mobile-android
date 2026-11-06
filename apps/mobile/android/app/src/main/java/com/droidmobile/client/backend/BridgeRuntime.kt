package com.droidmobile.client.backend

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * A nonvisual JS runtime, *not* a second frontend. It hosts only the existing
 * Factory SDK and the typed RPC dispatcher. Compose never touches a WebView.
 *
 * The untrusted internet cannot load here: WebViewAssetLoader serves bundled
 * static files over an HTTPS app-assets origin, navigation stays on that origin,
 * and messages are accepted exclusively from that exact origin.
 */
interface BackendTransport {
    fun send(command: String)
    fun setReceiver(receiver: (String) -> Unit)
    fun close()
}

class BridgeRuntime(context: Context) : BackendTransport {
    private val origin = "https://appassets.androidplatform.net"
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var receiver: (String) -> Unit = {}
    private val assets = WebViewAssetLoader.Builder()
        .setDomain("appassets.androidplatform.net")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    val webView: WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            error("Your Android System WebView does not support origin-confined messaging.")
        }
        WebViewCompat.addWebMessageListener(this, "DroidHost", setOf(origin)) {
                _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || sourceOrigin.toString() != origin) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            handler.post {
                val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return@post
                if (parsed.optString("event") == "host.ready") {
                    ready = true
                    while (pending.isNotEmpty()) evaluateCommand(pending.removeFirst())
                }
                receiver(raw)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): android.webkit.WebResourceResponse? = assets.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" ||
                    request.url.host != "appassets.androidplatform.net"
        }
        loadUrl("$origin/assets/bridge/index.html")
    }

    private fun evaluateCommand(json: String) {
        val quoted = JSONObject.quote(json)
        webView.evaluateJavascript("window.__nativeReceive && window.__nativeReceive($quoted);", null)
    }
    override fun send(command: String) {
        handler.post {
            if (ready) evaluateCommand(command) else pending.addLast(command)
        }
    }
    override fun setReceiver(receiver: (String) -> Unit) { this.receiver = receiver }
    override fun close() {
        handler.post {
            ready = false
            pending.clear()
            webView.stopLoading()
            webView.destroy()
        }
    }
}
