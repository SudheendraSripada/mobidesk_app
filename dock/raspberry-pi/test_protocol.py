#!/usr/bin/env python3
"""
Python Protocol Verification Test (test_protocol.py)

Tests FramingProtocol encoding, decoding, and parsing against the golden vectors
defined in tests/golden/frames.json.
"""

import os
import sys
import json
import struct

# Add parent directory to path to import mobidesk_dock
current_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, current_dir)

import mobidesk_dock


def find_golden_file():
    candidates = [
        os.path.join(current_dir, "..", "..", "tests", "golden", "frames.json"),
        os.path.join(current_dir, "..", "..", "..", "tests", "golden", "frames.json"),
        os.path.abspath("tests/golden/frames.json")
    ]
    for c in candidates:
        if os.path.exists(c):
            return os.path.abspath(c)
    raise FileNotFoundError("Could not find tests/golden/frames.json")


def run_tests():
    golden_path = find_golden_file()
    print(f"Loading golden vectors from: {golden_path}")
    with open(golden_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    vectors = {v['name']: v for v in data['vectors']}
    passed = 0
    total = 0

    print("=" * 60)
    print("MobiDesk Python FramingProtocol Golden Vector Verification")
    print("=" * 60)

    for name, v in vectors.items():
        total += 1
        expected_hex = v['packet_hex']
        expected_bytes = bytes.fromhex(expected_hex)
        pkt_type = v['type']
        flags = v['flags']
        payload_len = v['payload_length']
        pts_us = v['pts_us']
        payload_bytes = bytes.fromhex(v['payload_hex']) if v['payload_hex'] else b''

        # 1. Test header decoding
        dec_type, dec_flags, dec_len, dec_pts = mobidesk_dock.parse_header(expected_bytes[:16])
        assert dec_type == pkt_type, f"[{name}] Type mismatch: {dec_type} != {pkt_type}"
        assert dec_flags == flags, f"[{name}] Flags mismatch: {dec_flags} != {flags}"
        assert dec_len == payload_len, f"[{name}] Length mismatch: {dec_len} != {payload_len}"
        assert dec_pts == pts_us, f"[{name}] PtsUs mismatch: {dec_pts} != {pts_us}"

        # 2. Test packet encoding
        if name == 'CONFIG':
            encoded = mobidesk_dock.create_header(pkt_type, flags, len(payload_bytes), pts_us) + payload_bytes
        elif name == 'FRAME':
            encoded = mobidesk_dock.create_header(pkt_type, flags, len(payload_bytes), pts_us) + payload_bytes
        elif name == 'HEARTBEAT':
            encoded = mobidesk_dock.create_heartbeat_packet(pts_us=pts_us)
        elif name == 'SLEEP':
            encoded = mobidesk_dock.create_header(pkt_type, flags, len(payload_bytes), pts_us) + payload_bytes
        elif name == 'DISPLAY_INFO':
            fields = v['fields']
            encoded = mobidesk_dock.create_display_info_packet(
                fields['width'], fields['height'], fields['fps'], pts_us=pts_us
            )
            # Test payload decoding
            w, h, fps = mobidesk_dock.parse_display_info_payload(payload_bytes)
            assert w == fields['width'] and h == fields['height'] and fps == fields['fps']
        elif name == 'INPUT_MOUSE':
            fields = v['fields']
            encoded = mobidesk_dock.create_input_mouse_packet(
                norm_x=fields['norm_x'],
                norm_y=fields['norm_y'],
                button_mask=fields['button_mask'],
                wheel_dx=fields['wheel_dx'],
                wheel_dy=fields['wheel_dy'],
                pts_us=pts_us
            )
            parsed = mobidesk_dock.parse_input_mouse_payload(payload_bytes)
            assert parsed['norm_x'] == fields['norm_x']
            assert parsed['norm_y'] == fields['norm_y']
            assert parsed['button_mask'] == fields['button_mask']
            assert parsed['wheel_dx'] == fields['wheel_dx']
            assert parsed['wheel_dy'] == fields['wheel_dy']
        elif name == 'INPUT_KEY':
            fields = v['fields']
            encoded = mobidesk_dock.create_input_key_packet(
                key_code=fields['key_code'],
                state=fields['state'],
                modifier_mask=fields['modifier_mask'],
                pts_us=pts_us
            )
            parsed = mobidesk_dock.parse_input_key_payload(payload_bytes)
            assert parsed['key_code'] == fields['key_code']
            assert parsed['state'] == fields['state']
            assert parsed['modifier_mask'] == fields['modifier_mask']
        else:
            raise ValueError(f"Unknown vector: {name}")

        assert encoded.hex() == expected_hex, f"[{name}] Encoded hex mismatch:\nGot:  {encoded.hex()}\nWant: {expected_hex}"
        print(f" [PASS] Vector '{name}': {len(encoded)} bytes exact match ({v['description']})")
        passed += 1

    # Additional Test: Stream fragmentation and garbage resynchronization
    total += 1
    stream = b"GARBAGE_NOISE_12345" + bytes.fromhex(vectors['DISPLAY_INFO']['packet_hex']) + bytes.fromhex(vectors['INPUT_MOUSE']['packet_hex'])
    # Resynchronize by finding magic 'MB'
    idx = stream.find(mobidesk_dock.MAGIC_BYTES)
    assert idx != -1
    stream = stream[idx:]
    t1, f1, l1, p1 = mobidesk_dock.parse_header(stream[:16])
    assert t1 == mobidesk_dock.TYPE_DISPLAY_INFO
    stream = stream[16 + l1:]
    t2, f2, l2, p2 = mobidesk_dock.parse_header(stream[:16])
    assert t2 == mobidesk_dock.TYPE_INPUT_MOUSE
    print(f" [PASS] Stream fragmentation and garbage resynchronization passed")
    passed += 1

    print("=" * 60)
    print(f"Result: {passed}/{total} tests passed successfully.")
    print("=" * 60)
    return 0


if __name__ == '__main__':
    sys.exit(run_tests())
