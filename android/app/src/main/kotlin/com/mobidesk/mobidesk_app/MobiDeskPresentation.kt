package com.mobidesk.mobidesk_app

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Android Presentation displaying the Cloud PC HTML5 session (Apache Guacamole)
 * on an independent VirtualDisplay backed by the MediaCodec encoder input Surface.
 *
 * Implements:
 * - Branded monitor status screen ("MobiDesk - connecting your Cloud PC..." / spinner / error countdown).
 * - Automatic exponential backoff reconnection on WebSocket close / error (1s, 2s, 4s, 8s, max 15s).
 * - Persistent logging of page lifecycle, errors, and console messages via AppLogger.
 * - Chrome remote inspection via WebView.setWebContentsDebuggingEnabled(true).
 * - Software cursor overlay View tracked from injected mouse coordinates.
 * - Native MotionEvent / KeyEvent injection with DOM fallback.
 */
class MobiDeskPresentation(
    outerContext: Context,
    display: Display,
    private var sessionUrl: String? = null
) : Presentation(outerContext, display) {

    private val TAG = "MobiDeskPresentation"

    private var rootLayout: FrameLayout? = null
    private var webView: WebView? = null
    private var cursorView: CursorOverlayView? = null
    private var statusLayout: LinearLayout? = null
    private var statusTitleView: TextView? = null
    private var statusSubtitleView: TextView? = null
    private var statusProgressBar: ProgressBar? = null
    private var statusErrorView: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastMouseX = 0f
    private var lastMouseY = 0f
    private var lastButtonMask = 0
    private var downTime = SystemClock.uptimeMillis()

    // Reconnection backoff state
    private var reconnectAttempt = 0
    private var isReconnecting = false
    private val backoffDelays = longArrayOf(1000L, 2000L, 4000L, 8000L, 15000L)
    private var reconnectRunnable: Runnable? = null

    companion object {
        init {
            // Enable chrome://inspect for prototype diagnostics
            try {
                WebView.setWebContentsDebuggingEnabled(true)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        }

        val displayContext = context

        val root = FrameLayout(displayContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // 1. Accelerated HTML5 WebView
        val wv = WebView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
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
            setBackgroundColor(Color.BLACK)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    AppLogger.i(TAG, "WebView onPageStarted: $url")
                    showConnectingScreen("Connecting your Cloud PC...")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    AppLogger.i(TAG, "WebView onPageFinished: $url")
                    reconnectAttempt = 0
                    isReconnecting = false
                    hideStatusScreen()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() ?: "" else ""
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: 0 else 0
                    AppLogger.e(TAG, "WebView onReceivedError: code=$code desc=$desc url=${request?.url}")
                    if (request?.isForMainFrame == true) {
                        scheduleReconnect("Network connection error ($code): $desc")
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    val statusCode = errorResponse?.statusCode ?: 0
                    AppLogger.e(TAG, "WebView onReceivedHttpError: status=$statusCode url=${request?.url}")
                    if (request?.isForMainFrame == true && statusCode >= 400) {
                        scheduleReconnect("Server returned HTTP $statusCode")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: ""
                    AppLogger.d(TAG, "WebView Console: $msg [${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}]")
                    if (msg.contains("WebSocket", ignoreCase = true) &&
                        (msg.contains("closed", ignoreCase = true) || msg.contains("failed", ignoreCase = true) || msg.contains("error", ignoreCase = true))) {
                        scheduleReconnect("Guacamole WebSocket session closed: $msg")
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }

        // 2. Branded Monitor Status Screen View (never leave monitor black or frozen)
        val statusView = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A")) // Deep dark slate background
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(48, 48, 48, 48)
        }

        val titleTv = TextView(displayContext).apply {
            text = "MobiDesk"
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#6366F1")) // Indigo 500
            gravity = Gravity.CENTER
        }

        val subtitleTv = TextView(displayContext).apply {
            text = "MobiDesk - connecting your Cloud PC..."
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        val spinner = ProgressBar(displayContext).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                gravity = Gravity.CENTER
            }
        }

        val errorTv = TextView(displayContext).apply {
            text = ""
            textSize = 15f
            setTextColor(Color.parseColor("#FCA5A5")) // Light red
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
            visibility = View.GONE
        }

        statusView.addView(titleTv)
        statusView.addView(subtitleTv)
        statusView.addView(spinner)
        statusView.addView(errorTv)

        // 3. Software cursor overlay View
        val cursor = CursorOverlayView(displayContext).apply {
            visibility = View.VISIBLE
            elevation = 100f
            translationZ = 100f
        }

        root.addView(wv)
        root.addView(statusView)
        root.addView(cursor)
        setContentView(root)

        rootLayout = root
        webView = wv
        cursorView = cursor
        statusLayout = statusView
        statusTitleView = titleTv
        statusSubtitleView = subtitleTv
        statusProgressBar = spinner
        statusErrorView = errorTv

        // If session URL is already available, load it; otherwise display connecting status
        val url = sessionUrl
        if (!url.isNullOrEmpty()) {
            showConnectingScreen("MobiDesk - connecting your Cloud PC...")
            wv.loadUrl(url)
        } else {
            showConnectingScreen("MobiDesk - waiting for Cloud PC session assignment...")
        }
    }

    fun updateSessionUrl(newUrl: String) {
        sessionUrl = newUrl
        mainHandler.post {
            reconnectAttempt = 0
            isReconnecting = false
            showConnectingScreen("MobiDesk - connecting your Cloud PC...")
            webView?.loadUrl(newUrl)
        }
    }

    fun showConnectingScreen(message: String) {
        mainHandler.post {
            statusLayout?.visibility = View.VISIBLE
            statusProgressBar?.visibility = View.VISIBLE
            statusSubtitleView?.text = message
            statusErrorView?.visibility = View.GONE
        }
    }

    fun showErrorScreen(errorText: String, retryCountdownSeconds: Int = 0) {
        mainHandler.post {
            statusLayout?.visibility = View.VISIBLE
            statusProgressBar?.visibility = if (retryCountdownSeconds > 0) View.VISIBLE else View.GONE
            statusSubtitleView?.text = "Connection Error"
            val text = if (retryCountdownSeconds > 0) {
                "$errorText\nRetrying automatically in ${retryCountdownSeconds}s..."
            } else {
                errorText
            }
            statusErrorView?.text = text
            statusErrorView?.visibility = View.VISIBLE
        }
    }

    fun hideStatusScreen() {
        mainHandler.post {
            statusLayout?.visibility = View.GONE
            statusErrorView?.visibility = View.GONE
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (isReconnecting) return
        isReconnecting = true

        val delayIdx = reconnectAttempt.coerceAtMost(backoffDelays.size - 1)
        val delayMs = backoffDelays[delayIdx]
        val delaySec = delayMs / 1000
        reconnectAttempt++

        AppLogger.w(TAG, "Cloud PC connection failed: $reason. Reconnecting (attempt $reconnectAttempt) in ${delaySec}s...")

        showErrorScreen("Reconnecting to Cloud PC...\n$reason", delaySec.toInt())

        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            isReconnecting = false
            val url = sessionUrl
            if (!url.isNullOrEmpty() && webView != null) {
                AppLogger.i(TAG, "Executing reconnect attempt $reconnectAttempt to $url")
                showConnectingScreen("Reconnecting to Cloud PC (attempt $reconnectAttempt)...")
                webView?.loadUrl(url)
            }
        }
        reconnectRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    /**
     * Injects mouse event: updates software cursor and dispatches MotionEvent + DOM fallback.
     */
    fun injectMouseEvent(
        normX: Int,
        normY: Int,
        buttonMask: Int,
        wheelDx: Int = 0,
        wheelDy: Int = 0
    ) {
        mainHandler.post {
            val wv = webView ?: return@post
            val cursor = cursorView

            @Suppress("DEPRECATION")
            val displayW = display.width.toFloat().coerceAtLeast(1f)
            @Suppress("DEPRECATION")
            val displayH = display.height.toFloat().coerceAtLeast(1f)

            val x = (normX.toFloat() / 65535f) * displayW
            val y = (normY.toFloat() / 65535f) * displayH

            lastMouseX = x
            lastMouseY = y

            // Update software cursor overlay position
            cursor?.let {
                it.x = x
                it.y = y
                if (it.visibility != View.VISIBLE) {
                    it.visibility = View.VISIBLE
                }
            }

            val now = SystemClock.uptimeMillis()
            val leftPressed = (buttonMask and 0x01) != 0
            val wasLeftPressed = (lastButtonMask and 0x01) != 0
            val rightPressed = (buttonMask and 0x04) != 0
            val wasRightPressed = (lastButtonMask and 0x04) != 0

            // 1. Primary: dispatch real MotionEvent (SOURCE_MOUSE)
            try {
                if (wheelDy != 0 || wheelDx != 0) {
                    val pProps = MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_MOUSE
                    }
                    val pCoords = MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        setAxisValue(MotionEvent.AXIS_VSCROLL, wheelDy.toFloat())
                        setAxisValue(MotionEvent.AXIS_HSCROLL, wheelDx.toFloat())
                    }
                    val scrollEvt = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_SCROLL,
                        1, arrayOf(pProps), arrayOf(pCoords),
                        0, buttonMask, 1.0f, 1.0f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0
                    )
                    wv.dispatchGenericMotionEvent(scrollEvt)
                    scrollEvt.recycle()
                } else if (leftPressed && !wasLeftPressed) {
                    downTime = now
                    val downEvt = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                        source = InputDevice.SOURCE_MOUSE
                    }
                    wv.dispatchTouchEvent(downEvt)
                    downEvt.recycle()
                } else if (!leftPressed && wasLeftPressed) {
                    val upEvt = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_UP, x, y, 0).apply {
                        source = InputDevice.SOURCE_MOUSE
                    }
                    wv.dispatchTouchEvent(upEvt)
                    upEvt.recycle()
                } else if (leftPressed) {
                    val moveEvt = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x, y, 0).apply {
                        source = InputDevice.SOURCE_MOUSE
                    }
                    wv.dispatchTouchEvent(moveEvt)
                    moveEvt.recycle()
                } else {
                    val pProps = MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_MOUSE
                    }
                    val pCoords = MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                    }
                    val hoverEvt = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_HOVER_MOVE,
                        1, arrayOf(pProps), arrayOf(pCoords),
                        0, buttonMask, 1.0f, 1.0f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0
                    )
                    wv.dispatchGenericMotionEvent(hoverEvt)
                    hoverEvt.recycle()
                }
            } catch (_: Exception) {}

            // 2. Fallback: evaluateJavascript dispatching synthetic DOM events at document level
            try {
                val domButton = when {
                    leftPressed -> 0
                    rightPressed -> 2
                    (buttonMask and 0x02) != 0 -> 1
                    else -> 0
                }
                val js = StringBuilder()
                js.append("(function() {")
                js.append("var target = document.elementFromPoint($x, $y) || document;")
                if (wheelDy != 0) {
                    val deltaY = wheelDy * -100
                    js.append("target.dispatchEvent(new WheelEvent('wheel', {bubbles: true, cancelable: true, clientX: $x, clientY: $y, deltaY: $deltaY}));")
                } else if (leftPressed && !wasLeftPressed) {
                    js.append("target.dispatchEvent(new MouseEvent('mousedown', {bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 0, buttons: $buttonMask}));")
                } else if (!leftPressed && wasLeftPressed) {
                    js.append("target.dispatchEvent(new MouseEvent('mouseup', {bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 0, buttons: $buttonMask}));")
                    js.append("target.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 0, buttons: $buttonMask}));")
                } else if (rightPressed && !wasRightPressed) {
                    js.append("target.dispatchEvent(new MouseEvent('contextmenu', {bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 2, buttons: $buttonMask}));")
                } else {
                    js.append("target.dispatchEvent(new MouseEvent('mousemove', {bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: $domButton, buttons: $buttonMask}));")
                }
                js.append("})();")
                wv.evaluateJavascript(js.toString(), null)
            } catch (_: Exception) {}

            lastButtonMask = buttonMask
        }
    }

    /**
     * Injects keyboard event: dispatches KeyEvent + DOM KeyboardEvent fallback.
     */
    fun injectKeyEvent(
        keyCode: Int,
        state: Int,
        modifierMask: Int = 0
    ) {
        mainHandler.post {
            val wv = webView ?: return@post

            val androidKeyCode = mapEvdevToAndroidKey(keyCode)
            val action = if (state == 1) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val metaState = mapModifierMaskToMetaState(modifierMask)
            val now = SystemClock.uptimeMillis()

            try {
                val keyEvent = KeyEvent(
                    now, now, action, androidKeyCode, 0, metaState,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
                )
                wv.dispatchKeyEvent(keyEvent)
            } catch (_: Exception) {}

            // DOM fallback for Guacamole canvas
            try {
                val eventType = if (state == 1) "keydown" else "keyup"
                val domKey = mapEvdevToDomKey(keyCode)
                val domCode = mapEvdevToDomCode(keyCode)
                val isCtrl = (modifierMask and 0x02) != 0
                val isShift = (modifierMask and 0x01) != 0
                val isAlt = (modifierMask and 0x04) != 0
                val isMeta = (modifierMask and 0x08) != 0

                val js = """
                    (function() {
                        var e = new KeyboardEvent('$eventType', {
                            key: '$domKey',
                            code: '$domCode',
                            keyCode: $androidKeyCode,
                            which: $androidKeyCode,
                            bubbles: true,
                            cancelable: true,
                            ctrlKey: $isCtrl,
                            shiftKey: $isShift,
                            altKey: $isAlt,
                            metaKey: $isMeta
                        });
                        (document.activeElement || document).dispatchEvent(e);
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }
    }

    private fun mapEvdevToAndroidKey(evdevCode: Int): Int {
        return when (evdevCode) {
            1 -> KeyEvent.KEYCODE_ESCAPE
            2 -> KeyEvent.KEYCODE_1
            3 -> KeyEvent.KEYCODE_2
            4 -> KeyEvent.KEYCODE_3
            5 -> KeyEvent.KEYCODE_4
            6 -> KeyEvent.KEYCODE_5
            7 -> KeyEvent.KEYCODE_6
            8 -> KeyEvent.KEYCODE_7
            9 -> KeyEvent.KEYCODE_8
            10 -> KeyEvent.KEYCODE_9
            11 -> KeyEvent.KEYCODE_0
            14 -> KeyEvent.KEYCODE_DEL
            15 -> KeyEvent.KEYCODE_TAB
            16 -> KeyEvent.KEYCODE_Q
            17 -> KeyEvent.KEYCODE_W
            18 -> KeyEvent.KEYCODE_E
            19 -> KeyEvent.KEYCODE_R
            20 -> KeyEvent.KEYCODE_T
            21 -> KeyEvent.KEYCODE_Y
            22 -> KeyEvent.KEYCODE_U
            23 -> KeyEvent.KEYCODE_I
            24 -> KeyEvent.KEYCODE_O
            25 -> KeyEvent.KEYCODE_P
            28 -> KeyEvent.KEYCODE_ENTER
            29 -> KeyEvent.KEYCODE_CTRL_LEFT
            30 -> KeyEvent.KEYCODE_A
            31 -> KeyEvent.KEYCODE_S
            32 -> KeyEvent.KEYCODE_D
            33 -> KeyEvent.KEYCODE_F
            34 -> KeyEvent.KEYCODE_G
            35 -> KeyEvent.KEYCODE_H
            36 -> KeyEvent.KEYCODE_J
            37 -> KeyEvent.KEYCODE_K
            38 -> KeyEvent.KEYCODE_L
            42 -> KeyEvent.KEYCODE_SHIFT_LEFT
            44 -> KeyEvent.KEYCODE_Z
            45 -> KeyEvent.KEYCODE_X
            46 -> KeyEvent.KEYCODE_C
            47 -> KeyEvent.KEYCODE_V
            48 -> KeyEvent.KEYCODE_B
            49 -> KeyEvent.KEYCODE_N
            50 -> KeyEvent.KEYCODE_M
            54 -> KeyEvent.KEYCODE_SHIFT_RIGHT
            56 -> KeyEvent.KEYCODE_ALT_LEFT
            57 -> KeyEvent.KEYCODE_SPACE
            97 -> KeyEvent.KEYCODE_CTRL_RIGHT
            100 -> KeyEvent.KEYCODE_ALT_RIGHT
            103 -> KeyEvent.KEYCODE_DPAD_UP
            105 -> KeyEvent.KEYCODE_DPAD_LEFT
            106 -> KeyEvent.KEYCODE_DPAD_RIGHT
            108 -> KeyEvent.KEYCODE_DPAD_DOWN
            111 -> KeyEvent.KEYCODE_FORWARD_DEL
            125 -> KeyEvent.KEYCODE_META_LEFT
            126 -> KeyEvent.KEYCODE_META_RIGHT
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun mapEvdevToDomKey(evdevCode: Int): String {
        return when (evdevCode) {
            1 -> "Escape"
            14 -> "Backspace"
            15 -> "Tab"
            28 -> "Enter"
            29, 97 -> "Control"
            42, 54 -> "Shift"
            56, 100 -> "Alt"
            57 -> " "
            103 -> "ArrowUp"
            105 -> "ArrowLeft"
            106 -> "ArrowRight"
            108 -> "ArrowDown"
            111 -> "Delete"
            125, 126 -> "Meta"
            in 2..10 -> "${evdevCode - 1}"
            11 -> "0"
            16 -> "q"
            17 -> "w"
            18 -> "e"
            19 -> "r"
            20 -> "t"
            21 -> "y"
            22 -> "u"
            23 -> "i"
            24 -> "o"
            25 -> "p"
            30 -> "a"
            31 -> "s"
            32 -> "d"
            33 -> "f"
            34 -> "g"
            35 -> "h"
            36 -> "j"
            37 -> "k"
            38 -> "l"
            44 -> "z"
            45 -> "x"
            46 -> "c"
            47 -> "v"
            48 -> "b"
            49 -> "n"
            50 -> "m"
            else -> "Unidentified"
        }
    }

    private fun mapEvdevToDomCode(evdevCode: Int): String {
        return when (evdevCode) {
            1 -> "Escape"
            14 -> "Backspace"
            15 -> "Tab"
            28 -> "Enter"
            29 -> "ControlLeft"
            97 -> "ControlRight"
            42 -> "ShiftLeft"
            54 -> "ShiftRight"
            56 -> "AltLeft"
            100 -> "AltRight"
            57 -> "Space"
            103 -> "ArrowUp"
            105 -> "ArrowLeft"
            106 -> "ArrowRight"
            108 -> "ArrowDown"
            111 -> "Delete"
            125 -> "MetaLeft"
            126 -> "MetaRight"
            in 2..10 -> "Digit${evdevCode - 1}"
            11 -> "Digit0"
            16 -> "KeyQ"
            17 -> "KeyW"
            18 -> "KeyE"
            19 -> "KeyR"
            20 -> "KeyT"
            21 -> "KeyY"
            22 -> "KeyU"
            23 -> "KeyI"
            24 -> "KeyO"
            25 -> "KeyP"
            30 -> "KeyA"
            31 -> "KeyS"
            32 -> "KeyD"
            33 -> "KeyF"
            34 -> "KeyG"
            35 -> "KeyH"
            36 -> "KeyJ"
            37 -> "KeyK"
            38 -> "KeyL"
            44 -> "KeyZ"
            45 -> "KeyX"
            46 -> "KeyC"
            47 -> "KeyV"
            48 -> "KeyB"
            49 -> "KeyN"
            50 -> "KeyM"
            else -> "Unidentified"
        }
    }

    private fun mapModifierMaskToMetaState(mask: Int): Int {
        var meta = 0
        if ((mask and 0x01) != 0) meta = meta or KeyEvent.META_SHIFT_ON
        if ((mask and 0x02) != 0) meta = meta or KeyEvent.META_CTRL_ON
        if ((mask and 0x04) != 0) meta = meta or KeyEvent.META_ALT_ON
        if ((mask and 0x08) != 0) meta = meta or KeyEvent.META_META_ON
        return meta
    }

    override fun dismiss() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            webView?.stopLoading()
            webView?.destroy()
        } catch (_: Exception) {}
        webView = null
        super.dismiss()
    }

    /**
     * Software cursor overlay drawn on top of the Presentation WebView.
     */
    private class CursorOverlayView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        private val cursorPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 32f)
            lineTo(8f, 24f)
            lineTo(16f, 36f)
            lineTo(20f, 34f)
            lineTo(12f, 22f)
            lineTo(22f, 22f)
            close()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(cursorPath, fillPaint)
            canvas.drawPath(cursorPath, strokePaint)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(48, 48)
        }
    }
}
