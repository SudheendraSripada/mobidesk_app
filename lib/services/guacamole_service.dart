import 'dart:convert';
import 'dart:io';
import '../config/app_config.dart';

class GuacamoleAuthResult {
  final bool isSuccess;
  final String? authToken;
  final String? dataSource;
  final String? errorMessage;

  const GuacamoleAuthResult({
    required this.isSuccess,
    this.authToken,
    this.dataSource,
    this.errorMessage,
  });
}

class GuacamoleService {
  /// Authenticates against Guacamole REST API (/api/tokens).
  static Future<GuacamoleAuthResult> login({
    required String username,
    required String password,
  }) async {
    final config = AppConfig();
    final baseUrl = config.guacamoleBaseUrl;

    if (config.isDemoMode) {
      // In demo mode, generate a mock auth session token
      return const GuacamoleAuthResult(
        isSuccess: true,
        authToken: 'DEMO_MOBI_DESK_AUTH_TOKEN_2026',
        dataSource: 'postgresql',
      );
    }

    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 4);

      final uri = Uri.parse('$baseUrl/api/tokens');
      final request = await client.postUrl(uri);
      request.headers.contentType = ContentType(
        'application',
        'x-www-form-urlencoded',
        charset: 'utf-8',
      );

      final body =
          'username=${Uri.encodeQueryComponent(username)}&password=${Uri.encodeQueryComponent(password)}';
      request.write(body);

      final response = await request.close();
      final responseBody = await response.transform(utf8.decoder).join();

      if (response.statusCode >= 200 && response.statusCode < 300) {
        final data = jsonDecode(responseBody) as Map<String, dynamic>;
        final token = data['authToken'] as String?;
        final ds = data['dataSource'] as String? ?? 'postgresql';

        if (token != null && token.isNotEmpty) {
          return GuacamoleAuthResult(
            isSuccess: true,
            authToken: token,
            dataSource: ds,
          );
        } else {
          return const GuacamoleAuthResult(
            isSuccess: false,
            errorMessage: 'Guacamole did not return an authToken.',
          );
        }
      } else {
        return GuacamoleAuthResult(
          isSuccess: false,
          errorMessage: 'Authentication failed (HTTP ${response.statusCode})',
        );
      }
    } catch (e) {
      // Return clear error while preserving ability to fallback to demo mode
      return GuacamoleAuthResult(
        isSuccess: false,
        errorMessage: 'Unable to reach Guacamole server: $e',
      );
    } finally {
      client?.close();
    }
  }

  /// Helper to encode a connection ID, type, and dataSource into a Base64URL ClientIdentifier
  /// if a deployment specifically requires RFC 4648 unpadded Base64URL ClientIdentifier encoding.
  static String encodeClientIdentifier({
    required String connectionId,
    String type = 'c',
    String dataSource = 'postgresql',
  }) {
    final raw = '$connectionId\x00$type\x00$dataSource';
    return base64Url.encode(utf8.encode(raw)).replaceAll('=', '');
  }

  /// Builds the HTML5 client URL for a given VM connection ID and token.
  /// If [encodeIdentifier] is true, encodes raw connectionId into a Base64URL ClientIdentifier.
  static String buildClientUrl({
    required String connectionId,
    required String authToken,
    bool encodeIdentifier = false,
    String type = 'c',
    String dataSource = 'postgresql',
  }) {
    final baseUrl = AppConfig().guacamoleBaseUrl;
    final id = encodeIdentifier
        ? encodeClientIdentifier(
            connectionId: connectionId,
            type: type,
            dataSource: dataSource,
          )
        : connectionId;
    return '$baseUrl/#/client/${Uri.encodeComponent(id)}?token=${Uri.encodeComponent(authToken)}';
  }
}
