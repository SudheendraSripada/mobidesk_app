#!/usr/bin/env python3
"""
MobiDesk Raspberry Pi 4B USB Dock Daemon (mobidesk_dock.py)

Acts as a dedicated USB Host over a Pi USB-A port:
1. Detects connected Android device and initiates the Android Open Accessory (AOA 2.0) handshake.
2. Identifies monitor native resolution and refresh rate via DRM/KMS EDID.
3. Transmits TYPE_DISPLAY_INFO (width, height, fps) to the Android phone over the USB Bulk channel.
4. Reads incoming binary FramingProtocol packets (CONFIG/SPS/PPS and FRAME/H.264 NAL units).
5. Feeds H.264 video into a hardware-accelerated GStreamer pipeline (v4l2h264dec -> videoconvert -> kmssink)
   with automatic fallback to avdec_h264 if v4l2h264dec is unavailable.
6. Emits keyframe requests on start and every reconnect.
7. Reads evdev keyboard and mouse events, tracks an absolute cursor clamped to monitor size,
   and forwards them as TYPE_INPUT_MOUSE (6) and TYPE_INPUT_KEY (7) over USB bulk OUT.
8. Gracefully handles USB disconnection/replug without restarting the service.
9. Includes --selftest flag checking pyusb, GStreamer elements, DRM, and evdev.
"""

import os
import sys
import time
import glob
import struct
import signal
import logging
import threading

try:
    import usb.core
    import usb.util
except ImportError:
    usb = None

# evdev input handling
try:
    import evdev
    from evdev import ecodes
except ImportError:
    evdev = None
    ecodes = None

# GStreamer imports
try:
    import gi
    gi.require_version('Gst', '1.0')
    from gi.repository import Gst, GLib
except ImportError:
    Gst = None
    GLib = None

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] (MobiDeskDock) %(message)s'
)
logger = logging.getLogger("mobidesk_dock")

# FramingProtocol Constants (Endianness: strictly BIG-ENDIAN)
MAGIC_BYTES = b'MB'  # 0x4D, 0x42
HEADER_SIZE = 16

TYPE_CONFIG = 1
TYPE_FRAME = 2
TYPE_HEARTBEAT = 3
TYPE_SLEEP = 4
TYPE_DISPLAY_INFO = 5
TYPE_INPUT_MOUSE = 6
TYPE_INPUT_KEY = 7

FLAG_NONE = 0x00
FLAG_KEYFRAME = 0x01

# AOA 2.0 Handshake Constants
AOA_VENDOR_ID = 0x18D1
AOA_PRODUCT_IDS = {0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05}

AOA_GET_PROTOCOL = 51
AOA_SEND_STRING = 52
AOA_START_ACCESSORY = 53

AOA_STRINGS = [
    "MobiDesk",                      # 0: Manufacturer
    "MobiDeskDock",                  # 1: Model
    "MobiDesk Screen Receiver Dock", # 2: Description
    "1.0",                           # 3: Version
    "https://github.com/mobidesk",   # 4: URI
    "0000000012345678"               # 5: Serial
]


def detect_monitor_mode():
    """
    Detects the connected monitor resolution and refresh rate from Linux DRM/KMS sysfs.
    Falls back to 1920x1080@60 if undetected.
    """
    default_w, default_h, default_fps = 1920, 1080, 60

    try:
        # Check DRM modes in /sys/class/drm/card*-HDMI-*/modes
        mode_files = glob.glob('/sys/class/drm/card*-HDMI-*/modes')
        for mode_file in mode_files:
            if os.path.exists(mode_file):
                with open(mode_file, 'r') as f:
                    lines = [l.strip() for l in f.readlines() if l.strip()]
                    if lines:
                        # e.g. "1920x1080"
                        top_mode = lines[0]
                        if 'x' in top_mode:
                            parts = top_mode.split('x')
                            w = int(parts[0])
                            h = int(parts[1])
                            logger.info("Detected HDMI monitor resolution via DRM: %dx%d@60", w, h)
                            return w, h, default_fps
    except Exception as e:
        logger.warning("Error querying DRM modes: %s", e)

    logger.info("Using default display resolution: %dx%d@%d", default_w, default_h, default_fps)
    return default_w, default_h, default_fps


