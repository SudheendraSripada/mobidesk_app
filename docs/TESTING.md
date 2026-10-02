# MobiDesk Hardware & Simulator Testing Guide

This document describes the testing procedure for verifying the MobiDesk USB AOA 2.0 streaming pipeline and dock integration using either:
1. **Two Android Phones** (Phone A as Sender/Monitor Mode + Phone B as Dock Simulator)
2. **Raspberry Pi 4B Hardware Dock** (`dock/raspberry-pi/mobidesk_dock.py` + HDMI monitor)

---

## 1. Two-Phone Setup: Phone A (Sender) + Phone B (Dock Simulator)

This setup allows complete end-to-end testing of AOA 2.0 streaming, resolution negotiation, and input forwarding without requiring a Raspberry Pi.

### Hardware Prerequisites
- **Phone A (Sender)**: Android 8.0+ (API 26+) with MobiDesk installed. Acts as the USB Accessory.
- **Phone B (Dock Simulator / Receiver)**: Android 8.0+ (API 26+) with USB Host (OTG) support and MobiDesk installed. Acts as the USB Host dock.
- **USB OTG Adapter**: Plugged into **Phone B**.
- **USB Cable**: Connecting Phone B's OTG adapter to Phone A's USB-C port.

### Step-by-Step Test Procedure

#### Step 1: Launch Dock Simulator on Phone B
1. Open MobiDesk on **Phone B**.
2. Long-press the **MobiDesk** title in the app bar to access **Developer Tools**.
3. Tap **Receive (Receiver)** to launch `ReceiverActivity`.
4. Phone B displays a black screen with the status: *"Connecting to USB AOA Host..."* in fullscreen landscape mode.

#### Step 2: Connect Phone A
1. Plug the OTG adapter into Phone B and the USB cable into Phone A.
2. Phone B prompts: *"Allow MobiDesk to access the USB device?"* -> Tap **OK / Allow**.
3. Phone B performs the AOA 2.0 control handshake (`AOA_GET_PROTOCOL`, `AOA_SEND_STRING`, `AOA_START_ACCESSORY`).
4. Phone A re-enumerates in Google AOA Accessory mode (`VID 0x18D1`, `PID 0x2D00` or `0x2D01`).
5. Phone A displays an Android dialog: *"Open MobiDesk when this USB accessory is connected?"* -> Tap **OK**.

#### Step 3: Stream from Phone A
1. On Phone A, MobiDesk opens automatically to **Monitor Mode**.
2. Phone B transmits `TYPE_DISPLAY_INFO` with Phone B's physical landscape dimensions (e.g. 1920x1080 @ 60 FPS) via Bulk OUT.
3. Phone A receives `TYPE_DISPLAY_INFO`, queries `MediaCodec` AVC capabilities, clamps resolution to supported bounds, and replies with `sendDisplayInfoReply`.
4. Phone A launches `ScreenCaptureService` in `STREAM_MODE_VIRTUAL_DISPLAY` (or fallback mirror).
5. MediaCodec starts CBR H.264 encoding and streams NAL units over AOA bulk transfer.
6. Phone B receives the stream, initialises `MediaCodec` hardware decoder on its SurfaceView, and renders video fullscreen.

#### Step 4: Verify Sleep and Screen-Off Operation
1. With streaming active, press the **Power button** on Phone A to turn off the phone screen.
2. **VirtualDisplay Mode**: The monitor stream on Phone B remains live and uninterrupted because rendering occurs on the isolated virtual display.
3. **Mirror Mode**: If running fallback screen mirror, Phone A notifies Phone B with `TYPE_SLEEP` (`is_asleep=true`), and Phone B displays the sleep overlay (*"phone in sleep wake up to view"*).
4. Turn Phone A screen back on: Phone B resumes live video immediately without needing reconnection.

#### Step 5: Test Touch & Input Forwarding
1. Touch and drag across Phone B's screen:
   - Phone B sends `TYPE_INPUT_MOUSE` with normalized coordinates `(0..65535)` and button mask.
   - Phone A's `MobiDeskPresentation` moves the software cursor overlay and injects `MotionEvent`.
2. Connect a USB keyboard or mouse to Phone B (via USB hub):
   - Keystrokes are converted to evdev codes and forwarded via `TYPE_INPUT_KEY`.
   - Mouse clicks and scroll events forward via `TYPE_INPUT_MOUSE`.

---

## 2. Raspberry Pi 4B Hardware Dock Testing

### Prerequisites
- Raspberry Pi 4B running Raspberry Pi OS (Bullseye or Bookworm, 64-bit).
- Connected to an HDMI monitor.
- Phone A connected to one of the Pi's USB ports.

### Setup & Execution
1. Install dock dependencies on the Pi:
   ```bash
   cd dock/raspberry-pi
   sudo bash install.sh
   ```
2. Verify protocol implementation:
   ```bash
   python3 test_protocol.py
   python3 mobidesk_dock.py --selftest
   ```
3. Run the dock service:
   ```bash
   python3 mobidesk_dock.py
   ```
4. Plug in Phone A. The dock will:
   - Detect Phone A and initiate AOA mode.
   - Detect monitor HDMI resolution via KMS/DRM (or default 1920x1080@30).
   - Send `TYPE_DISPLAY_INFO` to Phone A.
   - Receive negotiated display info and H.264 NAL frames.
   - Decode via `v4l2h264dec` hardware decoder (or `avdec_h264` fallback) directly to `kmssink`.
   - Forward evdev input from USB keyboard/mouse connected to the Pi to Phone A.
