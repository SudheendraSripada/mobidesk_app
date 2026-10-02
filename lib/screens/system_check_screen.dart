import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/usb_stream_service.dart';

class SystemCheckItem {
  final String key;
  final String title;
  final String description;
  bool isRunning;
  bool? isPassed;
  String detail;

  SystemCheckItem({
    required this.key,
    required this.title,
    required this.description,
    this.isRunning = false,
    this.isPassed,
    this.detail = 'Pending execution',
  });
}

class SystemCheckScreen extends StatefulWidget {
  const SystemCheckScreen({super.key});

  @override
  State<SystemCheckScreen> createState() => _SystemCheckScreenState();
}

class _SystemCheckScreenState extends State<SystemCheckScreen> {
  bool _isLoadingAll = false;

  final List<SystemCheckItem> _checks = [
    SystemCheckItem(
      key: 'usbAccessory',
      title: 'USB AOA Accessory Attached',
      description: 'Verifies dock connection via Android Open Accessory protocol',
    ),
    SystemCheckItem(
      key: 'avcEncoder',
      title: 'MediaCodec H.264/AVC Hardware Encoder',
      description: 'Checks hardware video encoder and supported resolutions/CBR modes',
    ),
    SystemCheckItem(
      key: 'virtualDisplay',
      title: 'VirtualDisplay Subsystem (1280x720)',
      description: 'Performs dry-run creation and release of Presentation VirtualDisplay',
    ),
    SystemCheckItem(
      key: 'foregroundService',
      title: 'Foreground Service (connectedDevice / FGS)',
      description: 'Validates Android 14/15 FGS permissions and prerequisites',
    ),
    SystemCheckItem(
      key: 'wifiLock',
      title: 'Low-Latency WifiLock',
      description: 'Tests acquiring and releasing low-latency WifiLock without power-save throttling',
    ),
    SystemCheckItem(
      key: 'wakeLock',
      title: 'Partial WakeLock',
      description: 'Ensures CPU stays awake when phone screen is turned off during streaming',
    ),
    SystemCheckItem(
      key: 'notifications',
      title: 'Post Notifications Permission',
      description: 'Checks POST_NOTIFICATIONS permission required for foreground streaming service',
    ),
    SystemCheckItem(
      key: 'batteryOptimization',
      title: 'Battery Optimization Exemption',
      description: 'Verifies unrestricted background execution to prevent OS killing stream',
    ),
    SystemCheckItem(
      key: 'storage',
      title: 'Internal Log Storage (~1 MB Ring Buffer)',
      description: 'Verifies write access to internal app storage for diagnostics logging',
    ),
    SystemCheckItem(
      key: 'presentationDisplay',
      title: 'Presentation Display Manager',
      description: 'Checks Android DisplayManager Presentation category availability',
    ),
    SystemCheckItem(
      key: 'networkConnectivity',
      title: 'Network & Internet Connectivity',
      description: 'Validates active network connection and internet reachability',
    ),
  ];

  @override
  void initState() {
    super.initState();
    _runAllChecks();
  }

  Future<void> _runAllChecks() async {
    setState(() {
      _isLoadingAll = true;
      for (final check in _checks) {
        check.isRunning = true;
      }
    });

    final allResults = await UsbStreamService.runAllSystemChecks();

    if (!mounted) return;

    setState(() {
      for (final check in _checks) {
        check.isRunning = false;
        final res = allResults[check.key];
        if (res is Map) {
          check.isPassed = res['pass'] as bool? ?? false;
          check.detail = res['detail']?.toString() ?? 'No detail returned';
        } else {
          check.isPassed = false;
          check.detail = 'Check failed to execute';
        }
      }
      _isLoadingAll = false;
    });
  }

  Future<void> _runSingleCheck(SystemCheckItem item) async {
    setState(() {
      item.isRunning = true;
    });

    final res = await UsbStreamService.runSingleSystemCheck(item.key);

    if (!mounted) return;

    setState(() {
      item.isRunning = false;
      item.isPassed = res['pass'] as bool? ?? false;
      item.detail = res['detail']?.toString() ?? 'No detail returned';
    });
  }