def create_header(pkt_type, flags, payload_len, pts_us=0):
    """Creates a 16-byte FramingProtocol binary header (>2sBBIQ, big-endian)."""
    return struct.pack('>2sBBIQ', MAGIC_BYTES, pkt_type, flags, payload_len, pts_us & 0xFFFFFFFFFFFFFFFF)


def parse_header(header_bytes):
    """Parses a 16-byte FramingProtocol binary header. Returns (pkt_type, flags, payload_len, pts_us)."""
    if len(header_bytes) < HEADER_SIZE:
        raise ValueError(f"Header too short: {len(header_bytes)} bytes (expected {HEADER_SIZE})")
    magic, pkt_type, flags, payload_len, pts_us = struct.unpack('>2sBBIQ', header_bytes[:HEADER_SIZE])
    if magic != MAGIC_BYTES:
        raise ValueError(f"Invalid magic bytes: {magic} (expected {MAGIC_BYTES})")
    return pkt_type, flags, payload_len, pts_us


def create_display_info_packet(width, height, fps, pts_us=0):
    """Builds a TYPE_DISPLAY_INFO packet (16 bytes header + 12 bytes payload)."""
    header = create_header(TYPE_DISPLAY_INFO, FLAG_NONE, 12, pts_us)
    payload = struct.pack('>III', width, height, fps)
    return header + payload


def parse_display_info_payload(payload):
    """Parses a 12-byte TYPE_DISPLAY_INFO payload. Returns (width, height, fps)."""
    if len(payload) < 12:
        raise ValueError(f"Payload too short for DISPLAY_INFO: {len(payload)} (expected 12)")
    return struct.unpack('>III', payload[:12])


def create_heartbeat_packet(pts_us=0):
    """Builds a TYPE_HEARTBEAT packet requesting an IDR keyframe."""
    return create_header(TYPE_HEARTBEAT, FLAG_KEYFRAME, 0, pts_us)


def create_input_mouse_packet(norm_x, norm_y, button_mask, wheel_dx=0, wheel_dy=0, pts_us=0):
    """
    Builds a TYPE_INPUT_MOUSE packet (16 bytes header + 8 bytes payload).
    Layout: norm_x (uint16), norm_y (uint16), button_mask (uint8), wheel_dx (int8), wheel_dy (int8), reserved (uint8=0).
    """
    header = create_header(TYPE_INPUT_MOUSE, FLAG_NONE, 8, pts_us)
    payload = struct.pack('>HHBbbB', norm_x & 0xFFFF, norm_y & 0xFFFF, button_mask & 0xFF, wheel_dx, wheel_dy, 0)
    return header + payload


def parse_input_mouse_payload(payload):
    """Parses an 8-byte TYPE_INPUT_MOUSE payload into a dictionary."""
    if len(payload) < 8:
        raise ValueError(f"Payload too short for INPUT_MOUSE: {len(payload)} (expected 8)")
    norm_x, norm_y, button_mask, wheel_dx, wheel_dy, _ = struct.unpack('>HHBbbB', payload[:8])
    return {
        "norm_x": norm_x,
        "norm_y": norm_y,
        "button_mask": button_mask,
        "wheel_dx": wheel_dx,
        "wheel_dy": wheel_dy
    }


def create_input_key_packet(key_code, state, modifier_mask=0, pts_us=0):
    """
    Builds a TYPE_INPUT_KEY packet (16 bytes header + 8 bytes payload).
    Layout: key_code (uint32), state (uint8: 1=down, 0=up), modifier_mask (uint8), reserved (uint16=0).
    """
    header = create_header(TYPE_INPUT_KEY, FLAG_NONE, 8, pts_us)
    payload = struct.pack('>IBBH', key_code & 0xFFFFFFFF, state & 0xFF, modifier_mask & 0xFF, 0)
    return header + payload


