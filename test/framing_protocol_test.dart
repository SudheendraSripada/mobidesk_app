import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobidesk_app/services/framing_protocol.dart';

void main() {
  group('FramingProtocol Tests', () {
    test('Header constants and structure', () {
      expect(FramingProtocol.magic0, 0x4D); // 'M'
      expect(FramingProtocol.magic1, 0x42); // 'B'
      expect(FramingProtocol.headerSize, 16);
      expect(FramingProtocol.typeConfig, 1);
      expect(FramingProtocol.typeFrame, 2);
      expect(FramingProtocol.typeHeartbeat, 3);
      expect(FramingProtocol.typeSleep, 4);
      expect(FramingProtocol.typeDisplayInfo, 5);
      expect(FramingProtocol.flagNone, 0x00);
      expect(FramingProtocol.flagKeyframe, 0x01);
    });

    test('Header creation layout and byte order', () {
      final header = FramingProtocol.createHeader(
        type: FramingProtocol.typeDisplayInfo,
        flags: FramingProtocol.flagNone,
        payloadLength: 12,
        ptsUs: 987654321,
      );

      expect(header.length, 16);
      expect(header[0], 0x4D);
      expect(header[1], 0x42);
      expect(header[2], 5); // TYPE_DISPLAY_INFO
      expect(header[3], 0); // FLAG_NONE

      final bd = ByteData.sublistView(header);
      expect(bd.getUint32(4, Endian.big), 12);
      expect(bd.getUint64(8, Endian.big), 987654321);
    });

    test('TYPE_DISPLAY_INFO payload round-trip', () {
      const width = 1920;
      const height = 1080;
      const fps = 60;

      final payload = FramingProtocol.createDisplayInfoPayload(
        width: width,
        height: height,
        fps: fps,
      );

      expect(payload.length, 12);

      final displayInfo = FramingProtocol.parseDisplayInfo(payload);
      expect(displayInfo.width, width);
      expect(displayInfo.height, height);
      expect(displayInfo.fps, fps);
      expect(displayInfo.toString(), '1920x1080 @ 60fps');
    });

    test('parseDisplayInfo throws ArgumentError on too-short payload', () {
      final shortPayload = Uint8List(8);
      expect(
        () => FramingProtocol.parseDisplayInfo(shortPayload),
        throwsArgumentError,
      );
    });

    test('Encode complete frame packet', () {
      final payload = Uint8List.fromList([1, 2, 3, 4, 5]);
      final packet = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeFrame,
        flags: FramingProtocol.flagKeyframe,
        payload: payload,
        ptsUs: 12345,
      );

      expect(packet.length, 16 + 5);
      expect(packet[0], 0x4D);
      expect(packet[1], 0x42);
      expect(packet[2], FramingProtocol.typeFrame);
      expect(packet[3], FramingProtocol.flagKeyframe);
      expect(packet.sublist(16), equals(payload));
    });

    test('FramingDemuxer single frame parsing', () {
      final demuxer = FramingDemuxer();
      final receivedFrames = <FramePacket>[];

      final payload = Uint8List.fromList([10, 20, 30]);
      final packet = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeConfig,
        flags: FramingProtocol.flagKeyframe,
        payload: payload,
        ptsUs: 500,
      );

      demuxer.feedData(packet, (frame) => receivedFrames.add(frame));

      expect(receivedFrames.length, 1);
      expect(receivedFrames[0].type, FramingProtocol.typeConfig);
      expect(receivedFrames[0].flags, FramingProtocol.flagKeyframe);
      expect(receivedFrames[0].ptsUs, 500);
      expect(receivedFrames[0].payload, equals(payload));
      expect(receivedFrames[0].isKeyframe, isTrue);
    });

    test('FramingDemuxer TYPE_DISPLAY_INFO stream parsing', () {
      final demuxer = FramingDemuxer();
      final receivedFrames = <FramePacket>[];

      final displayPayload = FramingProtocol.createDisplayInfoPayload(
        width: 2560,
        height: 1440,
        fps: 120,
      );

      final packet = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeDisplayInfo,
        flags: FramingProtocol.flagNone,
        payload: displayPayload,
        ptsUs: 0,
      );

      demuxer.feedData(packet, (frame) => receivedFrames.add(frame));

      expect(receivedFrames.length, 1);
      expect(receivedFrames[0].type, FramingProtocol.typeDisplayInfo);

      final parsed = FramingProtocol.parseDisplayInfo(receivedFrames[0].payload);
      expect(parsed.width, 2560);
      expect(parsed.height, 1440);
      expect(parsed.fps, 120);
    });

    test('FramingDemuxer fragmented feed and garbage resynchronization', () {
      final demuxer = FramingDemuxer();
      final receivedFrames = <FramePacket>[];

      final payload = Uint8List.fromList([42, 84, 126]);
      final validPacket = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeFrame,
        flags: FramingProtocol.flagNone,
        payload: payload,
        ptsUs: 777,
      );

      // Prepend garbage bytes before the valid frame
      final streamWithGarbage = Uint8List.fromList([
        0x00, 0x11, 0x4D, 0x00, 0x99, ...validPacket,
      ]);

      // Feed one byte at a time
      for (final b in streamWithGarbage) {
        demuxer.feedData(Uint8List.fromList([b]), (frame) => receivedFrames.add(frame));
      }

      expect(receivedFrames.length, 1);
      expect(receivedFrames[0].type, FramingProtocol.typeFrame);
      expect(receivedFrames[0].ptsUs, 777);
      expect(receivedFrames[0].payload, equals(payload));
    });

    test('TYPE_INPUT_MOUSE payload round-trip and properties', () {
      final payload = FramingProtocol.createInputMousePayload(
        normX: 32768,
        normY: 16384,
        buttonMask: 1,
        wheelDx: 0,
        wheelDy: -1,
      );
      expect(payload.length, 8);
      final mouse = FramingProtocol.parseInputMouse(payload);
      expect(mouse.normX, 32768);
      expect(mouse.normY, 16384);
      expect(mouse.buttonMask, 1);
      expect(mouse.isLeftDown, isTrue);
      expect(mouse.isMiddleDown, isFalse);
      expect(mouse.isRightDown, isFalse);
      expect(mouse.wheelDx, 0);
      expect(mouse.wheelDy, -1);
    });

    test('TYPE_INPUT_KEY payload round-trip and properties', () {
      final payload = FramingProtocol.createInputKeyPayload(
        keyCode: 28,
        state: 1,
        modifierMask: 2,
      );
      expect(payload.length, 8);
      final key = FramingProtocol.parseInputKey(payload);
      expect(key.keyCode, 28);
      expect(key.state, 1);
      expect(key.isDown, isTrue);
      expect(key.isCtrlDown, isTrue);
      expect(key.isShiftDown, isFalse);
      expect(key.isAltDown, isFalse);
      expect(key.isMetaDown, isFalse);
    });

    test('Golden vectors decode and encode match exact byte specifications', () {
      final goldenHexes = <String, String>{
        'CONFIG': '4d4201010000000800000000000003e8000000016742001f',
        'FRAME': '4d42020000000008000000000000823500000001419a2401',
        'HEARTBEAT': '4d420301000000000000000000000000',
        'SLEEP': '4d42040000000001000000000000000001',
        'DISPLAY_INFO': '4d4205000000000c000000000000000000000780000004380000003c',
        'INPUT_MOUSE': '4d42060000000008000000000000c350800040000100ff00',
        'INPUT_KEY': '4d4207000000000800000000000124f80000001c01020000',
      };

      Uint8List hexToBytes(String hex) {
        final result = Uint8List(hex.length ~/ 2);
        for (int i = 0; i < result.length; i++) {
          result[i] = int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16);
        }
        return result;
      }

      String bytesToHex(Uint8List bytes) {
        return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
      }

      // 1. CONFIG
      final configPayload = hexToBytes('000000016742001f');
      final encodedConfig = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeConfig,
        flags: FramingProtocol.flagKeyframe,
        payload: configPayload,
        ptsUs: 1000,
      );
      expect(bytesToHex(encodedConfig), goldenHexes['CONFIG']);

      // 2. FRAME
      final framePayload = hexToBytes('00000001419a2401');
      final encodedFrame = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeFrame,
        flags: FramingProtocol.flagNone,
        payload: framePayload,
        ptsUs: 33333,
      );
      expect(bytesToHex(encodedFrame), goldenHexes['FRAME']);

      // 3. HEARTBEAT
      final encodedHb = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeHeartbeat,
        flags: FramingProtocol.flagKeyframe,
        payload: Uint8List(0),
        ptsUs: 0,
      );
      expect(bytesToHex(encodedHb), goldenHexes['HEARTBEAT']);

      // 4. SLEEP
      final encodedSleep = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeSleep,
        flags: FramingProtocol.flagNone,
        payload: Uint8List.fromList([1]),
        ptsUs: 0,
      );
      expect(bytesToHex(encodedSleep), goldenHexes['SLEEP']);

      // 5. DISPLAY_INFO
      final dispPayload = FramingProtocol.createDisplayInfoPayload(
        width: 1920,
        height: 1080,
        fps: 60,
      );
      final encodedDisp = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeDisplayInfo,
        flags: FramingProtocol.flagNone,
        payload: dispPayload,
        ptsUs: 0,
      );
      expect(bytesToHex(encodedDisp), goldenHexes['DISPLAY_INFO']);
      final parsedDisp = FramingProtocol.parseDisplayInfo(dispPayload);
      expect(parsedDisp.width, 1920);
      expect(parsedDisp.height, 1080);
      expect(parsedDisp.fps, 60);

      // 6. INPUT_MOUSE
      final mousePayload = FramingProtocol.createInputMousePayload(
        normX: 32768,
        normY: 16384,
        buttonMask: 1,
        wheelDx: 0,
        wheelDy: -1,
      );
      final encodedMouse = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeInputMouse,
        flags: FramingProtocol.flagNone,
        payload: mousePayload,
        ptsUs: 50000,
      );
      expect(bytesToHex(encodedMouse), goldenHexes['INPUT_MOUSE']);
      final parsedMouse = FramingProtocol.parseInputMouse(mousePayload);
      expect(parsedMouse.normX, 32768);
      expect(parsedMouse.normY, 16384);
      expect(parsedMouse.buttonMask, 1);
      expect(parsedMouse.wheelDy, -1);

      // 7. INPUT_KEY
      final keyPayload = FramingProtocol.createInputKeyPayload(
        keyCode: 28,
        state: 1,
        modifierMask: 2,
      );
      final encodedKey = FramingProtocol.encodeFrame(
        type: FramingProtocol.typeInputKey,
        flags: FramingProtocol.flagNone,
        payload: keyPayload,
        ptsUs: 75000,
      );
      expect(bytesToHex(encodedKey), goldenHexes['INPUT_KEY']);
      final parsedKey = FramingProtocol.parseInputKey(keyPayload);
      expect(parsedKey.keyCode, 28);
      expect(parsedKey.state, 1);
      expect(parsedKey.modifierMask, 2);
    });
  });
}
