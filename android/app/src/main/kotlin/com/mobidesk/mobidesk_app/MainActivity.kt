package com.mobidesk.mobidesk_app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.mobidesk/stream"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    // Placeholder for native screen capture initialization
                    result.success(null)
                }
                "stop" -> {
                    // Placeholder for stopping screen capture
                    result.success(null)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }
}
