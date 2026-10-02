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

## 2. Raspberry Pi 4B Hardware Dock Testing & Numbered 8-Step Bring-Up Order

This numbered 8-step procedure is the authoritative bring-up protocol for MobiDesk prototype builds (v3+). Follow each step sequentially, confirming the expected logs and screen output before proceeding to the next.

### Step 1: Pi Dock Environment & Driver Verification
- **Action**:
  ```bash
  cd dock/raspberry-pi
  python3 mobidesk_dock.py --selftest
  python3 mobidesk_dock.py --diagnose
  ```
- **Expected Output / Screen**:
  - Console prints `[PASS]` for pyusb, DRM HDMI mode detection, and evdev input devices.
  - `--diagnose` reports detected DRM HDMI modes (e.g. `1920x1080`), input nodes (`/dev/input/event*`), and GStreamer elements (`appsrc`, `h264parse`, `v4l2h264dec` or `avdec_h264`, `kmssink`).
  - Monitor screen: Standard Pi console or desktop.

### Step 2: HDMI Video Pipeline Verification (Independent of USB)
- **Action**:
  ```bash
  python3 mobidesk_dock.py --test-pattern
  ```
- **Expected Output / Screen**:
  - Console log: `[INFO] Initializing MobiDesk HDMI test pattern generator... Displaying SMPTE test pattern via kmssink`.
  - Monitor screen: Fullscreen SMPTE color bars (color calibration test pattern). Press `Ctrl+C` to terminate cleanly.

### Step 3: Phone App Pre-Flight System Check (11 Checks)
- **Action**:
  - Launch MobiDesk on Android Phone.
  - Navigate to **Settings > System check** (or **Developer Tools > Run System Check**).
  - Tap **Run System Check** to trigger all 11 prerequisite checks.
- **Expected Output / Screen**:
  - Phone screen: All 11 checks render with green `[PASS]` chips:
    1. USB AOA Accessory Attached (or informative notice if not yet plugged)
    2. MediaCodec H.264/AVC Hardware Encoder
    3. VirtualDisplay Subsystem (1280x720 / 1920x1080)
    4. Foreground Service (`connectedDevice` / FGS type)
    5. Low-Latency WifiLock
    6. Partial WakeLock
    7. Post Notifications Permission
    8. Battery Optimization Exemption
    9. Internal Log Storage (~1 MB Ring Buffer)
    10. Presentation Display Manager
    11. Network & Internet Connectivity
  - Tap **Copy report** to verify clipboard export.

### Step 4: Authentication & Credential Persistence
- **Action**:
  - Open **Login Screen**.
  - Enter credentials and ensure **"Keep me signed in"** is checked.
  - Tap **Sign In**.
- **Expected Output / Screen**:
  - Phone screen: Navigates to Dashboard.
  - AppLogger log: `[AUTH] Credentials saved to encrypted storage with keepSignedIn=true`.
  - Close and relaunch app: Automatically bypasses login screen and opens Dashboard.

### Step 5: Physical USB Attachment & AOA Handshake
- **Action**:
  - Start dock service on Raspberry Pi:
    ```bash
    python3 mobidesk_dock.py
    ```
  - Connect Phone via USB-C to USB-A cable to Raspberry Pi 4B.
- **Expected Output / Screen**:
  - Dock console log:
    ```text
    Found USB device xxxx:yyyy. Performing AOA 2.0 handshake...
    Android device supports AOA version: 2
    AOA start request sent. Waiting for phone re-enumeration...
    ```
  - Phone dialog: *"Open MobiDesk when this USB accessory is connected?"* -> Select always allow / OK.
  - Phone screen: Auto-launches directly into **Monitor Mode Screen** (or logs in silently if locked).
  - AppLogger log: `[AOA] USB accessory attached: MobiDesk / MobiDeskDock (AOA 2.0 active)`.

### Step 6: Display Resolution Negotiation & Presentation Launch
- **Action**:
  - Dock queries DRM EDID and transmits `TYPE_DISPLAY_INFO` (`1920x1080@60`).
  - Phone receives display info, queries MediaCodec bounds, launches `ScreenCaptureService` in VirtualDisplay mode, and creates `MobiDeskPresentation`.
- **Expected Output / Screen**:
  - Dock console log: `Sent TYPE_DISPLAY_INFO (1920x1080@60) to phone`.
  - Phone AppLogger log: `[SCREEN_CAP] Display info received: 1920x1080@60. Negotiated encoder: 1920x1080 @ 30 FPS`.
  - Monitor screen: Transitions immediately from blank to branded MobiDesk connecting screen with active loading spinner and status: *"Connecting to Cloud Desktop..."* (Never black or frozen).
  - Cloud PC desktop renders cleanly onto the external monitor once the WebRTC/Guacamole session establishes.

### Step 7: Video Streaming & 5s Periodic Stats Monitoring
- **Action**:
  - Observe real-time statistics on both Phone (Monitor Mode screen) and Pi terminal.
  - Tap the **"Request Keyframe"** floating action button on the Phone.
- **Expected Output / Screen**:
  - Phone Monitor Mode screen: Real-time chips display `fps` (28-30), `kbps` (~3500-4500), `dropped` (0), `keyframes` (incremented on tap).
  - Phone AppLogger log (every 5 seconds):
    ```text
    [5s STREAM STATS] fps=29.8 | kbps=3840 | dropped=0 | keyframes=1 | wakeLock=true | wifiLock=true | fgsType=connectedDevice
    ```
  - Pi Dock console log (every 5 seconds):
    ```text
    [5s DOCK STATS] decoder=v4l2h264dec (hardware) | incoming_fps=29.8 | kbps=3840.5 | last_keyframe_age=4.2s | input_events_per_sec=0.0 | usb_state=STREAMING
    ```
  - Monitor screen: Smooth 30+ FPS low-latency video feed.

### Step 8: Input Forwarding & Disconnect / Re-Attach Recovery
- **Action**:
  - Move mouse and type keys on keyboard connected to Raspberry Pi USB ports.
  - Unplug USB cable from phone, wait 5 seconds, then plug back in.
- **Expected Output / Screen**:
  - Mouse movement moves software cursor on monitor screen; keystrokes register in remote desktop.
  - Pi Dock console log: `input_events_per_sec=14.5`.
  - **On Cable Unplug**:
    - Monitor screen: Shows branded connecting overlay with spinner and countdown: *"Connection lost. Retrying in 1s..."*.
    - Phone screen: Monitor mode displays reconnection banner, starts exponential backoff (1s, 2s, 4s, 8s, 15s).
    - Pi Dock: Returns to `WAITING` state without crashing or terminating process.
  - **On Cable Re-Plug**:
    - AOA handshake re-executes immediately.
    - Phone automatically triggers silent re-auth, re-initializes `ScreenCaptureService`, and pushes keyframe.
    - Monitor resumes live desktop rendering within < 2 seconds.
    - AppLogger log: `[RECONNECT] Stream re-established successfully; presentation state=connected`.
