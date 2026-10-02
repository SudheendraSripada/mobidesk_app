import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/app_logger.dart';

class LogViewerScreen extends StatefulWidget {
  const LogViewerScreen({super.key});

  @override
  State<LogViewerScreen> createState() => _LogViewerScreenState();
}

class _LogViewerScreenState extends State<LogViewerScreen> {
  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  String _rawLogs = 'Loading logs...';
  bool _isLoading = false;
  String _searchFilter = '';

  @override
  void initState() {
    super.initState();
    _fetchLogs();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _fetchLogs() async {
    setState(() => _isLoading = true);
    final logs = await AppLogger.getLogs();
    if (!mounted) return;
    setState(() {
      _rawLogs = logs;
      _isLoading = false;
    });
  }

  Future<void> _clearLogs() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Clear Logs?'),
        content: const Text('This will wipe all stored diagnostic log messages from the device.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            child: const Text('Clear'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await AppLogger.clearLogs();
      await _fetchLogs();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Diagnostics log file cleared')),
        );
      }
    }
  }

  Future<void> _shareLogs() async {
    await AppLogger.shareLogs();
  }

  void _copyToClipboard() {
    Clipboard.setData(ClipboardData(text: _filteredLogs));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Logs copied to clipboard')),
    );
  }

  String get _filteredLogs {
    if (_searchFilter.trim().isEmpty) return _rawLogs;
    final query = _searchFilter.trim().toLowerCase();
    final lines = _rawLogs.split('\n');
    final matching = lines.where((l) => l.toLowerCase().contains(query)).join('\n');
    return matching.isEmpty ? 'No lines match "$_searchFilter"' : matching;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final logText = _filteredLogs;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Diagnostic Logs'),
        actions: [
          IconButton(
            icon: const Icon(Icons.share_rounded),
            tooltip: 'Share Logs (ACTION_SEND)',
            onPressed: _shareLogs,
          ),
          IconButton(
            icon: const Icon(Icons.copy_rounded),
            tooltip: 'Copy to Clipboard',
            onPressed: _copyToClipboard,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline_rounded),
            tooltip: 'Clear Logs',
            onPressed: _clearLogs,
          ),
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'Refresh',
            onPressed: _isLoading ? null : _fetchLogs,
          ),
        ],
      ),
      body: Column(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            color: colorScheme.surfaceContainerHighest,
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: 'Filter logs (e.g. STATS, ERROR, AOA, MediaCodec)...',
                prefixIcon: const Icon(Icons.search_rounded, size: 20),
                suffixIcon: _searchFilter.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear_rounded, size: 18),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _searchFilter = '');
                        },
                      )
                    : null,
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              onChanged: (val) {
                setState(() => _searchFilter = val);
              },
            ),
          ),
          if (_isLoading) const LinearProgressIndicator(),
          Expanded(
            child: Container(
              color: Colors.black,
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              child: SingleChildScrollView(
                controller: _scrollController,
                child: SelectableText(
                  logText,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    color: Color(0xFF81C784), // Light terminal green
                    height: 1.35,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.small(
        onPressed: () {
          _scrollController.animateTo(
            _scrollController.position.maxScrollExtent,
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut,
          );
        },
        tooltip: 'Scroll to bottom',
        child: const Icon(Icons.arrow_downward_rounded),
      ),
    );
  }
}
