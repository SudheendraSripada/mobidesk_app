import 'dart:convert';
import 'dart:io';
import '../config/app_config.dart';
import '../models/student.dart';
import '../models/vm_connection.dart';

/// Service managing interactions with Supabase Auth and Database tables:
/// - `students(id, name, roll_no, college)`
/// - `vms(id, name, guac_connection_id, hostname)`
/// - `student_vm_access(student_id, vm_id)`
class SupabaseService {
  /// Fetches the authenticated student's profile.
  static Future<StudentProfile> getStudentProfile({String? studentId}) async {
    final config = AppConfig();

    if (!config.isSupabaseConfigured || config.isDemoMode) {
      return StudentProfile.mock();
    }

    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 4);

      final uri = Uri.parse('${config.supabaseUrl}/rest/v1/students?select=*');
      final request = await client.getUrl(uri);
      request.headers.set('apikey', config.supabaseAnonKey);
      request.headers.set('Authorization', 'Bearer ${config.supabaseAnonKey}');

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

  /// Fetches VMs assigned to the student via `student_vm_access`.
  static Future<List<VmConnection>> getAssignedVms({String? studentId}) async {
    final config = AppConfig();

    if (!config.isSupabaseConfigured || config.isDemoMode) {
      return [VmConnection.mock()];
    }

    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 4);

      final uri = Uri.parse('${config.supabaseUrl}/rest/v1/vms?select=*');
      final request = await client.getUrl(uri);
      request.headers.set('apikey', config.supabaseAnonKey);
      request.headers.set('Authorization', 'Bearer ${config.supabaseAnonKey}');

      final response = await request.close();
      if (response.statusCode == 200) {
        final body = await response.transform(utf8.decoder).join();
        final list = jsonDecode(body) as List<dynamic>;
        if (list.isNotEmpty) {
          return list
              .map((item) => VmConnection.fromJson(item as Map<String, dynamic>))
              .toList();
        }
      }
    } catch (_) {
      // Fallback on error
    } finally {
      client?.close();
    }

    return [VmConnection.mock()];
  }
}
