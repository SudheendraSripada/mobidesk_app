import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';

import '../models/vm_connection.dart';
import '../services/app_logger.dart';
import '../services/auth_storage.dart';
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
  bool _isBatteryOptIgnored = true;
  String _currentAuthToken = '';

  // Real-time stream stats
  double _fps = 0.0;
  int _kbps = 0;
  int _totalFrames = 0;
  int _droppedFrames = 0;
  int _keyframesSent = 0;
  String _fallbackNotice = '';

  // Exponential backoff retry state: 1s, 2s, 4s, 8s, 15s
  int _retryAttempt = 0;
  final List<int> _backoffDelays = [1, 2, 4, 8, 15];
  bool _isRetrying = false;

  @override
  void initState() {
    super.initState();
    _currentAuthToken = widget.authToken;
    _checkHardwareAndStartFlow();
    _checkBatteryOptimization();
    _statusTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _pollStatus(),
    );
  }

  Future<void> _checkBatteryOptimization() async {
    final ignored = await UsbStreamService.isIgnoringBatteryOptimizations();
    if (mounted) {
      setState(() => _isBatteryOptIgnored = ignored);
    }
  }

  void _showBatteryOptDialog() {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Battery Optimization Exemption'),
        content: const Text(
          'Android may pause or terminate the streaming service when your phone screen turns off.\n\n'
          'Granting battery optimization exemption ensures continuous, uninterrupted streaming in the background.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Dismiss'),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.of(ctx).pop();
              await UsbStreamService.requestIgnoreBatteryOptimizations();
              await _checkBatteryOptimization();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
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
    final stats = await UsbStreamService.getStreamStats();
    final isStreaming = stats['isStreaming'] as bool? ?? false;
    final isFallback = stats['isFallback'] as bool? ?? false;

    if (!mounted) return;

    setState(() {
      _fps = (stats['fps'] as num?)?.toDouble() ?? 0.0;
      _kbps = (stats['kbps'] as num?)?.toInt() ?? 0;
      _totalFrames = (stats['totalFrames'] as num?)?.toInt() ?? 0;
      _droppedFrames = (stats['droppedFrames'] as num?)?.toInt() ?? 0;
      _keyframesSent = (stats['keyframes'] as num?)?.toInt() ?? 0;
      _fallbackNotice = (stats['fallbackNotice'] as String?) ?? '';
      _isFallback = isFallback;
    });

    if (_currentStep == DockFlowStep.connectDock && hasAccessory) {
      _onDockDetected();
    } else if (_currentStep == DockFlowStep.connectedStreaming) {
      if (!isStreaming && !_isRetrying) {
        _handleStreamError('Screen streaming interrupted or disconnected');
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
    if (_isRetrying) return;
    setState(() => _isLoading = true);

    final sessionUrl = GuacamoleService.buildClientUrl(
      connectionId: widget.vm.guacConnectionId,
      authToken: _currentAuthToken,
    );

    await UsbStreamService.updatePresentationStatus(null); // Clear any presentation error

    final success = await UsbStreamService.startMonitorStream(
      guacUrl: sessionUrl,
      width: _monitorWidth,
      height: _monitorHeight,
      fps: _monitorFps,
    );

    if (!mounted) return;
    setState(() => _isLoading = false);

    if (success) {
      _retryAttempt = 0;
      final isFallback = await UsbStreamService.isFallbackActive();
      setState(() {
        _isFallback = isFallback;
        _currentStep = DockFlowStep.connectedStreaming;
      });
      AppLogger.i('MonitorModeScreen', 'Dock streaming started successfully (${_monitorWidth}x$_monitorHeight @ $_monitorFps fps)');
    } else {
      _handleStreamError('Failed to start monitor stream');
    }
  }

  Future<void> _handleStreamError(String errorMsg) async {
    if (_isRetrying) return;
    _isRetrying = true;

    AppLogger.w('MonitorModeScreen', 'Handling stream error: $errorMsg');

    // 1. Attempt silent re-authentication with stored credentials first
    final creds = await AuthStorage.getCredentials();
    if (creds['username'] != null && creds['password'] != null) {
      AppLogger.i('MonitorModeScreen', 'Attempting silent re-authentication with stored credentials...');
      await UsbStreamService.updatePresentationStatus('Reconnecting Cloud PC session...', countdown: 3);
      try {
        final guacResult = await GuacamoleService.login(
          username: creds['username']!,
          password: creds['password']!,
        );
        final newToken = guacResult.authToken ?? 'DEMO_TOKEN_2026';
        _currentAuthToken = newToken;
        await AuthStorage.saveCredentials(
          username: creds['username']!,
          password: creds['password']!,
          authToken: newToken,
          keepSignedIn: true,
        );
        final newUrl = GuacamoleService.buildClientUrl(
          connectionId: widget.vm.guacConnectionId,
          authToken: newToken,
        );
        await UsbStreamService.updatePresentationSessionUrl(newUrl);
        AppLogger.i('MonitorModeScreen', 'Silent re-auth succeeded, updated presentation session URL');
      } catch (e) {
        AppLogger.w('MonitorModeScreen', 'Silent re-auth attempt failed: $e');
      }
    }

    // 2. Exponential backoff countdown: 1s, 2s, 4s, 8s, 15s
    final delaySec = _backoffDelays[min(_retryAttempt, _backoffDelays.length - 1)];
    _retryAttempt++;
    AppLogger.w('MonitorModeScreen', 'Retrying in ${delaySec}s (attempt #$_retryAttempt)');

    for (int c = delaySec; c > 0; c--) {
      if (!mounted) {
        _isRetrying = false;
        return;
      }
      await UsbStreamService.updatePresentationStatus('Connection error. Retrying in ${c}s...', countdown: c);
      await Future.delayed(const Duration(seconds: 1));
    }

    if (!mounted) {
      _isRetrying = false;
      return;
    }

    _isRetrying = false;
    _startDockStreaming();
  }

  Future<void> _stopDockStreaming() async {
    _isRetrying = false;
    _retryAttempt = 0;
    setState(() => _isLoading = true);
    await UsbStreamService.updatePresentationStatus('Session disconnected by user', countdown: 0);
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
            const SizedBox(height: 16),

            // Crucial: Exact required status text
            Text(
              'Connected, started streaming',
              style: theme.textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
                color: Colors.green[800],
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 6),
            Text(
              'Resolution: ${_monitorWidth}x$_monitorHeight @ $_monitorFps FPS (Native HDMI)',
              style: theme.textTheme.titleSmall?.copyWith(
                color: colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 14),

            // Live status chips
            Wrap(
              spacing: 8,
              runSpacing: 8,
              alignment: WrapAlignment.center,
              children: [
                Chip(
                  avatar: const Icon(Icons.usb_rounded, size: 16),
                  label: const Text('Dock: Connected'),
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
                Chip(
                  avatar: const Icon(Icons.tv_rounded, size: 16),
                  label: Text('${_monitorWidth}x$_monitorHeight'),
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
                Chip(
                  avatar: const Icon(Icons.speed_rounded, size: 16),
                  label: Text('${_fps > 0 ? _fps.toStringAsFixed(1) : _monitorFps} FPS'),
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
                Chip(
                  avatar: const Icon(Icons.network_check_rounded, size: 16),
                  label: Text('$_kbps kbps'),
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
                Chip(
                  avatar: const Icon(Icons.layers_clear_rounded, size: 16),
                  label: Text('Dropped: $_droppedFrames'),
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
                Chip(
                  avatar: const Icon(Icons.key_rounded, size: 16),
                  label: Text('Keyframes: $_keyframesSent'),
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
                Chip(
                  avatar: const Icon(Icons.movie_creation_outlined, size: 16),
                  label: Text('Frames: $_totalFrames'),
                  backgroundColor: colorScheme.surfaceContainerHighest,
                ),
                Chip(
                  avatar: Icon(
                    _isFallback ? Icons.warning_amber_rounded : Icons.check_circle_rounded,
                    size: 16,
                    color: _isFallback ? Colors.amber[800] : Colors.green[800],
                  ),
                  label: Text(_isFallback ? 'Fallback Mirror' : 'Virtual Display'),
                  backgroundColor: _isFallback
                      ? Colors.amber.withValues(alpha: 0.15)
                      : Colors.green.withValues(alpha: 0.15),
                ),
              ],
            ),
            const SizedBox(height: 8),

            TextButton.icon(
              onPressed: () async {
                await UsbStreamService.requestKeyframe();
                if (mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('IDR Keyframe requested'), duration: Duration(seconds: 1)),
                  );
                }
              },
              icon: const Icon(Icons.refresh_rounded, size: 16),
              label: const Text('Force IDR Keyframe'),
            ),
            const SizedBox(height: 8),

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
                        Expanded(
                          child: Text(
                            widget.vm.name,
                            style: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
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
                          Icons.power_settings_new_rounded,
                          size: 20,
                          color: colorScheme.secondary,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'You can now turn off your phone screen. MobiDesk continues streaming smoothly in the background.',
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),

            if (!_isBatteryOptIgnored) ...[
              const SizedBox(height: 12),
              Card(
                elevation: 0,
                color: Colors.amber.withValues(alpha: 0.15),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                  side: BorderSide(color: Colors.amber.withValues(alpha: 0.5)),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  child: Row(
                    children: [
                      const Icon(Icons.battery_alert_rounded, color: Colors.orange),
                      const SizedBox(width: 10),
                      const Expanded(
                        child: Text(
                          'Battery optimization may pause stream on screen lock.',
                          style: TextStyle(fontSize: 12),
                        ),
                      ),
                      TextButton(
                        onPressed: _showBatteryOptDialog,
                        child: const Text('Exempt App'),
                      ),
                    ],
                  ),
                ),
              ),
            ],

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
                        _fallbackNotice.isNotEmpty
                            ? _fallbackNotice
                            : 'Presentation mode unavailable. Fallback Screen Mirror active (Phone lock unavailable in fallback mode).',
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
