import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/usb_stream_service.dart';

/// Hidden Developer Tools screen accessible via long-press on the MobiDesk logo.
/// Preserves the original USB AOA Sender (Phone A) and Receiver (Phone B) diagnostic tools.
class DeveloperToolsScreen extends StatefulWidget {
  const DeveloperToolsScreen({super.key});

  @override
  State<DeveloperToolsScreen> createState() => _DeveloperToolsScreenState();
}

class _DeveloperToolsScreenState extends State<DeveloperToolsScreen> with WidgetsBindingObserver {
  bool _isStreaming = false;
  bool _isLoading = false;
  String _statusMessage = 'Ready for USB AOA connection';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
    }
  }

  Future<void> _refreshStatus() async {
    final streaming = await UsbStreamService.isStreaming();
    if (mounted) {
      setState(() {
        _isStreaming = streaming;
        if (_isStreaming) {
          _statusMessage = 'Screen streaming active over USB Accessory';
        }
      });
    }

    final usbStatus = await UsbStreamService.getUsbStatus();
    if (mounted) {
      final hasAccessory = usbStatus['hasAccessory'] as bool? ?? false;
      final deviceCount = usbStatus['deviceCount'] as int? ?? 0;
      setState(() {
        if (_isStreaming) {
          _statusMessage = 'Screen streaming active over USB Accessory';
        } else if (hasAccessory) {
          _statusMessage = 'USB Accessory attached to Host';
        } else if (deviceCount > 0) {
          _statusMessage = '$deviceCount USB device(s) detected';
        } else {
          _statusMessage = 'Ready for USB AOA connection';
        }
      });
    }
  }

  Future<void> _startStream() async {
    setState(() => _isLoading = true);
    try {
      final res = await UsbStreamService.startStream();
      if (mounted && res) {
        setState(() {
          _isStreaming = true;
          _statusMessage = 'Screen streaming active over USB Accessory';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Screen capture started over USB AOA 2.0'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Unable to start screen recording: ${e.message ?? e.code}'),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _stopStream() async {
    setState(() => _isLoading = true);
    try {
      await UsbStreamService.stopStream();
      if (mounted) {
        setState(() {
          _isStreaming = false;
          _statusMessage = 'Screen streaming stopped';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Screen streaming stopped.')),
        );
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Error stopping stream: ${e.message ?? e.code}'),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _startReceiver() async {
    try {
      await UsbStreamService.startReceiver();
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Unable to start receiver: ${e.message ?? e.code}'),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  void _showUsbGuide() {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.usb_rounded, color: Colors.indigo),
            SizedBox(width: 8),
            Text('USB AOA 2.0 Setup'),
          ],
        ),
        content: const SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Direct High-Speed USB Connection',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              SizedBox(height: 8),
              Text(
                '1. Connect a USB OTG adapter into Phone B (Receiver / Dock).\n'
                '2. Plug a standard USB cable between the OTG adapter and Phone A (Sender).\n'
                '3. On Phone B, tap "Receive (Receiver)".\n'
                '4. On Phone A, tap "Send (Sender)" and grant screen capture permission.\n\n'
                'Video will stream seamlessly via USB Bulk Transfer with low latency!',
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Got it'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Developer Tools'),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.help_outline_rounded),
            tooltip: 'USB Connection Guide',
            onPressed: _showUsbGuide,
          ),
        ],
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(
                  Icons.developer_mode_rounded,
                  size: 44,
                  color: colorScheme.primary,
                ),
                const SizedBox(height: 8),
                Text(
                  'USB AOA Screen Streamer',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Hardware Diagnostics & Direct P2P Mode',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 16),

                // Live Status Card
                Card(
                  elevation: 0,
                  color: _isStreaming
                      ? colorScheme.primaryContainer
                      : colorScheme.surfaceContainerHighest,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                    child: Row(
                      children: [
                        Icon(
                          _isStreaming
                              ? Icons.sensors_rounded
                              : Icons.info_outline_rounded,
                          size: 20,
                          color: _isStreaming
                              ? colorScheme.onPrimaryContainer
                              : colorScheme.onSurfaceVariant,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                _isStreaming ? 'Streaming Live' : 'Status',
                                style: theme.textTheme.labelSmall?.copyWith(
                                  color: _isStreaming
                                      ? colorScheme.onPrimaryContainer
                                      : colorScheme.onSurfaceVariant,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              Text(
                                _statusMessage,
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: _isStreaming
                                      ? colorScheme.onPrimaryContainer
                                      : colorScheme.onSurfaceVariant,
                                ),
                              ),
                            ],
                          ),
                        ),
                        if (_isLoading)
                          const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 14),

                // Sender Card (Phone A)
                Card(
                  elevation: 1,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            Icon(Icons.upload_rounded, color: colorScheme.primary, size: 20),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                'Phone A (Sender)',
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Acts as USB Accessory to capture and stream display.',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: colorScheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: 12),
                        if (_isStreaming) ...[
                          FilledButton.icon(
                            onPressed: _isLoading ? null : _stopStream,
                            style: FilledButton.styleFrom(
                              backgroundColor: colorScheme.error,
                              foregroundColor: colorScheme.onError,
                              padding: const EdgeInsets.symmetric(vertical: 12),
                            ),
                            icon: const Icon(Icons.stop_rounded),
                            label: const Text('Stop Sharing'),
                          ),
                        ] else ...[
                          FilledButton.icon(
                            onPressed: _isLoading ? null : _startStream,
                            style: FilledButton.styleFrom(
                              padding: const EdgeInsets.symmetric(vertical: 12),
                            ),
                            icon: const Icon(Icons.screen_share_rounded),
                            label: const Text('Send (Sender)'),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 12),

                // Receiver Card (Phone B)
                Card(
                  elevation: 1,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            Icon(Icons.download_rounded, color: colorScheme.secondary, size: 20),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                'Phone B (Receiver / Dock)',
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Acts as USB Host via OTG to decode and display video.',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: colorScheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: 12),
                        OutlinedButton.icon(
                          onPressed: _isLoading ? null : _startReceiver,
                          style: OutlinedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                          ),
                          icon: const Icon(Icons.tv_rounded),
                          label: const Text('Receive (Receiver)'),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 12),

                TextButton.icon(
                  onPressed: _showUsbGuide,
                  icon: const Icon(Icons.cable_rounded, size: 16),
                  label: const Text('How to connect with USB OTG cable'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
