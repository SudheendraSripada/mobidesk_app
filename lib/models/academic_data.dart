/// Models for student academic dashboard features (Attendance, Quizzes, Academics).
library;

class SubjectAttendance {
  final String subject;
  final int attendedClasses;
  final int totalClasses;

  const SubjectAttendance({
    required this.subject,
    required this.attendedClasses,
    required this.totalClasses,
  });

  double get percentage =>
      totalClasses > 0 ? (attendedClasses / totalClasses) * 100 : 0.0;
}

class AttendanceSummary {
  final double overallPercentage;
  final List<SubjectAttendance> subjects;

  const AttendanceSummary({
    required this.overallPercentage,
    required this.subjects,
  });

  factory AttendanceSummary.mock() {
    return const AttendanceSummary(
      overallPercentage: 88.5,
      subjects: [
        SubjectAttendance(
          subject: 'Operating Systems',
          attendedClasses: 23,
          totalClasses: 25,
        ),
        SubjectAttendance(
          subject: 'Computer Networks',
          attendedClasses: 18,
          totalClasses: 21,
        ),
        SubjectAttendance(
          subject: 'Cloud Virtualization',
          attendedClasses: 19,
          totalClasses: 20,
        ),
        SubjectAttendance(
          subject: 'Database Systems',
          attendedClasses: 20,
          totalClasses: 24,
        ),
      ],
    );
  }
}

class QuizItem {
  final String id;
  final String title;
  final String course;
  final String dueDate;
  final int durationMinutes;
  final bool isCompleted;

  const QuizItem({
    required this.id,
    required this.title,
    required this.course,
    required this.dueDate,
    required this.durationMinutes,
    this.isCompleted = false,
  });

  static List<QuizItem> mockList() {
    return const [
      QuizItem(
        id: 'q1',
        title: 'Virtualization & Hypervisors Quiz',
        course: 'Cloud Virtualization',
        dueDate: 'Tomorrow, 5:00 PM',
        durationMinutes: 30,
      ),
      QuizItem(
        id: 'q2',
        title: 'CPU Scheduling & Semaphores',
        course: 'Operating Systems',
        dueDate: 'Oct 12, 11:59 PM',
        durationMinutes: 45,
      ),
      QuizItem(
        id: 'q3',
        title: 'TCP Flow Control & Congestion',
        course: 'Computer Networks',
        dueDate: 'Oct 15, 6:00 PM',
        durationMinutes: 25,
      ),
    ];
  }
}

class AcademicOverview {
  final double cgpa;
  final int currentSemester;
  final int completedCredits;
  final int totalCredits;

  const AcademicOverview({
    required this.cgpa,
    required this.currentSemester,
    required this.completedCredits,
    required this.totalCredits,
  });

  factory AcademicOverview.mock() {
    return const AcademicOverview(
      cgpa: 3.82,
      currentSemester: 5,
      completedCredits: 78,
      totalCredits: 120,
    );
  }
}
