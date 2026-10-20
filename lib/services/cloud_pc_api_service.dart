import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../config/app_config.dart';

class CloudPcStatus {
  final String state;
  final bool ready;

  const CloudPcStatus({required this.state, required this.ready});

  factory CloudPcStatus.fromJson(Map<String, dynamic> json) {
    final state = json['state'];
    final ready = json['ready'];
    if (state is! String || ready is! bool) {
      throw const FormatException('Invalid Cloud PC status response.');
    }
    return CloudPcStatus(state: state, ready: ready);
  }
}

/// Calls the private host lifecycle API using the authenticated Supabase JWT.
/// The URL is supplied at build time; it is never editable in student settings.
class CloudPcApiService {
  static String get baseUrl => AppConfig().cloudPcApiBaseUrl;

  static Future<CloudPcStatus> status({required String supabaseAccessToken}) =>
      _request('GET', '/api/v1/cloud-pc/status', supabaseAccessToken);

  static Future<CloudPcStatus> start({required String supabaseAccessToken}) =>
      _request('POST', '/api/v1/cloud-pc/start', supabaseAccessToken);

  static Future<CloudPcStatus> stop({required String supabaseAccessToken}) =>
      _request('POST', '/api/v1/cloud-pc/stop', supabaseAccessToken);

  static Future<CloudPcStatus> _request(
    String method,
    String path,
    String token,
  ) async {
    if (AppConfig().isDemoMode) {
      throw const CloudPcApiException('Cloud PC lifecycle is unavailable in Demo Mode.');
    }
    final uri = Uri.tryParse(baseUrl);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        !uri.hasAuthority ||
        uri.userInfo.isNotEmpty ||
        uri.query.isNotEmpty ||
        uri.fragment.isNotEmpty) {
      throw const CloudPcApiException('Cloud PC service is not configured for secure access.');
    }
    if (token.trim().isEmpty) {
      throw const CloudPcApiException('Please sign in again.');
    }

    HttpClient? client;
    try {
      client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
      final requestUri = uri.resolve(path);
      final request = method == 'GET'
          ? await client.getUrl(requestUri)
          : await client.postUrl(requestUri);
      request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.headers.set(HttpHeaders.contentTypeHeader, 'application/json');
      final response = await request.close().timeout(const Duration(seconds: 130));
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        String message = 'Cloud PC request failed. Please try again.';
        try {
          final data = jsonDecode(body);
          if (data is Map<String, dynamic> && data['detail'] is String) {
            final detail = data['detail'] as String;
            const safeMessages = {
              'Please sign in again.',
              'No Cloud PC is assigned to this account.',
              'Cloud PC service is not configured.',
              'Cloud PC control is unavailable.',
              'Cloud PC did not become ready. Please try again.',
              'Too many requests. Please wait and try again.',
              'Unable to verify Cloud PC access.',
            };
            if (safeMessages.contains(detail)) message = detail;
          }
        } catch (_) {}
        throw CloudPcApiException(message);
      }
      final data = jsonDecode(body);
      if (data is! Map<String, dynamic>) {
        throw const CloudPcApiException('Cloud PC returned an invalid response.');
      }
      return CloudPcStatus.fromJson(data);
    } on CloudPcApiException {
      rethrow;
    } on TimeoutException {
      throw const CloudPcApiException('Cloud PC request timed out. Check your connection and try again.');
    } catch (_) {
      throw const CloudPcApiException('Cloud PC is unavailable. Check your connection and try again.');
    } finally {
      client?.close(force: true);
    }
  }
}

class CloudPcApiException implements Exception {
  final String message;
  const CloudPcApiException(this.message);

  @override
  String toString() => message;
}
