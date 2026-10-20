import 'package:flutter/material.dart';

import '../config/app_config.dart';
import '../services/cloud_pc_api_service.dart';

/// Student-safe lifecycle controls for the one assigned pilot Cloud PC.
class CloudPcLifecycleCard extends StatefulWidget {
  final String supabaseAccessToken;
  final ValueChanged<bool> onReadinessChanged;

  const CloudPcLifecycleCard({
    super.key,
    required this.supabaseAccessToken,
    required this.onReadinessChanged,
  });

  @override
  State<CloudPcLifecycleCard> createState() => _CloudPcLifecycleCardState();
}

class _CloudPcLifecycleCardState extends State<CloudPcLifecycleCard> {
  CloudPcStatus? _status;
  bool _busy = false;
  String? _message;

  bool get _configured {
    final uri = Uri.tryParse(AppConfig().cloudPcApiBaseUrl);
    return uri != null && uri.scheme == 'https' && uri.host.isNotEmpty;
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _refresh());
  }

  Future<void> _refresh() async {
    if (AppConfig().isDemoMode) {
      if (!mounted) return;
      setState(() {
        _status = const CloudPcStatus(state: 'demo', ready: true);
        _message = 'Demo mode — lifecycle operations are simulated.';
      });
      widget.onReadinessChanged(true);
      return;
    }
    if (!_configured) {
      if (!mounted) return;
      setState(() {
        _status = null;
        _message = 'Cloud PC service is not configured for this build.';
      });
      widget.onReadinessChanged(false);
      return;
    }
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final result = await CloudPcApiService.status(
        supabaseAccessToken: widget.supabaseAccessToken,
      );
      if (!mounted) return;
      setState(() {
        _status = result;
        _message = null;
      });
      widget.onReadinessChanged(result.ready);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _message = e is CloudPcApiException
            ? e.message
            : 'Cloud PC status is unavailable. Please try again.';
        _status = null;
      });
      widget.onReadinessChanged(false);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _start() async {
    if (_busy || !_configured || AppConfig().isDemoMode) return;
    setState(() {
      _busy = true;
      _message = 'Starting your Cloud PC and checking readiness…';
    });
    widget.onReadinessChanged(false);
    try {
      final result = await CloudPcApiService.start(
        supabaseAccessToken: widget.supabaseAccessToken,
      );
      if (!mounted) return;
      setState(() {
        _status = result;
        _message = result.ready ? 'Your Cloud PC is ready.' : 'Cloud PC is starting.';
      });
      widget.onReadinessChanged(result.ready);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _message = e is CloudPcApiException
            ? e.message
            : 'Cloud PC could not be started. Please try again.';
        _status = null;
      });
      widget.onReadinessChanged(false);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _stop() async {
    if (_busy || !_configured || AppConfig().isDemoMode) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Stop your Cloud PC?'),
        content: const Text('Your remote desktop session will disconnect. Unsaved work may be lost.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Stop Cloud PC'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _busy = true;
      _message = 'Requesting Cloud PC shutdown…';
    });
    widget.onReadinessChanged(false);
    try {
      final result = await CloudPcApiService.stop(
        supabaseAccessToken: widget.supabaseAccessToken,
      );
      if (!mounted) return;
      setState(() {
        _status = result;
        _message = result.state == 'stopping'
            ? 'Shutdown requested. You can leave this screen.'
            : 'Cloud PC is stopped.';
      });
      widget.onReadinessChanged(false);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _message = e is CloudPcApiException
            ? e.message
            : 'Cloud PC could not be stopped. Please try again.';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final state = _status?.state;
    final ready = _status?.ready ?? false;
    final stateLabel = AppConfig().isDemoMode
        ? 'Demo'
        : ready
            ? 'Ready'
            : state == 'running'
                ? 'Starting'
                : state == 'stopping'
                    ? 'Stopping'
                    : state == 'stopped'
                        ? 'Stopped'
                        : 'Unavailable';

    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(Icons.cloud_outlined, color: colorScheme.primary),
                const SizedBox(width: 8),
                const Expanded(
                  child: Text('Cloud PC power', style: TextStyle(fontWeight: FontWeight.bold)),
                ),
                if (_busy)
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  TextButton.icon(
                    onPressed: _refresh,
                    icon: const Icon(Icons.refresh, size: 16),
                    label: const Text('Refresh'),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Text('Status: $stateLabel'),
            if (_message != null) ...[
              const SizedBox(height: 6),
              Text(_message!, style: Theme.of(context).textTheme.bodySmall),
            ],
            const SizedBox(height: 10),
            if (!AppConfig().isDemoMode)
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: _busy || !_configured || ready ? null : _start,
                      icon: const Icon(Icons.power_settings_new),
                      label: const Text('Start'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: _busy || !_configured || state == 'stopped' || state == null ? null : _stop,
                      icon: const Icon(Icons.stop_circle_outlined),
                      label: const Text('Stop'),
                    ),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
