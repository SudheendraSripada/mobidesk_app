package com.mobidesk.mobidesk_app

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Android Presentation displaying the Cloud PC HTML5 session (Apache Guacamole)
 * on an independent VirtualDisplay backed by the MediaCodec encoder input Surface.
 * Runs completely decoupled from the phone's primary screen, continuing to stream
 * seamlessly even when the phone screen is turned off.
 */
class MobiDeskPresentation(
    outerContext: Context,
    display: Display,
    private val sessionUrl: String
) : Presentation(outerContext, display) {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use context (inherited from Dialog/Presentation), which encapsulates the VirtualDisplay metrics
        val displayContext = context

        val root = FrameLayout(displayContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val wv = WebView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(sessionUrl)
        }

        root.addView(wv)
        setContentView(root)
        webView = wv
    }

    override fun onDetachedFromWindow() {
        try {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
        } catch (_: Exception) {}
        webView = null
        super.onDetachedFromWindow()
    }
}