def parse_input_key_payload(payload):
    """Parses an 8-byte TYPE_INPUT_KEY payload into a dictionary."""
    if len(payload) < 8:
        raise ValueError(f"Payload too short for INPUT_KEY: {len(payload)} (expected 8)")
    key_code, state, modifier_mask, _ = struct.unpack('>IBBH', payload[:8])
    return {
        "key_code": key_code,
        "state": state,
        "modifier_mask": modifier_mask
    }


class DockGStreamerPipeline:
    """Manages low-latency H.264 decoding with v4l2h264dec and avdec_h264 fallback."""

    def __init__(self):
        self.pipeline = None
        self.appsrc = None
        self.active_decoder = None
        if Gst is not None:
            Gst.init(None)

    def start(self):
        if Gst is None:
            logger.warning("GStreamer python bindings not available. Video output disabled.")
            return

        # 1. Primary: hardware v4l2h264dec + videoconvert + kmssink
        # 2. Fallback: software avdec_h264 + videoconvert + kmssink
        # 3. Fallback: software avdec_h264 + videoconvert + autovideosink
        pipeline_candidates = [
            (
                "v4l2h264dec (hardware)",
                "appsrc name=src is-live=true format=time block=false ! h264parse ! v4l2h264dec ! videoconvert ! kmssink sync=false"
            ),
            (
                "avdec_h264 (software fallback with kmssink)",
                "appsrc name=src is-live=true format=time block=false ! h264parse ! avdec_h264 ! videoconvert ! kmssink sync=false"
            ),
            (
                "avdec_h264 (software fallback with autovideosink)",
                "appsrc name=src is-live=true format=time block=false ! h264parse ! avdec_h264 ! videoconvert ! autovideosink sync=false"
            )
        ]

        for label, desc in pipeline_candidates:
            try:
                logger.info("Attempting GStreamer pipeline with decoder: %s", label)
                self.pipeline = Gst.parse_launch(desc)
                self.appsrc = self.pipeline.get_by_name("src")
                self.pipeline.set_state(Gst.State.PLAYING)
                self.active_decoder = label
                logger.info("GStreamer pipeline active using decoder: %s", self.active_decoder)
                return
            except Exception as e:
                logger.warning("Pipeline launch failed (%s): %s", label, e)
                if self.pipeline:
                    try:
                        self.pipeline.set_state(Gst.State.NULL)
                    except Exception:
                        pass
                    self.pipeline = None

        logger.error("All GStreamer pipelines failed to launch.")

    def push_buffer(self, data):
        if self.appsrc is not None and self.pipeline is not None:
            buf = Gst.Buffer.new_allocate(None, len(data), None)
            buf.fill(0, data)
            self.appsrc.emit("push-buffer", buf)

    def stop(self):
        if self.pipeline is not None:
            try:
                self.pipeline.set_state(Gst.State.NULL)
            except Exception as e:
                logger.warning("Error stopping GStreamer pipeline: %s", e)
            self.pipeline = None
            self.appsrc = None
            self.active_decoder = None


