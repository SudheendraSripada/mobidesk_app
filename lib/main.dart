import 'package:android_intent_plus/android_intent.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MobiDesk',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const MyHomePage(),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key});

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> with WidgetsBindingObserver {
  static const _streamChannel = MethodChannel('com.mobidesk/stream');
  bool _shouldStartStreamOnResume = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _shouldStartStreamOnResume) {
      _shouldStartStreamOnResume = false;
      _startStream();
    }
  }

  Future<void> _startStream() async {
    try {
      await _streamChannel.invokeMethod<void>('start');
    } on MissingPluginException {
      // The native stream implementation can be added independently.
    } on PlatformException {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Unable to start screen recording.')),
      );
    }
  }

  Future<void> _showSendDialog() async {
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('USB tethering required'),
        content: const Text('Turn on USB tethering to send the video output'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton.icon(
            onPressed: () async {
              Navigator.of(dialogContext).pop();
              _shouldStartStreamOnResume = true;
              const intent = AndroidIntent(
                action: 'android.settings.TETHER_SETTINGS',
              );
              try {
                await intent.launch();
              } on PlatformException {
                _shouldStartStreamOnResume = false;
                if (!mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Unable to open tethering settings.'),
                  ),
                );
              }
            },
            icon: const Icon(Icons.settings_outlined),
            label: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('MobiDesk')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(
                  Icons.devices_other,
                  size: 64,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(height: 20),
                Text(
                  'Share your screen',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 32),
                FilledButton.icon(
                  onPressed: _showSendDialog,
                  icon: const Icon(Icons.upload_rounded),
                  label: const Text('Send (Host)'),
                ),
                const SizedBox(height: 14),
                OutlinedButton.icon(
                  onPressed: () {},
                  icon: const Icon(Icons.download_rounded),
                  label: const Text('Receive (Client)'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
