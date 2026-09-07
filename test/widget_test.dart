// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

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
}