class EvdevInputForwarder:
    """Reads Linux evdev keyboard and mouse, maintaining absolute cursor and forwarding packets."""

    def __init__(self, monitor_w, monitor_h, send_callback):
        self.monitor_w = monitor_w
        self.monitor_h = monitor_h
        self.send_callback = send_callback
        self.running = False
        self.cursor_x = monitor_w / 2.0
        self.cursor_y = monitor_h / 2.0
        self.button_mask = 0
        self.modifier_mask = 0
        self.threads = []

    def start(self):
        if evdev is None:
            logger.info("python-evdev not installed; physical keyboard/mouse forwarding disabled.")
            return

        self.running = True
        try:
            devices = [evdev.InputDevice(path) for path in evdev.list_devices()]
        except Exception as e:
            logger.warning("Could not list evdev devices: %s", e)
            return

        for dev in devices:
            caps = dev.capabilities()
            is_mouse = ecodes.EV_REL in caps or ecodes.EV_ABS in caps
            is_keyboard = ecodes.EV_KEY in caps
            if is_mouse or is_keyboard:
                logger.info("Found evdev input device: %s (%s)", dev.name, dev.path)
                t = threading.Thread(target=self._device_loop, args=(dev,), daemon=True)
                t.start()
                self.threads.append(t)

    def _device_loop(self, dev):
        wheel_dx = 0
        wheel_dy = 0
        mouse_moved = False

        try:
            for event in dev.read_loop():
                if not self.running:
                    break

                if event.type == ecodes.EV_REL:
                    if event.code == ecodes.REL_X:
                        self.cursor_x = max(0.0, min(float(self.monitor_w), self.cursor_x + event.value))
                        mouse_moved = True
                    elif event.code == ecodes.REL_Y:
                        self.cursor_y = max(0.0, min(float(self.monitor_h), self.cursor_y + event.value))
                        mouse_moved = True
                    elif event.code == ecodes.REL_WHEEL:
                        wheel_dy = event.value
                        mouse_moved = True
                    elif event.code == ecodes.REL_HWHEEL:
                        wheel_dx = event.value
                        mouse_moved = True

                elif event.type == ecodes.EV_KEY:
                    # Mouse buttons
                    if event.code == ecodes.BTN_LEFT:
                        if event.value == 1: self.button_mask |= 0x01
                        else: self.button_mask &= ~0x01
                        mouse_moved = True
                    elif event.code == ecodes.BTN_MIDDLE:
                        if event.value == 1: self.button_mask |= 0x02
                        else: self.button_mask &= ~0x02
                        mouse_moved = True
                    elif event.code == ecodes.BTN_RIGHT:
                        if event.value == 1: self.button_mask |= 0x04
                        else: self.button_mask &= ~0x04
                        mouse_moved = True
                    else:
                        # Keyboard modifiers
                        if event.code in (ecodes.KEY_LEFTSHIFT, ecodes.KEY_RIGHTSHIFT):
                            if event.value: self.modifier_mask |= 0x01
                            else: self.modifier_mask &= ~0x01
                        elif event.code in (ecodes.KEY_LEFTCTRL, ecodes.KEY_RIGHTCTRL):
                            if event.value: self.modifier_mask |= 0x02
                            else: self.modifier_mask &= ~0x02
                        elif event.code in (ecodes.KEY_LEFTALT, ecodes.KEY_RIGHTALT):
                            if event.value: self.modifier_mask |= 0x04
                            else: self.modifier_mask &= ~0x04
                        elif event.code in (ecodes.KEY_LEFTMETA, ecodes.KEY_RIGHTMETA):
                            if event.value: self.modifier_mask |= 0x08
                            else: self.modifier_mask &= ~0x08

                        # Send keyboard event
                        key_pkt = create_input_key_packet(
                            key_code=event.code,
                            state=1 if event.value > 0 else 0,
                            modifier_mask=self.modifier_mask,
                            pts_us=int(event.sec * 1_000_000 + event.usec)
                        )
                        self.send_callback(key_pkt)

                elif event.type == ecodes.EV_SYN and mouse_moved:
                    norm_x = int((self.cursor_x / self.monitor_w) * 65535) if self.monitor_w > 0 else 0
                    norm_y = int((self.cursor_y / self.monitor_h) * 65535) if self.monitor_h > 0 else 0
                    mouse_pkt = create_input_mouse_packet(
                        norm_x=max(0, min(65535, norm_x)),
                        norm_y=max(0, min(65535, norm_y)),
                        button_mask=self.button_mask,
                        wheel_dx=wheel_dx,
                        wheel_dy=wheel_dy,
                        pts_us=int(event.sec * 1_000_000 + event.usec)
                    )
                    self.send_callback(mouse_pkt)
                    wheel_dx = 0
                    wheel_dy = 0
                    mouse_moved = False
        except Exception as e:
            if self.running:
                logger.debug("Evdev device loop ended: %s (%s)", dev.path, e)

    def stop(self):
        self.running = False


