import 'package:flutter/material.dart';

import '../models/vm_connection.dart';
import '../services/guacamole_service.dart';
import '../services/usb_stream_service.dart';

/// Screen launched for Phone Mode (Option A).
///
/// Automatically delegates to the native fullscreen [PhoneCloudPcActivity]
/// hosting the hardware-accelerated Guacamole HTML5 client with zoom/scroll
/// and contextual touch support.
class PhoneCloudPcScreen extends StatefulWidget {
  final VmConnection vm;
  final String authToken;

  const PhoneCloudPcScreen({
    super.key,
    required this.vm,
    required this.authToken,
  });

  @override
  State<PhoneCloudPcScreen> createState() => _PhoneCloudPcScreenState();
}

class _PhoneCloudPcScreenState extends State<PhoneCloudPcScreen> {
  late String _sessionUrl;

  @override
  void initState() {
    super.initState();
    _sessionUrl = GuacamoleService.buildClientUrl(
      connectionId: widget.vm.guacConnectionId,
      authToken: widget.authToken,
    );

    // Launch native immersive fullscreen Cloud PC activity on Android
    UsbStreamService.startPhoneCloudPc(_sessionUrl);
  }

  void _launchNativeCloudPc() {
    UsbStreamService.startPhoneCloudPc(_sessionUrl);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Launching fullscreen Cloud PC session...'),
        duration: Duration(milliseconds: 1000),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.grey[900],
        foregroundColor: Colors.white,
        title: Text(widget.vm.name, style: const TextStyle(fontSize: 16)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'Relaunch Cloud PC',
            onPressed: _launchNativeCloudPc,
          ),
          IconButton(
            icon: const Icon(Icons.close_rounded),
            tooltip: 'Back to Dashboard',
            onPressed: () => Navigator.of(context).pop(),
          ),
        ],
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Card(
              color: Colors.grey[900],
              elevation: 4,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(20),
                side: BorderSide(color: colorScheme.primary.withValues(alpha: 0.4)),
              ),
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      padding: const EdgeInsets.all(20),
                      decoration: BoxDecoration(
                        color: colorScheme.primaryContainer,
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        Icons.desktop_windows_rounded,
                        size: 48,
                        color: colorScheme.onPrimaryContainer,
                      ),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      widget.vm.name,
                      style: theme.textTheme.titleLarge?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                      ),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Host: ${widget.vm.hostname} (RDP :3389 via Guacamole)',
                      style: const TextStyle(color: Colors.white70, fontSize: 13),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 16),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                      decoration: BoxDecoration(
                        color: Colors.black45,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        'Session: $_sessionUrl',
                        style: const TextStyle(
                          color: Colors.white60,
                          fontSize: 11,
                          fontFamily: 'monospace',
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                      ),
                    ),
                    const SizedBox(height: 24),
                    FilledButton.icon(
                      style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(48),
                        backgroundColor: colorScheme.primary,
                      ),
                      icon: const Icon(Icons.open_in_new_rounded),
                      label: const Text('Open Fullscreen Cloud PC'),
                      onPressed: _launchNativeCloudPc,
                    ),
                    const SizedBox(height: 12),
                    OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(44),
                        foregroundColor: Colors.white70,
                        side: const BorderSide(color: Colors.white24),
                      ),
                      icon: const Icon(Icons.arrow_back_rounded),
                      label: const Text('Back to Dashboard'),
                      onPressed: () => Navigator.of(context).pop(),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
