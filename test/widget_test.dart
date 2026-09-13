import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mobidesk_app/main.dart';

void main() {
  testWidgets('Home screen shows Sender and Receiver controls and USB setup guidance', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    expect(find.text('Send (Sender)'), findsOneWidget);
    expect(find.text('Receive (Receiver)'), findsOneWidget);
    expect(find.text('USB AOA Screen Streamer'), findsOneWidget);

    // Open USB guide dialog
    await tester.tap(find.byTooltip('USB Connection Guide'));
    await tester.pumpAndSettle();

    expect(find.text('USB AOA 2.0 Setup'), findsOneWidget);
    expect(find.text('Got it'), findsOneWidget);

    await tester.tap(find.text('Got it'));
    await tester.pumpAndSettle();

    expect(find.text('USB AOA 2.0 Setup'), findsNothing);
  });

  testWidgets('Tapping Send (Sender) invokes startStream on MethodChannel', (WidgetTester tester) async {
    final List<MethodCall> log = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.mobidesk/stream'),
      (MethodCall methodCall) async {
        log.add(methodCall);
        if (methodCall.method == 'startStream') {
          return true;
        } else if (methodCall.method == 'isStreaming') {
          return false;
        } else if (methodCall.method == 'getUsbStatus') {
          return {'hasAccessory': false, 'deviceCount': 0, 'isStreaming': false};
        }
        return null;
      },
    );

    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Send (Sender)'));
    await tester.tap(find.text('Send (Sender)'));
    await tester.pumpAndSettle();

    expect(log.any((call) => call.method == 'startStream'), isTrue);
    expect(find.text('Stop Sharing'), findsOneWidget);
    expect(find.text('Screen capture started over USB AOA 2.0'), findsOneWidget);
  });

  testWidgets('Tapping Stop Sharing invokes stop on MethodChannel', (WidgetTester tester) async {
    final List<MethodCall> log = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.mobidesk/stream'),
      (MethodCall methodCall) async {
        log.add(methodCall);
        if (methodCall.method == 'startStream') {
          return true;
        } else if (methodCall.method == 'stop') {
          return true;
        } else if (methodCall.method == 'isStreaming') {
          return false;
        } else if (methodCall.method == 'getUsbStatus') {
          return {'hasAccessory': false, 'deviceCount': 0, 'isStreaming': false};
        }
        return null;
      },
    );

    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    // Start stream
    await tester.ensureVisible(find.text('Send (Sender)'));
    await tester.tap(find.text('Send (Sender)'));
    await tester.pumpAndSettle();
    expect(find.text('Stop Sharing'), findsOneWidget);

    // Stop stream
    await tester.ensureVisible(find.text('Stop Sharing'));
    await tester.tap(find.text('Stop Sharing'));
    await tester.pumpAndSettle();

    expect(log.any((call) => call.method == 'stop'), isTrue);
    expect(find.text('Send (Sender)'), findsOneWidget);
  });

  testWidgets('Tapping Receive (Receiver) invokes startReceiver on MethodChannel', (WidgetTester tester) async {
    final List<MethodCall> log = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.mobidesk/stream'),
      (MethodCall methodCall) async {
        log.add(methodCall);
        if (methodCall.method == 'startReceiver') {
          return true;
        } else if (methodCall.method == 'isStreaming') {
          return false;
        } else if (methodCall.method == 'getUsbStatus') {
          return {'hasAccessory': false, 'deviceCount': 0, 'isStreaming': false};
        }
        return null;
      },
    );

    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Receive (Receiver)'));
    await tester.tap(find.text('Receive (Receiver)'));
    await tester.pumpAndSettle();

    expect(log.any((call) => call.method == 'startReceiver'), isTrue);
  });

  testWidgets('Receive (Receiver) handles platform error by showing SnackBar', (WidgetTester tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.mobidesk/stream'),
      (MethodCall methodCall) async {
        if (methodCall.method == 'startReceiver') {
          throw PlatformException(code: 'USB_HOST_ERROR', message: 'No OTG host support');
        } else if (methodCall.method == 'isStreaming') {
          return false;
        } else if (methodCall.method == 'getUsbStatus') {
          return {'hasAccessory': false, 'deviceCount': 0, 'isStreaming': false};
        }
        return null;
      },
    );

    await tester.pumpWidget(const MyApp());
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Receive (Receiver)'));
    await tester.tap(find.text('Receive (Receiver)'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Unable to start receiver: No OTG host support'), findsOneWidget);
  });
}