  void _copyReportToClipboard() {
    final buffer = StringBuffer();
    buffer.writeln('# MobiDesk System Diagnostic Report');
    buffer.writeln('Generated at: ${DateTime.now().toUtc().toIso8601String()}');
    buffer.writeln('');

    int passedCount = 0;
    for (final check in _checks) {
      if (check.isPassed == true) passedCount++;
      final status = check.isPassed == true ? 'PASS' : 'FAIL';
      buffer.writeln('### [$status] ${check.title}');
      buffer.writeln('- Description: ${check.description}');
      buffer.writeln('- Result: ${check.detail}');
      buffer.writeln('');
    }

    buffer.writeln('**Summary:** $passedCount / ${_checks.length} checks passed.');

    Clipboard.setData(ClipboardData(text: buffer.toString()));

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Diagnostic report copied to clipboard ($passedCount/${_checks.length} passed)'),
        backgroundColor: Colors.teal[800],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    final passed = _checks.where((c) => c.isPassed == true).length;
    final total = _checks.length;

    return Scaffold(
      appBar: AppBar(
        title: const Text('System Check'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy_rounded),
            tooltip: 'Copy Report',
            onPressed: _copyReportToClipboard,
          ),
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'Re-run All Checks',
            onPressed: _isLoadingAll ? null : _runAllChecks,
          ),
        ],
      ),
      body: Column(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
            color: colorScheme.surfaceContainerHighest,
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Hardware & Service Prerequisites',
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '$passed of $total checks passed',
                        style: theme.textTheme.bodySmall?.copyWith(color: colorScheme.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
                FilledButton.tonalIcon(
                  onPressed: _copyReportToClipboard,
                  icon: const Icon(Icons.copy_rounded, size: 16),
                  label: const Text('Copy report'),
                ),
              ],
            ),
          ),
          if (_isLoadingAll) const LinearProgressIndicator(),
          Expanded(
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              itemCount: _checks.length,
              separatorBuilder: (context, index) => const Divider(height: 1),
              itemBuilder: (context, index) {
                final item = _checks[index];
                return ListTile(
                  contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                  leading: _buildStatusIcon(item, colorScheme),
                  title: Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.title,
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                        ),
                      ),
                      _buildStatusChip(item),
                    ],
                  ),
                  subtitle: Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          item.description,
                          style: TextStyle(fontSize: 12, color: colorScheme.onSurfaceVariant),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          item.detail,
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                            color: item.isPassed == true
                                ? Colors.green[800]
                                : (item.isPassed == false ? Colors.red[800] : colorScheme.outline),
                          ),
                        ),
                      ],
                    ),
                  ),
                  trailing: IconButton(
                    icon: const Icon(Icons.play_arrow_outlined, size: 20),
                    tooltip: 'Test again',
                    onPressed: item.isRunning ? null : () => _runSingleCheck(item),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusIcon(SystemCheckItem item, ColorScheme colorScheme) {
    if (item.isRunning) {
      return const SizedBox(
        width: 24,
        height: 24,
        child: CircularProgressIndicator(strokeWidth: 2),
      );
    }
    if (item.isPassed == true) {
      return const Icon(Icons.check_circle_rounded, color: Colors.green, size: 24);
    }
    if (item.isPassed == false) {
      return const Icon(Icons.cancel_rounded, color: Colors.red, size: 24);
    }
    return Icon(Icons.help_outline_rounded, color: colorScheme.outline, size: 24);
  }

  Widget _buildStatusChip(SystemCheckItem item) {
    if (item.isRunning) {
      return const SizedBox.shrink();
    }
    final passed = item.isPassed == true;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: passed ? Colors.green.withValues(alpha: 0.15) : Colors.red.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: passed ? Colors.green : Colors.red,
          width: 0.5,
        ),
      ),
      child: Text(
        passed ? 'PASS' : 'FAIL',
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.bold,
          color: passed ? Colors.green[800] : Colors.red[800],
        ),
      ),
    );
  }
}
