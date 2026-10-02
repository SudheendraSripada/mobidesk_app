import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobidesk_app/main.dart';
import 'package:mobidesk_app/screens/developer_tools_screen.dart';

void main() {
  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('com.mobidesk/stream'), (
          MethodCall methodCall,
        ) async {
          if (methodCall.method == 'startStream') {
            return true;
          } else if (methodCall.method == 'stopStream' ||
              methodCall.method == 'stop') {
            return true;
          } else if (methodCall.method == 'isStreaming') {
            return false;
          } else if (methodCall.method == 'getUsbStatus') {
            return {
              'hasAccessory': false,
              'deviceCount': 0,
              'isStreaming': false,
            };
          } else if (methodCall.method == 'getDockDisplayInfo') {
            return {
              'hasDock': false,
              'hasReceivedInfo': false,
              'width': 1920,
              'height': 1080,
              'fps': 60,
              'isStreaming': false,
              'isFallback': false,
            };
          } else if (methodCall.method == 'startReceiver') {
            return true;
          } else if (methodCall.method == 'startPhoneCloudPc') {
            return true;
          } else if (methodCall.method == 'startMonitorStream') {
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
    expect(find.text('Alex Mercer'), findsOneWidget);
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
