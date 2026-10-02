package com.mobidesk.mobidesk_app

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 *
 * Implements:
 * - Software cursor overlay View tracked from injected mouse coordinates.
 * - Primary input injection: dispatch real MotionEvent (SOURCE_MOUSE) and KeyEvent to WebView.
 * - Fallback input injection: evaluateJavascript dispatching synthetic DOM events at document level.
 * - Full Linux evdev key mapping -> Android KeyEvent and DOM keys.
 */
class MobiDeskPresentation(
    outerContext: Context,
    display: Display,
    private val sessionUrl: String
) : Presentation(outerContext, display) {

    private var webView: WebView? = null
    private var cursorView: CursorOverlayView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastMouseX = 0f
    private var lastMouseY = 0f
    private var lastButtonMask = 0
    private var downTime = SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure Presentation window covers the entire virtual display without borders
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
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(sessionUrl)
        }

        // Software cursor overlay View
        val cursor = CursorOverlayView(displayContext).apply {
            visibility = View.VISIBLE
            elevation = 100f
            translationZ = 100f
        }

        root.addView(wv)
        root.addView(cursor)
        setContentView(root)

        webView = wv
        cursorView = cursor
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

            val displayW = display.width.toFloat().coerceAtLeast(1f)
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
            val isDown = state != 0
            val mapping = EvdevKeyMapper.map(keyCode)

            // 1. Primary: dispatch native KeyEvent to WebView
            try {
                val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                val metaState = computeMetaState(modifierMask)
                val keyEvent = KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    action,
                    mapping.androidKeyCode,
                    0,
                    metaState
                )
                wv.dispatchKeyEvent(keyEvent)
            } catch (_: Exception) {}

            // 2. Fallback: dispatch synthetic DOM KeyboardEvent at document level
            try {
                val eventType = if (isDown) "keydown" else "keyup"
                val shiftKey = (modifierMask and 0x01) != 0
                val ctrlKey = (modifierMask and 0x02) != 0
                val altKey = (modifierMask and 0x04) != 0
                val metaKey = (modifierMask and 0x08) != 0

                val js = """
                    (function() {
                        var target = document.activeElement || document;
                        var evt = new KeyboardEvent('$eventType', {
                            key: '${mapping.domKey}',
                            code: '${mapping.domCode}',
                            keyCode: ${mapping.domKeyCode},
                            which: ${mapping.domKeyCode},
                            shiftKey: $shiftKey,
                            ctrlKey: $ctrlKey,
                            altKey: $altKey,
                            metaKey: $metaKey,
                            bubbles: true,
                            cancelable: true
                        });
                        target.dispatchEvent(evt);
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }
    }

    private fun computeMetaState(modifierMask: Int): Int {
        var meta = 0
        if ((modifierMask and 0x01) != 0) meta = meta or KeyEvent.META_SHIFT_ON
        if ((modifierMask and 0x02) != 0) meta = meta or KeyEvent.META_CTRL_ON
        if ((modifierMask and 0x04) != 0) meta = meta or KeyEvent.META_ALT_ON
        if ((modifierMask and 0x08) != 0) meta = meta or KeyEvent.META_META_ON
        return meta
    }

    override fun onDetachedFromWindow() {
        try {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
        } catch (_: Exception) {}
        webView = null
        cursorView = null
        super.onDetachedFromWindow()
    }
}

/**
 * Lightweight software cursor overlay View drawn directly on top of the Presentation WebView.
 */
class CursorOverlayView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 26f)
        lineTo(6f, 20f)
        lineTo(11f, 30f)
        lineTo(15f, 28f)
        lineTo(10f, 18f)
        lineTo(18f, 18f)
        close()
    }

    init {
        layoutParams = FrameLayout.LayoutParams(36, 36)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }
}

/**
 * Maps Linux evdev key codes to Android KeyEvent codes and DOM KeyboardEvent keys.
 */
object EvdevKeyMapper {
    data class KeyMapping(
        val androidKeyCode: Int,
        val domKey: String,
        val domCode: String,
        val domKeyCode: Int
    )

    fun map(evdevCode: Int): KeyMapping {
        return when (evdevCode) {
            1 -> KeyMapping(KeyEvent.KEYCODE_ESCAPE, "Escape", "Escape", 27)
            2 -> KeyMapping(KeyEvent.KEYCODE_1, "1", "Digit1", 49)
            3 -> KeyMapping(KeyEvent.KEYCODE_2, "2", "Digit2", 50)
            4 -> KeyMapping(KeyEvent.KEYCODE_3, "3", "Digit3", 51)
            5 -> KeyMapping(KeyEvent.KEYCODE_4, "4", "Digit4", 52)
            6 -> KeyMapping(KeyEvent.KEYCODE_5, "5", "Digit5", 53)
            7 -> KeyMapping(KeyEvent.KEYCODE_6, "6", "Digit6", 54)
            8 -> KeyMapping(KeyEvent.KEYCODE_7, "7", "Digit7", 55)
            9 -> KeyMapping(KeyEvent.KEYCODE_8, "8", "Digit8", 56)
            10 -> KeyMapping(KeyEvent.KEYCODE_9, "9", "Digit9", 57)
            11 -> KeyMapping(KeyEvent.KEYCODE_0, "0", "Digit0", 48)
            14 -> KeyMapping(KeyEvent.KEYCODE_DEL, "Backspace", "Backspace", 8)
            15 -> KeyMapping(KeyEvent.KEYCODE_TAB, "Tab", "Tab", 9)
            16 -> KeyMapping(KeyEvent.KEYCODE_Q, "q", "KeyQ", 81)
            17 -> KeyMapping(KeyEvent.KEYCODE_W, "w", "KeyW", 87)
            18 -> KeyMapping(KeyEvent.KEYCODE_E, "e", "KeyE", 69)
            19 -> KeyMapping(KeyEvent.KEYCODE_R, "r", "KeyR", 82)
            20 -> KeyMapping(KeyEvent.KEYCODE_T, "t", "KeyT", 84)
            21 -> KeyMapping(KeyEvent.KEYCODE_Y, "y", "KeyY", 89)
            22 -> KeyMapping(KeyEvent.KEYCODE_U, "u", "KeyU", 85)
            23 -> KeyMapping(KeyEvent.KEYCODE_I, "i", "KeyI", 73)
            24 -> KeyMapping(KeyEvent.KEYCODE_O, "o", "KeyO", 79)
            25 -> KeyMapping(KeyEvent.KEYCODE_P, "p", "KeyP", 80)
            28 -> KeyMapping(KeyEvent.KEYCODE_ENTER, "Enter", "Enter", 13)
            29 -> KeyMapping(KeyEvent.KEYCODE_CTRL_LEFT, "Control", "ControlLeft", 17)
            30 -> KeyMapping(KeyEvent.KEYCODE_A, "a", "KeyA", 65)
            31 -> KeyMapping(KeyEvent.KEYCODE_S, "s", "KeyS", 83)
            32 -> KeyMapping(KeyEvent.KEYCODE_D, "d", "KeyD", 68)
            33 -> KeyMapping(KeyEvent.KEYCODE_F, "f", "KeyF", 70)
            34 -> KeyMapping(KeyEvent.KEYCODE_G, "g", "KeyG", 71)
            35 -> KeyMapping(KeyEvent.KEYCODE_H, "h", "KeyH", 72)
            36 -> KeyMapping(KeyEvent.KEYCODE_J, "j", "KeyJ", 74)
            37 -> KeyMapping(KeyEvent.KEYCODE_K, "k", "KeyK", 75)
            38 -> KeyMapping(KeyEvent.KEYCODE_L, "l", "KeyL", 76)
            42 -> KeyMapping(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", "ShiftLeft", 16)
            44 -> KeyMapping(KeyEvent.KEYCODE_Z, "z", "KeyZ", 90)
            45 -> KeyMapping(KeyEvent.KEYCODE_X, "x", "KeyX", 88)
            46 -> KeyMapping(KeyEvent.KEYCODE_C, "c", "KeyC", 67)
            47 -> KeyMapping(KeyEvent.KEYCODE_V, "v", "KeyV", 86)
            48 -> KeyMapping(KeyEvent.KEYCODE_B, "b", "KeyB", 66)
            49 -> KeyMapping(KeyEvent.KEYCODE_N, "n", "KeyN", 78)
            50 -> KeyMapping(KeyEvent.KEYCODE_M, "m", "KeyM", 77)
            54 -> KeyMapping(KeyEvent.KEYCODE_SHIFT_RIGHT, "Shift", "ShiftRight", 16)
            56 -> KeyMapping(KeyEvent.KEYCODE_ALT_LEFT, "Alt", "AltLeft", 18)
            57 -> KeyMapping(KeyEvent.KEYCODE_SPACE, " ", "Space", 32)
            58 -> KeyMapping(KeyEvent.KEYCODE_CAPS_LOCK, "CapsLock", "CapsLock", 20)
            59 -> KeyMapping(KeyEvent.KEYCODE_F1, "F1", "F1", 112)
            60 -> KeyMapping(KeyEvent.KEYCODE_F2, "F2", "F2", 113)
            61 -> KeyMapping(KeyEvent.KEYCODE_F3, "F3", "F3", 114)
            62 -> KeyMapping(KeyEvent.KEYCODE_F4, "F4", "F4", 115)
            63 -> KeyMapping(KeyEvent.KEYCODE_F5, "F5", "F5", 116)
            64 -> KeyMapping(KeyEvent.KEYCODE_F6, "F6", "F6", 117)
            65 -> KeyMapping(KeyEvent.KEYCODE_F7, "F7", "F7", 118)
            66 -> KeyMapping(KeyEvent.KEYCODE_F8, "F8", "F8", 119)
            67 -> KeyMapping(KeyEvent.KEYCODE_F9, "F9", "F9", 120)
            68 -> KeyMapping(KeyEvent.KEYCODE_F10, "F10", "F10", 121)
            87 -> KeyMapping(KeyEvent.KEYCODE_F11, "F11", "F11", 122)
            88 -> KeyMapping(KeyEvent.KEYCODE_F12, "F12", "F12", 123)
            97 -> KeyMapping(KeyEvent.KEYCODE_CTRL_RIGHT, "Control", "ControlRight", 17)
            100 -> KeyMapping(KeyEvent.KEYCODE_ALT_RIGHT, "Alt", "AltRight", 18)
            103 -> KeyMapping(KeyEvent.KEYCODE_DPAD_UP, "ArrowUp", "ArrowUp", 38)
            105 -> KeyMapping(KeyEvent.KEYCODE_DPAD_LEFT, "ArrowLeft", "ArrowLeft", 37)
            106 -> KeyMapping(KeyEvent.KEYCODE_DPAD_RIGHT, "ArrowRight", "ArrowRight", 39)
            108 -> KeyMapping(KeyEvent.KEYCODE_DPAD_DOWN, "ArrowDown", "ArrowDown", 40)
            111 -> KeyMapping(KeyEvent.KEYCODE_FORWARD_DEL, "Delete", "Delete", 46)
            125 -> KeyMapping(KeyEvent.KEYCODE_META_LEFT, "Meta", "MetaLeft", 91)
            126 -> KeyMapping(KeyEvent.KEYCODE_META_RIGHT, "Meta", "MetaRight", 92)
            else -> {
                // Direct Android keyCode mappings
                when (evdevCode) {
                    KeyEvent.KEYCODE_ENTER -> KeyMapping(KeyEvent.KEYCODE_ENTER, "Enter", "Enter", 13)
                    KeyEvent.KEYCODE_ESCAPE -> KeyMapping(KeyEvent.KEYCODE_ESCAPE, "Escape", "Escape", 27)
                    KeyEvent.KEYCODE_TAB -> KeyMapping(KeyEvent.KEYCODE_TAB, "Tab", "Tab", 9)
                    else -> KeyMapping(KeyEvent.KEYCODE_UNKNOWN, "Unidentified", "Unidentified", 0)
                }
            }
        }
    }
}
