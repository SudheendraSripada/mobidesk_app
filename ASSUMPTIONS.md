# ASSUMPTIONS.md - MobiDesk Prototype Implementation

This document details all technical, architectural, and operational assumptions made during the implementation of the demo-ready MobiDesk prototype APK (Prototype v2).

---

## 1. Authentication & Data Layer (Supabase)

1. **Authentication Binding**:
   - The `students` table uses `auth.users(id)` as its primary key (`id UUID PRIMARY KEY REFERENCES auth.users(id)`).
   - Row-Level Security (RLS) is enabled on `students`, `vms`, and `student_vm_access`.
   - A student can only query rows where `auth.uid() = student_id` (or `auth.uid() = id`).
2. **Credential Security**:
   - Windows VM credentials, RDP passwords, and administrative tokens are **NEVER** stored in Supabase or on the mobile device.
   - RDP credentials exist **EXCLUSIVELY** within Apache Guacamole's internal connection configuration in PostgreSQL.
3. **Graceful Unconfigured Fallback**:
   - If `SUPABASE_URL` and `SUPABASE_ANON_KEY` are not configured (or point to placeholder values), the application displays a friendly Setup screen rather than crashing.
   - The app provides an instant "Quick Demo Login" / "Offline Demo Mode" pre-populating realistic student profile and VM data.

---

## 2. Cloud PC Transport Layer (Apache Guacamole)

1. **Guacamole Stack Architecture**:
   - Guacamole runs as a containerized stack on Ubuntu Server 24.04 LTS using `guacd:1.5.5`, `postgres:15-alpine`, and `guacamole:1.5.5`.
   - The REST API endpoint `POST /api/tokens` accepts `application/x-www-form-urlencoded` credentials (`username` and `password`).
   - The HTML5 client is accessed via `${GUACAMOLE_BASE_URL}/#/client/{connectionId}?token={authToken}`.
2. **Student User Mapping**:
   - Each student account in Guacamole matches their MobiDesk username and password.
   - In Guacamole, the student user is granted read permission only to their assigned VM connection ID.
3. **Windows VM & RDP Connectivity**:
   - Windows VMs run RDP on standard port TCP 3389 with firewall permissions enabled.
   - Guacamole handles the RDP protocol translation, audio redirection, and dynamic desktop resizing.

---

## 3. USB AOA 2.0 & Framing Protocol Specification

1. **USB Host/Accessory Roles**:
   - The Raspberry Pi 4B acts as **USB Host** via any of its USB-A ports (USB 3.0 ports recommended).
   - The Android phone acts as **USB Accessory** using Android Open Accessory (AOA 2.0).
   - Handshake sequence: Pi requests protocol version (request 51), sends identification strings (request 52), and triggers accessory mode switch (request 53).
2. **Framing Protocol Extension (Golden Test Vectors in `tests/golden/frames.json`)**:
   - Protocol header: Exactly 16 bytes big-endian (`0x4D, 0x42`, packet type, flags, payload length, 64-bit ptsUs).
   - Python packing uses `>2sBBIQ` matching Kotlin `buffer.putLong(ptsUs)` and Dart `bd.setUint64(8, ptsUs)`.
   - **Packet Types**:
     - Type 1 (`TYPE_CONFIG`): SPS/PPS configuration packet.
     - Type 2 (`TYPE_FRAME`): H.264 video NAL unit frame (FLAG_KEYFRAME indicates IDR).
     - Type 3 (`TYPE_HEARTBEAT`): Keepalive / keyframe request.
     - Type 4 (`TYPE_SLEEP`): Phone sleep state packet (`isAsleep` boolean).
     - Type 5 (`TYPE_DISPLAY_INFO`): 12 bytes (`width` int32, `height` int32, `fps` int32).
     - Type 6 (`TYPE_INPUT_MOUSE`): 8 bytes (`normX` uint16, `normY` uint16, `buttonMask` uint8, `wheelDx` int8, `wheelDy` int8, `reserved` uint8).
     - Type 7 (`TYPE_INPUT_KEY`): 8 bytes (`keyCode` uint32, `state` uint8, `modifierMask` uint8, `reserved` uint16).
