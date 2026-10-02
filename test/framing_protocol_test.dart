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
  });
}
