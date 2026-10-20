import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../config/app_config.dart';

class GuacamoleSession {
  final String authToken;
  final String dataSource;

  const GuacamoleSession({
    required this.authToken,
    required this.dataSource,
  });

  factory GuacamoleSession.fromJson(Map<String, dynamic> json) {
    final token = json['authToken'];
    final source = json['dataSource'];
    if (token is! String || token.isEmpty) {
      throw const FormatException('Invalid Guacamole session response.');
    }
    return GuacamoleSession(
      authToken: token,
      dataSource: source is String && source.isNotEmpty ? source : 'postgresql',
    );
  }
}

/// Requests a short-lived, assignment-checked Guacamole session from the
/// private Azure API. Student passwords are never sent to Guacamole.
class CloudPcSessionService {
  static Future<GuacamoleSession> create({
    required String supabaseAccessToken,
  }) async {
    if (AppConfig().isDemoMode) {
      return const GuacamoleSession(
        authToken: 'DEMO_MOBI_DESK_AUTH_TOKEN_2026',
        dataSource: 'postgresql',
      );
    }

    final baseUrl = AppConfig().cloudPcApiBaseUrl;
    final uri = Uri.tryParse(baseUrl);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        !uri.hasAuthority ||
        uri.userInfo.isNotEmpty ||
        uri.query.isNotEmpty ||
        uri.fragment.isNotEmpty) {
      throw const CloudPcSessionException(
        'Cloud PC service is not configured for secure access.',
      );
    }
    if (supabaseAccessToken.trim().isEmpty) {
      throw const CloudPcSessionException('Please sign in again.');
    }

    HttpClient? client;
    try {
      client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
      final request = await client.postUrl(
        uri.resolve('/api/v1/cloud-pc/session'),
      );
      request.headers.set(
        HttpHeaders.authorizationHeader,
        'Bearer $supabaseAccessToken',
      );
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.headers.set(HttpHeaders.contentTypeHeader, 'application/json');
      final response = await request.close().timeout(const Duration(seconds: 12));
      final body = await response.transform(utf8.decoder).join();

      if (response.statusCode < 200 || response.statusCode >= 300) {
        if (response.statusCode == 401) {
          throw const CloudPcSessionException('Please sign in again.');
        }
        if (response.statusCode == 403) {
          throw const CloudPcSessionException(
            'No Cloud PC is assigned to this account.',
          );
        }
        throw const CloudPcSessionException(
          'Cloud PC session is unavailable. Please contact the administrator.',
        );
      }

      final data = jsonDecode(body);
      if (data is! Map<String, dynamic>) {
        throw const CloudPcSessionException(
          'Cloud PC returned an invalid session response.',
        );
      }
      return GuacamoleSession.fromJson(data);
    } on CloudPcSessionException {
      rethrow;
    } on TimeoutException {
      throw const CloudPcSessionException(
        'Cloud PC session request timed out. Please try again.',
      );
    } catch (_) {
      throw const CloudPcSessionException(
        'Cloud PC session is unavailable. Check your connection and try again.',
      );
    } finally {
      client?.close(force: true);
    }
  }
}

class CloudPcSessionException implements Exception {
  final String message;
  const CloudPcSessionException(this.message);

  @override
  String toString() => message;
}
