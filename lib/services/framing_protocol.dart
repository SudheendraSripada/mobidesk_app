import 'dart:typed_data';

/// Binary Framing Protocol implementation for MobiDesk USB Bulk streaming.
///
/// 16-byte Header Layout:
/// - [0..1]  Magic bytes: 0x4D, 0x42 ('MB')
/// - [2]     Packet Type:
///           1 = TYPE_CONFIG (SPS/PPS)
///           2 = TYPE_FRAME (Video NAL)
///           3 = TYPE_HEARTBEAT
///           4 = TYPE_SLEEP
///           5 = TYPE_DISPLAY_INFO (Monitor width, height, fps)
/// - [3]     Flags: 0x01 = KEY_FRAME, 0x00 = NONE
/// - [4..7]  Payload Length (32-bit big-endian unsigned int)
/// - [8..15] Presentation Time Stamp in microseconds (64-bit big-endian)
class FramingProtocol {
  static const int magic0 = 0x4D; // 'M'
  static const int magic1 = 0x42; // 'B'
  static const int headerSize = 16;

  static const int typeConfig = 1;
  static const int typeFrame = 2;
  static const int typeHeartbeat = 3;
  static const int typeSleep = 4;
  static const int typeDisplayInfo = 5;
  static const int typeInputMouse = 6;
  static const int typeInputKey = 7;

  static const int flagNone = 0x00;
  static const int flagKeyframe = 0x01;

  /// Creates a 16-byte header.
  static Uint8List createHeader({
    required int type,
    required int flags,
    required int payloadLength,
    int ptsUs = 0,
  }) {
    final bytes = Uint8List(headerSize);
    final bd = ByteData.sublistView(bytes);

    bytes[0] = magic0;
    bytes[1] = magic1;
    bytes[2] = type;
    bytes[3] = flags;
    bd.setUint32(4, payloadLength, Endian.big);
    bd.setUint64(8, ptsUs, Endian.big);

    return bytes;
  }

  /// Encodes a complete frame packet (header + payload).
  static Uint8List encodeFrame({
    required int type,
    required int flags,
    required Uint8List payload,
    int ptsUs = 0,
  }) {
    final header = createHeader(
      type: type,
      flags: flags,
      payloadLength: payload.length,
      ptsUs: ptsUs,
    );
    final packet = Uint8List(headerSize + payload.length);
    packet.setRange(0, headerSize, header);
    if (payload.isNotEmpty) {
      packet.setRange(headerSize, packet.length, payload);
    }
    return packet;
  }

  /// Creates a 12-byte payload for TYPE_DISPLAY_INFO.
  static Uint8List createDisplayInfoPayload({
    required int width,
    required int height,
    required int fps,
  }) {
    final bytes = Uint8List(12);
    final bd = ByteData.sublistView(bytes);
    bd.setUint32(0, width, Endian.big);
    bd.setUint32(4, height, Endian.big);
    bd.setUint32(8, fps, Endian.big);
    return bytes;
  }

  /// Parses a 12-byte TYPE_DISPLAY_INFO payload.
  static DisplayInfo parseDisplayInfo(Uint8List payload) {
    if (payload.length < 12) {
      throw ArgumentError(
        'Payload too short for DisplayInfo: ${payload.length} bytes (required: 12)',
      );
    }
    final bd = ByteData.sublistView(payload);
    final width = bd.getUint32(0, Endian.big);
    final height = bd.getUint32(4, Endian.big);
    final fps = bd.getUint32(8, Endian.big);
    return DisplayInfo(width: width, height: height, fps: fps);
  }

  /// Creates an 8-byte payload for TYPE_INPUT_MOUSE.
  static Uint8List createInputMousePayload({
    required int normX,
    required int normY,
    required int buttonMask,
    int wheelDx = 0,
    int wheelDy = 0,
  }) {
    final bytes = Uint8List(8);
    final bd = ByteData.sublistView(bytes);
    bd.setUint16(0, normX, Endian.big);
    bd.setUint16(2, normY, Endian.big);
    bd.setUint8(4, buttonMask);
    bd.setInt8(5, wheelDx);
    bd.setInt8(6, wheelDy);
    bd.setUint8(7, 0); // reserved
    return bytes;
  }

  /// Parses an 8-byte TYPE_INPUT_MOUSE payload.
  static InputMouseEvent parseInputMouse(Uint8List payload) {
    if (payload.length < 8) {
      throw ArgumentError(
        'Payload too short for InputMouseEvent: ${payload.length} bytes (required: 8)',
      );
    }
    final bd = ByteData.sublistView(payload);
    final normX = bd.getUint16(0, Endian.big);
    final normY = bd.getUint16(2, Endian.big);
    final buttonMask = bd.getUint8(4);
    final wheelDx = bd.getInt8(5);
    final wheelDy = bd.getInt8(6);
    return InputMouseEvent(
      normX: normX,
      normY: normY,
      buttonMask: buttonMask,
      wheelDx: wheelDx,
      wheelDy: wheelDy,
    );
  }

  /// Creates an 8-byte payload for TYPE_INPUT_KEY.
  static Uint8List createInputKeyPayload({
    required int keyCode,
    required int state,
    int modifierMask = 0,
  }) {
    final bytes = Uint8List(8);
    final bd = ByteData.sublistView(bytes);
    bd.setUint32(0, keyCode, Endian.big);
    bd.setUint8(4, state);
    bd.setUint8(5, modifierMask);
    bd.setUint16(6, 0, Endian.big); // reserved
    return bytes;
  }

