import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/test/test_flutter_secure_storage_platform.dart';
import 'package:flutter_secure_storage_platform_interface/flutter_secure_storage_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobidesk_app/screens/log_viewer_screen.dart';
import 'package:mobidesk_app/screens/login_screen.dart';
import 'package:mobidesk_app/screens/system_check_screen.dart';
import 'package:mobidesk_app/services/auth_storage.dart';
import 'package:mobidesk_app/services/usb_stream_service.dart';

void main() {
  setUp(() {
    FlutterSecureStoragePlatform.instance = TestFlutterSecureStoragePlatform({});

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('com.mobidesk/stream'), (
          MethodCall methodCall,
        ) async {
          if (methodCall.method == 'getStreamStats') {
            return {
              'fps': 29.5,
              'kbps': 3800,
              'totalFrames': 500,
              'droppedFrames': 2,
              'keyframes': 10,
              'keyframeRequests': 2,
              'width': 1280,
              'height': 720,
              'isStreaming': true,
              'isFallback': false,
              'fallbackNotice': '',
              'fgsType': 8, // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            };
          } else if (methodCall.method == 'getLogs') {
            return '[2026-10-02 12:00:00.000] [I/ScreenCaptureService] MediaCodec initialized\n'
                '[2026-10-02 12:00:05.000] [I/ScreenCaptureService] STREAM STATS: fps=30.0, kbps=4000\n'
                '[2026-10-02 12:00:06.000] [W/Monitor] Minor frame drop\n';
          } else if (methodCall.method == 'clearLogs' ||
              methodCall.method == 'shareLogs' ||
              methodCall.method == 'logMessage' ||
              methodCall.method == 'updatePresentationStatus' ||
              methodCall.method == 'updatePresentationSessionUrl' ||
              methodCall.method == 'clearPendingDockAttach') {
            return true;
          } else if (methodCall.method == 'hasPendingDockAttach' ||
              methodCall.method == 'checkAutoLaunchMonitor') {
            return false;
          } else if (methodCall.method == 'runAllSystemChecks') {
            return {
              'usbAccessory': {'pass': true, 'detail': 'MobiDeskDock attached'},
              'avcEncoder': {'pass': true, 'detail': 'c2.android.avc.encoder (720p: true)'},
              'virtualDisplay': {'pass': true, 'detail': 'Presentation VirtualDisplay dry run passed'},
              'foregroundService': {'pass': true, 'detail': 'FGS: true, CONNECTED_DEVICE: true'},
              'wifiLock': {'pass': true, 'detail': 'Low-latency WifiLock acquired and released'},
              'wakeLock': {'pass': true, 'detail': 'Partial WakeLock acquired and released'},
              'notifications': {'pass': true, 'detail': 'POST_NOTIFICATIONS granted'},
              'batteryOptimization': {'pass': true, 'detail': 'Unrestricted execution active'},
              'storage': {'pass': true, 'detail': 'App filesDir writable (1024 MB free)'},
              'presentationDisplay': {'pass': true, 'detail': 'DisplayManager active'},
              'networkConnectivity': {'pass': true, 'detail': 'Connected (validated: true)'},
            };
          } else if (methodCall.method == 'runSystemCheck') {
            return {'pass': true, 'detail': 'Single check passed'};
          }
          return null;
        });
  });

  test('AuthStorage securely persists and retrieves credentials', () async {
    await AuthStorage.clear();
    expect(await AuthStorage.isKeepSignedIn(), isFalse);

    await AuthStorage.saveCredentials(
      username: 'student@mobidesk.edu',
      password: 'SecretPassword123!',
      authToken: 'TOKEN_XYZ_999',
      keepSignedIn: true,
    );

    expect(await AuthStorage.isKeepSignedIn(), isTrue);
    final creds = await AuthStorage.getCredentials();
    expect(creds['username'], equals('student@mobidesk.edu'));
    expect(creds['password'], equals('SecretPassword123!'));
    expect(creds['authToken'], equals('TOKEN_XYZ_999'));

    await AuthStorage.clear();
    expect(await AuthStorage.isKeepSignedIn(), isFalse);
  });

  test('UsbStreamService parses getStreamStats accurately', () async {
    final stats = await UsbStreamService.getStreamStats();
    expect(stats['fps'], equals(29.5));
    expect(stats['kbps'], equals(3800));
    expect(stats['totalFrames'], equals(500));
    expect(stats['droppedFrames'], equals(2));
    expect(stats['keyframes'], equals(10));
    expect(stats['isStreaming'], isTrue);
  });

  testWidgets('SystemCheckScreen renders all 11 prerequisite checks and copies report', (
    WidgetTester tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        home: SystemCheckScreen(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('System Check'), findsOneWidget);
    expect(find.text('Hardware & Service Prerequisites'), findsOneWidget);
    expect(find.text('11 of 11 checks passed'), findsOneWidget);

    // Verify all 11 checks are rendered
    expect(find.text('USB AOA Accessory Attached'), findsOneWidget);
    expect(find.text('MediaCodec H.264/AVC Hardware Encoder'), findsOneWidget);
    expect(find.text('VirtualDisplay Subsystem (1280x720)'), findsOneWidget);
    expect(find.text('Foreground Service (connectedDevice / FGS)'), findsOneWidget);
    expect(find.text('Low-Latency WifiLock'), findsOneWidget);
    expect(find.text('Partial WakeLock'), findsOneWidget);
    expect(find.text('Post Notifications Permission'), findsOneWidget);
    expect(find.text('Battery Optimization Exemption'), findsOneWidget);
    expect(find.text('Internal Log Storage (~1 MB Ring Buffer)'), findsOneWidget);
    expect(find.text('Presentation Display Manager'), findsOneWidget);
    expect(find.text('Network & Internet Connectivity'), findsOneWidget);

    // Verify Copy report button
    expect(find.text('Copy report'), findsOneWidget);
    await tester.tap(find.text('Copy report'));
    await tester.pump();
    expect(find.textContaining('Diagnostic report copied to clipboard'), findsOneWidget);
  });

  testWidgets('LogViewerScreen renders logs and filters entries', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: LogViewerScreen(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Diagnostic Logs'), findsOneWidget);
    expect(find.textContaining('STREAM STATS'), findsOneWidget);

    // Filter for STREAM STATS
    await tester.enterText(find.byType(TextField), 'STATS');
    await tester.pumpAndSettle();
    expect(find.textContaining('STREAM STATS: fps=30.0'), findsOneWidget);

    // Filter for non-existent
    await tester.enterText(find.byType(TextField), 'NONEXISTENT_KEYWORD');
    await tester.pumpAndSettle();
    expect(find.textContaining('No lines match'), findsOneWidget);
  });

  testWidgets('LoginScreen displays Keep me signed in and responds to toggle', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: LoginScreen(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Keep me signed in'), findsOneWidget);
    final checkboxFinder = find.byType(Checkbox);
    expect(checkboxFinder, findsOneWidget);

    // Tap checkbox to toggle
    await tester.tap(checkboxFinder);
    await tester.pumpAndSettle();
  });
}
