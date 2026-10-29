import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Centralized configuration management for MobiDesk.
///
/// Reads configuration from local storage or defaults, ensuring no crashes occur
/// if Supabase or Guacamole server settings are not yet entered.
class AppConfig {
  static const allowDemoMode = bool.fromEnvironment('ALLOW_DEMO_MODE', defaultValue: false);
  static const allowServerSetup = bool.fromEnvironment('ALLOW_SERVER_SETUP', defaultValue: false);
  static const _supabaseUrlKey = 'config.supabaseUrl';
  static const _supabaseAnonKeyKey = 'config.supabaseAnonKey';
  static const _guacamoleBaseUrlKey = 'config.guacamoleBaseUrl';
  static const _demoModeKey = 'config.demoMode';
  static const _storage = FlutterSecureStorage();

  static final AppConfig _instance = AppConfig._internal();
  factory AppConfig() => _instance;
  AppConfig._internal() {
    _loadFromEnvironment();
  }

  String supabaseUrl = '';
  String supabaseAnonKey = '';
  String guacamoleBaseUrl = 'http://10.0.2.2:8080/guacamole';
  String cloudPcApiBaseUrl = '';

  // Production is fail-closed by default. Demo mode must be explicitly enabled.
  bool isDemoMode = false;

  Future<void> load() async {
    try {
      final values = await _storage.readAll();
      final storedSupabaseUrl = values[_supabaseUrlKey];
      final storedSupabaseAnonKey = values[_supabaseAnonKeyKey];
      final storedGuacamoleBaseUrl = values[_guacamoleBaseUrlKey];
      final storedDemoMode = values[_demoModeKey];

      const envSupabaseUrl = String.fromEnvironment('SUPABASE_URL');
      const envSupabaseKey = String.fromEnvironment('SUPABASE_ANON_KEY');
      const envGuacamoleUrl = String.fromEnvironment('GUACAMOLE_BASE_URL');

      if (storedSupabaseUrl != null && envSupabaseUrl.isEmpty) {
        supabaseUrl = normalizeUrl(storedSupabaseUrl);
      }
      if (storedSupabaseAnonKey != null && envSupabaseKey.isEmpty) {
        supabaseAnonKey = storedSupabaseAnonKey.trim();
      }
      if (storedGuacamoleBaseUrl != null && envGuacamoleUrl.isEmpty) {
        guacamoleBaseUrl = normalizeUrl(storedGuacamoleBaseUrl);
      }
      isDemoMode = allowDemoMode && storedDemoMode == 'true';
    } catch (_) {
      // Keep environment-provided values and defaults if secure storage is unavailable.
    }
  }

  static String normalizeUrl(String value) {
    var url = value.trim();
    while (url.endsWith('/')) {
      url = url.substring(0, url.length - 1);
    }
    return url;
  }

  static String? validateGuacamoleUrl(String value) {
    final normalized = normalizeUrl(value);
    final uri = Uri.tryParse(normalized);
    if (normalized.isEmpty ||
        uri == null ||
        !uri.isAbsolute ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        (uri.scheme != 'http' && uri.scheme != 'https')) {
      return 'Enter an absolute Guacamole URL using HTTP or HTTPS with a valid host.';
    }
    return null;
  }

  void _loadFromEnvironment() {
    const envUrl = String.fromEnvironment('SUPABASE_URL');
    const envKey = String.fromEnvironment('SUPABASE_ANON_KEY');
    const envGuac = String.fromEnvironment('GUACAMOLE_BASE_URL');
    const envCloudApi = String.fromEnvironment('CLOUD_PC_API_BASE_URL');
    if (envUrl.isNotEmpty) {
      supabaseUrl = normalizeUrl(envUrl);
    }
    if (envKey.isNotEmpty) supabaseAnonKey = envKey.trim();
    if (envGuac.isNotEmpty) {
      guacamoleBaseUrl = normalizeUrl(envGuac);
    }
    if (envCloudApi.isNotEmpty) {
      cloudPcApiBaseUrl = normalizeUrl(envCloudApi);
    }
  }

  bool get isSupabaseConfigured =>
      supabaseUrl.trim().isNotEmpty &&
      supabaseAnonKey.trim().isNotEmpty &&
      !supabaseUrl.contains('your-project-id');

  Future<bool> update({
    String? newSupabaseUrl,
    String? newSupabaseAnonKey,
    String? newGuacamoleBaseUrl,
    bool? demoMode,
  }) async {
    final nextSupabaseUrl = newSupabaseUrl == null
        ? supabaseUrl
        : normalizeUrl(newSupabaseUrl);
    final nextSupabaseAnonKey = newSupabaseAnonKey?.trim() ?? supabaseAnonKey;
    final nextGuacamoleBaseUrl = newGuacamoleBaseUrl == null
        ? guacamoleBaseUrl
        : normalizeUrl(newGuacamoleBaseUrl);
    final nextDemoMode = demoMode ?? isDemoMode;

    // Demo identities are only permitted in builds that explicitly opt in.
    // This also protects developer setup builds from silently enabling mock auth.
    if (nextDemoMode && !allowDemoMode) {
      throw StateError('Demo Mode is not enabled in this build.');
    }

    if (!nextDemoMode) {
      final validationError = validateGuacamoleUrl(nextGuacamoleBaseUrl);
      if (validationError != null) throw FormatException(validationError);
    }

    supabaseUrl = nextSupabaseUrl;
    supabaseAnonKey = nextSupabaseAnonKey;
    guacamoleBaseUrl = nextGuacamoleBaseUrl;
    isDemoMode = nextDemoMode;

    try {
      await _storage.write(key: _supabaseUrlKey, value: supabaseUrl);
      await _storage.write(key: _supabaseAnonKeyKey, value: supabaseAnonKey);
      await _storage.write(key: _guacamoleBaseUrlKey, value: guacamoleBaseUrl);
      await _storage.write(key: _demoModeKey, value: isDemoMode.toString());
      return true;
    } catch (_) {
      return false;
    }
  }
}