class MobiDeskDock:
    def __init__(self):
        self.running = True
        self.gst = DockGStreamerPipeline()
        self.monitor_w, self.monitor_h, self.monitor_fps = detect_monitor_mode()

    def signal_handler(self, sig, frame):
        logger.info("Termination signal received. Exiting...")
        self.running = False

    def find_accessory_device(self):
        """Looks for an Android device already in AOA accessory mode."""
        for dev in usb.core.find(find_all=True):
            if dev.idVendor == AOA_VENDOR_ID and dev.idProduct in AOA_PRODUCT_IDS:
                return dev
        return None

    def trigger_aoa_switch(self, dev):
        """Sends AOA handshake control transfers to put the Android phone into accessory mode."""
        logger.info("Found USB device %04x:%04x. Performing AOA 2.0 handshake...", dev.idVendor, dev.idProduct)
        try:
            # Step 1: Request 51 (Get Protocol Version)
            buf = dev.ctrl_transfer(0xC0, AOA_GET_PROTOCOL, 0, 0, 2)
            if len(buf) < 2:
                logger.warning("Device did not return protocol version.")
                return False
            version = buf[0] | (buf[1] << 8)
            logger.info("Android device supports AOA version: %d", version)
            if version < 1:
                logger.warning("Device does not support AOA protocol.")
                return False

            # Step 2: Request 52 (Send Identification Strings)
            for i, s in enumerate(AOA_STRINGS):
                data = s.encode('utf-8') + b'\x00'
                dev.ctrl_transfer(0x40, AOA_SEND_STRING, 0, i, data)

            # Step 3: Request 53 (Start Accessory)
            dev.ctrl_transfer(0x40, AOA_START_ACCESSORY, 0, 0, None)
            logger.info("AOA start request sent. Waiting for phone re-enumeration...")
            return True
        except usb.core.USBError as e:
            logger.warning("AOA control transfer failed: %s", e)
            return False

    def setup_endpoints(self, dev):
        """Configures interface and finds bulk IN and OUT endpoints."""
        try:
            try:
                dev.set_configuration()
            except Exception:
                pass
            cfg = dev.get_active_configuration()
            intf = cfg[(0, 0)]

            # Claim interface if kernel driver active
            try:
                if dev.is_kernel_driver_active(0):
                    dev.detach_kernel_driver(0)
            except Exception:
                pass
            usb.util.claim_interface(dev, 0)

            ep_in = None
            ep_out = None
            for ep in intf:
                if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
                    ep_in = ep
                else:
                    ep_out = ep

            return ep_in, ep_out
        except Exception as e:
            logger.error("Error setting up endpoints: %s", e)
            return None, None

    def stream_session(self, dev, ep_in, ep_out):
        """Runs the active streaming loop with the connected phone."""
        logger.info("Starting active streaming session over USB AOA 2.0...")
        self.gst.start()

        # Send TYPE_DISPLAY_INFO with detected monitor mode
        display_info_pkt = create_display_info_packet(self.monitor_w, self.monitor_h, self.monitor_fps)
        try:
            ep_out.write(display_info_pkt, timeout=1000)
            logger.info("Sent TYPE_DISPLAY_INFO (%dx%d@%d) to phone.", self.monitor_w, self.monitor_h, self.monitor_fps)
        except Exception as e:
            logger.warning("Failed to send TYPE_DISPLAY_INFO: %s", e)

        # Send initial keyframe request on reconnect
        try:
            ep_out.write(create_heartbeat_packet(), timeout=1000)
            logger.info("Sent initial keyframe request heartbeat on reconnect.")
        except Exception as e:
            logger.warning("Failed to send initial heartbeat: %s", e)

        # Start evdev input forwarder for mouse and keyboard
        def send_input_packet(pkt):
            try:
                ep_out.write(pkt, timeout=200)
            except Exception:
                pass

        input_forwarder = EvdevInputForwarder(self.monitor_w, self.monitor_h, send_input_packet)
        input_forwarder.start()

        accumulator = bytearray()
        last_heartbeat_time = time.time()

        while self.running:
            try:
                # Read bulk data from phone
                data = ep_in.read(64 * 1024, timeout=2000)
                if data:
                    accumulator.extend(data)

                # Process all complete FramingProtocol frames in accumulator
                while len(accumulator) >= HEADER_SIZE:
                    if accumulator[0] != 0x4D or accumulator[1] != 0x42:
                        idx = accumulator.find(MAGIC_BYTES, 1)
                        if idx != -1:
                            accumulator = accumulator[idx:]
                        else:
                            if accumulator[-1] == 0x4D:
                                accumulator = accumulator[-1:]
                            else:
                                accumulator.clear()
                            break

                    if len(accumulator) < HEADER_SIZE:
                        break

                    pkt_type = accumulator[2]
                    flags = accumulator[3]
                    payload_len = struct.unpack('>I', accumulator[4:8])[0]

                    if payload_len > 10 * 1024 * 1024:
                        logger.warning("Invalid payload length: %d, resetting sync...", payload_len)
                        accumulator = accumulator[2:]
                        continue

                    total_len = HEADER_SIZE + payload_len
                    if len(accumulator) < total_len:
                        break  # Incomplete payload, wait for next bulk chunk

                    payload = accumulator[HEADER_SIZE:total_len]
                    accumulator = accumulator[total_len:]

                    if pkt_type in (TYPE_CONFIG, TYPE_FRAME) and len(payload) > 0:
                        self.gst.push_buffer(bytes(payload))
                    elif pkt_type == TYPE_SLEEP:
                        is_asleep = payload[0] == 1 if len(payload) > 0 else False
                        logger.info("Received sleep state packet: is_asleep=%s", is_asleep)
                    elif pkt_type == TYPE_DISPLAY_INFO and len(payload) >= 12:
                        neg_w, neg_h, neg_fps = parse_display_info_payload(payload)
                        logger.info("Phone replied with negotiated display size: %dx%d@%d", neg_w, neg_h, neg_fps)

                # Periodic heartbeat every 5 seconds to keep stream alive
                if time.time() - last_heartbeat_time > 5.0:
                    try:
                        ep_out.write(create_heartbeat_packet(), timeout=500)
                    except Exception:
                        pass
                    last_heartbeat_time = time.time()

            except usb.core.USBTimeoutError:
                # Normal read timeout; request keyframe if needed
                try:
                    ep_out.write(create_heartbeat_packet(), timeout=500)
                except Exception:
                    pass
                continue
            except usb.core.USBError as e:
                logger.info("USB session interrupted (unplugged or stall): %s", e)
                break
            except Exception as e:
                logger.error("Unexpected error in streaming loop: %s", e)
                break

        input_forwarder.stop()
        self.gst.stop()
        logger.info("Streaming session closed. Ready for phone reconnection.")

    def run(self):
        signal.signal(signal.SIGINT, self.signal_handler)
        signal.signal(signal.SIGTERM, self.signal_handler)

        logger.info("=========================================")
        logger.info("MobiDesk Raspberry Pi 4B Dock Daemon Active")
        logger.info("Target Monitor: %dx%d@%d FPS", self.monitor_w, self.monitor_h, self.monitor_fps)
        logger.info("Plug Android phone into USB-A port...")
        logger.info("=========================================")

        while self.running:
            try:
                # 1. Check if device is already in AOA mode
                accessory_dev = self.find_accessory_device()
                if accessory_dev is not None:
                    ep_in, ep_out = self.setup_endpoints(accessory_dev)
                    if ep_in and ep_out:
                        self.stream_session(accessory_dev, ep_in, ep_out)
                    time.sleep(1)
                    continue

                # 2. Look for non-AOA Android devices and trigger handshake
                all_devices = usb.core.find(find_all=True)
                handled = False
                for dev in all_devices:
                    if dev.bDeviceClass == 9:
                        continue
                    if dev.idVendor == AOA_VENDOR_ID and dev.idProduct in AOA_PRODUCT_IDS:
                        continue

                    if self.trigger_aoa_switch(dev):
                        handled = True
                        time.sleep(2)  # Wait for phone to re-enumerate
                        break

                if not handled:
                    time.sleep(1.5)
            except Exception as e:
                logger.warning("Error in main dock loop (recovering): %s", e)
                time.sleep(1.5)


