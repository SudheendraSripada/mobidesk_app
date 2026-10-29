import 'package:flutter_test/flutter_test.dart';
import 'package:mobidesk_app/config/app_config.dart';

void main() {
  test('Demo Mode cannot be enabled unless build explicitly allows it', () async {
    if (AppConfig.allowDemoMode) return;

    await expectLater(
      AppConfig().update(demoMode: true),
      throwsA(isA<StateError>()),
    );
  });
}
