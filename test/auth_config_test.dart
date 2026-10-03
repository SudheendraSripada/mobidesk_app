import 'package:flutter_test/flutter_test.dart';
import 'package:mobidesk_app/config/app_config.dart';
import 'package:mobidesk_app/models/academic_data.dart';
import 'package:mobidesk_app/models/student.dart';
import 'package:mobidesk_app/models/student_vm_access.dart';
import 'package:mobidesk_app/models/vm_connection.dart';
import 'package:mobidesk_app/services/guacamole_service.dart';
import 'package:mobidesk_app/services/supabase_service.dart';

void main() {
  group('Auth & Configuration Logic Tests', () {
    test('AppConfig default state and updates', () {
      final config = AppConfig();
      expect(config.isDemoMode, isTrue);

      config.update(
        newSupabaseUrl: 'https://test-project.supabase.co',
        newSupabaseAnonKey: 'sample-anon-key-12345',
        newGuacamoleBaseUrl: 'http://192.168.1.50:8080/guacamole/',
        demoMode: false,
      );

      expect(config.supabaseUrl, 'https://test-project.supabase.co');
      expect(config.supabaseAnonKey, 'sample-anon-key-12345');
      // Trailing slash should be stripped cleanly
      expect(config.guacamoleBaseUrl, 'http://192.168.1.50:8080/guacamole');
      expect(config.isDemoMode, isFalse);
      expect(config.isSupabaseConfigured, isTrue);
    });

    test('StudentProfile model fromJson and mock', () {
      final mockStudent = StudentProfile.mock();
      expect(mockStudent.name, 'Alex Mercer');
      expect(mockStudent.rollNo, 'CS-2024-042');
      expect(mockStudent.college, 'School of Engineering & Technology');

      final json = mockStudent.toJson();
      final fromJsonStudent = StudentProfile.fromJson(json);

      expect(fromJsonStudent.id, mockStudent.id);
      expect(fromJsonStudent.name, mockStudent.name);
      expect(fromJsonStudent.rollNo, mockStudent.rollNo);
      expect(fromJsonStudent.college, mockStudent.college);
    });

    test('VmConnection model fromJson and mock', () {
      final mockVm = VmConnection.mock();
      expect(mockVm.name, contains('Windows 11'));
      expect(mockVm.guacConnectionId, '1');
      expect(mockVm.hostname, '10.0.1.101');

      final json = mockVm.toJson();
      final parsedVm = VmConnection.fromJson(json);

      expect(parsedVm.id, mockVm.id);
      expect(parsedVm.name, mockVm.name);
      expect(parsedVm.guacConnectionId, mockVm.guacConnectionId);
      expect(parsedVm.hostname, mockVm.hostname);
    });

    test('GuacamoleService client URL building and escaping', () {
      AppConfig().update(newGuacamoleBaseUrl: 'http://10.0.2.2:8080/guacamole');

      final url = GuacamoleService.buildClientUrl(
        connectionId: 'lab-vm-1',
        authToken: 'test_token_abc123',
      );

      expect(
        url,
        'http://10.0.2.2:8080/guacamole/#/client/lab-vm-1?token=test_token_abc123',
      );
    });

    test('GuacamoleService demo login fallback', () async {
      AppConfig().update(demoMode: true);

      final result = await GuacamoleService.login(
        username: 'student@mobidesk.edu',
        password: 'Password123!',
      );

      expect(result.isSuccess, isTrue);
      expect(result.authToken, isNotNull);
      expect(result.dataSource, 'postgresql');
    });

    test('Academic data mock records integrity', () {
      final attendance = AttendanceSummary.mock();
      expect(attendance.overallPercentage, greaterThan(0.0));
      expect(attendance.subjects.length, 4);

      final quizzes = QuizItem.mockList();
      expect(quizzes.isNotEmpty, isTrue);
      expect(quizzes.first.title, contains('Quiz'));

      final overview = AcademicOverview.mock();
      expect(overview.cgpa, greaterThan(3.0));
      expect(overview.currentSemester, 5);
    });

    test('StudentVmAccess junction model fromJson and toJson', () {
      final json = {
        'student_id': 'd0000000-0000-0000-0000-000000000001',
        'vm_id': 'a0000000-0000-0000-0000-000000000001',
      };
      final access = StudentVmAccess.fromJson(json);
      expect(access.studentId, 'd0000000-0000-0000-0000-000000000001');
      expect(access.vmId, 'a0000000-0000-0000-0000-000000000001');
      expect(access.toJson(), equals(json));
    });

    test('SupabaseService demo login and authenticated role profile/VM retrieval', () async {
      AppConfig().update(demoMode: true);

      final loginRes = await SupabaseService.login(
        email: 'student@mobidesk.edu',
        password: 'Password123!',
      );
      expect(loginRes.isSuccess, isTrue);
      expect(loginRes.accessToken, isNotNull);
      expect(loginRes.userId, isNotNull);

      final student = await SupabaseService.getStudentProfile(
        studentId: loginRes.userId,
        authToken: loginRes.accessToken,
      );
      expect(student.name, 'Alex Mercer');
      expect(student.rollNo, 'CS-2024-042');
      expect(student.college, contains('Engineering'));

      final vm = await SupabaseService.getAssignedVm(
        studentId: loginRes.userId,
        authToken: loginRes.accessToken,
      );
      expect(vm, isNotNull);
      expect(vm!.guacConnectionId, '1');
      expect(vm.hostname, '10.0.1.101');

      final vms = await SupabaseService.getAssignedVms(
        studentId: loginRes.userId,
        authToken: loginRes.accessToken,
      );
      expect(vms.length, 1); // Enforcing one VM per student
    });

    test('Username transformation to Supabase email and Guacamole username', () {
      // With domain
      const emailInput = 'student@mobidesk.edu';
      final supaEmail1 = emailInput.contains('@') ? emailInput : '$emailInput@mobidesk.edu';
      expect(supaEmail1, 'student@mobidesk.edu');

      // Without domain
      const rollInput = 'CS-2024-042';
      final supaEmail2 = rollInput.contains('@') ? rollInput : '$rollInput@mobidesk.edu';
      expect(supaEmail2, 'CS-2024-042@mobidesk.edu');
    });

    test('GuacamoleService encodeClientIdentifier Base64URL structure', () {
      final encoded = GuacamoleService.encodeClientIdentifier(
        connectionId: '1',
        type: 'c',
        dataSource: 'postgresql',
      );
      expect(encoded.isNotEmpty, isTrue);
      expect(encoded.contains('+'), isFalse);
      expect(encoded.contains('/'), isFalse);
      expect(encoded.contains('='), isFalse);

      final urlWithEncoded = GuacamoleService.buildClientUrl(
        connectionId: '1',
        authToken: 'test_token_123',
        encodeIdentifier: true,
      );
      expect(urlWithEncoded, contains(encoded));
    });

    test('AppConfig strips trailing slash from supabaseUrl', () {
      AppConfig().update(newSupabaseUrl: 'https://test.supabase.co///');
      expect(AppConfig().supabaseUrl, 'https://test.supabase.co');
    });

    test('SupabaseService returns null and empty list when unassigned outside demo mode', () async {
      AppConfig().update(
        demoMode: false,
        newSupabaseUrl: 'http://127.0.0.1:54321',
        newSupabaseAnonKey: 'test_anon_key',
      );

      final vm = await SupabaseService.getAssignedVm(
        studentId: 'unassigned-uuid',
        authToken: 'jwt_token',
      );
      expect(vm, isNull);

      final vms = await SupabaseService.getAssignedVms(
        studentId: 'unassigned-uuid',
        authToken: 'jwt_token',
      );
      expect(vms, isEmpty);

      // Reset to demo mode for subsequent tests
      AppConfig().update(demoMode: true);
    });
  });
}