3. **Resolution Negotiation & Bitrate Clamping**:
   - Receiver/Dock transmits `TYPE_DISPLAY_INFO` upon connection.
   - Sender queries `MediaCodecInfo.VideoCapabilities` for `video/avc`, clamping width and height to encoder capability limits (capped at 1920x1080@30).
   - Bitrate is dynamically scaled: 8 Mbps for 1080p, 4 Mbps for 720p, 2 Mbps lower; configured with CBR bitrate mode and 1-second I-frame interval.
   - Sender replies to dock with clamped dimensions via `TYPE_DISPLAY_INFO` reply.

---

## 4. Monitor Mode & VirtualDisplay Pipeline

1. **Foreground Service Separation**:
   - `STREAM_MODE_VIRTUAL_DISPLAY` runs under Android foreground service type `connectedDevice` (with fallback to `specialUse`), eliminating the need for `MediaProjection` runtime prompt upfront.
   - If fallback mirror mode is triggered, the service promotes to `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` before calling `getMediaProjection(resultCode, dataIntent)`.
2. **Independent Off-Screen Rendering**:
   - In Monitor Mode, Android creates a `VirtualDisplay` at negotiated monitor resolution using `DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`.
   - The `VirtualDisplay` is backed directly by the `MediaCodec` hardware encoder input Surface.
   - An Android `Presentation` (`MobiDeskPresentation`) displays an accelerated `WebView` rendering the Guacamole HTML5 client.
   - Because the `Presentation` renders to the `VirtualDisplay` rather than the primary screen, the phone's status message is not baked into the HDMI monitor image.
