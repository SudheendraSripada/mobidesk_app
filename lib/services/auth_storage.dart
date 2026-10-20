import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Stores the student's opt-in sign-in details in Android secure storage.
///
/// Secure-storage operations are awaited without an artificially short timeout:
/// a timeout followed by a swallowed exception can make "Keep me signed in"
/// appear enabled even though the values were never persisted.
class AuthStorage {
  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(),
  );
  static const _keyKeepSignedIn = 'mobidesk_keep_signed_in';
  static const _keyUsername = 'mobidesk_saved_username';
  static const _keyPassword = 'mobidesk_saved_password';
  static const _keyAuthToken = 'mobidesk_saved_auth_token';

  /// Returns true only when the preference was persisted or the storage
  /// operation has not yet completed in this process.
  static Future<bool> isKeepSignedIn() async {
    try {
      final value = await _storage.read(key: _keyKeepSignedIn);
      return value == 'true';
    } catch (_) {
      return false;
    }
  }

  /// Persists the opt-in credentials in the platform secure-storage backend.
  /// Errors are surfaced to the caller instead of silently reporting success.
  static Future<void> saveCredentials({
    required String username,
    required String password,
    String? authToken,
    bool keepSignedIn = true,
  }) async {
    if (!keepSignedIn) {
      await clear();
      return;
    }

    await _storage.write(key: _keyUsername, value: username);
    await _storage.write(key: _keyPassword, value: password);
    if (authToken != null && authToken.isNotEmpty) {
      await _storage.write(key: _keyAuthToken, value: authToken);
    }
    await _storage.write(key: _keyKeepSignedIn, value: 'true');
  }

  /// Loads the saved values. A failed read is treated as no saved session,
  /// rather than relying on an in-memory cache that disappears on app restart.
  static Future<Map<String, String?>> getCredentials() async {
    try {
      final keep = await _storage.read(key: _keyKeepSignedIn) == 'true';
      if (!keep) {
        return {
          'username': null,
          'password': null,
          'authToken': null,
          'keepSignedIn': 'false',
        };
      }
      final username = await _storage.read(key: _keyUsername);
      final password = await _storage.read(key: _keyPassword);
      final authToken = await _storage.read(key: _keyAuthToken);
      return {
        'username': username,
        'password': password,
        'authToken': authToken,
        'keepSignedIn': 'true',
      };
    } catch (_) {
      return {
        'username': null,
        'password': null,
        'authToken': null,
        'keepSignedIn': 'false',
      };
    }
  }

  /// Removes persisted credentials and tokens.
  static Future<void> clear() async {
    await _storage.delete(key: _keyKeepSignedIn);
    await _storage.delete(key: _keyUsername);
    await _storage.delete(key: _keyPassword);
    await _storage.delete(key: _keyAuthToken);
  }
}
