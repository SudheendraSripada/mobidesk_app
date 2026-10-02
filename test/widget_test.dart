import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobidesk_app/main.dart';
import 'package:mobidesk_app/screens/developer_tools_screen.dart';

void main() {
  var mockStreaming = false;

  setUp(() {
    mockStreaming = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('com.mobidesk/stream'), (
          MethodCall methodCall,
        ) async {
          if (methodCall.method == 'startStream' || methodCall.method == 'startMonitorStream') {
            mockStreaming = true;
            return true;
          } else if (methodCall.method == 'stopStream' ||
              methodCall.method == 'stop') {
            mockStreaming = false;
            return true;
          } else if (methodCall.method == 'isStreaming') {
            return mockStreaming;
          } else if (methodCall.method == 'getUsbStatus') {
            return {
              'hasAccessory': false,
              'deviceCount': 0,
              'isStreaming': mockStreaming,
            };
          } else if (methodCall.method == 'getDockDisplayInfo') {
            return {
              'hasDock': false,
              'hasReceivedInfo': false,
              'width': 1920,
              'height': 1080,
              'fps': 60,
              'isStreaming': mockStreaming,
              'isFallback': false,
            };
          } else if (methodCall.method == 'startReceiver') {
            return true;
          } else if (methodCall.method == 'startPhoneCloudPc') {
            return true;
          }
          return null;
        });
  });

  testWidgets('App launches with LoginScreen and displays branding', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    expect(find.text('MobiDesk'), findsOneWidget);
    expect(find.text('Welcome to MobiDesk'), findsOneWidget);
    expect(find.text('Sign In'), findsOneWidget);
    expect(find.text('Quick Demo Login'), findsOneWidget);
  });

  testWidgets('Long-pressing MobiDesk logo opens Developer Tools', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    // Long press the logo title in AppBar
    await tester.longPress(find.text('MobiDesk'));
    await tester.pumpAndSettle();

    expect(find.text('Developer Tools'), findsOneWidget);
    expect(find.text('Send (Sender)'), findsOneWidget);
    expect(find.text('Receive (Receiver)'), findsOneWidget);
  });

  testWidgets('Quick Demo Login navigates to Dashboard with Cloud PC options', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Quick Demo Login'));
    await tester.pumpAndSettle();

    // Verify Dashboard student profile
    expect(find.textContaining('Alex Mercer'), findsWidgets);
    expect(find.text('Roll No: CS-2024-042'), findsOneWidget);

    // Verify Cloud PC section
    expect(find.text('Assigned Cloud PC'), findsOneWidget);
    expect(find.text('Phone Mode'), findsOneWidget);
    expect(find.text('Monitor Mode'), findsOneWidget);

    // Verify Academic cards
    expect(find.text('Attendance'), findsOneWidget);
    expect(find.text('Upcoming Quizzes & Labs'), findsOneWidget);
    expect(find.text('Academics Overview'), findsOneWidget);
  });

  testWidgets('Tapping Phone Mode opens PhoneCloudPcScreen and delegates to fullscreen activity', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Quick Demo Login'));
    await tester.pumpAndSettle();

    // Tap Phone Mode
    await tester.tap(find.text('Phone Mode'));
    await tester.pumpAndSettle();

    // Verify Phone Mode Screen is displayed with connection info and actions
    expect(find.textContaining('Windows 11'), findsWidgets);
    expect(find.text('Open Fullscreen Cloud PC'), findsOneWidget);
    expect(find.text('Back to Dashboard'), findsOneWidget);
    expect(find.textContaining('Session:'), findsOneWidget);

    // Test clicking Open Fullscreen Cloud PC
    await tester.tap(find.text('Open Fullscreen Cloud PC'));
    await tester.pumpAndSettle();
    expect(find.text('Launching fullscreen Cloud PC session...'), findsOneWidget);

    // Return to dashboard via Back to Dashboard button
    await tester.tap(find.text('Back to Dashboard'));
    await tester.pumpAndSettle();
    expect(find.text('Assigned Cloud PC'), findsOneWidget);
  });

  testWidgets('Dashboard renders cleanly on compact 360x640 phone screen without overflow', (
    WidgetTester tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Quick Demo Login'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Alex Mercer'), findsWidgets);
    expect(find.text('Assigned Cloud PC'), findsOneWidget);
    expect(find.text('Phone Mode'), findsOneWidget);
    expect(find.text('Monitor Mode'), findsOneWidget);
    expect(find.text('Attendance'), findsOneWidget);
  });

  testWidgets('Dashboard renders cleanly on standard 412x915 phone screen', (
    WidgetTester tester,
  ) async {
    tester.view.physicalSize = const Size(412, 915);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Quick Demo Login'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Alex Mercer'), findsWidgets);
    expect(find.text('Assigned Cloud PC'), findsOneWidget);
    expect(find.text('Phone Mode'), findsOneWidget);
    expect(find.text('Monitor Mode'), findsOneWidget);
    expect(find.text('Attendance'), findsOneWidget);
  });

  testWidgets('Tapping Monitor Mode guides through dock detection and streaming', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Quick Demo Login'));
    await tester.pumpAndSettle();

    // Tap Monitor Mode
    await tester.tap(find.text('Monitor Mode'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));

    // Verify Connect Dock screen
    expect(find.text('Connect MobiDesk Dock'), findsOneWidget);
    expect(find.text('Simulate Dock Detected (Demo)'), findsOneWidget);

    // Trigger simulated dock detection
    await tester.tap(find.text('Simulate Dock Detected (Demo)'));
    await tester.pump();

    // Verify resolution reading state
    expect(find.text('Dock Detected'), findsOneWidget);
    expect(find.text('Reading monitor resolution from HDMI EDID...'), findsOneWidget);

    // Complete simulated delay and start stream
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.pump(const Duration(milliseconds: 500));

    // Verify streaming active state with exact required text
    expect(find.text('Connected, started streaming'), findsOneWidget);
    expect(find.text('Resolution: 1920x1080 @ 60 FPS (Native HDMI)'), findsOneWidget);
    expect(find.text('Stop Streaming / Disconnect'), findsOneWidget);

    // Stop streaming
    await tester.ensureVisible(find.text('Stop Streaming / Disconnect'));
    await tester.tap(find.text('Stop Streaming / Disconnect'));
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('Connect MobiDesk Dock'), findsOneWidget);
  });

  testWidgets('DeveloperToolsScreen standalone execution and controls', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const MaterialApp(home: DeveloperToolsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('Send (Sender)'), findsOneWidget);
    expect(find.text('Receive (Receiver)'), findsOneWidget);
    expect(find.text('USB AOA Screen Streamer'), findsOneWidget);

    // Test sending screen stream
    await tester.tap(find.text('Send (Sender)'));
    await tester.pumpAndSettle();

    expect(find.text('Stop Sharing'), findsOneWidget);

    // Stop stream
    await tester.tap(find.text('Stop Sharing'));
    await tester.pumpAndSettle();

    expect(find.text('Send (Sender)'), findsOneWidget);
  });
}
