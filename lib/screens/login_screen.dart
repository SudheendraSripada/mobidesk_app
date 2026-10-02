import 'package:flutter/material.dart';
import '../config/app_config.dart';
import '../models/vm_connection.dart';
import '../services/app_logger.dart';
import '../services/auth_storage.dart';
import '../services/guacamole_service.dart';
import '../services/supabase_service.dart';
import '../services/usb_stream_service.dart';
import 'dashboard_screen.dart';
import 'developer_tools_screen.dart';
import 'monitor_mode_screen.dart';
import 'setup_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _usernameController = TextEditingController(text: 'student@mobidesk.edu');
  final _passwordController = TextEditingController(text: 'MobiDesk2026!');
  bool _isLoading = false;
  bool _obscurePassword = true;
  bool _keepMeSignedIn = true;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _checkStoredCredentialsAndPendingDock();
  }

  Future<void> _checkStoredCredentialsAndPendingDock() async {
    final creds = await AuthStorage.getCredentials();
    final keep = creds['keepSignedIn'] == 'true';
    if (creds['username'] != null && creds['username']!.isNotEmpty) {
      _usernameController.text = creds['username']!;
    }
    if (creds['password'] != null && creds['password']!.isNotEmpty) {
      _passwordController.text = creds['password']!;
    }
    if (mounted) {
      setState(() {
        _keepMeSignedIn = keep;
      });
    }

    if (keep && creds['username'] != null && creds['password'] != null) {
      AppLogger.i('LoginScreen', 'Auto-signing in from secure storage');
      _handleLogin(isSilent: true);
    }
  }

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _handleLogin({bool isSilent = false}) async {
    if (!isSilent && !_formKey.currentState!.validate()) return;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    final username = _usernameController.text.trim();
    final password = _passwordController.text;

    try {
      // 1. Authenticate against Guacamole REST API (/api/tokens)
      final guacResult = await GuacamoleService.login(
        username: username,
        password: password,
      );

      final authToken = guacResult.authToken ?? 'DEMO_TOKEN_2026';

      // 2. Load student profile and VM mapping from Supabase (or mock fallback)
      final student = await SupabaseService.getStudentProfile();
      final vms = await SupabaseService.getAssignedVms();
      final vm = vms.isNotEmpty ? vms.first : VmConnection.mock();

      // 3. Persist credentials in encrypted storage
      await AuthStorage.saveCredentials(
        username: username,
        password: password,
        authToken: authToken,
        keepSignedIn: _keepMeSignedIn,
      );

      // 4. Check if dock was attached prior to login
      final hasPendingDock = await UsbStreamService.hasPendingDockAttach(consume: true);
      final autoLaunch = await UsbStreamService.checkAutoLaunchMonitor();

      if (!mounted) return;

      if (hasPendingDock || autoLaunch) {
        AppLogger.i('LoginScreen', 'Pending dock attach detected: auto-navigating to Monitor mode');
        Navigator.of(context).pushReplacement(
          MaterialPageRoute<void>(
            builder: (_) => MonitorModeScreen(
              vm: vm,
              authToken: authToken,
            ),
          ),
        );
      } else {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute<void>(
            builder: (_) => DashboardScreen(
              student: student,
              vm: vm,
              guacAuthToken: authToken,
            ),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage = 'Login failed: $e';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  void _quickDemoLogin() {
    _usernameController.text = 'student@mobidesk.edu';
    _passwordController.text = 'MobiDesk2026!';
    AppConfig().isDemoMode = true;
    _handleLogin();
  }

  void _openDeveloperTools() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => const DeveloperToolsScreen(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: GestureDetector(
          onLongPress: _openDeveloperTools,
          child: const Text('MobiDesk'),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.tune_rounded),
            tooltip: 'Server Setup',
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const SetupScreen(),
                ),
              );
            },
          ),
        ],
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Form(
              key: _formKey,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // App Logo with long-press trigger for hidden Developer Tools
                  GestureDetector(
                    onLongPress: _openDeveloperTools,
                    child: Center(
                      child: Container(
                        width: 76,
                        height: 76,
                        decoration: BoxDecoration(
                          color: colorScheme.primaryContainer,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.laptop_chromebook_rounded,
                          size: 40,
                          color: colorScheme.onPrimaryContainer,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Welcome to MobiDesk',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Student Cloud PC & Academic Workspace',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 24),

                  if (_errorMessage != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: colorScheme.errorContainer,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.error_outline_rounded, color: colorScheme.onErrorContainer),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              _errorMessage!,
                              style: TextStyle(color: colorScheme.onErrorContainer, fontSize: 13),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 16),
                  ] else if (!AppConfig().isSupabaseConfigured && !AppConfig().isDemoMode) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: colorScheme.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: colorScheme.outlineVariant),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.tune_rounded, color: colorScheme.primary, size: 20),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              'Supabase backend not configured. Use Quick Demo Login or configure in Setup.',
                              style: TextStyle(fontSize: 12, color: colorScheme.onSurfaceVariant),
                            ),
                          ),
                          TextButton(
                            onPressed: () {
                              Navigator.of(context).push(
                                MaterialPageRoute<void>(builder: (_) => const SetupScreen()),
                              );
                            },
                            child: const Text('Setup'),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 16),
                  ],

                  TextFormField(
                    controller: _usernameController,
                    decoration: const InputDecoration(
                      labelText: 'Student ID or Email',
                      prefixIcon: Icon(Icons.person_outline_rounded),
                      border: OutlineInputBorder(),
                    ),
                    validator: (val) {
                      if (val == null || val.trim().isEmpty) {
                        return 'Please enter your student ID or email';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 14),

                  TextFormField(
                    controller: _passwordController,
                    obscureText: _obscurePassword,
                    decoration: InputDecoration(
                      labelText: 'Password',
                      prefixIcon: const Icon(Icons.lock_outline_rounded),
                      border: const OutlineInputBorder(),
                      suffixIcon: IconButton(
                        icon: Icon(
                          _obscurePassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                        ),
                        onPressed: () {
                          setState(() => _obscurePassword = !_obscurePassword);
                        },
                      ),
                    ),
                    validator: (val) {
                      if (val == null || val.isEmpty) {
                        return 'Please enter your password';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 8),

                  CheckboxListTile(
                    value: _keepMeSignedIn,
                    onChanged: (val) {
                      setState(() => _keepMeSignedIn = val ?? true);
                    },
                    title: const Text('Keep me signed in', style: TextStyle(fontSize: 14)),
                    contentPadding: EdgeInsets.zero,
                    controlAffinity: ListTileControlAffinity.leading,
                    dense: true,
                  ),
                  const SizedBox(height: 12),

                  FilledButton(
                    onPressed: _isLoading ? null : () => _handleLogin(),
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                    ),
                    child: _isLoading
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Text('Sign In', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                  ),
                  const SizedBox(height: 10),

                  OutlinedButton.icon(
                    onPressed: _isLoading ? null : _quickDemoLogin,
                    icon: const Icon(Icons.play_arrow_rounded),
                    label: const Text('Quick Demo Login'),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                  const SizedBox(height: 16),

                  Text(
                    'Tip: Long-press the MobiDesk logo to access USB hardware developer tools.',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: colorScheme.outline,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
