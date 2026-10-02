/// Centralized configuration management for MobiDesk.
///
/// Reads configuration from local storage or defaults, ensuring no crashes occur
/// if Supabase or Guacamole server settings are not yet entered.
class AppConfig {
  static final AppConfig _instance = AppConfig._internal();
  factory AppConfig() => _instance;
  AppConfig._internal() {
    _loadFromEnvironment();
  }

  String supabaseUrl = '';
  String supabaseAnonKey = '';
  String guacamoleBaseUrl = 'http://10.0.2.2:8080/guacamole';

  bool isDemoMode = true;

  void _loadFromEnvironment() {
    const envUrl = String.fromEnvironment('SUPABASE_URL');
    const envKey = String.fromEnvironment('SUPABASE_ANON_KEY');
    const envGuac = String.fromEnvironment('GUACAMOLE_BASE_URL');
    if (envUrl.isNotEmpty) supabaseUrl = envUrl.trim();
    if (envKey.isNotEmpty) supabaseAnonKey = envKey.trim();
    if (envGuac.isNotEmpty) {
      String url = envGuac.trim();
      while (url.endsWith('/')) {
        url = url.substring(0, url.length - 1);
      }
      guacamoleBaseUrl = url;
    }
  }

  bool get isSupabaseConfigured =>
      supabaseUrl.trim().isNotEmpty &&
      supabaseAnonKey.trim().isNotEmpty &&
      !supabaseUrl.contains('your-project-id');

  void update({
    String? newSupabaseUrl,
    String? newSupabaseAnonKey,
    String? newGuacamoleBaseUrl,
    bool? demoMode,
  }) {
    if (newSupabaseUrl != null) supabaseUrl = newSupabaseUrl.trim();
    if (newSupabaseAnonKey != null) supabaseAnonKey = newSupabaseAnonKey.trim();
    if (newGuacamoleBaseUrl != null) {
      String url = newGuacamoleBaseUrl.trim();
      while (url.endsWith('/')) {
        url = url.substring(0, url.length - 1);
      }
      guacamoleBaseUrl = url;
    }
    if (demoMode != null) isDemoMode = demoMode;
  }
}
