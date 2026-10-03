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

## 2. Numbered Bring-Up Order & Hardware Validation Procedure

Follow this exact 8-step bring-up sequence sequentially. For every step, record and send back the indicated log outputs and screen captures.

### Step 1: Server Connectivity & Guacamole Browser Login
- **Action**:
  - Test TCP reachability from the local network to Guacamole web, guacd daemon, and Windows RDP:
    ```bash
    nc -vz <GUACAMOLE_HOST> 8080
    nc -vz <GUACAMOLE_HOST> 4822
    nc -vz <WINDOWS_VM_HOST> 3389
    ```
  - Open a web browser on your computer or phone and navigate to `http://<GUACAMOLE_HOST>:8080/guacamole/#/`.
  - Log in with the student credentials (e.g., `student@mobidesk.edu` / `MobiDesk2026!`).
  - Verify the assigned Windows VM desktop launches and responds to mouse/keyboard.
- **Expected Result**:
  - All `nc -vz` commands report `succeeded` / `open`.
  - Guacamole HTML5 client renders the Windows 11 desktop cleanly in the browser.
- **What to Send Back**:
  - Terminal output of the three `nc -vz` commands.
  - Screenshot of the Guacamole browser session showing the logged-in Windows desktop.

### Step 2: App Phone Mode Verification
- **Action**:
  - Launch MobiDesk on your Android phone.
  - On the Login screen, enter credentials (or tap **Quick Demo Login**). Ensure **"Keep me signed in"** is checked.
  - From the Dashboard under **Cloud PC**, tap **PHONE** mode.
- **Expected Result**:
  - The phone transitions to immersive landscape mode via `PhoneCloudPcActivity`.
  - The Windows VM desktop displays fullscreen on the phone.
  - Floating toolbar allows toggling soft keyboard, sending helper keys (Ctrl, Alt, Win, Esc, Tab), right-clicking (via long-press), and disconnecting.
- **What to Send Back**:
  - Screenshot of the phone in fullscreen Phone Cloud PC mode showing the floating toolbar and Windows VM desktop.
  - AppLogger log snippet from **Developer Tools > View Logs** showing `[AUTH]` and `PhoneCloudPcActivity` initialization.

### Step 3: System Check Screen (All Green)
- **Action**:
  - In MobiDesk, open **Settings > System check** (or **Developer Tools > Run System Check**).
  - Tap **Run System Check** to evaluate all 11 diagnostic items.
  - Once all checks complete, tap **Copy report**.
- **Expected Result**:
  - All 11 diagnostic items report green `[PASS]` status:
    1. Supabase reachable & session valid
    2. Guacamole URL reachable (HTTP 200/302)
    3. Guacamole token authentication
    4. Assigned VM connection ID resolved
    5. USB accessory subsystem ready
    6. Dry-run VirtualDisplay creation (1280x720 dummy Surface)
    7. MediaCodec AVC hardware encoder capabilities
    8. Foreground service type permission (`connectedDevice` / `specialUse`)
    9. Battery optimization exemption status
    10. Low-latency WifiLock acquirable
    11. `POST_NOTIFICATIONS` permission granted
- **What to Send Back**:
  - Screenshot of the System Check screen showing all 11 green `[PASS]` chips.
  - Pasted clipboard text from the **Copy report** button.

### Step 4: Two-Phone Dock Simulator Test
- **Action**:
  - On **Phone B** (Host/OTG): Open **Developer Tools > Receive (Receiver)**. Connect USB OTG adapter.
  - Connect USB cable from Phone B's OTG adapter to **Phone A**'s USB-C port.
  - On Phone B: Accept USB accessory permission prompt.
  - On Phone A: App auto-navigates into Monitor mode, receives Phone B's `TYPE_DISPLAY_INFO`, and begins streaming.
- **Expected Result**:
  - Phone B displays Phone A's stream fullscreen with no letterboxing.
  - Touch input on Phone B forwards as `TYPE_INPUT_MOUSE` to Phone A.
- **What to Send Back**:
  - Photo of both phones side-by-side with Phone B rendering Phone A's stream.
  - AppLogger log from Phone A and logcat output from Phone B (`UsbHostReceiver`).

