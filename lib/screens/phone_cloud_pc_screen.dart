import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/vm_connection.dart';
import '../services/guacamole_service.dart';
import '../services/usb_stream_service.dart';

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
  bool _isKeyboardVisible = false;
  final List<String> _keyEventLog = [];

  @override
  void initState() {
    super.initState();
    _sessionUrl = GuacamoleService.buildClientUrl(
      connectionId: widget.vm.guacConnectionId,
      authToken: widget.authToken,
    );

    // Prefer native immersive fullscreen activity on Android
    UsbStreamService.startPhoneCloudPc(_sessionUrl);
  }

  void _injectKey(String keyName) {
    setState(() {
      _keyEventLog.add(keyName);
      if (_keyEventLog.length > 5) _keyEventLog.removeAt(0);
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Injected key: $keyName'),
        duration: const Duration(milliseconds: 600),
      ),
    );
  }

  void _reconnect() {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Reconnecting to Windows Cloud PC...'),
        backgroundColor: Colors.indigo,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.grey[900],
        foregroundColor: Colors.white,
        title: Text(widget.vm.name, style: const TextStyle(fontSize: 16)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            tooltip: 'Reconnect',
            onPressed: _reconnect,
          ),
          IconButton(
            icon: const Icon(Icons.close_rounded),
            tooltip: 'Disconnect',
            onPressed: () => Navigator.of(context).pop(),
          ),
        ],
      ),
      body: Stack(
        children: [
          // Simulated / Native Guacamole HTML5 Canvas Client
          Center(
            child: Container(
              color: const Color(0xFF0078D4), // Windows blue
              alignment: Alignment.center,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(
                    Icons.desktop_windows_rounded,
                    size: 64,
                    color: Colors.white,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    widget.vm.name,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Host: ${widget.vm.hostname} (RDP :3389 via Guacamole)',
                    style: const TextStyle(color: Colors.white70, fontSize: 13),
                  ),
                  const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.black38,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      'Session URL: $_sessionUrl',
                      style: const TextStyle(
                        color: Colors.white60,
                        fontSize: 11,
                        fontFamily: 'monospace',
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (_keyEventLog.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    Text(
                      'Recent Key Inputs: ${_keyEventLog.join(' → ')}',
                      style: const TextStyle(
                        color: Colors.amberAccent,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),

          // Floating helper toolbar for mobile control
          Positioned(
            bottom: 16,
            left: 16,
            right: 16,
            child: Center(
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.85),
                  borderRadius: BorderRadius.circular(32),
                  border: Border.all(color: Colors.white24),
                ),
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      // Keyboard Toggle
                      TextButton.icon(
                        style: TextButton.styleFrom(
                          foregroundColor: Colors.white,
                        ),
                        icon: const Icon(Icons.keyboard_alt_outlined, size: 18),
                        label: Text(
                          _isKeyboardVisible ? 'Hide KB' : 'Keyboard',
                        ),
                        onPressed: () {
                          setState(
                            () => _isKeyboardVisible = !_isKeyboardVisible,
                          );
                          if (_isKeyboardVisible) {
                            SystemChannels.textInput.invokeMethod(
                              'TextInput.show',
                            );
                          } else {
                            SystemChannels.textInput.invokeMethod(
                              'TextInput.hide',
                            );
                          }
                        },
                      ),
                      const VerticalDivider(color: Colors.white30, width: 16),

                      // Shortcut Keys
                      _helperKeyButton('Ctrl', () => _injectKey('Ctrl')),
                      _helperKeyButton('Alt', () => _injectKey('Alt')),
                      _helperKeyButton('⊞ Win', () => _injectKey('Windows')),
                      _helperKeyButton('Esc', () => _injectKey('Escape')),
                      _helperKeyButton('Tab', () => _injectKey('Tab')),

                      const VerticalDivider(color: Colors.white30, width: 16),

                      // Disconnect
                      IconButton(
                        icon: const Icon(
                          Icons.power_settings_new_rounded,
                          color: Colors.redAccent,
                          size: 20,
                        ),
                        tooltip: 'Disconnect',
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _helperKeyButton(String label, VoidCallback onPressed) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 3),
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          foregroundColor: Colors.white,
          side: const BorderSide(color: Colors.white30),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          minimumSize: const Size(40, 32),
        ),
        onPressed: onPressed,
        child: Text(label, style: const TextStyle(fontSize: 12)),
      ),
    );
  }
}
