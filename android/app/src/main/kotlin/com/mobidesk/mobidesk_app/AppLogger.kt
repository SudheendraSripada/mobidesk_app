package com.mobidesk.mobidesk_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent ring-buffer file logger (~1 MB cap) under the app's internal files dir.
 * Accessible from both Kotlin services/activities and Dart Flutter via MethodChannel.
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val LOG_FILE_NAME = "mobidesk.log"
    private const val MAX_LOG_SIZE = 1024 * 1024 // 1 MB
    private const val TRIMMED_LOG_SIZE = 750 * 1024 // 750 KB retained after trim

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (logFile == null) {
            val dir = context.applicationContext.filesDir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            logFile = File(dir, LOG_FILE_NAME)
            if (!logFile!!.exists()) {
                try {
                    logFile!!.createNewFile()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create log file: ${e.message}")
                }
            }
            i(TAG, "=== AppLogger initialized at ${logFile!!.absolutePath} ===")
        }
    }

    private fun getFile(context: Context? = null): File? {
        if (logFile == null && context != null) {
            init(context)
        }
        return logFile
    }

    @Synchronized
    private fun writeEntry(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val now = dateFormat.format(Date())
        val sb = StringBuilder()
        sb.append("[").append(now).append("] [").append(level).append("/").append(tag).append("] ")
        sb.append(message)
        if (throwable != null) {
            sb.append("\n").append(Log.getStackTraceString(throwable))
        }
        sb.append("\n")
        val line = sb.toString()

        val file = logFile
        if (file != null) {
            try {
                // Check cap before write
                if (file.length() > MAX_LOG_SIZE) {
                    trimLogFile(file)
                }
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error writing to persistent log: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun trimLogFile(file: File) {
        try {
            val bytes = file.readBytes()
            if (bytes.size > MAX_LOG_SIZE) {
                val dropCount = bytes.size - TRIMMED_LOG_SIZE
                // Search for next newline after dropCount
                var cutIndex = dropCount
                while (cutIndex < bytes.size && bytes[cutIndex] != '\n'.code.toByte()) {
                    cutIndex++
                }
                if (cutIndex < bytes.size) cutIndex++
                val retained = bytes.copyOfRange(cutIndex, bytes.size)
                FileOutputStream(file, false).use { out ->
                    out.write("[LOG ROTATION: trimmed oldest entries]\n".toByteArray(Charsets.UTF_8))
                    out.write(retained)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed trimming log file: ${e.message}")
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeEntry("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeEntry("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeEntry("E", tag, message, throwable)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeEntry("D", tag, message)
    }

    @Synchronized
    fun getLogs(): String {
        val file = logFile ?: return "Log file not initialized."
        return try {
            if (file.exists()) file.readText(Charsets.UTF_8) else "No logs recorded."
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    @Synchronized
    fun clearLogs() {
        val file = logFile ?: return
        try {
            if (file.exists()) {
                file.writeText("")
                i(TAG, "Logs cleared by user request.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing log file: ${e.message}")
        }
    }

    fun shareLogs(activity: Activity) {
        val file = logFile ?: run {
            init(activity)
            logFile
        } ?: return

        try {
            val authority = "${activity.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(activity, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MobiDesk Diagnostics Log")
                putExtra(Intent.EXTRA_STREAM, uri)
                // Also provide preview text in EXTRA_TEXT
                val preview = if (file.length() <= 200_000) file.readText() else "MobiDesk log file attached (${file.length()} bytes)"
                putExtra(Intent.EXTRA_TEXT, preview)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(shareIntent, "Share MobiDesk Logs"))
            i(TAG, "Launched ACTION_SEND for log file: ${file.absolutePath}")
        } catch (e: Exception) {
            e(TAG, "Failed to share logs via ACTION_SEND: ${e.message}", e)
            // Fallback to text-only share without URI
            try {
                val textOnlyIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "MobiDesk Diagnostics Log (Text Fallback)")
                    putExtra(Intent.EXTRA_TEXT, getLogs())
                }
                activity.startActivity(Intent.createChooser(textOnlyIntent, "Share MobiDesk Logs"))
            } catch (e2: Exception) {
                e(TAG, "Fallback text share failed: ${e2.message}", e2)
            }
        }
    }
}