3. **Continuous Streaming When Phone Screen is Off**:
   - In `STREAM_MODE_VIRTUAL_DISPLAY`, `ScreenCaptureService` ignores `Intent.ACTION_SCREEN_OFF` broadcasts and keeps encoding.
   - A CPU Partial WakeLock, low-latency WifiLock (`FULL_LOW_LATENCY` on API 29+ / `FULL_HIGH_PERF`), and Foreground Service ensure continuous streaming while the phone screen is locked.
   - An in-app prompt allows the user to request exemption from Android battery optimizations (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
4. **Input Forwarding & Software Cursor Overlay**:
   - Incoming `TYPE_INPUT_MOUSE` and `TYPE_INPUT_KEY` packets from the dock are demuxed and forwarded to `MobiDeskPresentation`.
   - A software cursor overlay tracks mouse position.
   - Injected events dispatch both native Android `MotionEvent` / `KeyEvent`s and synthetic DOM `MouseEvent` / `KeyboardEvent` JavaScript fallbacks to Guacamole canvas.

---

## 5. Phone Mode (Option A)

1. **Deduplicated Fullscreen Architecture**:
   - Clicking Phone Mode directly launches `PhoneCloudPcActivity` (native immersive fullscreen WebView) rather than maintaining duplicate Flutter toolbars.
   - Hardware-accelerated WebView with pinch-to-zoom (`setSupportZoom(true)`, `builtInZoomControls = true`, `displayZoomControls = false`).
   - Long-press gesture detector dispatches DOM `contextmenu` right-click event at touch coordinates.
   - Floating translucent toolbar provides quick soft-keyboard toggle and disconnect controls.

---

## 6. Raspberry Pi 4B Dock Script

1. **GStreamer Pipeline Resilience**:
   - Attempts hardware-accelerated pipeline `v4l2h264dec ! videoconvert ! kmssink` first on Raspberry Pi OS.
   - Falls back gracefully to `avdec_h264` if hardware decoder is unavailable.
   - Requests a keyframe via `FLAG_KEYFRAME` heartbeat packet on initial connection and after every reconnect.
   - Handles phone unplug/replug cleanly in a continuous daemon loop.
   - Supports `--selftest` CLI flag to verify pyusb, GStreamer, HDMI EDID mode, and evdev dependencies on hardware.

---

## 7. App Quality, Permissions & Network Security

1. **SDK Targets**:
   - `minSdk 24` (Android 7.0 Nougat).
   - `targetSdk 35` (Android 15).
   - `compileSdk 35`.
2. **Network Security**:
   - `android:usesCleartextTraffic="true"` configured with prototype note in `AndroidManifest.xml` to allow direct LAN testing against local Guacamole endpoints without certificate installation.
   - Removed invalid CIDR masks from `network_security_config.xml` to prevent build/runtime config parsing errors.
3. **Developer Tools & Dual Phone Testing**:
   - Long-press on the MobiDesk logo in the AppBar opens Developer Tools.
   - Receiver Activity allows using Phone B as an emulated dock, sending `TYPE_DISPLAY_INFO` and forwarding touch/keyboard inputs as `TYPE_INPUT_MOUSE` and `TYPE_INPUT_KEY` to Phone A.

---

## 8. Prototype v3 Architecture & Reliability Enhancements

1. **AOA Identity Single Source of Truth**:
   - `aoa_identity.json` acts as the authoritative definition of the 6-tuple AOA identity (manufacturer, model, description, version, uri, serial).
   - Kotlin `AoaConstants.kt`, Python `mobidesk_dock.AOA_STRINGS`, and `res/xml/accessory_filter.xml` strictly match this identity.
   - Verified via automated unit tests in both Kotlin (`AoaIdentityTest.kt`) and Python (`test_protocol.py`).

2. **Foreground Service Prerequisites & System Check Suite**:
   - Android 14/15 target SDK 35 requires strict foreground service type declarations. `ScreenCaptureService` declares `connectedDevice`, `mediaProjection`, and `specialUse`.
   - Native `MainActivity.kt` provides an 11-point system diagnostic suite (`runAllSystemChecks`), surfaced through Flutter UI at **Settings > System check**:
     1. USB AOA Accessory Attached
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
   - Provides a "Copy report" button that formats all 11 checks with timestamps and details to clipboard.

3. **Dock-Attach Flow & Branded Status Presentation**:
   - When the phone is attached to a dock while locked or logged out, the pending `UsbAccessory` intent is preserved in `MainActivity` (`hasPendingDockAttach`) and auto-navigates directly to Monitor Mode once authenticated.
   - "Keep me signed in" checkbox on the Login screen securely stores credentials via `FlutterSecureStorage` (backed by Android Keystore / encrypted shared prefs with in-memory fallback), enabling silent re-authentication on USB dock reconnection.
   - `MobiDeskPresentation` replaces any blank/black/frozen screen with a branded MobiDesk connecting screen featuring an active Material loading spinner, status text (*"Connecting to Cloud Desktop..."*), and exponential backoff retry countdown (1s, 2s, 4s, 8s, 15s).
   - `WebView.setWebContentsDebuggingEnabled(true)` enables remote debugging of the Presentation WebView via Chrome DevTools (`chrome://inspect`).

4. **Persistent Diagnostics & Observability**:
   - Thread-safe ~1 MB ring-buffer file logger (`AppLogger.kt`) persists timestamped logs to app-private cache storage (`mobidesk.log` and `mobidesk.log.old`).
   - Integrated log viewer screen (**Developer Tools > View Logs**) provides filtering, auto-scroll to bottom, log clearing, clipboard copying, and sharing via Android `Intent.ACTION_SEND` through an AndroidX `FileProvider`.
   - `ScreenCaptureService` emits a 5-second periodic stream stats log (`fps, kbps, dropped, keyframes, wakeLock, wifiLock, fgsType`).
   - `UsbAccessorySender` maintains atomic accounting for dropped video frames (`droppedFramesCount`) when the USB bulk channel buffers are saturated.

5. **Pi-Side Diagnostics & HDMI Output Testing**:
   - `mobidesk_dock.py` logs 5-second periodic dock stats (`decoder, incoming_fps, kbps, last_keyframe_age, input_events_per_sec, usb_state`).
   - `--diagnose` flag inspects `lsusb`, AOA driver/state, DRM/KMS EDID modes, GStreamer elements, and `/dev/input/event*` devices.
   - `--test-pattern` flag displays a standalone SMPTE test pattern via `kmssink` or `autovideosink` to test the HDMI display independently of USB connection.
