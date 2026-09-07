// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mobidesk_app/main.dart';

void main() {
  testWidgets('Home screen shows host and client actions', (WidgetTester tester) async {
    // Build our app and trigger a frame.
    await tester.pumpWidget(const MyApp());

    expect(find.text('Send (Host)'), findsOneWidget);
    expect(find.text('Receive (Client)'), findsOneWidget);

    await tester.tap(find.text('Send (Host)'));
    await tester.pump();

    expect(find.text('Turn on USB tethering to send the video output'), findsOneWidget);

    await tester.tap(find.text('Open Settings'));
    await tester.pump();

    expect(find.text('Open Settings'), findsNothing);
  });

  testWidgets('Tapping Receive (Client) invokes startReceiver on MethodChannel', (WidgetTester tester) async {
    final List<MethodCall> log = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.mobidesk/stream'),
      (MethodCall methodCall) async {
        log.add(methodCall);
        if (methodCall.method == 'startReceiver') {
          return true;
        }
        return null;
      },
    );

    await tester.pumpWidget(const MyApp());
    await tester.tap(find.text('Receive (Client)'));
    await tester.pumpAndSettle();

    expect(log, hasLength(1));
    expect(log.first.method, equals('startReceiver'));
  });

  testWidgets('Receive (Client) handles platform error by showing SnackBar', (WidgetTester tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.mobidesk/stream'),
      (MethodCall methodCall) async {
        if (methodCall.method == 'startReceiver') {
          throw PlatformException(code: 'RECEIVER_ERROR', message: 'Socket connection refused');
        }
        return null;
      },
    );

    await tester.pumpWidget(const MyApp());
    await tester.tap(find.text('Receive (Client)'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Unable to start receiver: Socket connection refused'), findsOneWidget);
  });
}
