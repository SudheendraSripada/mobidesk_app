/// Model representing a Virtual Machine connection mapped in Supabase `vms`.
class VmConnection {
  final String id;
  final String name;
  final String guacConnectionId;
  final String hostname;

  const VmConnection({
    required this.id,
    required this.name,
    required this.guacConnectionId,
    required this.hostname,
  });

  factory VmConnection.fromJson(Map<String, dynamic> json) {
    return VmConnection(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? 'Student Windows VM',
      guacConnectionId: json['guac_connection_id']?.toString() ?? '1',
      hostname: json['hostname'] as String? ?? '10.0.1.101',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'guac_connection_id': guacConnectionId,
      'hostname': hostname,
    };
  }

  /// Default demo VM connection for offline/mock demo mode.
  factory VmConnection.mock() {
    return const VmConnection(
      id: 'a0000000-0000-0000-0000-000000000001',
      name: 'Windows 11 Student Lab VM (Win11-01)',
      guacConnectionId: '1',
      hostname: '10.0.1.101',
    );
  }
}