### Step 5: Pi Self-Test & Test Pattern Generation
- **Action**:
  - On the Raspberry Pi 4B (running Raspberry Pi OS Lite 64-bit), connect an HDMI monitor to micro-HDMI port 0.
  - Run the diagnostic self-test:
    ```bash
    python3 dock/raspberry-pi/mobidesk_dock.py --selftest
    python3 dock/raspberry-pi/mobidesk_dock.py --diagnose
    ```
  - Generate the HDMI test pattern to isolate monitor/display output from USB:
    ```bash
    python3 dock/raspberry-pi/mobidesk_dock.py --test-pattern
    ```
  - Verify SMPTE color bars on the HDMI monitor. Terminate with `Ctrl+C`.
- **Expected Result**:
  - `--selftest` reports `[PASS]` for all core dependencies.
  - `--diagnose` lists connected DRM HDMI modes (e.g. `1920x1080@60`), input devices (`/dev/input/event*`), and GStreamer elements.
  - `--test-pattern` displays crisp fullscreen SMPTE color bars on the HDMI monitor.
- **What to Send Back**:
  - Console text output of `python3 mobidesk_dock.py --selftest` and `--diagnose`.
  - Photo of the physical HDMI monitor displaying the SMPTE color bar test pattern.

### Step 6: Real Pi Dock + Phone End-to-End Streaming
- **Action**:
  - Connect USB keyboard and mouse to Raspberry Pi 4B USB ports.
  - Start the dock daemon on the Pi:
    ```bash
    python3 dock/raspberry-pi/mobidesk_dock.py
    ```
  - Connect the Android phone via USB-C to USB-A cable to a Pi USB 3.0 port.
  - On phone: Accept USB accessory prompt if presented.
  - App auto-launches into Monitor Mode. Pi sends `TYPE_DISPLAY_INFO` (`1920x1080@60`). Phone negotiates encoder and begins AOA streaming.
- **Expected Result**:
  - Physical HDMI monitor immediately displays branded connecting screen with loading spinner: *"MobiDesk - connecting your Cloud PC..."* (never blank or frozen).
  - External monitor transitions to fullscreen Windows VM desktop once Guacamole session connects.
  - Phone screen displays Monitor Mode status chips (Dock connected, 1920x1080, ~30 FPS, Bitrate ~4000 kbps, 0 dropped frames).
  - Status text *"Connected, started streaming"* appears ONLY on the phone, never baked into the HDMI monitor image.
- **What to Send Back**:
  - Photo showing physical setup: Phone with status chips, Pi 4B, and HDMI monitor displaying the Windows desktop.
  - Terminal output of Pi dock showing initial AOA handshake and 5-second periodic stats (`[5s DOCK STATS]`).

### Step 7: Screen-Off Continuous Streaming Test
- **Action**:
  - While streaming is active on the HDMI monitor, press the physical **Power button** on the phone to turn off and lock the phone screen.
  - Observe the HDMI monitor for 60 seconds.
  - Unlock the phone.
- **Expected Result**:
  - The HDMI monitor continues rendering the live Windows Cloud PC session smoothly without freezing, black screen, or disconnection.
  - WakeLock and low-latency WifiLock prevent CPU throttling or network teardown.
  - Unlocking the phone returns to the Monitor Mode screen with streaming uninterrupted.
- **What to Send Back**:
  - Short video or photo showing the phone screen completely black/off while the HDMI monitor is displaying the live Windows desktop.
  - Exported log from **Developer Tools > View Logs > Share logs** covering the 60-second screen-off period.

### Step 8: Hardware Input Forwarding & Disconnect/Re-Attach Recovery
- **Action**:
  - Move the USB mouse connected to the Pi: verify software cursor moves on the HDMI monitor.
  - Click and drag, right-click, and scroll the mouse wheel.
  - Type on the USB keyboard connected to the Pi: verify keystrokes register in Windows Notepad or browser.
  - Unplug the USB cable from the phone. Wait 5 seconds.
  - Plug the USB cable back into the phone.
- **Expected Result**:
  - Software cursor on the HDMI monitor tracks mouse smoothly; keystrokes register without lag.
  - On unplug: HDMI monitor transitions to branded reconnecting overlay with countdown (*"Reconnecting to Cloud PC..."*); phone shows reconnecting state.
  - On re-plug: Phone triggers silent re-authentication with stored credentials, AOA session re-establishes, and live streaming resumes within < 2 seconds.
- **What to Send Back**:
  - Video or photo showing cursor movement/typing in the Windows VM via Pi-attached peripherals.
  - Pi dock terminal log showing `input_events_per_sec`, disconnect handling, and successful re-enumeration.
