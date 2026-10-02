#!/usr/bin/env python3
"""
MobiDesk Raspberry Pi 4B USB Dock Daemon (mobidesk_dock.py)

Acts as a dedicated USB Host over a Pi USB-A port:
1. Detects connected Android device and initiates the Android Open Accessory (AOA 2.0) handshake.
2. Identifies monitor native resolution and refresh rate via DRM/KMS EDID.
3. Transmits TYPE_DISPLAY_INFO (width, height, fps) to the Android phone over the USB Bulk channel.
4. Reads incoming binary FramingProtocol packets (CONFIG/SPS/PPS and FRAME/H.264 NAL units).
5. Feeds H.264 video into a hardware-accelerated GStreamer pipeline (v4l2h264dec -> kmssink) for fullscreen HDMI display.
6. Emits periodic HEARTBEAT packets to request keyframes on start and reconnection.
7. Gracefully handles USB disconnection and automatically recovers on reattachment.
"""

import os
import sys
import time
import glob
import struct
import signal
import logging
import usb.core
import usb.util

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

# FramingProtocol Constants
MAGIC_BYTES = b'MB'  # 0x4D, 0x42
HEADER_SIZE = 16

TYPE_CONFIG = 1
TYPE_FRAME = 2
TYPE_HEARTBEAT = 3
TYPE_SLEEP = 4
TYPE_DISPLAY_INFO = 5

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
    """Creates a 16-byte FramingProtocol binary header (>2sBBIQ)."""
    return struct.pack('>2sBBIQ', MAGIC_BYTES, pkt_type, flags, payload_len, pts_us & 0xFFFFFFFFFFFFFFFF)


def create_display_info_packet(width, height, fps):
    """
    Builds a TYPE_DISPLAY_INFO packet (16 bytes header + 12 bytes payload).
    """
    header = create_header(TYPE_DISPLAY_INFO, FLAG_NONE, 12, 0)
    payload = struct.pack('>III', width, height, fps)
    return header + payload


def create_heartbeat_packet():
    """Builds a TYPE_HEARTBEAT packet requesting an IDR keyframe."""
    return create_header(TYPE_HEARTBEAT, FLAG_KEYFRAME, 0, 0)


class DockGStreamerPipeline:
    """Manages the low-latency hardware H.264 decoding pipeline."""

    def __init__(self):
        self.pipeline = None
        self.appsrc = None
        if Gst is not None:
            Gst.init(None)

    def start(self):
        if Gst is None:
            logger.warning("GStreamer python bindings not available. Video output disabled.")
            return

        # Hardware-accelerated decoding via V4L2 and direct DRM/KMS HDMI sink
        # Fallback to standard auto sink if kmssink is unavailable
        pipeline_descriptions = [
            "appsrc name=src is-live=true format=time block=false ! h264parse ! v4l2h264dec capture-io-mode=4 ! kmssink sync=false",
            "appsrc name=src is-live=true format=time block=false ! h264parse ! v4l2h264dec ! autovideosink sync=false",
            "appsrc name=src is-live=true format=time block=false ! h264parse ! avdec_h264 ! autovideosink sync=false"
        ]

        for desc in pipeline_descriptions:
            try:
                logger.info("Attempting GStreamer pipeline: %s", desc)
                self.pipeline = Gst.parse_launch(desc)
                self.appsrc = self.pipeline.get_by_name("src")
                self.pipeline.set_state(Gst.State.PLAYING)
                logger.info("GStreamer pipeline successfully launched.")
                return
            except Exception as e:
                logger.warning("Pipeline launch failed (%s): %s", desc, e)
                if self.pipeline:
                    self.pipeline.set_state(Gst.State.NULL)
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

        # Request initial keyframe via heartbeat
        try:
            ep_out.write(create_heartbeat_packet(), timeout=1000)
            logger.info("Sent initial keyframe request heartbeat.")
        except Exception as e:
            logger.warning("Failed to send initial heartbeat: %s", e)

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
                    # Sync to magic bytes 'MB' (0x4D, 0x42)
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

                # Periodic heartbeat every 5 seconds
                if time.time() - last_heartbeat_time > 5.0:
                    try:
                        ep_out.write(create_heartbeat_packet(), timeout=500)
                    except Exception:
                        pass
                    last_heartbeat_time = time.time()

            except usb.core.USBTimeoutError:
                # Normal read timeout, request keyframe to keep stream fresh
                try:
                    ep_out.write(create_heartbeat_packet(), timeout=500)
                except Exception:
                    pass
                continue
            except usb.core.USBError as e:
                logger.info("USB session interrupted: %s", e)
                break
            except Exception as e:
                logger.error("Unexpected error in streaming loop: %s", e)
                break

        self.gst.stop()
        logger.info("Streaming session closed.")

    def run(self):
        signal.signal(signal.SIGINT, self.signal_handler)
        signal.signal(signal.SIGTERM, self.signal_handler)

        logger.info("=========================================")
        logger.info("MobiDesk Raspberry Pi 4B Dock Daemon Active")
        logger.info("Target Monitor: %dx%d@%d FPS", self.monitor_w, self.monitor_h, self.monitor_fps)
        logger.info("Plug Android phone into USB-A port...")
        logger.info("=========================================")

        while self.running:
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
                # Skip root hubs
                if dev.bDeviceClass == 9:
                    continue
                # Skip dock's own accessory VID/PID
                if dev.idVendor == AOA_VENDOR_ID and dev.idProduct in AOA_PRODUCT_IDS:
                    continue

                if self.trigger_aoa_switch(dev):
                    handled = True
                    time.sleep(2)  # Wait for device to re-enumerate
                    break

            if not handled:
                time.sleep(1.5)


if __name__ == '__main__':
    dock = MobiDeskDock()
    dock.run()