  /// Parses an 8-byte TYPE_INPUT_KEY payload.
  static InputKeyEvent parseInputKey(Uint8List payload) {
    if (payload.length < 8) {
      throw ArgumentError(
        'Payload too short for InputKeyEvent: ${payload.length} bytes (required: 8)',
      );
    }
    final bd = ByteData.sublistView(payload);
    final keyCode = bd.getUint32(0, Endian.big);
    final state = bd.getUint8(4);
    final modifierMask = bd.getUint8(5);
    return InputKeyEvent(
      keyCode: keyCode,
      state: state,
      modifierMask: modifierMask,
    );
  }
}

/// Mouse input event forward packet data.
class InputMouseEvent {
  final int normX; // 0..65535
  final int normY; // 0..65535
  final int buttonMask; // bit 0 = Left, bit 1 = Middle, bit 2 = Right
  final int wheelDx;
  final int wheelDy;

  const InputMouseEvent({
    required this.normX,
    required this.normY,
    required this.buttonMask,
    this.wheelDx = 0,
    this.wheelDy = 0,
  });

  bool get isLeftDown => (buttonMask & 0x01) != 0;
  bool get isMiddleDown => (buttonMask & 0x02) != 0;
  bool get isRightDown => (buttonMask & 0x04) != 0;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is InputMouseEvent &&
          runtimeType == other.runtimeType &&
          normX == other.normX &&
          normY == other.normY &&
          buttonMask == other.buttonMask &&
          wheelDx == other.wheelDx &&
          wheelDy == other.wheelDy;

  @override
  int get hashCode =>
      normX.hashCode ^
      normY.hashCode ^
      buttonMask.hashCode ^
      wheelDx.hashCode ^
      wheelDy.hashCode;
}

/// Keyboard input event forward packet data.
class InputKeyEvent {
  final int keyCode;
  final int state; // 0 = up, 1 = down
  final int modifierMask; // bit 0 = Shift, bit 1 = Ctrl, bit 2 = Alt, bit 3 = Meta

  const InputKeyEvent({
    required this.keyCode,
    required this.state,
    this.modifierMask = 0,
  });

  bool get isDown => state != 0;
  bool get isShiftDown => (modifierMask & 0x01) != 0;
  bool get isCtrlDown => (modifierMask & 0x02) != 0;
  bool get isAltDown => (modifierMask & 0x04) != 0;
  bool get isMetaDown => (modifierMask & 0x08) != 0;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is InputKeyEvent &&
          runtimeType == other.runtimeType &&
          keyCode == other.keyCode &&
          state == other.state &&
          modifierMask == other.modifierMask;

  @override
  int get hashCode => keyCode.hashCode ^ state.hashCode ^ modifierMask.hashCode;
}

/// Represents the monitor resolution and refresh rate received from the dock.
class DisplayInfo {
  final int width;
  final int height;
  final int fps;

  const DisplayInfo({
    required this.width,
    required this.height,
    required this.fps,
  });

  @override
  String toString() => '${width}x$height @ ${fps}fps';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is DisplayInfo &&
          runtimeType == other.runtimeType &&
          width == other.width &&
          height == other.height &&
          fps == other.fps;

  @override
  int get hashCode => width.hashCode ^ height.hashCode ^ fps.hashCode;
}

/// Parsed FramingProtocol packet.
class FramePacket {
  final int type;
  final int flags;
  final int ptsUs;
  final Uint8List payload;

  const FramePacket({
    required this.type,
    required this.flags,
    required this.ptsUs,
    required this.payload,
  });

  bool get isKeyframe => (flags & FramingProtocol.flagKeyframe) != 0;
}

/// Streaming demuxer for incoming USB chunks.
class FramingDemuxer {
  final List<int> _buffer = [];

  void feedData(Uint8List chunk, void Function(FramePacket frame) onFrame) {
    _buffer.addAll(chunk);

    while (_buffer.length >= FramingProtocol.headerSize) {
      // Find magic bytes
      if (_buffer[0] != FramingProtocol.magic0 || _buffer[1] != FramingProtocol.magic1) {
        int syncPos = -1;
        for (int i = 1; i < _buffer.length - 1; i++) {
          if (_buffer[i] == FramingProtocol.magic0 && _buffer[i + 1] == FramingProtocol.magic1) {
            syncPos = i;
            break;
          }
        }
        if (syncPos != -1) {
          _buffer.removeRange(0, syncPos);
        } else {
          if (_buffer.last == FramingProtocol.magic0) {
            _buffer.removeRange(0, _buffer.length - 1);
          } else {
            _buffer.clear();
          }
          return;
        }
      }

      if (_buffer.length < FramingProtocol.headerSize) break;

      final bd = ByteData.sublistView(Uint8List.fromList(_buffer.sublist(0, FramingProtocol.headerSize)));
      final type = bd.getUint8(2);
      final flags = bd.getUint8(3);
      final payloadLen = bd.getUint32(4, Endian.big);
      final ptsUs = bd.getUint64(8, Endian.big);

      final totalSize = FramingProtocol.headerSize + payloadLen;
      if (_buffer.length < totalSize) {
        break; // Incomplete payload, wait for next chunk
      }

      final payload = Uint8List.fromList(
        _buffer.sublist(FramingProtocol.headerSize, totalSize),
      );

      _buffer.removeRange(0, totalSize);

      onFrame(FramePacket(
        type: type,
        flags: flags,
        ptsUs: ptsUs,
        payload: payload,
      ));
    }
  }

  void reset() {
    _buffer.clear();
  }
}
