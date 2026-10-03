/// Model representing the junction table `student_vm_access(student_id, vm_id)`.
class StudentVmAccess {
  final String studentId;
  final String vmId;

  const StudentVmAccess({
    required this.studentId,
    required this.vmId,
  });

  factory StudentVmAccess.fromJson(Map<String, dynamic> json) {
    return StudentVmAccess(
      studentId: json['student_id'] as String? ?? '',
      vmId: json['vm_id'] as String? ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'student_id': studentId,
      'vm_id': vmId,
    };
  }
}