def run_selftest():
    """Runs a self-test of the Pi dock environment and prints a PASS/FAIL checklist."""
    print("=" * 60)
    print("MobiDesk Raspberry Pi 4B Dock Self-Test Checklist")
    print("=" * 60)

    results = []

    # 1. Check pyusb
    if usb is None:
        results.append(("pyusb & libusb backend", "FAIL", "pyusb not installed (install python3-usb)"))
    else:
        try:
            devs = list(usb.core.find(find_all=True))
            results.append(("pyusb & libusb backend", "PASS", f"Found {len(devs)} USB devices"))
        except Exception as e:
            results.append(("pyusb & libusb backend", "FAIL", str(e)))

    # 2. Check GStreamer and elements
    if Gst is None:
        results.append(("GStreamer Python bindings (gi.repository.Gst)", "FAIL", "Not installed"))
    else:
        results.append(("GStreamer Python bindings", "PASS", "Available"))
        required_elements = ['appsrc', 'h264parse', 'v4l2h264dec', 'avdec_h264', 'videoconvert', 'kmssink']
        for elem in required_elements:
            factory = Gst.ElementFactory.find(elem)
            if factory is not None:
                results.append((f"GStreamer element '{elem}'", "PASS", "Installed"))
            else:
                level = "WARN" if elem in ('v4l2h264dec', 'kmssink') else "FAIL"
                results.append((f"GStreamer element '{elem}'", level, "Missing (will use fallback)"))

    # 3. Check DRM mode detection
    try:
        mode_files = glob.glob('/sys/class/drm/card*-HDMI-*/modes')
        if mode_files:
            modes = []
            for mf in mode_files:
                with open(mf) as f:
                    modes.extend([l.strip() for l in f if l.strip()])
            results.append(("DRM HDMI mode detection", "PASS", f"Detected modes: {modes[:3]}"))
        else:
            results.append(("DRM HDMI mode detection", "PASS", "No physical HDMI detected; defaults to 1920x1080@60"))
    except Exception as e:
        results.append(("DRM HDMI mode detection", "WARN", str(e)))

    # 4. Check evdev devices
    if evdev is None:
        results.append(("python-evdev library", "WARN", "Not installed (install python3-evdev for input forwarding)"))
    else:
        try:
            dev_paths = evdev.list_devices()
            results.append(("evdev devices", "PASS", f"Found {len(dev_paths)} input event devices"))
        except Exception as e:
            results.append(("evdev devices", "WARN", f"Cannot list /dev/input: {e}"))

    # Print results summary
    all_pass = True
    for item, status, note in results:
        status_str = f"[{status}]"
        print(f" {status_str:7s} {item:<35s} - {note}")
        if status == "FAIL":
            all_pass = False

    print("=" * 60)
    print("Self-Test Result:", "PASSED (Ready for operation)" if all_pass else "FAILED (Missing dependencies)")
    print("=" * 60)
    return 0 if all_pass else 1


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(run_selftest())
    dock = MobiDeskDock()
    dock.run()
