import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Manages encrypted credential and session persistence for silent re-authentication.
class AuthStorage {
  static const _storage = FlutterSecureStorage();
  static const _keyKeepSignedIn = 'mobidesk_keep_signed_in';
  static const _keyUsername = 'mobidesk_saved_username';
  static const _keyPassword = 'mobidesk_saved_password';
  static const _keyAuthToken = 'mobidesk_saved_auth_token';

  static final Map<String, String> _inMemoryFallback = {};

  /// Returns true if the user opted to "Keep me signed in".
  static Future<bool> isKeepSignedIn() async {
    try {
      final val = await _storage
          .read(key: _keyKeepSignedIn)
          .timeout(const Duration(milliseconds: 250));
      return (val ?? _inMemoryFallback[_keyKeepSignedIn]) == 'true';
    } catch (_) {
      return _inMemoryFallback[_keyKeepSignedIn] == 'true';
    }
  }

  /// Stores user credentials securely in Android EncryptedSharedPreferences / KeyStore.
  static Future<void> saveCredentials({
    required String username,
    required String password,
    String? authToken,
    bool keepSignedIn = true,
  }) async {
    _inMemoryFallback[_keyKeepSignedIn] = keepSignedIn.toString();
    if (keepSignedIn) {
      _inMemoryFallback[_keyUsername] = username;
      _inMemoryFallback[_keyPassword] = password;
      if (authToken != null) _inMemoryFallback[_keyAuthToken] = authToken;
    } else {
      _inMemoryFallback.clear();
    }

    try {
      await _storage
          .write(key: _keyKeepSignedIn, value: keepSignedIn.toString())
          .timeout(const Duration(milliseconds: 250));
      if (keepSignedIn) {
        await _storage
            .write(key: _keyUsername, value: username)
            .timeout(const Duration(milliseconds: 250));
        await _storage
            .write(key: _keyPassword, value: password)
            .timeout(const Duration(milliseconds: 250));
        if (authToken != null) {
          await _storage
              .write(key: _keyAuthToken, value: authToken)
              .timeout(const Duration(milliseconds: 250));
        }
      } else {
        await clear();
      }
    } catch (_) {}
  }

  /// Loads saved credentials for silent re-authentication.
  static Future<Map<String, String?>> getCredentials() async {
    try {
      final username = await _storage
          .read(key: _keyUsername)
          .timeout(const Duration(milliseconds: 250));
      final password = await _storage
          .read(key: _keyPassword)
          .timeout(const Duration(milliseconds: 250));
      final authToken = await _storage
          .read(key: _keyAuthToken)
          .timeout(const Duration(milliseconds: 250));
      final keep = await isKeepSignedIn();
      return {
        'username': username ?? _inMemoryFallback[_keyUsername],
        'password': password ?? _inMemoryFallback[_keyPassword],
        'authToken': authToken ?? _inMemoryFallback[_keyAuthToken],
        'keepSignedIn': keep.toString(),
      };
    } catch (_) {
      final keep = _inMemoryFallback[_keyKeepSignedIn] == 'true';
      return {
        'username': _inMemoryFallback[_keyUsername],
        'password': _inMemoryFallback[_keyPassword],
        'authToken': _inMemoryFallback[_keyAuthToken],
        'keepSignedIn': keep.toString(),
      };
    }
  }

  /// Clears stored credentials.
  static Future<void> clear() async {
    _inMemoryFallback.clear();
    try {
      await _storage
          .delete(key: _keyKeepSignedIn)
          .timeout(const Duration(milliseconds: 250));
      await _storage
          .delete(key: _keyUsername)
          .timeout(const Duration(milliseconds: 250));
      await _storage
          .delete(key: _keyPassword)
          .timeout(const Duration(milliseconds: 250));
      await _storage
          .delete(key: _keyAuthToken)
          .timeout(const Duration(milliseconds: 250));
    } catch (_) {}
  }
}
