import 'package:flutter/services.dart';

/// Dart service providing access to the native persistent ring-buffer AppLogger.
class AppLogger {
  static const MethodChannel _channel = MethodChannel('com.mobidesk/stream');

  /// Logs an informational message.
  static Future<void> i(String tag, String message) async {
    try {
      await _channel.invokeMethod('logMessage', {
        'level': 'I',
        'tag': tag,
        'message': message,
      });
    } catch (_) {}
  }

  /// Logs a warning message.
  static Future<void> w(String tag, String message) async {
    try {
      await _channel.invokeMethod('logMessage', {
        'level': 'W',
        'tag': tag,
        'message': message,
      });
    } catch (_) {}
  }

  /// Logs an error message.
  static Future<void> e(String tag, String message) async {
    try {
      await _channel.invokeMethod('logMessage', {
        'level': 'E',
        'tag': tag,
        'message': message,
      });
    } catch (_) {}
  }

  /// Logs a debug message.
  static Future<void> d(String tag, String message) async {
    try {
      await _channel.invokeMethod('logMessage', {
        'level': 'D',
        'tag': tag,
        'message': message,
      });
    } catch (_) {}
  }

  /// Retrieves the current contents of the persistent log file (~1MB ring buffer).
  static Future<String> getLogs() async {
    try {
      final logs = await _channel.invokeMethod<String>('getLogs');
      return logs ?? 'No logs recorded.';
    } on MissingPluginException {
      return 'MissingPluginException: Native logging unavailable.';
    } catch (e) {
      return 'Error retrieving logs: $e';
    }
  }

  /// Clears the persistent log file.
  static Future<bool> clearLogs() async {
    try {
      final res = await _channel.invokeMethod<bool>('clearLogs');
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }

  /// Initiates an Android ACTION_SEND share intent to share the log file via FileProvider.
  static Future<bool> shareLogs() async {
    try {
      final res = await _channel.invokeMethod<bool>('shareLogs');
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }
}
