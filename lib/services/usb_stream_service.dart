import 'package:flutter/services.dart';

class UsbStreamService {
  static const MethodChannel _channel = MethodChannel('com.mobidesk/stream');

  /// Checks if the screen capture service is currently active.
  static Future<bool> isStreaming() async {
    try {
      final res = await _channel.invokeMethod<bool>('isStreaming');
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }

  /// Checks if monitor streaming fell back to screen mirroring.
  static Future<bool> isFallbackActive() async {
    try {
      final res = await _channel.invokeMethod<bool>('isFallbackActive');
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }

  /// Queries USB hardware and accessory status.
  static Future<Map<String, dynamic>> getUsbStatus() async {
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>('getUsbStatus');
      return res ?? {'hasAccessory': false, 'deviceCount': 0, 'isStreaming': false};
    } on MissingPluginException {
      return {'hasAccessory': false, 'deviceCount': 0, 'isStreaming': false};
    } catch (_) {
      return {'hasAccessory': false, 'deviceCount': 0, 'isStreaming': false};
    }
  }

  /// Queries the dock's reported display info (width, height, fps).
  static Future<Map<String, dynamic>> getDockDisplayInfo() async {
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>('getDockDisplayInfo');
      return res ??
          {
            'hasDock': false,
            'hasReceivedInfo': false,
            'width': 1920,
            'height': 1080,
            'fps': 60,
            'isStreaming': false,
            'isFallback': false,
          };
    } on MissingPluginException {
      return {
        'hasDock': false,
        'hasReceivedInfo': false,
        'width': 1920,
        'height': 1080,
        'fps': 60,
        'isStreaming': false,
        'isFallback': false,
      };
    } catch (_) {
      return {
        'hasDock': false,
        'hasReceivedInfo': false,
        'width': 1920,
        'height': 1080,
        'fps': 60,
        'isStreaming': false,
        'isFallback': false,
      };
    }
  }

  /// Launches the native fullscreen landscape Phone Cloud PC activity.
  static Future<bool> startPhoneCloudPc(String sessionUrl) async {
    try {
      final res = await _channel.invokeMethod<bool>(
        'startPhoneCloudPc',
        {'sessionUrl': sessionUrl},
      );
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }

  /// Starts Monitor Mode streaming to the Pi Dock via VirtualDisplay + Presentation.
  static Future<bool> startMonitorStream({
    required String guacUrl,
    int width = 1920,
    int height = 1080,
    int fps = 60,
  }) async {
    try {
      final res = await _channel.invokeMethod<bool>('startMonitorStream', {
        'guacUrl': guacUrl,
        'width': width,
        'height': height,
        'fps': fps,
      });
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }

  /// Starts standard screen projection stream (Phone A sender).
  static Future<bool> startStream() async {
    try {
      final res = await _channel.invokeMethod<bool>('startStream');
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }

  /// Stops any active screen capture stream.
  static Future<bool> stopStream() async {
    try {
      final res = await _channel.invokeMethod<bool>('stopStream');
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }

  /// Launches native ReceiverActivity (Phone B receiver mode).
  static Future<bool> startReceiver() async {
    try {
      final res = await _channel.invokeMethod<bool>('startReceiver');
      return res ?? false;
    } on MissingPluginException {
      return false;
    } catch (_) {
      return false;
    }
  }
}
