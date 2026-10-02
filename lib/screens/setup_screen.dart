import 'package:flutter/material.dart';
import '../config/app_config.dart';

class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key});

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  late final TextEditingController _supabaseUrlController;
  late final TextEditingController _supabaseKeyController;
  late final TextEditingController _guacUrlController;

  @override
  void initState() {
    super.initState();
    final config = AppConfig();
    _supabaseUrlController = TextEditingController(text: config.supabaseUrl);
    _supabaseKeyController = TextEditingController(text: config.supabaseAnonKey);
    _guacUrlController = TextEditingController(text: config.guacamoleBaseUrl);
  }

  @override
  void dispose() {
    _supabaseUrlController.dispose();
    _supabaseKeyController.dispose();
    _guacUrlController.dispose();
    super.dispose();
  }

  void _saveSettings({required bool demoMode}) {
    AppConfig().update(
      newSupabaseUrl: _supabaseUrlController.text,
      newSupabaseAnonKey: _supabaseKeyController.text,
      newGuacamoleBaseUrl: _guacUrlController.text,
      demoMode: demoMode,
    );

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          demoMode
              ? 'Demo mode enabled with sample student & VM data.'
              : 'Server configuration saved.',
        ),
        backgroundColor: Colors.green,
      ),
    );

    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Server Configuration'),
        centerTitle: true,
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(
                  Icons.settings_suggest_rounded,
                  size: 48,
                  color: colorScheme.primary,
                ),
                const SizedBox(height: 12),
                Text(
                  'Backend Connection Setup',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Configure Supabase and Apache Guacamole endpoints. You can also run in complete offline Demo Mode.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 24),

                Card(
                  elevation: 0,
                  color: colorScheme.surfaceContainerHighest,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          'Supabase Database & Auth',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _supabaseUrlController,
                          decoration: const InputDecoration(
                            labelText: 'SUPABASE_URL',
                            hintText: 'https://xyzcompany.supabase.co',
                            prefixIcon: Icon(Icons.cloud_outlined),
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _supabaseKeyController,
                          obscureText: true,
                          decoration: const InputDecoration(
                            labelText: 'SUPABASE_ANON_KEY',
                            hintText: 'eyJhbGciOiJIUzI1NiIs...',
                            prefixIcon: Icon(Icons.key_outlined),
                            border: OutlineInputBorder(),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                Card(
                  elevation: 0,
                  color: colorScheme.surfaceContainerHighest,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          'Apache Guacamole Endpoint',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _guacUrlController,
                          decoration: const InputDecoration(
                            labelText: 'Guacamole Base URL',
                            hintText: 'http://192.168.1.100:8080/guacamole',
                            prefixIcon: Icon(Icons.desktop_windows_outlined),
                            border: OutlineInputBorder(),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 24),

                FilledButton.icon(
                  onPressed: () => _saveSettings(demoMode: false),
                  icon: const Icon(Icons.save_rounded),
                  label: const Text('Save & Connect to Backend'),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
                const SizedBox(height: 12),

                OutlinedButton.icon(
                  onPressed: () => _saveSettings(demoMode: true),
                  icon: const Icon(Icons.play_circle_outline_rounded),
                  label: const Text('Use Mock / Demo Mode (Offline)'),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
