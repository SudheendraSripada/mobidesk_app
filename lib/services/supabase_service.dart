import 'dart:convert';
import 'dart:io';
import '../config/app_config.dart';
import '../models/student.dart';
import '../models/vm_connection.dart';

/// Result of authenticating against Supabase Auth (`/auth/v1/token`).
class SupabaseAuthResult {
  final bool isSuccess;
  final String? accessToken;
  final String? userId;
  final String? email;
  final String? errorMessage;

  const SupabaseAuthResult({
    required this.isSuccess,
    this.accessToken,
    this.userId,
    this.email,
    this.errorMessage,
  });
}

/// Service managing interactions with Supabase Auth and Database tables:
/// - `students(id, name, roll_no, college)`
/// - `vms(id, name, guac_connection_id, hostname)`
/// - `student_vm_access(student_id, vm_id)`
/// Enforces schema rules:
/// - Read-only for the authenticated role using Supabase user JWT Bearer token
/// - One VM per student via student_vm_access junction mapping
class SupabaseService {
  /// Authenticates against Supabase Auth (`/auth/v1/token?grant_type=password`)
  /// returning an `accessToken` JWT with the `authenticated` role.
  static Future<SupabaseAuthResult> login({
    required String email,
    required String password,
  }) async {
    final config = AppConfig();

    if (!config.isSupabaseConfigured || config.isDemoMode) {
      return const SupabaseAuthResult(
        isSuccess: true,
        accessToken: 'DEMO_MOBI_DESK_SUPABASE_TOKEN_2026',
        userId: 'd0000000-0000-0000-0000-000000000001',
        email: 'student@mobidesk.edu',
      );
    }

    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 4);

      final uri = Uri.parse(
        '${config.supabaseUrl}/auth/v1/token?grant_type=password',
      );
      final request = await client.postUrl(uri);
      request.headers.set('apikey', config.supabaseAnonKey);
      request.headers.contentType = ContentType.json;

      final body = jsonEncode({'email': email, 'password': password});
      request.write(body);

      final response = await request.close();
      final responseBody = await response.transform(utf8.decoder).join();

      if (response.statusCode >= 200 && response.statusCode < 300) {
        final data = jsonDecode(responseBody) as Map<String, dynamic>;
        final token = data['access_token'] as String?;
        final user = data['user'] as Map<String, dynamic>?;
        final userId = user?['id'] as String?;
        final userEmail = user?['email'] as String? ?? email;

        if (token != null && token.isNotEmpty) {
          return SupabaseAuthResult(
            isSuccess: true,
            accessToken: token,
            userId: userId,
            email: userEmail,
          );
        }
      }

      return SupabaseAuthResult(
        isSuccess: false,
        errorMessage:
            'Supabase authentication failed (HTTP ${response.statusCode})',
      );
    } catch (e) {
      return SupabaseAuthResult(
        isSuccess: false,
        errorMessage: 'Unable to reach Supabase Auth: $e',
      );
    } finally {
      client?.close();
    }
  }

  /// Fetches the authenticated student's profile from `students(id, name, roll_no, college)`.
  /// Uses the authenticated user JWT to satisfy RLS read-only access for the authenticated role.
  static Future<StudentProfile> getStudentProfile({
    String? studentId,
    String? authToken,
  }) async {
    final config = AppConfig();

    if (!config.isSupabaseConfigured || config.isDemoMode) {
      return StudentProfile.mock();
    }

    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 4);

      final filter = studentId != null
          ? '&id=eq.${Uri.encodeQueryComponent(studentId)}'
          : '';
      final uri = Uri.parse(
        '${config.supabaseUrl}/rest/v1/students?select=id,name,roll_no,college$filter',
      );
      final request = await client.getUrl(uri);
      request.headers.set('apikey', config.supabaseAnonKey);
      request.headers.set(
        'Authorization',
        'Bearer ${authToken ?? config.supabaseAnonKey}',
      );

      final response = await request.close();
      if (response.statusCode == 200) {
        final body = await response.transform(utf8.decoder).join();
        final list = jsonDecode(body) as List<dynamic>;
        if (list.isNotEmpty) {
          return StudentProfile.fromJson(list.first as Map<String, dynamic>);
        }
      }
    } catch (_) {
      // Fallback on error
    } finally {
      client?.close();
    }

    return StudentProfile.mock();
  }

  /// Fetches the student's assigned VM from `vms(id, name, guac_connection_id, hostname)`
  /// through the junction table `student_vm_access(student_id, vm_id)`.
  /// Enforces schema constraints: one VM per student, read-only for authenticated role.
  static Future<VmConnection?> getAssignedVm({
    String? studentId,
    String? authToken,
  }) async {
    final config = AppConfig();

    if (!config.isSupabaseConfigured || config.isDemoMode) {
      return VmConnection.mock();
    }

    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 4);
      final token = authToken ?? config.supabaseAnonKey;

      // 1. Query student_vm_access junction table for assigned vm_id
      final accessFilter = studentId != null
          ? '&student_id=eq.${Uri.encodeQueryComponent(studentId)}'
          : '';
      final accessUri = Uri.parse(
        '${config.supabaseUrl}/rest/v1/student_vm_access?select=student_id,vm_id$accessFilter&limit=1',
      );
      final accessReq = await client.getUrl(accessUri);
      accessReq.headers.set('apikey', config.supabaseAnonKey);
      accessReq.headers.set('Authorization', 'Bearer $token');

      final accessRes = await accessReq.close();
      String? assignedVmId;

      if (accessRes.statusCode == 200) {
        final accessBody = await accessRes.transform(utf8.decoder).join();
        final accessList = jsonDecode(accessBody) as List<dynamic>;
        if (accessList.isNotEmpty) {
          final mapping = accessList.first as Map<String, dynamic>;
          assignedVmId = mapping['vm_id'] as String?;
        }
      }

      // If no VM is assigned in the junction table, student has no VM access (enforcing 1 VM per student)
      if (assignedVmId == null || assignedVmId.isEmpty) {
        return null;
      }

      // 2. Query vms table for VM details using assigned vm_id
      final vmUri = Uri.parse(
        '${config.supabaseUrl}/rest/v1/vms?select=id,name,guac_connection_id,hostname&id=eq.${Uri.encodeQueryComponent(assignedVmId)}&limit=1',
      );
      final vmReq = await client.getUrl(vmUri);
      vmReq.headers.set('apikey', config.supabaseAnonKey);
      vmReq.headers.set('Authorization', 'Bearer $token');

      final vmRes = await vmReq.close();
      if (vmRes.statusCode == 200) {
        final vmBody = await vmRes.transform(utf8.decoder).join();
        final vmList = jsonDecode(vmBody) as List<dynamic>;
        if (vmList.isNotEmpty) {
          return VmConnection.fromJson(vmList.first as Map<String, dynamic>);
        }
      }
      return null;
    } catch (_) {
      // Fallback on error: return null so caller can handle missing VM or offline fallback
      return null;
    } finally {
      client?.close();
    }
  }

  /// Fetches assigned VMs returning a list (enforces one VM per student schema constraint).
  static Future<List<VmConnection>> getAssignedVms({
    String? studentId,
    String? authToken,
  }) async {
    final vm = await getAssignedVm(studentId: studentId, authToken: authToken);
    return vm != null ? [vm] : [];
  }
}
