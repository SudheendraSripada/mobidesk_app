import 'package:flutter_test/flutter_test.dart';
import 'package:mobidesk_app/config/app_config.dart';
import 'package:mobidesk_app/models/academic_data.dart';
import 'package:mobidesk_app/models/student.dart';
import 'package:mobidesk_app/models/vm_connection.dart';
import 'package:mobidesk_app/services/guacamole_service.dart';

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
  });
}
