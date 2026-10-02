/// Model representing a student profile from the Supabase `students` table.
class StudentProfile {
  final String id;
  final String name;
  final String rollNo;
  final String college;

  const StudentProfile({
    required this.id,
    required this.name,
    required this.rollNo,
    required this.college,
  });

  factory StudentProfile.fromJson(Map<String, dynamic> json) {
    return StudentProfile(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? 'Student User',
      rollNo: json['roll_no'] as String? ?? 'CS-2024-001',
      college: json['college'] as String? ?? 'School of Engineering & Technology',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'roll_no': rollNo,
      'college': college,
    };
  }

  /// Default demo student profile for mock and offline testing.
  factory StudentProfile.mock() {
    return const StudentProfile(
      id: 'd0000000-0000-0000-0000-000000000001',
      name: 'Alex Mercer',
      rollNo: 'CS-2024-042',
      college: 'School of Engineering & Technology',
    );
  }
}
