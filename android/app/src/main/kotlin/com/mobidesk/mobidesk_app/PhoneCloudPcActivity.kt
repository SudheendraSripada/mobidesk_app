package com.mobidesk.mobidesk_app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Fullscreen immersive landscape Activity hosting the Cloud PC HTML5 client
 * for Phone Mode (Option A).
 *
 * Features:
 * - Immersive sticky fullscreen mode with system bars hidden.
 * - Hardware-accelerated WebView with JavaScript and DOM storage enabled.
 * - Floating helper toolbar with soft keyboard toggle and shortcut keys (Ctrl, Alt, Win, Esc, Tab).
 * - Full mouse/touch/scroll navigation.
 * - Disconnect and Reconnect controls.
 */
class PhoneCloudPcActivity : Activity() {

    companion object {
        const val EXTRA_SESSION_URL = "extra_session_url"
        private const val DEFAULT_URL = "http://localhost:8080/guacamole/#/"
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var floatingToolbar: View
    private var sessionUrl: String = DEFAULT_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        applyFullScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sessionUrl = intent.getStringExtra(EXTRA_SESSION_URL) ?: DEFAULT_URL

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Initialize WebView for Guacamole HTML5 Client
        webView = WebView(this).apply {
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
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {}
            webChromeClient = object : WebChromeClient() {}
            loadUrl(sessionUrl)
        }

        rootLayout.addView(webView)

        // Build floating helper toolbar
        setupFloatingToolbar()
        rootLayout.addView(floatingToolbar)

        setContentView(rootLayout)
    }

    private fun setupFloatingToolbar() {
        val scrollContainer = HorizontalScrollView(this).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 16
            }
            layoutParams = lp
            isHorizontalScrollBarEnabled = false
        }

        val toolbarPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(Color.argb(210, 20, 20, 30))
                cornerRadius = 32f
                setStroke(2, Color.argb(100, 255, 255, 255))
            }
            background = bg
            setPadding(16, 8, 16, 8)
        }

        // Helper button builder
        fun createToolButton(text: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 12f
                val btnBg = GradientDrawable().apply {
                    setColor(Color.argb(140, 60, 60, 80))
                    cornerRadius = 20f
                }
                background = btnBg
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    80
                ).apply {
                    setMargins(6, 0, 6, 0)
                }
                layoutParams = params
                setOnClickListener { onClick() }
            }
        }

        // Toggle Keyboard
        toolbarPanel.addView(createToolButton("⌨ Keyboard") {
            toggleSoftKeyboard()
        })

        // Shortcut Keys: Ctrl, Alt, Win, Esc, Tab
        toolbarPanel.addView(createToolButton("Ctrl") {
            injectKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", "Control")
        })
        toolbarPanel.addView(createToolButton("Alt") {
            injectKeyEvent(KeyEvent.KEYCODE_ALT_LEFT, "Alt", "Alt")
        })
        toolbarPanel.addView(createToolButton("⊞ Win") {
            injectKeyEvent(KeyEvent.KEYCODE_META_LEFT, "Win", "Meta")
        })
        toolbarPanel.addView(createToolButton("Esc") {
            injectKeyEvent(KeyEvent.KEYCODE_ESCAPE, "Esc", "Escape")
        })
        toolbarPanel.addView(createToolButton("Tab") {
            injectKeyEvent(KeyEvent.KEYCODE_TAB, "Tab", "Tab")
        })

        // Right Click Helper Key
        toolbarPanel.addView(createToolButton("🖱 Right Click") {
            injectRightClick()
        })

        // Reconnect
        toolbarPanel.addView(createToolButton("↻ Reconnect") {
            webView.loadUrl(sessionUrl)
            Toast.makeText(this, "Reconnecting to Cloud PC...", Toast.LENGTH_SHORT).show()
        })

        // Disconnect
        toolbarPanel.addView(createToolButton("✕ Disconnect") {
            Toast.makeText(this, "Disconnected from Cloud PC", Toast.LENGTH_SHORT).show()
            finish()
        })

        // Back to Dashboard
        toolbarPanel.addView(createToolButton("← Dashboard") {
            finish()
        })

        scrollContainer.addView(toolbarPanel)
        floatingToolbar = scrollContainer
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        webView.requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun injectRightClick() {
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        val js = """
            (function() {
                var el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2) || document.body;
                var evt = new MouseEvent('contextmenu', {
                    bubbles: true,
                    cancelable: true,
                    view: window,
                    button: 2,
                    buttons: 2,
                    clientX: window.innerWidth / 2,
                    clientY: window.innerHeight / 2
                });
                el.dispatchEvent(evt);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Right click dispatched", Toast.LENGTH_SHORT).show()
    }

    private fun injectKeyEvent(keyCode: Int, keyName: String? = null, jsKey: String? = null) {
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        if (jsKey != null) {
            val js = """
                (function() {
                    var target = document.activeElement || window;
                    target.dispatchEvent(new KeyboardEvent('keydown', { key: '$jsKey', bubbles: true }));
                    target.dispatchEvent(new KeyboardEvent('keyup', { key: '$jsKey', bubbles: true }));
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
        Toast.makeText(this, "Sent ${keyName ?: KeyEvent.keyCodeToString(keyCode)}", Toast.LENGTH_SHORT).show()
    }

    private fun applyFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullScreen()
        }
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
