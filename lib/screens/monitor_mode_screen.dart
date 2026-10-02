import 'dart:async';

import 'package:flutter/material.dart';

import '../models/vm_connection.dart';
import '../services/guacamole_service.dart';
import '../services/usb_stream_service.dart';

enum DockFlowStep { connectDock, detectedReadingResolution, connectedStreaming }

class MonitorModeScreen extends StatefulWidget {
  final VmConnection vm;
  final String authToken;

  const MonitorModeScreen({
    super.key,
    required this.vm,
    required this.authToken,
  });

  @override
  State<MonitorModeScreen> createState() => _MonitorModeScreenState();
}

class _MonitorModeScreenState extends State<MonitorModeScreen> {
  DockFlowStep _currentStep = DockFlowStep.connectDock;
  Timer? _statusTimer;

  bool _isFallback = false;
  int _monitorWidth = 1920;
  int _monitorHeight = 1080;
  int _monitorFps = 60;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _checkHardwareAndStartFlow();
    _statusTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _pollStatus(),
    );
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }

  Future<void> _checkHardwareAndStartFlow() async {
    final status = await UsbStreamService.getUsbStatus();
    final hasAccessory = status['hasAccessory'] as bool? ?? false;
    final isAlreadyStreaming = status['isStreaming'] as bool? ?? false;

    if (isAlreadyStreaming) {
      final info = await UsbStreamService.getDockDisplayInfo();
      if (mounted) {
        setState(() {
          _currentStep = DockFlowStep.connectedStreaming;
          _monitorWidth = info['width'] as int? ?? 1920;
          _monitorHeight = info['height'] as int? ?? 1080;
          _monitorFps = info['fps'] as int? ?? 60;
          _isFallback = info['isFallback'] as bool? ?? false;
        });
      }
      return;
    }

    if (hasAccessory) {
      _onDockDetected();
    }
  }

  Future<void> _pollStatus() async {
    final status = await UsbStreamService.getUsbStatus();
    final hasAccessory = status['hasAccessory'] as bool? ?? false;
    final isStreaming = status['isStreaming'] as bool? ?? false;
    final isFallback = await UsbStreamService.isFallbackActive();

    if (!mounted) return;

    if (_currentStep == DockFlowStep.connectDock && hasAccessory) {
      _onDockDetected();
    } else if (_currentStep == DockFlowStep.connectedStreaming) {
      if (!isStreaming) {
        setState(() {
          _currentStep = hasAccessory
              ? DockFlowStep.detectedReadingResolution
              : DockFlowStep.connectDock;
        });
      } else {
        setState(() {
          _isFallback = isFallback;
        });
      }
    }
  }

  Future<void> _onDockDetected() async {
    setState(() {
      _currentStep = DockFlowStep.detectedReadingResolution;
    });

    // Simulate reading EDID and display info over USB AOA bulk channel
    await Future.delayed(const Duration(milliseconds: 1200));

    final info = await UsbStreamService.getDockDisplayInfo();
    final w = info['width'] as int? ?? 1920;
    final h = info['height'] as int? ?? 1080;
    final fps = info['fps'] as int? ?? 60;

    if (!mounted) return;

    setState(() {
      _monitorWidth = w;
      _monitorHeight = h;
      _monitorFps = fps;
    });

    _startDockStreaming();
  }

  Future<void> _startDockStreaming() async {
    setState(() => _isLoading = true);

    final sessionUrl = GuacamoleService.buildClientUrl(
      connectionId: widget.vm.guacConnectionId,
      authToken: widget.authToken,
    );

    final success = await UsbStreamService.startMonitorStream(
      guacUrl: sessionUrl,
      width: _monitorWidth,
      height: _monitorHeight,
      fps: _monitorFps,
    );

    if (!mounted) return;
    setState(() => _isLoading = false);

    if (success) {
      final isFallback = await UsbStreamService.isFallbackActive();
      setState(() {
        _isFallback = isFallback;
        _currentStep = DockFlowStep.connectedStreaming;
      });
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Failed to start monitor stream. Ensure USB accessory permission is granted.',
          ),
          backgroundColor: Colors.red,
        ),
      );
      setState(() {
        _currentStep = DockFlowStep.connectDock;
      });
    }
  }

  Future<void> _stopDockStreaming() async {
    setState(() => _isLoading = true);
    await UsbStreamService.stopStream();
    if (mounted) {
      setState(() {
        _isLoading = false;
        _currentStep = DockFlowStep.connectDock;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Monitor Mode (Pi Dock)'),
        centerTitle: true,
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [_buildFlowContent(theme, colorScheme)],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFlowContent(ThemeData theme, ColorScheme colorScheme) {
    switch (_currentStep) {
      case DockFlowStep.connectDock:
        return Column(
          children: [
            Container(
              padding: const EdgeInsets.all(28),
              decoration: BoxDecoration(
                color: colorScheme.surfaceContainerHighest,
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.cable_rounded,
                size: 64,
                color: colorScheme.primary,
              ),
            ),
            const SizedBox(height: 24),
            Text(
              'Connect MobiDesk Dock',
              style: theme.textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 10),
            Text(
              'Plug your phone into the Raspberry Pi 4B dock using a standard USB-C cable.\nMake sure the dock is connected to your HDMI monitor.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            Card(
              elevation: 0,
              color: colorScheme.surfaceContainerLow,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Text(
                        'Listening for USB AOA connection...',
                        style: theme.textTheme.bodySmall,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            // Manual trigger button for testing/simulated accessory
            OutlinedButton.icon(
              onPressed: _onDockDetected,
              icon: const Icon(Icons.usb_rounded),
              label: const Text('Simulate Dock Detected (Demo)'),
            ),
          ],
        );

      case DockFlowStep.detectedReadingResolution:
        return Column(
          children: [
            Container(
              padding: const EdgeInsets.all(28),
              decoration: BoxDecoration(
                color: colorScheme.primaryContainer,
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.tv_rounded,
                size: 64,
                color: colorScheme.onPrimaryContainer,
              ),
            ),
            const SizedBox(height: 24),
            Text(
              'Dock Detected',
              style: theme.textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'Reading monitor resolution from HDMI EDID...',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            const CircularProgressIndicator(),
          ],
        );

      case DockFlowStep.connectedStreaming:
        return Column(
          children: [
            Container(
              padding: const EdgeInsets.all(28),
              decoration: BoxDecoration(
                color: Colors.green.withValues(alpha: 0.15),
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.check_circle_rounded,
                size: 64,
                color: Colors.green,
              ),
            ),
            const SizedBox(height: 20),

            // Crucial: Exact required status text
            Text(
              'Connected, started streaming',
              style: theme.textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
                color: Colors.green[800],
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'Resolution: ${_monitorWidth}x$_monitorHeight @ $_monitorFps FPS (Native HDMI)',
              style: theme.textTheme.titleSmall?.copyWith(
                color: colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 20),

            Card(
              elevation: 0,
              color: colorScheme.surfaceContainerHighest,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          Icons.desktop_windows_rounded,
                          color: colorScheme.primary,
                        ),
                        const SizedBox(width: 8),
                        Text(
                          widget.vm.name,
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Session is rendering fullscreen on your HDMI monitor with NO black bars.',
                      style: theme.textTheme.bodySmall,
                    ),
                    const Divider(height: 24),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(
                          Icons.lock_clock_rounded,
                          size: 20,
                          color: colorScheme.secondary,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Phone Screen Off Supported: You can now safely lock your phone screen or use other apps while your desktop stays live on the monitor.',
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),

            if (_isFallback) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.amber.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.amber),
                ),
                child: Row(
                  children: [
                    const Icon(
                      Icons.warning_amber_rounded,
                      color: Colors.orange,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Presentation mode unavailable. Fallback Screen Mirror active (Phone lock unavailable in fallback mode).',
                        style: TextStyle(
                          color: Colors.orange[900],
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],

            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _isLoading ? null : _stopDockStreaming,
              style: FilledButton.styleFrom(
                backgroundColor: colorScheme.error,
                foregroundColor: colorScheme.onError,
                padding: const EdgeInsets.symmetric(vertical: 14),
                minimumSize: const Size.fromHeight(48),
              ),
              icon: const Icon(Icons.stop_circle_outlined),
              label: const Text('Stop Streaming / Disconnect'),
            ),
          ],
        );
    }
  }
}
